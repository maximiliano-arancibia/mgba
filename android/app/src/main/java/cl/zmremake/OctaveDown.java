package cl.zmremake;

/**
 * Baja el tono una octava sin cambiar la velocidad (WSOLA + remuestreo).
 * 1) compresion en el tiempo a la mitad con ventanas alineadas por correlacion,
 * 2) lectura a mitad de velocidad (linea de retardo), que baja el tono una octava y devuelve la duracion original.
 */
final class OctaveDown {
    private static final int N = 768, HS = 192, HA = 384, D = 96, CL = 192;
    private static final int RING = 8192, MASK = RING - 1;
    private static final float[] WIN = new float[N];
    static {
        for (int i = 0; i < N; i++) WIN[i] = 0.5f * (0.5f - 0.5f * (float) Math.cos(2 * Math.PI * i / N));
    }

    private final float[] inL = new float[RING], inR = new float[RING];
    private final float[] sL = new float[RING], sR = new float[RING];
    private final float[] qL = new float[RING], qR = new float[RING];
    private final float[] olaL = new float[N], olaR = new float[N];
    private final float[] tmpl = new float[CL];
    private long inAbs = 0, tgt = D, prevStart = -1, sW = 0, qHead = 0, qTail = 0;
    private double pos = 0;

    OctaveDown(int primeFrames) {
        qTail = primeFrames;   // silencio inicial: colchon contra rafagas
    }

    void push(float[] l, float[] r, int n) {
        for (int i = 0; i < n; i++) {
            int k = (int) ((inAbs + i) & MASK);
            inL[k] = l[i];
            inR[k] = r[i];
        }
        inAbs += n;
        while (inAbs >= tgt + D + N + 1) grain();
        while (pos + 1 < sW) {
            int i0 = (int) pos;
            float fr = (float) (pos - i0);
            int a = i0 & MASK, b = (i0 + 1) & MASK;
            int q = (int) (qTail & MASK);
            qL[q] = sL[a] + (sL[b] - sL[a]) * fr;
            qR[q] = sR[a] + (sR[b] - sR[a]) * fr;
            qTail++;
            pos += 0.5;
        }
    }

    private void grain() {
        long start = tgt;
        if (prevStart >= 0) {
            long nat = prevStart + HS;
            for (int i = 0; i < CL; i++) {
                int k = (int) ((nat + i) & MASK);
                tmpl[i] = inL[k] + inR[k];
            }
            double best = -1e30;
            for (int d = -D; d <= D; d++) {
                long cs = tgt + d;
                double dot = 0, e = 0;
                for (int i = 0; i < CL; i++) {
                    int k = (int) ((cs + i) & MASK);
                    float b = inL[k] + inR[k];
                    dot += tmpl[i] * b;
                    e += b * b;
                }
                double score = dot / Math.sqrt(e + 1e-3);
                if (score > best) { best = score; start = cs; }
            }
        }
        for (int i = 0; i < N; i++) {
            int k = (int) ((start + i) & MASK);
            olaL[i] += WIN[i] * inL[k];
            olaR[i] += WIN[i] * inR[k];
        }
        for (int i = 0; i < HS; i++) {
            int s = (int) ((sW + i) & MASK);
            sL[s] = olaL[i];
            sR[s] = olaR[i];
        }
        sW += HS;
        System.arraycopy(olaL, HS, olaL, 0, N - HS);
        System.arraycopy(olaR, HS, olaR, 0, N - HS);
        java.util.Arrays.fill(olaL, N - HS, N, 0f);
        java.util.Arrays.fill(olaR, N - HS, N, 0f);
        prevStart = start;
        tgt += HA;
    }

    /** Saca hasta max cuadros ya listos. Devuelve cuantos. */
    int pull(float[] l, float[] r, int max) {
        int avail = (int) (qTail - qHead);
        int n = Math.min(avail, max);
        for (int i = 0; i < n; i++) {
            int q = (int) ((qHead + i) & MASK);
            l[i] = qL[q];
            r[i] = qR[q];
        }
        qHead += n;
        return n;
    }
}
