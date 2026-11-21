package cl.zmremake;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Botones tactiles en pantalla (varios dedos a la vez). */
public class Controls extends View {
    // bits de mGBA
    public static final int A = 1, B = 2, SELECT = 4, START = 8, RIGHT = 16, LEFT = 32, UP = 64, DOWN = 128, R = 256, L = 512;

    private static class Btn {
        final String label; final int key; final RectF r = new RectF();
        Btn(String l, int k) { label = l; key = k; }
    }
    private final Btn[] btns = {
            new Btn("↑", UP), new Btn("↓", DOWN), new Btn("←", LEFT), new Btn("→", RIGHT),
            new Btn("A", A), new Btn("B", B), new Btn("L", L), new Btn("R", R),
            new Btn("SEL", SELECT), new Btn("START", START) };
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int pressed = 0;
    private int padKeys = 0; // desde un mando fisico
    public Controls(Context c) {
        super(c);
        fill.setColor(0x33FFFFFF);
        line.setColor(0x66FFFFFF); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(3);
        txt.setColor(0xCCFFFFFF); txt.setTextAlign(Paint.Align.CENTER);
    }

    public void setPadKeys(int k) { padKeys = k; push(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float u = Math.min(w, h) / 9f;       // unidad de tamaño
        boolean land = w > h;
        float dx = u * 2.2f, dy = h - u * 2.4f;
        set(0, dx, dy - u * 1.1f, u); set(1, dx, dy + u * 1.1f, u);
        set(2, dx - u * 1.1f, dy, u); set(3, dx + u * 1.1f, dy, u);
        float ax = w - u * 1.4f, ay = h - u * 2.4f;
        set(4, ax, ay - u * 0.5f, u * 1.1f); set(5, ax - u * 1.5f, ay + u * 0.6f, u * 1.1f);
        set(6, u * 1.2f, u * 0.9f, u * 0.9f); set(7, w - u * 1.2f, u * 0.9f, u * 0.9f);
        set(8, w / 2f - u * 1.2f, h - u * 0.8f, u * 0.6f); set(9, w / 2f + u * 1.2f, h - u * 0.8f, u * 0.6f);
        txt.setTextSize(u * 0.6f);
    }
    private void set(int i, float cx, float cy, float rad) { btns[i].r.set(cx - rad, cy - rad, cx + rad, cy + rad); }

    @Override protected void onDraw(Canvas c) {
        for (Btn b : btns) {
            fill.setColor(((pressed | padKeys) & b.key) != 0 ? 0x77FFFFFF : 0x33FFFFFF);
            c.drawRoundRect(b.r, 24, 24, fill);
            c.drawRoundRect(b.r, 24, 24, line);
            c.drawText(b.label, b.r.centerX(), b.r.centerY() + txt.getTextSize() * 0.35f, txt);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int keys = 0;
        int up = e.getActionMasked() == MotionEvent.ACTION_UP ? -1 : (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -2);
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == up || up == -1) continue;
            for (Btn b : btns) if (b.r.contains(e.getX(i), e.getY(i))) keys |= b.key;
        }
        pressed = keys;
        push();
        invalidate();
        return true;
    }

    private void push() { Native.setKeys(pressed | padKeys); }
}
