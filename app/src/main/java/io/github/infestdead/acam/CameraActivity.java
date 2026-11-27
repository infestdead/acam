package io.github.infestdead.acam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The screen for camera mode: switch it on and off, and see what to enter on the viewing phone.
 *
 * Left open while camera mode is on, it keeps the screen on and turns it into a lamp when the
 * viewer asks for light with the selfie camera; otherwise it goes black after a little while, so
 * it lights nothing up. A touch brings the page back.
 */
public class CameraActivity extends Activity {
    private static final long IDLE_MS = 20_000;       // untouched this long: the page goes dark
    private static final long PEEK_MS = 15_000;       // a touch shows the page over the lamp this long
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, toggle, steps, address, newCode;
    private android.widget.FrameLayout lamp;           // over the page: white as a light, or black
    private TextView lampNote;
    private long touchedAt;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            show();
            handler.removeCallbacks(this);
            handler.postDelayed(this, 1000); // addresses come and go with the network; the page dims
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding((int) (24 * dp), (int) (24 * dp), (int) (24 * dp), (int) (24 * dp));

        TextView title = text(22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setText("This phone as a camera");
        page.addView(title);

        status = text(16, 0xCCFFFFFF);
        status.setPadding(0, (int) (6 * dp), 0, (int) (18 * dp));
        page.addView(status);

        toggle = text(16, Color.WHITE);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setGravity(Gravity.CENTER);
        toggle.setPadding((int) (20 * dp), (int) (13 * dp), (int) (20 * dp), (int) (13 * dp));
        toggle.setOnClickListener(v -> {
            if (CameraService.instance != null) {
                startService(new Intent(this, CameraService.class).setAction(CameraService.ACTION_STOP));
            } else {
                begin();
            }
        });
        page.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        steps = text(15, 0xCCFFFFFF);
        steps.setPadding(0, (int) (22 * dp), 0, 0);
        steps.setLineSpacing(3 * dp, 1f);
        page.addView(steps);

        address = text(15, Color.WHITE);
        address.setTypeface(Typeface.MONOSPACE);
        address.setTextIsSelectable(true);
        address.setPadding(0, (int) (10 * dp), 0, 0);
        address.setLineSpacing(4 * dp, 1f);
        page.addView(address);

        newCode = text(14, 0xFF64B5F6);
        newCode.setText("Make a new code");
        newCode.setPadding(0, (int) (16 * dp), 0, (int) (8 * dp));
        newCode.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage("Phones that saved this camera with the old code will have to add it again.")
                .setPositiveButton("New code", (d, w) -> {
                    CameraService.newCode(this);
                    show();
                })
                .setNegativeButton("Cancel", null)
                .show());
        page.addView(newCode);

        TextView notes = text(13, 0x99FFFFFF);
        notes.setPadding(0, (int) (18 * dp), 0, 0);
        notes.setLineSpacing(3 * dp, 1f);
        notes.setText("Keep this phone plugged in, and stand it either way up. The camera only runs while someone "
                + "is watching. The viewing phone can switch between the back and the selfie camera, and turn on "
                + "a light: the torch, or with the selfie camera this screen \u2014 for that, leave this page open; "
                + "it goes dark by itself. Otherwise you can switch the screen off.\n\n"
                + "A phone has no night vision: in a dark room leave a dim light on.\n\n"
                + "The picture and sound are sent unencrypted. Use this on a network you trust, or over a VPN "
                + "such as Tailscale.");
        page.addView(notes);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFitsSystemWindows(true);
        scroll.addView(page);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(scroll);
        lamp = new android.widget.FrameLayout(this);
        lampNote = text(13, 0x66000000);
        lampNote.setGravity(Gravity.CENTER);
        lampNote.setPadding((int) (24 * dp), 0, (int) (24 * dp), (int) (32 * dp));
        lamp.addView(lampNote, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        lamp.setVisibility(android.view.View.GONE);
        root.addView(lamp, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            boolean covered = lamp.getVisibility() == android.view.View.VISIBLE;
            touchedAt = android.os.SystemClock.uptimeMillis();
            show();
            if (covered) return true; // this touch only uncovers the page
        }
        return super.dispatchTouchEvent(e);
    }

