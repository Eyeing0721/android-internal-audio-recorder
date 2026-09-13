package dev.eye.internalrec;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * agent 控制面。
 *
 *   adb shell am broadcast -a dev.eye.internalrec.CONTROL --es cmd start --es name take1
 *   adb shell am broadcast -a dev.eye.internalrec.CONTROL --es cmd stop
 *   adb shell am broadcast -a dev.eye.internalrec.CONTROL --es cmd quit
 *
 * arm（拿投屏授权）不能纯靠广播：Android 10 起后台启动 Activity 会被拦，
 * 系统授权弹窗必须有个前台界面。所以 arm 这条路要用
 *   adb shell am start -n dev.eye.internalrec/.MainActivity --ez autoArm true
 * 用户在手机上点一次"允许"，之后 start/stop 就全自动了。
 */
public class ControlReceiver extends BroadcastReceiver {

    private static final String TAG = "ControlReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) {
            Log.w(TAG, "没有 cmd");
            return;
        }
        Log.i(TAG, "收到 cmd=" + cmd);
        Intent svc = new Intent(ctx, CaptureService.class);
        switch (cmd.toLowerCase()) {
            case "start":
            case "stop":
            case "quit":
                svc.setAction("dev.eye.internalrec.action." + cmd.toUpperCase());
                svc.putExtra(CaptureService.EXTRA_NAME, intent.getStringExtra("name"));
                svc.putExtra(CaptureService.EXTRA_SOURCE, intent.getStringExtra("source"));
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
                else ctx.startService(svc);
                break;
            case "arm":
                Intent act = new Intent(ctx, MainActivity.class);
                act.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                act.putExtra("autoArm", true);
                act.putExtra("source", intent.getStringExtra("source"));
                try {
                    ctx.startActivity(act);
                } catch (Exception e) {
                    Log.w(TAG, "后台起 Activity 被拦了，改用 am start", e);
                }
                break;
            default:
                Log.w(TAG, "不认识的 cmd: " + cmd);
        }
    }
}
