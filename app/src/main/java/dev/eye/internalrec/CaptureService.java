package dev.eye.internalrec;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 录制前台服务。
 *
 * 生命周期分两段，故意分开的：
 *   arm   —— 拿到投屏授权、建好 MediaProjection，之后一直挂着（这样只需授权一次）
 *   start/stop —— 在同一个 projection 上反复开关录音
 * 这样 agent 用 adb 广播就能反复录，不用每次去点系统授权弹窗。
 */
public class CaptureService extends Service {

    private static final String TAG = "CaptureService";

    public static final String ACTION_ARM = "dev.eye.internalrec.action.ARM";
    public static final String ACTION_START = "dev.eye.internalrec.action.START";
    public static final String ACTION_STOP = "dev.eye.internalrec.action.STOP";
    public static final String ACTION_QUIT = "dev.eye.internalrec.action.QUIT";

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_NAME = "name";

    private static final String CHANNEL = "rec";
    private static final int NOTIF_ID = 41;

    /** 单进程共享状态，MainActivity 轮询它刷新界面。 */
    public static volatile boolean armed = false;
    public static volatile boolean recording = false;
    public static volatile String lastError = null;
    public static volatile String currentFile = null;
    public static volatile float level = 0f;
    public static volatile double seconds = 0;

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private CaptureEngine engine;
    private CaptureEngine.Source source = CaptureEngine.Source.CAPTURE;
    private String wantName;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static File recordDir(Context ctx) {
        File d = new File(ctx.getExternalFilesDir(null), "Recordings");
        if (!d.exists() && !d.mkdirs()) {
            Log.w(TAG, "建目录失败: " + d);
        }
        return d;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(0, getString(R.string.btn_stop), stopPi)
                .build();
    }

    private void goForeground(String text) {
        Notification n = notification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (source == CaptureEngine.Source.MIC || source == CaptureEngine.Source.BOTH) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIF_ID, n, type);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            goForeground("待机");
            return START_STICKY;
        }

        switch (action) {
            case ACTION_ARM:
                doArm(intent);
                break;
            case ACTION_START:
                doStart(intent);
                break;
            case ACTION_STOP:
                doStop("已停止");
                break;
            case ACTION_QUIT:
                doStop("已退出");
                stopSelf();
                break;
            default:
                goForeground("待机");
        }
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void doArm(Intent intent) {
        source = parseSource(intent.getStringExtra(EXTRA_SOURCE));
        int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (data == null) {
            lastError = "没有拿到投屏授权数据";
            goForeground("待机");
            return;
        }

        // Android 14 起必须先进入前台（且带 mediaProjection 类型）才能取 projection
        goForeground("已就绪，等待指令");

        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
        }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            lastError = "getMediaProjection 失败: " + e;
            Log.e(TAG, lastError, e);
            return;
        }
        if (projection == null) {
            lastError = "getMediaProjection 返回 null";
            return;
        }
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "投屏授权被系统/用户收回");
                armed = false;
                doStop("授权已收回");
            }
        };
        projection.registerCallback(projectionCallback, main);
        armed = true;
        lastError = null;
        Log.i(TAG, "armed, source=" + source);
    }

    private CaptureEngine.Source parseSource(String s) {
        if (s == null) return CaptureEngine.Source.CAPTURE;
        switch (s.toLowerCase(Locale.US)) {
            case "mic":
                return CaptureEngine.Source.MIC;
            case "both":
                return CaptureEngine.Source.BOTH;
            default:
                return CaptureEngine.Source.CAPTURE;
        }
    }

    private void doStart(Intent intent) {
        if (recording) {
            Log.i(TAG, "已经在录了");
            return;
        }
        if (projection == null && source != CaptureEngine.Source.MIC) {
            lastError = "还没授权投屏，先 arm（打开 App 点一次开始录制）";
            goForeground("待机");
            return;
        }
        String name = intent.getStringExtra(EXTRA_NAME);
        if (name == null || name.trim().isEmpty()) {
            name = "rec-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        }
        String src = intent.getStringExtra(EXTRA_SOURCE);
        if (src != null) source = parseSource(src);

        File f = new File(recordDir(this), name + ".wav");
        engine = new CaptureEngine(projection, source, f);
        try {
            engine.start();
            recording = true;
            currentFile = f.getAbsolutePath();
            lastError = null;
            goForeground("录制中 · " + f.getName());
            Log.i(TAG, "开始录制 -> " + f);
        } catch (Exception e) {
            lastError = e.toString();
            Log.e(TAG, "开始录制失败", e);
            engine = null;
            recording = false;
            goForeground("待机");
        }
    }

    private void doStop(String why) {
        if (engine != null) {
            engine.stop();
            seconds = engine.seconds();
            if (engine.error() != null) lastError = engine.error();
            level = 0;
            engine = null;
        }
        if (recording) {
            recording = false;
            Log.i(TAG, "停止录制: " + why + " -> " + currentFile);
        }
        if (armed || projection != null) {
            goForeground("已就绪，等待指令");
        }
    }

    @Override
    public void onDestroy() {
        doStop("服务销毁");
        if (projection != null) {
            try {
                if (projectionCallback != null) projection.unregisterCallback(projectionCallback);
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
        }
        armed = false;
        recording = false;
        super.onDestroy();
    }
}
