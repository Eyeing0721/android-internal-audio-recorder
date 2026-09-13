package dev.eye.internalrec;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Recording {
    public final File file;

    public Recording(File file) {
        this.file = file;
    }

    public String name() {
        return file.getName();
    }

    public String meta() {
        long sec = Math.max(0, file.length() - 44) / (48000L * 2 * 2);
        String t = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(new Date(file.lastModified()));
        return String.format(Locale.US, "%s · %d:%02d · %.1f MB",
                t, sec / 60, sec % 60, file.length() / 1048576.0);
    }
}
