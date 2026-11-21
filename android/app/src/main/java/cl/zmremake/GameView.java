package cl.zmremake;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Dibuja el cuadro del juego en una textura, ajustado a la pantalla sin deformarlo. */
public class GameView extends GLSurfaceView implements GLSurfaceView.Renderer {
    static final int W = 240, H = 160;
    private final ByteBuffer frame = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
    private final Object lock = new Object();
    private boolean dirty = false;

    private int prog, tex, aPos, aUv, uTex;
    private int vw = 1, vh = 1;
    private FloatBuffer quad;

    public GameView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** Buffer donde el hilo del emulador deja el cuadro. */
    public ByteBuffer frameBuffer() { return frame; }

    /** El hilo del emulador avisa que hay un cuadro nuevo. */
    public void frameReady() {
        synchronized (lock) { dirty = true; }
        requestRender();
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        String vs = "attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv;"
                + "void main(){ vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }";
        String fs = "precision mediump float; varying vec2 vUv; uniform sampler2D uTex;"
                + "void main(){ gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0); }";
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(prog);
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        aUv = GLES20.glGetAttribLocation(prog, "aUv");
        uTex = GLES20.glGetUniformLocation(prog, "uTex");

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        tex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, W, H, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        vw = w; vh = h;
        GLES20.glViewport(0, 0, w, h);
        // Ajuste 3:2 centrado
        float sx = 1f, sy = 1f;
        float want = (float) W / H, have = (float) w / h;
        if (have > want) sx = want / have; else sy = have / want;
        float[] v = {
                -sx, -sy, 0f, 1f,
                 sx, -sy, 1f, 1f,
                -sx,  sy, 0f, 0f,
                 sx,  sy, 1f, 0f };
        quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(v).position(0);
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (quad == null) return;
        boolean d;
        synchronized (lock) { d = dirty; dirty = false; }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        if (d) {
            frame.position(0);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, W, H,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frame);
        }
        GLES20.glUseProgram(prog);
        GLES20.glUniform1i(uTex, 0);
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
}
