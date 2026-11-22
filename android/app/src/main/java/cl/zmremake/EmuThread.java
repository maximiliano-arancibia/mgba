package cl.zmremake;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Corre el juego. El audio bloquea la escritura y eso marca el ritmo (60 cuadros por segundo). */
public class EmuThread extends Thread {
    private final GameView view;
    private volatile boolean running = true;
    public static volatile float fps = 0, runMs = 0;
    public static volatile int speed = 1;
    public static volatile int eq = 0;   // 0 = sin ecualizar, 1..3 = mas grave
    private static final float[] BASS = {0f, 0.5f, 1.0f, 1.8f};
    private static final float[] TREB = {0f, 10000f, 7000f, 5000f};
    private float lb0, lb1, hc0, hc1;   // estados de los filtros (izq, der)

    // limitador suave: por encima de 24000 se comprime en vez de cortar
    private static short soft(float v) {
        float m = Math.abs(v);
        if (m > 24000f) m = 24000f + (m - 24000f) * 0.4f;
        if (m > 32767f) m = 32767f;
        return (short) (v < 0 ? -m : m);
    }

    private void applyEq(short[] a, int frames, int rate) {
        int lv = eq;
        if (lv <= 0) return;
        float g = BASS[lv];
        float ab = 1f - (float) Math.exp(-2 * Math.PI * 250.0 / rate);
        float ah = 1f - (float) Math.exp(-2 * Math.PI * TREB[lv] / rate);
        for (int i = 0; i < frames; i++) {
            float l = a[2 * i], r = a[2 * i + 1];
            lb0 += ab * (l - lb0); lb1 += ab * (r - lb1);
            l += g * lb0; r += g * lb1;
            hc0 += ah * (l - hc0); hc1 += ah * (r - hc1);
            a[2 * i] = soft(hc0);
            a[2 * i + 1] = soft(hc1);
        }
    }

    public EmuThread(GameView view) { this.view = view; }

    public void shutdown() {
        running = false;
        try { join(2000); } catch (InterruptedException ignored) { }
    }

    @Override public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        int rate = Native.audioRate();
        int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(minBuf, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        short[] audio = new short[4096];
        final long period = (long) (1e9 / 59.7275);
        long next = System.nanoTime() + period;
        int curRate = rate;
        long t0 = System.nanoTime(), acc = 0;
        int cnt = 0;
        while (running) {
            long a = System.nanoTime();
            int n = Native.runFrame(view.frameBuffer(), audio);
            acc += System.nanoTime() - a;
            if (++cnt == 60) {
                long now = System.nanoTime();
                fps = 60f * 1e9f / (now - t0);
                runMs = acc / 60f / 1e6f;
                t0 = now; acc = 0; cnt = 0;
            }
            view.setScroll(Native.scrollX(), Native.scrollY());
            view.frameReady();
            int r = Native.audioRate();
            if (r != curRate && r > 0) { curRate = r; track.setPlaybackRate(r); }
            if (n > 0 && speed == 1) {
                applyEq(audio, n, curRate);
                track.write(audio, 0, n * 2, AudioTrack.WRITE_NON_BLOCKING);
            }
            // reloj propio: esperar hasta el siguiente cuadro
            long now2 = System.nanoTime();
            if (now2 - next > 100_000_000L) next = now2; // nos atrasamos mucho: reiniciar
            long wait = next - now2;
            if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
            next += period / speed;
        }
        track.stop();
        track.release();
    }
}
