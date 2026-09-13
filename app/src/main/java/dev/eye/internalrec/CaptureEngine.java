package dev.eye.internalrec;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.util.Log;

import java.io.File;

/**
 * 录音引擎。
 *
 * 内录走 AudioPlaybackCaptureConfiguration：Android 10 起系统允许把"其他 App 播出来的声音"
 * 直接录下来，不需要 root、不需要虚拟声卡。代价是目标 App 可以声明 allowAudioPlaybackCapture=false
 * 拒绝被录（比如部分银行/DRM 应用），那种情况录出来就是静音。
 *
 * 麦克风是另一条独立的 AudioRecord。注意系统限制：**同一个 App 同时录内录和麦克风，很多机型会拒绝**，
 * 所以 BOTH 模式可能起不来，这里会如实报错而不是假装成功。
 */
public class CaptureEngine {

    private static final String TAG = "CaptureEngine";

    public static final int SAMPLE_RATE = 48000;
    public static final int BITS = 16;

    public enum Source {CAPTURE, MIC, BOTH}

    private final MediaProjection projection;
    private final Source source;
    private final File outFile;

    private WavWriter writer;
    private AudioRecord playback;
    private AudioRecord mic;
    private Thread pump;
    private volatile boolean running;
    private volatile float level = 0f;
    private volatile String error;
    private volatile double seconds;

    public CaptureEngine(MediaProjection projection, Source source, File outFile) {
        this.projection = projection;
        this.source = source;
        this.outFile = outFile;
    }

    public float level() {
        return level;
    }

    public String error() {
        return error;
    }

    public double seconds() {
        return seconds;
    }

    public double peakHold = 0;

    private int channels() {
        return source == Source.MIC ? 1 : 2;
    }

    public void start() throws Exception {
        int mask = channels() == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(mask)
                .build();

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 16384;

        if (source == Source.CAPTURE || source == Source.BOTH) {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration
                    .Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .build();
            playback = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(minBuf * 2)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
        }

        if (source == Source.MIC || source == Source.BOTH) {
            AudioFormat micFmt = source == Source.BOTH
                    ? new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
                    : fmt;
            int micMin = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    source == Source.BOTH ? AudioFormat.CHANNEL_IN_MONO : mask,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (micMin <= 0) micMin = 16384;
            try {
                mic = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(micFmt)
                        .setBufferSizeInBytes(micMin * 2)
                        .build();
            } catch (Exception e) {
                if (source == Source.MIC) throw e;
                Log.w(TAG, "麦克风起不来，退化成只录内录: " + e);
                mic = null;
            }
        }

        if (playback != null && playback.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("内录 AudioRecord 初始化失败（状态=" + playback.getState() + "）");
        }
        if (mic != null && mic.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("麦克风 AudioRecord 初始化失败");
        }
        if (playback == null && mic == null) {
            throw new IllegalStateException("没有可用的音源");
        }

        writer = new WavWriter(outFile, SAMPLE_RATE, channels(), BITS);
        running = true;
        if (playback != null) playback.startRecording();
        if (mic != null) mic.startRecording();

        pump = new Thread(this::loop, "capture-pump");
        pump.start();
    }

    private void loop() {
        final int chunk = 2048;                 // 每声道采样数
        short[] pb = new short[chunk * 2];      // 立体声
        short[] mc = new short[chunk];          // 单声道
        byte[] out = new byte[chunk * 2 * 2];
        long framesWritten = 0;

        try {
            while (running) {
                int pn = 0, mn = 0;
                if (playback != null) {
                    pn = playback.read(pb, 0, pb.length);
                    if (pn < 0) pn = 0;
                }
                if (mic != null) {
                    mn = mic.read(mc, 0, mc.length);
                    if (mn < 0) mn = 0;
                }
                if (pn == 0 && mn == 0) {
                    Thread.sleep(4);
                    continue;
                }

                int frames;
                float peak = 0f;
                if (channels() == 1) {
                    frames = mn;
                    for (int i = 0; i < frames; i++) {
                        short v = mc[i];
                        peak = Math.max(peak, Math.abs(v) / 32768f);
                        put16(out, i * 2, v);
                    }
                } else {
                    int pbFrames = pn / 2;
                    int micFrames = mn;
                    frames = Math.max(pbFrames, micFrames);
                    for (int i = 0; i < frames; i++) {
                        int l = i < pbFrames ? pb[i * 2] : 0;
                        int r = i < pbFrames ? pb[i * 2 + 1] : 0;
                        if (mic != null && i < micFrames) {
                            int m = mc[i];
                            l += m;
                            r += m;
                        }
                        l = clamp(l);
                        r = clamp(r);
                        peak = Math.max(peak, Math.max(Math.abs(l), Math.abs(r)) / 32768f);
                        put16(out, i * 4, (short) l);
                        put16(out, i * 4 + 2, (short) r);
                    }
                }
                writer.write(out, frames * channels() * 2);
                framesWritten += frames;
                level = peak;
                peakHold = Math.max(peakHold * 0.92, peak);
                seconds = framesWritten / (double) SAMPLE_RATE;
            }
        } catch (Throwable t) {
            Log.e(TAG, "录制循环挂了", t);
            error = t.toString();
        } finally {
            level = 0;
        }
    }

    private static int clamp(int v) {
        return v > 32767 ? 32767 : (v < -32768 ? -32768 : v);
    }

    private static void put16(byte[] b, int off, short v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    public void stop() {
        running = false;
        try {
            if (pump != null) pump.join(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        release(playback);
        release(mic);
        playback = null;
        mic = null;
        try {
            if (writer != null) writer.close();
        } catch (Exception e) {
            Log.w(TAG, "关闭 WAV 失败", e);
            error = e.toString();
        }
        writer = null;
    }

    private static void release(AudioRecord r) {
        if (r == null) return;
        try {
            if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) r.stop();
        } catch (Exception ignored) {
        }
        try {
            r.release();
        } catch (Exception ignored) {
        }
    }
}