    /** White at the asked brightness while lighting, black while idle, gone while in use. */
    private void showLamp(CameraService service) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean lighting = service != null && service.front && service.streaming && service.screen > 0;
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        float brightness;
        if (service == null) {
            lamp.setVisibility(android.view.View.GONE);
            brightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        } else if (lighting && now - touchedAt > PEEK_MS) {
            lamp.setBackgroundColor(Color.WHITE);
            lampNote.setText("Lit from the viewing phone. Touch to see the page.");
            lamp.setVisibility(android.view.View.VISIBLE);
            brightness = service.screen / (float) CameraService.SCREEN_LEVELS;
        } else if (!lighting && now - touchedAt > IDLE_MS) {
            lamp.setBackgroundColor(Color.BLACK);
            lampNote.setText("");
            lamp.setVisibility(android.view.View.VISIBLE);
            brightness = 0f; // the lowest the screen goes
        } else {
            lamp.setVisibility(android.view.View.GONE);
            brightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        if (lp.screenBrightness != brightness) {
            lp.screenBrightness = brightness;
            getWindow().setAttributes(lp);
        }
        // While camera mode is on the screen stays on, so the lamp is there when it is asked for.
        if (service != null) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (lamp.getVisibility() == android.view.View.VISIBLE) {
            getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        CameraService.pageShown = true;
        CameraService.onChange = this::show;
        touchedAt = android.os.SystemClock.uptimeMillis();
        refresh.run();
    }

    @Override
    protected void onStop() {
        super.onStop();
        CameraService.pageShown = false;
        CameraService.onChange = null;
        handler.removeCallbacks(refresh);
    }

    private void begin() {
        java.util.ArrayList<String> ask = new java.util.ArrayList<>();
        for (String p : wanted()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        }
        if (ask.isEmpty()) startCamera();
        else requestPermissions(ask.toArray(new String[0]), 1);
    }

    private static String[] wanted() {
        return Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        // Only the camera is essential: without the microphone the stream is silent, and without
        // notifications the service still runs.
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            status.setText("ACam needs the camera permission for this. Allow it in the phone's settings.");
        }
    }

    private void startCamera() {
        Intent i = new Intent(this, CameraService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void show() {
        float dp = getResources().getDisplayMetrics().density;
        CameraService service = CameraService.instance;
        boolean on = service != null;
        showLamp(service);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(on ? 0xFF424242 : 0xFF1E88E5);
        bg.setCornerRadius(12 * dp);
        toggle.setBackground(bg);
        toggle.setText(on ? "Stop" : "Start");
        newCode.setVisibility(on ? android.view.View.GONE : android.view.View.VISIBLE); // the running server keeps its code
        if (!on) {
            status.setText("Off");
            status.setTextColor(0xCCFFFFFF);
            steps.setText("Stand this phone where it can see what you want to watch, then watch from another "
                    + "phone running ACam, or any RTSP player.");
            address.setText("");
            return;
        }
        int n = service.viewers;
        String problem = service.problem;
        if (problem != null) {
            status.setText(problem);
            status.setTextColor(0xFFFF8A80);
        } else {
            status.setText(n == 0 ? "On — waiting for a viewer" : n == 1 ? "On — 1 viewer watching" : "On — " + n + " viewers watching");
            status.setTextColor(n == 0 ? 0xCCFFFFFF : 0xFF4CD964);
        }
        String code = CameraService.code(this), name = service.nsdName;
        steps.setText("On the other phone open ACam and tap Add camera.\n\n"
                + "On the same Wi‑Fi this phone is listed there"
                + (name != null ? " as “" + name + "”" : "") + ". Tap it and enter this code:");
        StringBuilder a = new StringBuilder(code).append("\n\n");
        java.util.List<String> hosts = addresses();
        if (hosts.isEmpty()) {
            a.append("This phone is not on a network.");
        } else {
            a.append(hosts.size() == 1 ? "Or type the address:" : "Or type one of the addresses:");
            for (String h : hosts) {
                a.append("\n").append("rtsp://").append(CameraService.USER).append(':').append(code).append('@')
                        .append(h).append(':').append(CameraService.PORT).append('/');
            }
        }
        address.setText(a);
    }

    /** This phone's addresses another device might reach: Wi-Fi, hotspot, VPN; not the mobile network. */
    static java.util.List<String> addresses() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            for (java.net.NetworkInterface i : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                String name = i.getName();
                if (!i.isUp() || i.isLoopback() || name.startsWith("rmnet") || name.startsWith("ccmni")
                        || name.startsWith("pdp") || name.startsWith("dummy") || name.contains("clat")) {
                    continue;
                }
                for (java.net.InetAddress address : java.util.Collections.list(i.getInetAddresses())) {
                    if (address instanceof java.net.Inet4Address) out.add(address.getHostAddress());
                }
            }
        } catch (java.net.SocketException | RuntimeException ignored) { }
        return out;
    }

    private TextView text(float size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }
}
