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
            if (dbg != null && emu != null) dbg.setText(Native.info() + String.format("\nfps=%.1f  runFrame=%.1f ms", EmuThread.fps, EmuThread.runMs));
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
        h.post(tick);
        emu = new EmuThread(view);
        emu.start();
    }

    private void stopGame() {
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
