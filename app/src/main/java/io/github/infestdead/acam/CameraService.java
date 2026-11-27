package io.github.infestdead.acam;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.OrientationEventListener;

/**
 * Camera mode: this phone offers its camera and microphone as an RTSP stream for another phone
 * running ACam (or any RTSP player) to watch. A foreground service, so it carries on with the
 * screen off.
 *
 * The camera itself only runs while someone is connected; between viewers the phone just listens.
 */
public class CameraService extends Service implements CamServer.Listener {
    static final int PORT = 8554;
    static final String USER = CamServer.USER;
    static final String NSD_TYPE = "_acam._tcp";
    static final String ACTION_STOP = "io.github.infestdead.acam.STOP_CAMERA";
    private static final String CHANNEL = "camera";
    private static final int NOTIFICATION = 1;
    private static final long LINGER_MS = 5000;    // keep the camera warm this long after the last viewer
    private static final long RESHAPE_MS = 1500;   // the phone must stay turned this long before the picture follows
    private static final String CODE_LETTERS = "abcdefghjkmnpqrstuvwxyz23456789"; // none that look alike

    /** The running service, or null. Read by the screens on the main thread. */
    static volatile CameraService instance;
    /** Run on the main thread whenever something the camera screen shows has changed. */
    static volatile Runnable onChange;
    /** Whether the camera page is on screen: only then can the screen serve as a light. */
    static volatile boolean pageShown;
    static final int SCREEN_LEVELS = 10;

    // Remote-controlled settings, kept across runs. Written under `this`, read anywhere.
    volatile boolean front;       // using the selfie camera
    volatile int torch;           // back camera's torch: 0 off, else its strength
    volatile int screen;          // front camera's light, the screen: 0 off, else 1..SCREEN_LEVELS
    // What this phone has.
    private String backId, frontId;
    private int backTorchMax, frontTorchMax;

    volatile int viewers;
    volatile boolean streaming;   // the camera is on
    volatile String problem;      // why nothing can be seen, or null
    volatile String nsdName;      // the name this phone goes by on the network, once registered

    private final Handler main = new Handler(Looper.getMainLooper());
    // Starting and stopping the camera, in order, off the main thread: letting go of a camera can
    // take seconds, and a new one must not open before the old one is closed.
    private final java.util.concurrent.ExecutorService lifecycle = java.util.concurrent.Executors.newSingleThreadExecutor();
    private CamServer server;
    private CamCapture capture;
    private boolean sound;
    private int turned, capturedTurned;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private OrientationEventListener orientation;
    private NsdManager.RegistrationListener registration;
    private final Runnable stopCapture = this::stopCapture;
    private final Runnable reshape = () -> {
        if (capture != null && turned != capturedTurned) {
            // A different shape of picture is a different stream: end this one, viewers reconnect.
            stopCapture();
            server.dropClients();
        }
    };

    /** The code a viewer needs, made up once and kept so saved cameras keep working. */
    static String code(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("acam", 0);
        String code = prefs.getString("cameraCode", null);
        if (code == null) code = newCode(context);
        return code;
    }

