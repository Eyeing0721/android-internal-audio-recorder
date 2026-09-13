package dev.eye.internalrec;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 边录边写 WAV。
 *
 * 先占着 44 字节的头，停止时回到开头把 RIFF/data 长度回填。
 * 这样录到一半拔线/断电，文件也只是一个长度字段不对的 WAV，数据还在，
 * 比录完再封装（内存里攒一堆）稳。
 */
public class WavWriter implements Closeable {

    private final RandomAccessFile raf;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private long dataBytes = 0;
    private boolean closed = false;

    public WavWriter(File file, int sampleRate, int channels, int bitsPerSample) throws IOException {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.raf = new RandomAccessFile(file, "rw");
        raf.setLength(0);
        raf.seek(0);
        raf.write(header(0));
    }

    public synchronized void write(byte[] buf, int len) throws IOException {
        if (closed || len <= 0) return;
        raf.write(buf, 0, len);
        dataBytes += len;
    }

    public synchronized long dataBytes() {
        return dataBytes;
    }

    public double seconds() {
        int frame = channels * (bitsPerSample / 8);
        return frame == 0 ? 0 : dataBytes / (double) (sampleRate * frame);
    }

    private byte[] header(long dataLen) {
        int frame = channels * (bitsPerSample / 8);
        long byteRate = (long) sampleRate * frame;
        byte[] h = new byte[44];
        putStr(h, 0, "RIFF");
        putInt(h, 4, (int) (36 + dataLen));
        putStr(h, 8, "WAVE");
        putStr(h, 12, "fmt ");
        putInt(h, 16, 16);                       // PCM 子块大小
        putShort(h, 20, 1);                      // PCM
        putShort(h, 22, channels);
        putInt(h, 24, sampleRate);
        putInt(h, 28, (int) byteRate);
        putShort(h, 32, frame);                  // blockAlign
        putShort(h, 34, bitsPerSample);
        putStr(h, 36, "data");
        putInt(h, 40, (int) dataLen);
        return h;
    }

    private static void putStr(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        raf.seek(0);
        raf.write(header(dataBytes));
        raf.close();
    }
}
