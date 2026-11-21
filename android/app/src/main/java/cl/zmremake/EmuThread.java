package cl.zmremake;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Corre el juego. El audio bloquea la escritura y eso marca el ritmo (60 cuadros por segundo). */
public class EmuThread extends Thread {
    private final GameView view;
    private volatile boolean running = true;

    public EmuThread(GameView view) { this.view = view; }

    public void shutdown() {
        running = false;
        try { join(2000); } catch (InterruptedException ignored) { }
    }

    @Override public void run() {
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
                .setBufferSizeInBytes(Math.max(minBuf, 4096 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        short[] audio = new short[4096];
        while (running) {
            int n = Native.runFrame(view.frameBuffer(), audio);
            view.frameReady();
            if (n > 0) track.write(audio, 0, n * 2);
            else try { Thread.sleep(16); } catch (InterruptedException ignored) { }
        }
        track.stop();
        track.release();
    }
}