    static String newCode(Context context) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 8; i++) s.append(CODE_LETTERS.charAt(random.nextInt(CODE_LETTERS.length())));
        context.getSharedPreferences("acam", 0).edit().putString("cameraCode", s.toString()).apply();
        return s.toString();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server != null) return START_NOT_STICKY; // already running
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        sound = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        | (sound ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0));
            } else {
                startForeground(NOTIFICATION, notification());
            }
        } catch (RuntimeException e) { // started from the background, where the camera is off limits
            stopSelf();
            return START_NOT_STICKY;
        }
        findCameras();
        SharedPreferences prefs = getSharedPreferences("acam", 0);
        front = prefs.getBoolean("cameraFront", false) && frontId != null || backId == null;
        torch = prefs.getInt("cameraTorch", 0);
        screen = prefs.getInt("cameraScreen", 0);
        server = new CamServer(PORT, USER, code(this), this);
        server.setAudio(sound ? CamCapture.SAMPLE_RATE : 0);
        try {
            server.start();
        } catch (java.io.IOException e) {
            server = null;
            problem = "Port " + PORT + " is in use by another app";
            instance = this;
            changed();
            return START_NOT_STICKY;
        }

        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "acam:camera");
        wakeLock.acquire();
        wifiLock = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "acam:camera");
        wifiLock.acquire();

        orientation = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int degrees) {
                if (degrees == ORIENTATION_UNKNOWN) return; // lying flat: keep what it was
                int now = (degrees + 45) / 90 * 90 % 360;
                if (now == turned) return;
                turned = now;
                main.removeCallbacks(reshape);
                main.postDelayed(reshape, RESHAPE_MS);
            }
        };
        if (orientation.canDetectOrientation()) orientation.enable();

        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(Build.MODEL);
        info.setServiceType(NSD_TYPE);
        info.setPort(PORT);
        registration = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo registered) {
                nsdName = registered.getServiceName(); // may differ if the name was taken
                changed();
            }
            @Override public void onRegistrationFailed(NsdServiceInfo i, int error) { }
            @Override public void onServiceUnregistered(NsdServiceInfo i) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo i, int error) { }
        };
        try {
            ((NsdManager) getSystemService(NSD_SERVICE)).registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (RuntimeException e) {
            registration = null; // not announced; the address still works
        }
        instance = this;
        changed();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        instance = null;
        main.removeCallbacksAndMessages(null);
        if (orientation != null) orientation.disable();
        if (registration != null) {
            try {
                ((NsdManager) getSystemService(NSD_SERVICE)).unregisterService(registration);
            } catch (RuntimeException ignored) { }
        }
        if (server != null) server.stop();
        if (capture != null) lifecycle.execute(capture::stop);
        capture = null;
        lifecycle.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        Runnable r = onChange;
        if (r != null) new Handler(Looper.getMainLooper()).post(r);
        super.onDestroy();
    }

    // CamServer.Listener: called on the server's threads.

    @Override public void onWanted(boolean wanted) {
        main.post(() -> {
            main.removeCallbacks(stopCapture);
            if (!wanted) {
                main.postDelayed(stopCapture, LINGER_MS);
            } else if (capture == null) {
                problem = null;
                capturedTurned = turned;
                boolean f = front;
                CamCapture[] made = new CamCapture[1];
                made[0] = new CamCapture(this, server, f ? frontId : backId, f, f ? frontTorchMax : backTorchMax,
                        f ? 0 : torch, sound, turned, () -> main.post(() -> captureFailed(made[0])));
                capture = made[0];
                lifecycle.execute(made[0]::start);
                streaming = true;
                changed();
            }
        });
    }

    @Override public void onViewers(int watching) {
        main.post(() -> {
            viewers = watching;
            changed();
        });
    }

    @Override public void onKeyFrameWanted() {
        main.post(() -> {
            if (capture != null) capture.requestKeyFrame();
        });
    }

    /** The first back and first front camera, and how much each torch can do. */
    private void findCameras() {
        try {
            android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                android.hardware.camera2.CameraCharacteristics about = manager.getCameraCharacteristics(id);
                Integer facing = about.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                Boolean flash = about.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                int levels = 0;
                if (flash != null && flash) {
                    levels = 1;
                    if (Build.VERSION.SDK_INT >= 35) {
                        Integer max = about.get(android.hardware.camera2.CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL);
                        if (max != null && max > 1) levels = max;
                    }
                }
                if (facing != null && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                    if (frontId == null) {
                        frontId = id;
                        frontTorchMax = levels;
                    }
                } else if (backId == null) { // back, or external: either will do as "the camera"
                    backId = id;
                    backTorchMax = levels;
                }
            }
        } catch (Exception ignored) { } // no cameras listed: the capture reports the failure
        if (backId == null && frontId == null) backId = "0";
    }

    // Called on a server thread when a viewer asks about or changes the settings.
    @Override public synchronized String control(java.util.Map<String, String> changes) {
        boolean lensChanged = false, lightChanged = false;
        String lens = changes.get("lens");
        if (lens != null) {
            boolean want = lens.equals("front") && frontId != null || backId == null;
            lensChanged = want != front;
            front = want;
        }
        String light = changes.get("light");
        if (light != null) {
            int level;
            try {
                level = Integer.parseInt(light);
            } catch (NumberFormatException e) {
                level = 0;
            }
            if (front) screen = Math.max(0, Math.min(SCREEN_LEVELS, level));
            else torch = Math.max(0, Math.min(backTorchMax, level));
            lightChanged = true;
        }
        if (lensChanged || lightChanged) {
            getSharedPreferences("acam", 0).edit().putBoolean("cameraFront", front)
                    .putInt("cameraTorch", torch).putInt("cameraScreen", screen).apply();
            boolean relens = lensChanged;
            main.post(() -> {
                if (relens && capture != null) {
                    // The other camera: start it afresh. Viewers reconnect and get its picture.
                    stopCapture();
                    server.dropClients();
                } else if (capture != null && !front) {
                    capture.setTorch(torch);
                }
                changed();
            });
        }
        StringBuilder out = new StringBuilder("acam: 1\r\n");
        out.append("lens: ").append(front ? "front" : "back").append("\r\n");
        out.append("lenses: ").append(backId != null ? "back" : "").append(backId != null && frontId != null ? " " : "")
                .append(frontId != null ? "front" : "").append("\r\n");
        if (front) {
            out.append("light-kind: screen\r\nlight: ").append(screen).append("\r\nlight-max: ").append(SCREEN_LEVELS)
                    .append("\r\nscreen-page: ").append(pageShown ? "open" : "closed").append("\r\n");
        } else {
            out.append("light-kind: ").append(backTorchMax > 0 ? "torch" : "none").append("\r\nlight: ")
                    .append(backTorchMax > 0 ? torch : 0).append("\r\nlight-max: ").append(backTorchMax).append("\r\n");
        }
        return out.toString();
    }

    private void captureFailed(CamCapture failed) {
        if (capture != failed) return; // one already being stopped
        stopCapture();
        problem = "The camera could not be started. Another app may be using it.";
        server.dropClients();
        changed();
    }

    private void stopCapture() {
        main.removeCallbacks(stopCapture);
        if (capture == null) return;
        CamCapture old = capture;
        capture = null;
        server.clearVideoConfig(); // a viewer arriving now waits for the next camera's picture
        lifecycle.execute(old::stop);
        streaming = false;
        changed();
    }

    private void changed() {
        if (instance == this && server != null) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification());
        }
        Runnable r = onChange;
        if (r != null) main.post(r);
    }

    private Notification notification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Camera mode", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while this phone is offering its camera to another phone.");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
        int n = viewers;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_camera)
                .setContentTitle("This phone is a camera")
                .setContentText(n == 0 ? "Waiting for a viewer" : n == 1 ? "1 viewer watching" : n + " viewers watching")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, CameraActivity.class),
                        PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, "Stop",
                        PendingIntent.getService(this, 1, new Intent(this, CameraService.class).setAction(ACTION_STOP),
                                PendingIntent.FLAG_IMMUTABLE)).build());
        return b.build();
    }
}
