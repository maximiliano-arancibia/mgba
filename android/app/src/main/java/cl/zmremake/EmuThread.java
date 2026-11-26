package cl.zmremake;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Corre el juego. El audio bloquea la escritura y eso marca el ritmo (60 cuadros por segundo). */
public class EmuThread extends Thread {
    private final GameView view;
    private volatile boolean running = true;
    public static volatile float fps = 0, runMs = 0;
    public static volatile float speed = 1f;
    public static volatile int tone = 0;   // 0 = sin cambio, 1 = una octava mas grave, 2 = dos octavas

    // limitador suave: por encima de 24000 se comprime en vez de cortar
    private static short soft(float v) {
        float m = Math.abs(v);
        if (m > 24000f) m = 24000f + (m - 24000f) * 0.4f;
        if (m > 32767f) m = 32767f;
        return (short) (v < 0 ? -m : m);
    }

    private OctaveDown p1, p2;
    private int curTone = 0;
    private boolean needReset = true;
    private final float[] fl = new float[4096], fr = new float[4096], tl = new float[4096], tr = new float[4096];

    // Baja el tono (1 o 2 octavas) y deja exactamente n cuadros en el arreglo
    private void applyTone(short[] a, int frames) {
        int lv = tone;
        if (lv != curTone || needReset) {
            curTone = lv;
            needReset = false;
            p1 = lv >= 1 ? new OctaveDown(lv == 1 ? 1100 : 0) : null;
            p2 = lv >= 2 ? new OctaveDown(2100) : null;
        }
        if (lv <= 0 || frames <= 0 || frames > 2048) return;
        for (int i = 0; i < frames; i++) { fl[i] = a[2 * i]; fr[i] = a[2 * i + 1]; }
        OctaveDown last = p1;
        p1.push(fl, fr, frames);
        if (p2 != null) {
            int got = p1.pull(tl, tr, 4096);
            p2.push(tl, tr, got);
            last = p2;
        }
        int got = last.pull(tl, tr, frames);
        for (int i = 0; i < frames; i++) {
            float l = i < got ? tl[i] : 0f, r = i < got ? tr[i] : 0f;
            a[2 * i] = soft(l);
            a[2 * i + 1] = soft(r);
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
            int n = Native.runFrame(view.frameBuffer(), view.maskBuffer(), audio);
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
            if (n > 0 && speed == 1f) {
                applyTone(audio, n);
                track.write(audio, 0, n * 2, AudioTrack.WRITE_NON_BLOCKING);
            } else if (speed != 1f) {
                needReset = true;   // al volver a x1 se empieza limpio
            }
            // reloj propio: esperar hasta el siguiente cuadro
            long now2 = System.nanoTime();
            if (now2 - next > 100_000_000L) next = now2; // nos atrasamos mucho: reiniciar
            long wait = next - now2;
            if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
            next += (long) (period / speed);
        }
        track.stop();
        track.release();
    }
}
