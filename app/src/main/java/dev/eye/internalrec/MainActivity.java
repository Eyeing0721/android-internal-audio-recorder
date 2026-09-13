package dev.eye.internalrec;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements RecordingsAdapter.Action {

    private static final int REQ_AUDIO = 101;
    private static final int REQ_CAPTURE = 102;

    private TextView status, detail, empty;
    private MaterialButton toggle;
    private RadioGroup sources;
    private RecyclerView list;
    private RecordingsAdapter adapter;
    private final List<Recording> items = new ArrayList<>();
    private final Handler poll = new Handler(Looper.getMainLooper());
    private String pendingSource = "capture";

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            poll.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.tvStatus);
        detail = findViewById(R.id.tvDetail);
        empty = findViewById(R.id.tvEmpty);
        toggle = findViewById(R.id.btnToggle);
        sources = findViewById(R.id.rgSource);
        list = findViewById(R.id.rvRecordings);

        adapter = new RecordingsAdapter(items, this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        toggle.setOnClickListener(v -> onToggle());

        askNotifications();
        refreshList();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        poll.removeCallbacks(ticker);
        poll.post(ticker);
        refreshList();

        Intent it = getIntent();
        if (it != null && it.getBooleanExtra("autoArm", false)) {
            it.removeExtra("autoArm");
            String s = it.getStringExtra("source");
            if (s != null) pendingSource = s;
            beginCaptureFlow();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        poll.removeCallbacks(ticker);
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private String selectedSource() {
        int id = sources.getCheckedRadioButtonId();
        if (id == R.id.rbMic) return "mic";
        if (id == R.id.rbBoth) return "both";
        return "capture";
    }

    private boolean needsMic() {
        String s = selectedSource();
        return "mic".equals(s) || "both".equals(s);
    }

    private void onToggle() {
        if (CaptureService.recording) {
            sendCmd(CaptureService.ACTION_STOP, null);
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
            poll.postDelayed(this::refreshList, 900);
            return;
        }
        if (CaptureService.armed) {
            sendCmd(CaptureService.ACTION_START, null);
            return;
        }
        beginCaptureFlow();
    }

    private void beginCaptureFlow() {
        pendingSource = selectedSource();
        if (needsMic() && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        launchConsent();
    }

    private void launchConsent() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_AUDIO) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                launchConsent();
            } else {
                Toast.makeText(this, "麦克风权限被拒绝，只能用内录", Toast.LENGTH_LONG).show();
                pendingSource = "capture";
                launchConsent();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(this, "没有授权投屏", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_ARM)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(CaptureService.EXTRA_SOURCE, pendingSource);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        poll.postDelayed(() -> sendCmd(CaptureService.ACTION_START, null), 700);
    }

    private void sendCmd(String action, String name) {
        Intent svc = new Intent(this, CaptureService.class).setAction(action);
        if (name != null) svc.putExtra(CaptureService.EXTRA_NAME, name);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
    }

    private void render() {
        if (CaptureService.recording) {
            status.setText(R.string.status_recording);
            status.setTextColor(getColor(R.color.rec));
            toggle.setText(R.string.btn_stop);
            detail.setText(String.format(Locale.US, "%.1f 秒 · 电平 %.0f%% · %s",
                    CaptureService.seconds, CaptureService.level * 100,
                    CaptureService.currentFile == null ? "" : new File(CaptureService.currentFile).getName()));
        } else {
            status.setText(R.string.status_idle);
            status.setTextColor(getColor(R.color.ink));
            toggle.setText(R.string.btn_start);
            if (CaptureService.lastError != null) {
                detail.setText("出错：" + CaptureService.lastError);
            } else if (CaptureService.armed) {
                detail.setText("已授权，可以随时开始");
            } else {
                detail.setText("第一次要先授权一次屏幕录制");
            }
        }
    }

    private void refreshList() {
        File dir = CaptureService.recordDir(this);
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".wav"));
        items.clear();
        if (fs != null) {
            Arrays.sort(fs, Comparator.comparingLong(File::lastModified).reversed());
            for (File f : fs) if (f.length() > 44) items.add(new Recording(f));
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? TextView.VISIBLE : TextView.GONE);
    }

    @Override
    public void onShare(Recording r) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", r.file);
        Intent i = new Intent(Intent.ACTION_SEND).setType("audio/wav")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "分享录音"));
    }

    @Override
    public void onDelete(Recording r) {
        new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage(r.name() + " ?")
                .setPositiveButton("删除", (d, w) -> {
                    if (r.file.delete()) refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.menu_help);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.help_title)
                    .setMessage(R.string.help_message)
                    .setPositiveButton("知道了", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
