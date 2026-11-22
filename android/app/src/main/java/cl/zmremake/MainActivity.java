package cl.zmremake;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int PICK = 1;
    private FrameLayout root;
    private GameView view;
    private Controls controls;
    private EmuThread emu;
    private int padKeys = 0;
    private TextView dbg;
    private final Handler h = new Handler();
    private final Runnable tick = new Runnable() {
        public void run() {
            if (dbg != null && emu != null) updateLegend();
            if (dbg != null && emu != null) dbg.setText(Native.info() + String.format("\nfps=%.1f  runFrame=%.1f ms", EmuThread.fps, EmuThread.runMs) + "  capa=BG" + Native.scrollBg() + " scroll=" + Native.scrollX() + "," + Native.scrollY());
            h.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);
        showMenu();
    }

    private void showMenu() {
        stopGame();
        root.removeAllViews();
        Button open = new Button(this);
        open.setText("Abrir ROM");
        open.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, PICK);
        });
        root.addView(open, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        String last = sp.getString("last", null);
        if (last != null && new File(last).exists()) {
            Button cont = new Button(this);
            cont.setText("Continuar: " + new File(last).getName());
            cont.setOnClickListener(v -> startGame(new File(last)));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            lp.topMargin = 220;
            root.addView(cont, lp);
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != PICK || res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        try {
            String name = "juego.gba";
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (i >= 0) name = c.getString(i).replaceAll("[^A-Za-z0-9._-]", "_");
                }
            }
            File dir = new File(getFilesDir(), "roms");
            dir.mkdirs();
            File out = new File(dir, name);
            try (InputStream raw = getContentResolver().openInputStream(uri)) {
                InputStream in = raw;
                if (name.toLowerCase().endsWith(".zip")) {
                    java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(raw);
                    java.util.zip.ZipEntry ze;
                    boolean found = false;
                    while ((ze = zin.getNextEntry()) != null) {
                        String n2 = ze.getName().toLowerCase();
                        if (!ze.isDirectory() && (n2.endsWith(".gba") || n2.endsWith(".agb") || n2.endsWith(".bin"))) {
                            name = new File(ze.getName()).getName().replaceAll("[^A-Za-z0-9._-]", "_");
                            out = new File(dir, name);
                            found = true;
                            break;
                        }
                    }
                    if (!found) throw new Exception("el zip no trae una ROM .gba");
                    in = zin;
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
            startGame(out);
        } catch (Exception e) {
            Toast.makeText(this, "No pude leer la ROM: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private void startGame(File rom) {
        String sav = rom.getAbsolutePath().replaceAll("\\.[^.]*$", "") + ".sav";
        if (!Native.open(rom.getAbsolutePath(), sav)) {
            Toast.makeText(this, "El nucleo no pudo abrir esa ROM", Toast.LENGTH_LONG).show();
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("last", rom.getAbsolutePath()).apply();
        root.removeAllViews();
        view = new GameView(this);
        controls = new Controls(this);
        root.addView(view);
        root.addView(controls);
        dbg = new TextView(this);
        dbg.setTextColor(0xFFFF00FF);
        dbg.setBackgroundColor(0xAA000000);
        dbg.setTextSize(12);
        root.addView(dbg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        fxOn = true;
        applyAll();
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(barBtn("⚙", v -> showSettings()));
        final TextView fx = barBtn("FX ✓", null);
        fx.setOnClickListener(v -> {
            fxOn = !fxOn;
            fx.setText(fxOn ? "FX ✓" : "FX ✗");
            if (view != null) view.setMaster(fxOn && !testOn);
        });
        bar.addView(fx);
        EmuThread.speed = 1;
        final TextView ff = barBtn("» x1", null);
        ff.setOnClickListener(v -> {
            EmuThread.speed = EmuThread.speed == 1 ? 2 : (EmuThread.speed == 2 ? 4 : 1);
            ff.setText("» x" + EmuThread.speed);
        });
        bar.addView(ff);
        final TextView tst = barBtn("TEST", null);
        tst.setOnClickListener(v -> {
            testOn = !testOn;
            Native.setTest(testOn);
            tst.setAlpha(testOn ? 1f : 0.7f);
            tst.setBackgroundColor(testOn ? 0xCC8800AA : 0x66000000);
            if (view != null) view.setMaster(fxOn && !testOn);
            if (legend != null) legend.setVisibility(testOn ? View.VISIBLE : View.GONE);
        });
        bar.addView(tst);
        final TextView ps = barBtn("⏸", null);
        ps.setOnClickListener(v -> {
            pausedOn = !pausedOn;
            Native.setPaused(pausedOn);
            ps.setText(pausedOn ? "▶" : "⏸");
            ps.setBackgroundColor(pausedOn ? 0xCC008800 : 0x66000000);
        });
        bar.addView(ps);
        bar.addView(barBtn("📷", v -> takeShot()));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blp.topMargin = 90;
        root.addView(bar, blp);

        legend = new TextView(this);
        legend.setTextColor(0xFFFFFFFF);
        legend.setBackgroundColor(0xAA000000);
        legend.setTextSize(13);
        legend.setPadding(16, 8, 16, 8);
        legend.setVisibility(View.GONE);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        llp.bottomMargin = 12;
        root.addView(legend, llp);
        testOn = false;
        pausedOn = false;
        Native.setTest(false);
        Native.setPaused(false);
        h.post(tick);
        emu = new EmuThread(view);
        emu.start();
    }

    private LinearLayout panel;
    private boolean fxOn = true, testOn = false, pausedOn = false;
    private TextView legend;
    private LinearLayout bar;
    private final int[] st = new int[22];
    private static final int[] PALC = {0xE6194B, 0x3CB44B, 0xFFE119, 0x0082C8, 0xF58231, 0x911EB4, 0x46F0F0, 0xF032E6,
            0xD2F53C, 0xFABEBE, 0x008080, 0xAA6E28, 0x800000, 0xAAFFC3, 0x000080, 0x808080};
    private static final int[] BGC = {0x7FA6FF, 0x7FFF8C, 0xFF8C73, 0xFFF273, 0x808080};

    private TextView barBtn(String t, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(16);
        b.setPadding(30, 14, 30, 14);
        b.setBackgroundColor(0x66000000);
        if (l != null) b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 10;
        b.setLayoutParams(lp);
        return b;
    }

    private void updateLegend() {
        if (legend == null || !testOn) return;
        Native.testStats(st);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append("Fondo: ");
        String[] bn = {"BG0", "BG1", "BG2", "BG3"};
        for (int i = 0; i < 4; i++) {
            if (st[i] > 0) {
                int s0 = sb.length();
                sb.append("■ " + bn[i] + " (" + st[i] + ")  ");
                sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF000000 | BGC[i]), s0, s0 + 1, 0);
            }
        }
        sb.append("\nSprites, paletas: ");
        boolean any = false;
        for (int p = 0; p < 16; p++) {
            if (st[5 + p] > 0) {
                int s0 = sb.length();
                sb.append("■ " + p + " (" + st[5 + p] + ")  ");
                sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF000000 | PALC[p]), s0, s0 + 1, 0);
                any = true;
            }
        }
        if (!any) sb.append("ninguno  ");
        sb.append("\nBlanco = sprite sin paleta clara: " + st[21]);
        legend.setText(sb);
    }
    private static final String[] KEYS = {"xbr", "grain", "gsz2", "color", "luces", "audio"};
    private static final int[] DEFS = {0, 0, 3, 0, 0, 0};

    private int pref(String k, int d) { return getPreferences(MODE_PRIVATE).getInt(k, d); }

    private void applyAll() {
        EmuThread.eq = pref("audio", 0);
        if (view == null) return;
        view.setXbr(pref("xbr", 0));
        view.setGrain(pref("grain", 0));
        view.setGrainSize(pref("gsz2", 3));
        view.setColor(pref("color", 0));
        view.setLuces(pref("luces", 0));
    }

    private TextView label(String t) {
        TextView l = new TextView(this);
        l.setText(t);
        l.setTextColor(0xFFCCCCCC);
        l.setTextSize(13);
        l.setPadding(0, 12, 0, 2);
        return l;
    }

    private Button small(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(13);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(28, 10, 28, 10);
        return b;
    }

    private void optionRow(String title, String key, int first, int last, String[] names) {
        panel.addView(label(title));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int cur = pref(key, DEFS[java.util.Arrays.asList(KEYS).indexOf(key)]);
        final Button[] bs = new Button[last + 1];
        for (int i = first; i <= last; i++) {
            final int lv = i;
            bs[i] = small(names[i - first]);
            bs[i].setAlpha(i == cur ? 1f : 0.45f);
            bs[i].setOnClickListener(v -> {
                getPreferences(MODE_PRIVATE).edit().putInt(key, lv).apply();
                applyAll();
                for (int j = first; j <= last; j++) bs[j].setAlpha(j == lv ? 1f : 0.45f);
            });
            row.addView(bs[i]);
        }
        panel.addView(row);
    }

    private void showSettings() {
        if (panel != null) { root.removeView(panelHolder); panelHolder = null; panel = null; return; }
        buildPanel();
    }

    private void buildPanel() {
        if (panelHolder != null) root.removeView(panelHolder);
        panelHolder = null;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xEE111111);
        panel.setPadding(36, 24, 36, 24);
        TextView t = new TextView(this);
        t.setText("Ajustes");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(17);
        panel.addView(t);

        optionRow("Filtro xBR (bordes suaves)", "xbr", 0, 4, new String[]{"No", "1", "2", "3", "4"});
        optionRow("Grano (intensidad)", "grain", 0, 3, new String[]{"No", "1", "2", "3"});
        optionRow("Tamaño del grano", "gsz2", 1, 4, new String[]{"1", "2", "3", "4"});
        optionRow("Color (más oscuro y serio)", "color", 0, 4, new String[]{"No", "1", "2", "3", "4"});
        optionRow("Luces (devuelve brillo a lo claro)", "luces", 0, 4, new String[]{"No", "1", "2", "3", "4"});
        optionRow("Sonido (más grave)", "audio", 0, 3, new String[]{"No", "1", "2", "3"});

        // ---- slots ----
        panel.addView(label("Cargar combinación"));
        LinearLayout load = new LinearLayout(this);
        load.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 1; i <= 5; i++) {
            final int slot = i;
            String saved = getPreferences(MODE_PRIVATE).getString("slot" + i, null);
            Button b = small("" + i);
            b.setAlpha(saved == null ? 0.25f : 1f);
            b.setOnClickListener(v -> {
                String sv = getPreferences(MODE_PRIVATE).getString("slot" + slot, null);
                if (sv == null) { Toast.makeText(this, "Slot " + slot + " vacío", Toast.LENGTH_SHORT).show(); return; }
                String[] p = sv.split(",");
                SharedPreferences.Editor e = getPreferences(MODE_PRIVATE).edit();
                for (int k = 0; k < KEYS.length && k < p.length; k++) e.putInt(KEYS[k], Integer.parseInt(p[k]));
                e.apply();
                applyAll();
                buildPanel();
            });
            load.addView(b);
        }
        panel.addView(load);

        panel.addView(label("Guardar la combinación actual en"));
        LinearLayout save = new LinearLayout(this);
        save.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 1; i <= 5; i++) {
            final int slot = i;
            Button b = small("" + i);
            b.setOnClickListener(v -> {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < KEYS.length; k++) {
                    if (k > 0) sb.append(',');
                    sb.append(pref(KEYS[k], DEFS[k]));
                }
                getPreferences(MODE_PRIVATE).edit().putString("slot" + slot, sb.toString()).apply();
                Toast.makeText(this, "Guardado en slot " + slot, Toast.LENGTH_SHORT).show();
                buildPanel();
            });
            save.addView(b);
        }
        panel.addView(save);

        panel.addView(label("Estado del juego: guardar en"));
        LinearLayout ss1 = new LinearLayout(this);
        ss1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout ss2 = new LinearLayout(this);
        ss2.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 1; i <= 3; i++) {
            final int slot = i;
            Button gb = small("" + i);
            gb.setOnClickListener(v -> stateAction(slot, 1));
            ss1.addView(gb);
            Button lb = small("" + i);
            lb.setOnClickListener(v -> stateAction(slot, 2));
            ss2.addView(lb);
        }
        panel.addView(ss1);
        panel.addView(label("Estado del juego: cargar de"));
        panel.addView(ss2);

        Button close = small("Cerrar");
        close.setOnClickListener(v -> { root.removeView(panelHolder); panelHolder = null; panel = null; });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = 16;
        panel.addView(close, clp);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setBackgroundColor(0xEE111111);
        sv.addView(panel, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.8f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        root.addView(sv, lp);
        panelHolder = sv;
    }
    private View panelHolder;

    @android.annotation.TargetApi(29)
    private void takeShot() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Captura no disponible en este Android", Toast.LENGTH_SHORT).show();
            return;
        }
        final View barV = bar;
        if (barV != null) barV.setVisibility(View.INVISIBLE);
        h.postDelayed(() -> {
            View decor = getWindow().getDecorView();
            final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    decor.getWidth(), decor.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(getWindow(),
                    new android.graphics.Rect(0, 0, decor.getWidth(), decor.getHeight()), bmp, res -> {
                if (barV != null) barV.setVisibility(View.VISIBLE);
                if (res != android.view.PixelCopy.SUCCESS) {
                    Toast.makeText(this, "No se pudo capturar", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "zm_" + System.currentTimeMillis() + ".png");
                    cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                    cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZMRemake");
                    Uri u = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream os = getContentResolver().openOutputStream(u)) {
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os);
                    }
                    Toast.makeText(this, "Captura guardada (Galería, carpeta ZMRemake)", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Error al guardar: " + e, Toast.LENGTH_LONG).show();
                }
            }, h);
        }, 150);
    }

    private void stateAction(int slot, int action) {
        String last = getPreferences(MODE_PRIVATE).getString("last", null);
        if (last == null) return;
        Native.requestState(last + ".ss" + slot, action);
        h.postDelayed(() -> {
            int r = Native.stateResult();
            String m = r == 1 ? (action == 1 ? "Estado guardado en " : "Estado cargado de ") + slot
                    : (action == 2 ? "No hay estado en " + slot : "No se pudo guardar el estado");
            Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
        }, 400);
    }

    private void stopGame() {
        panel = null; panelHolder = null;
        h.removeCallbacks(tick);
        dbg = null;
        if (emu != null) {
            emu.shutdown();
            emu = null;
            Native.close();
        }
    }

    @Override protected void onPause() { super.onPause(); stopGame(); showMenuIfNeeded(); }
    private void showMenuIfNeeded() { if (view != null) { view = null; showMenu(); } }

    // ---- mando fisico ----
    private static int map(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return Controls.A;
            case KeyEvent.KEYCODE_BUTTON_B: return Controls.B;
            case KeyEvent.KEYCODE_BUTTON_X: return Controls.B;
            case KeyEvent.KEYCODE_BUTTON_Y: return Controls.A;
            case KeyEvent.KEYCODE_BUTTON_L1: return Controls.L;
            case KeyEvent.KEYCODE_BUTTON_R1: return Controls.R;
            case KeyEvent.KEYCODE_BUTTON_START: return Controls.START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return Controls.SELECT;
            case KeyEvent.KEYCODE_DPAD_UP: return Controls.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Controls.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Controls.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Controls.RIGHT;
            default: return 0;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = map(e.getKeyCode());
        if (k != 0 && controls != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) padKeys |= k; else if (e.getAction() == KeyEvent.ACTION_UP) padKeys &= ~k;
            controls.setPadKeys(padKeys);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (controls != null && (e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 && e.getAction() == MotionEvent.ACTION_MOVE) {
            float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            int keep = padKeys & ~(Controls.UP | Controls.DOWN | Controls.LEFT | Controls.RIGHT);
            if (x < -0.5f || hx < -0.5f) keep |= Controls.LEFT;
            if (x > 0.5f || hx > 0.5f) keep |= Controls.RIGHT;
            if (y < -0.5f || hy < -0.5f) keep |= Controls.UP;
            if (y > 0.5f || hy > 0.5f) keep |= Controls.DOWN;
            padKeys = keep;
            controls.setPadKeys(padKeys);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }
}
