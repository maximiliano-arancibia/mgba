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
        view.setXbr(getPreferences(MODE_PRIVATE).getInt("xbr", 0));
        view.setGrain(getPreferences(MODE_PRIVATE).getInt("grain", 0));
        view.setGrainSize(getPreferences(MODE_PRIVATE).getInt("gsz2", 3));
        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextColor(0xFFFFFFFF);
        gear.setTextSize(22);
        gear.setPadding(36, 8, 36, 8);
        gear.setBackgroundColor(0x66000000);
        gear.setOnClickListener(v -> showSettings());
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        glp.topMargin = 24; glp.rightMargin = 24;
        root.addView(gear, glp);
        EmuThread.speed = 1;
        TextView ff = new TextView(this);
        ff.setText("» x1");
        ff.setTextColor(0xFFFFFFFF);
        ff.setTextSize(16);
        ff.setPadding(36, 18, 36, 18);
        ff.setBackgroundColor(0x66000000);
        ff.setOnClickListener(v -> {
            EmuThread.speed = EmuThread.speed == 1 ? 2 : (EmuThread.speed == 2 ? 4 : 1);
            ff.setText("» x" + EmuThread.speed);
        });
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        flp.topMargin = 140; flp.rightMargin = 24;
        root.addView(ff, flp);
        h.post(tick);
        emu = new EmuThread(view);
        emu.start();
    }

    private LinearLayout panel;

    private void showSettings() {
        if (panel != null) { root.removeView(panel); panel = null; return; }
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xEE111111);
        panel.setPadding(40, 30, 40, 30);
        TextView t = new TextView(this);
        t.setText("Ajustes");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(18);
        panel.addView(t);

        TextView lbl = new TextView(this);
        lbl.setText("Filtro xBR (bordes suaves)");
        lbl.setTextColor(0xFFCCCCCC);
        lbl.setPadding(0, 20, 0, 6);
        panel.addView(lbl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"No", "1", "2", "3", "4"};
        final Button[] bs = new Button[5];
        int cur = getPreferences(MODE_PRIVATE).getInt("xbr", 0);
        for (int i = 0; i < 5; i++) {
            final int lv = i;
            bs[i] = new Button(this);
            bs[i].setText(names[i]);
            bs[i].setAlpha(i == cur ? 1f : 0.45f);
            bs[i].setOnClickListener(v -> {
                getPreferences(MODE_PRIVATE).edit().putInt("xbr", lv).apply();
                if (view != null) view.setXbr(lv);
                for (int j = 0; j < 5; j++) bs[j].setAlpha(j == lv ? 1f : 0.45f);
            });
            row.addView(bs[i]);
        }
        panel.addView(row);

        TextView lbl2 = new TextView(this);
        lbl2.setText("Grano");
        lbl2.setTextColor(0xFFCCCCCC);
        lbl2.setPadding(0, 20, 0, 6);
        panel.addView(lbl2);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        String[] gn = {"No", "1", "2", "3"};
        final Button[] gb = new Button[4];
        int curG = getPreferences(MODE_PRIVATE).getInt("grain", 0);
        for (int i = 0; i < 4; i++) {
            final int lv = i;
            gb[i] = new Button(this);
            gb[i].setText(gn[i]);
            gb[i].setAlpha(i == curG ? 1f : 0.45f);
            gb[i].setOnClickListener(v -> {
                getPreferences(MODE_PRIVATE).edit().putInt("grain", lv).apply();
                if (view != null) view.setGrain(lv);
                for (int j = 0; j < 4; j++) gb[j].setAlpha(j == lv ? 1f : 0.45f);
            });
            row2.addView(gb[i]);
        }
        panel.addView(row2);

        TextView lbl3 = new TextView(this);
        lbl3.setText("Tamaño del grano");
        lbl3.setTextColor(0xFFCCCCCC);
        lbl3.setPadding(0, 20, 0, 6);
        panel.addView(lbl3);
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        final Button[] sb = new Button[5];
        int curS = getPreferences(MODE_PRIVATE).getInt("gsz2", 3);
        for (int i = 1; i <= 4; i++) {
            final int lv = i;
            sb[i] = new Button(this);
            sb[i].setText(String.valueOf(i));
            sb[i].setAlpha(i == curS ? 1f : 0.45f);
            sb[i].setOnClickListener(v -> {
                getPreferences(MODE_PRIVATE).edit().putInt("gsz2", lv).apply();
                if (view != null) view.setGrainSize(lv);
                for (int j = 1; j <= 4; j++) sb[j].setAlpha(j == lv ? 1f : 0.45f);
            });
            row3.addView(sb[i]);
        }
        panel.addView(row3);

        Button close = new Button(this);
        close.setText("Cerrar");
        close.setOnClickListener(v -> { root.removeView(panel); panel = null; });
        panel.addView(close);

        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private void stopGame() {
        panel = null;
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
