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

    private int prog, tex, aPos, aUv, uTex, uOn, uTol, uCut, uAA;
    private volatile int xbr = 0;
    private float quadPx = 1f;
    public void setXbr(int level) { xbr = level; requestRender(); }
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
                + "uniform float uOn, uTol, uCut, uAA;"
                + "const vec2 SZ = vec2(240.0, 160.0);"
                + "vec3 px(vec2 c){ return texture2D(uTex, (floor(c) + 0.5) / SZ).rgb; }"
                + "bool eq(vec3 a, vec3 b){ return dot(abs(a - b), vec3(1.0)) < uTol; }"
                + "void main(){"
                + "  vec2 pos = vUv * SZ; vec2 base = floor(pos); vec2 f = fract(pos);"
                + "  vec3 P = px(base);"
                + "  if (uOn < 0.5) { gl_FragColor = vec4(P, 1.0); return; }"
                + "  vec3 A = px(base + vec2(0.0,-1.0)), B = px(base + vec2(1.0,0.0)),"
                + "       C = px(base + vec2(-1.0,0.0)), D = px(base + vec2(0.0,1.0));"
                + "  bool left = f.x < 0.5, top = f.y < 0.5;"
                + "  vec3 n1 = top ? A : D; vec3 o1 = top ? D : A;"
                + "  vec3 n2 = left ? C : B; vec3 o2 = left ? B : C;"
                + "  float u = left ? f.x : 1.0 - f.x; float v = top ? f.y : 1.0 - f.y;"
                + "  vec3 o = P;"
                + "  if (eq(n1, n2) && !eq(n1, o1) && !eq(n2, o2)) {"
                + "    float w = 1.0 - smoothstep(uCut - uAA, uCut + uAA, u + v);"
                + "    o = mix(P, n1, w);"
                + "  }"
                + "  gl_FragColor = vec4(o, 1.0);"
                + "}";
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(prog);
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        aUv = GLES20.glGetAttribLocation(prog, "aUv");
        uTex = GLES20.glGetUniformLocation(prog, "uTex");
        uOn = GLES20.glGetUniformLocation(prog, "uOn");
        uTol = GLES20.glGetUniformLocation(prog, "uTol");
        uCut = GLES20.glGetUniformLocation(prog, "uCut");
        uAA = GLES20.glGetUniformLocation(prog, "uAA");

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
        quadPx = w * sx;
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
        int lv = xbr;
        float[] tol = {0f, 0.10f, 0.25f, 0.40f};
        float[] cut = {0f, 0.40f, 0.50f, 0.60f};
        GLES20.glUniform1f(uOn, lv > 0 ? 1f : 0f);
        GLES20.glUniform1f(uTol, tol[lv]);
        GLES20.glUniform1f(uCut, cut[lv]);
        GLES20.glUniform1f(uAA, Math.max(0.01f, (float) W / quadPx * 0.7f));
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
}
