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

    private int prog, tex, aPos, aUv, uTex, uOn, uTol, uCut, uAA, uGrainL, uCellL, uSeedL, uScrollL;
    private volatile int grain = 0, gsize = 1;
    private volatile float scx = 0, scy = 0;
    public void setGrainSize(int s) { gsize = s; requestRender(); }
    public void setScroll(int x, int y) { scx = ((x % 6144) + 6144) % 6144; scy = ((y % 6144) + 6144) % 6144; }
    private int frameNo = 0;
    public void setGrain(int level) { grain = level; requestRender(); }
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
        String fs = "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
                + "varying vec2 vUv; uniform sampler2D uTex;"
                + "uniform float uOn, uTol, uCut, uAA, uGrain, uCell, uSeed;"
                + "uniform vec2 uScroll;"
                + "const vec2 SZ = vec2(240.0, 160.0);"
                + "vec3 px(vec2 c){ return texture2D(uTex, (floor(c) + 0.5) / SZ).rgb; }"
                + "bool eq(vec3 a, vec3 b){ return dot(abs(a - b), vec3(1.0)) < uTol; }"
                + "float hash(vec2 p){ vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }"
                + "void main(){"
                + "  vec2 pos = vUv * SZ; vec2 base = floor(pos); vec2 f = fract(pos);"
                + "  vec3 P = px(base);"
                + "  vec3 o = P;"
                + "  if (uOn > 0.5) {"
                + "    vec3 A = px(base + vec2(0.0,-1.0)), B = px(base + vec2(1.0,0.0)),"
                + "         C = px(base + vec2(-1.0,0.0)), D = px(base + vec2(0.0,1.0));"
                + "    bool left = f.x < 0.5, top = f.y < 0.5;"
                + "    vec3 n1 = top ? A : D; vec3 o1 = top ? D : A;"
                + "    vec3 n2 = left ? C : B; vec3 o2 = left ? B : C;"
                + "    float u = left ? f.x : 1.0 - f.x; float v = top ? f.y : 1.0 - f.y;"
                + "    if (eq(n1, n2) && !eq(n1, o1) && !eq(n2, o2)) {"
                + "      float w = 1.0 - smoothstep(uCut - uAA, uCut + uAA, u + v);"
                + "      o = mix(P, n1, w);"
                + "    }"
                + "  }"
                + "  if (uGrain > 0.0) {"
                + "    float vmax = max(o.r, max(o.g, o.b));"
                + "    float wgt = smoothstep(0.22, 0.50, vmax);"
                + "    float n = hash(floor((pos + uScroll) / uCell)) * 2.0 - 1.0;"
                + "    o += n * uGrain * wgt;"
                + "  }"
                + "  gl_FragColor = vec4(clamp(o, 0.0, 1.0), 1.0);"
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
        uGrainL = GLES20.glGetUniformLocation(prog, "uGrain");
        uCellL = GLES20.glGetUniformLocation(prog, "uCell");
        uSeedL = GLES20.glGetUniformLocation(prog, "uSeed");
        uScrollL = GLES20.glGetUniformLocation(prog, "uScroll");

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
        float[] tol = {0f, 0.10f, 0.28f, 0.45f, 0.65f};
        float[] cut = {0f, 0.40f, 0.55f, 0.70f, 0.85f};
        GLES20.glUniform1f(uOn, lv > 0 ? 1f : 0f);
        GLES20.glUniform1f(uTol, tol[lv]);
        GLES20.glUniform1f(uCut, cut[lv]);
        float[] gr = {0f, 0.025f, 0.045f, 0.070f};
        float c1 = Math.min(1f, (float) W / quadPx);   // un pixel de pantalla
        float rr = (float) Math.pow(2f / c1, 1.0 / 3.0);
        float[] cellSz = {1f, c1, c1 * rr, c1 * rr * rr, 2f};
        GLES20.glUniform1f(uGrainL, gr[grain]);
        GLES20.glUniform1f(uCellL, cellSz[gsize]);
        GLES20.glUniform2f(uScrollL, scx, scy);
        if (d) frameNo++;
        GLES20.glUniform1f(uSeedL, (frameNo % 997) * 1.37f);
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
