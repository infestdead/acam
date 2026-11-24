package io.github.infestdead.acam;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;



public class MainActivity extends Activity {
    /** A camera the user added: a label and its rtsp:// or rtsps:// address. */
    private static final class Cam {
        String name, url;
        String pin; // SHA-256 of the certificate an rtsps camera showed when first connected, or null
    }

    private final java.util.ArrayList<Cam> cams = new java.util.ArrayList<>();
    private TextView cameraChip;
    private android.app.AlertDialog certDialog;
    private static final float MAX_ZOOM = 8f;
    private static final long RETRY_MS = 2000;
    private static final long FLASH_PAUSE_MS = 2000; // gap before an ongoing alarm flashes again

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private TextureView video;
    private TextView status;
    private ScaleGestureDetector scaleDetector;

    private float zoom = 1f, panX = 0f, panY = 0f;
    private float lastX, lastY;
    private int activePointer = MotionEvent.INVALID_POINTER_ID;
    private float videoAspect = 16f / 9f;
    private boolean surfaceReady = false;
    private boolean started = false;

    private final Runnable restart = this::startStream;

    // Liveness badge: frames are counted as they are rendered, so a paused/stalled stream shows up.
    private static final long STALE_MS = 1500;       // no frame for this long -> "NOT LIVE"
    private static final long GIVE_UP_MS = 10_000;   // no frame for this long -> reconnect
    private android.widget.LinearLayout badge;        // pill holding the text and the frame odometer
    private TextView badgeText;
    private Odometer odometer;
    private android.widget.LinearLayout picker;
    private android.widget.LinearLayout controls;     // button row, drop-down and, upright, the badge
    private FrameLayout.LayoutParams badgeLp;         // the badge's place when the screen is on its side
    private int camera;
    private boolean muted = true; // start silent; the choice is remembered
    private android.widget.ImageView speaker;
    private final AudioLevels levels = new AudioLevels();
    private final Trace moves = new Trace();  // how much of the picture was moving, for the graph
    private WaveView wave;
    private boolean waveOn = true;        // sound level line; independent of the speaker
    private android.widget.ImageView waveButton;
    private FrameLayout stage;            // video + motion overlay; zoom and pan move both together
    private MotionView motion;
    private boolean peaking = false;      // highlight small movements; the choice is remembered
    private android.widget.ImageView peakButton;
    private long peekedFrames = -1;
    // Polled rather than driven by the TextureView's frame callback: the player installs its own
    // SurfaceTextureListener on the view, which replaces ours once a stream starts.
    private final Runnable peek = new Runnable() {
        @Override public void run() {
            long count = frames.get();
            if ((peaking || motionOn) && count != peekedFrames && video.isAvailable()) {
                peekedFrames = count;
                analyseMotion();
            }
            // Slices are stamped with the moment they are heard, so the flash lands with the sound.
            long up = android.os.SystemClock.uptimeMillis();
            if (soundOn && levels.peakBetween(soundCheckedAt, up) >= soundLimit()) flashAlarm();
            soundCheckedAt = up;
            handler.postDelayed(this, MotionView.INTERVAL_MS);
        }
    };
    private android.widget.LinearLayout tuner; // drop-down with the sliders, opened by the tune button
    private android.widget.ImageView tuneButton;
    private boolean tunerOpen = false;
    private TextView highlightReadout, soundReadout, motionReadout; // each shows its slider's position
    // Alarm sliders, 0..100: further right = triggers more easily. Each alarm also has its own
    // on/off switch (its label in the drop-down), so it can be silenced without losing the setting.
    // All remembered.
    private boolean soundOn = true, motionOn = true;
    private TextView soundChip, motionChip, rowLabel;   // rowLabel / rowBar: the row tunerRow just built
    private android.widget.SeekBar soundBar, motionBar, rowBar;
    private static final int DEFAULT_SENSITIVITY = 50;
    private static final int DEFAULT_SOUND_ALARM = 26;  // where the sound line turns red
    private static final int DEFAULT_MOTION_ALARM = 70; // 1% of the picture clearly moving
    private static final int DETENT = 2;  // a slider this close to its default snaps onto it
    private int soundAlarm = DEFAULT_SOUND_ALARM;
    private int motionAlarm = DEFAULT_MOTION_ALARM;
    private boolean audioLoaded;          // whether the running stream was started with its audio track
    private long soundCheckedAt;
    private View flash;                   // red sheet over everything, pulsed by an alarm
    private android.animation.ObjectAnimator flashAnim;
    private long flashQuietUntil;
    private final java.util.concurrent.atomic.AtomicLong frames = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong liveMs = new java.util.concurrent.atomic.AtomicLong(); // time the picture has been moving
    private volatile long lastFrameAt;                // elapsedRealtime of the last rendered frame, 0 = none yet
    private long streamStartedAt, prevFrames, prevTickAt;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateBadge();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        video = new TextureView(this);
        stage.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        motion = new MotionView(this);
        stage.addView(motion, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setText("Connecting…");
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        badge = new android.widget.LinearLayout(this);
        badge.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badgeText = new TextView(this);
        badgeText.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        badgeText.setTextSize(14);
        badge.addView(badgeText);
        odometer = new Odometer(this, liveMs);
        android.widget.LinearLayout.LayoutParams olp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.setMargins((int) (10 * getResources().getDisplayMetrics().density), 0, 0, 0);
        badge.addView(odometer, olp);
        float dp = getResources().getDisplayMetrics().density;
        badge.setPadding((int) (10 * dp), (int) (5 * dp), (int) (10 * dp), (int) (5 * dp));
        badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        badgeLp.setMargins(0, (int) (16 * dp), (int) (16 * dp), 0);
        root.addView(badge, badgeLp);
        try {
            loadCams();
            camera = Math.max(0, Math.min(cams.size() - 1, getSharedPreferences("acam", 0).getInt("camera", 0)));
            muted = getSharedPreferences("acam", 0).getBoolean("muted", true);
            peaking = getSharedPreferences("acam", 0).getBoolean("peaking", false);
            waveOn = getSharedPreferences("acam", 0).getBoolean("wave", true);
            soundAlarm = getSharedPreferences("acam", 0).getInt("soundAlarm", soundAlarm);
            motionAlarm = getSharedPreferences("acam", 0).getInt("motionAlarm", motionAlarm);
            soundOn = getSharedPreferences("acam", 0).getBoolean("soundOn", true);
            motionOn = getSharedPreferences("acam", 0).getBoolean("motionOn", true);
        } catch (RuntimeException ignored) { }
        picker = new android.widget.LinearLayout(this);
        picker.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        // One chip names the camera on screen; tapping it opens the list to switch, add or edit.
        cameraChip = new TextView(this);
        cameraChip.setTextSize(14);
        cameraChip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cameraChip.setTextColor(Color.WHITE);
        cameraChip.setSingleLine(true);
        cameraChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cameraChip.setMaxWidth((int) (150 * dp));
        cameraChip.setPadding((int) (12 * dp), (int) (7 * dp), (int) (12 * dp), (int) (7 * dp));
        cameraChip.setOnClickListener(v -> {
            if (cams.isEmpty()) editCamera(-1);
            else chooseCamera();
        });
        android.widget.LinearLayout.LayoutParams tlp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(0, 0, (int) (6 * dp), 0);
        picker.addView(cameraChip, tlp);
        speaker = new android.widget.ImageView(this);
        speaker.setPadding((int) (12 * dp), (int) (5 * dp), (int) (12 * dp), (int) (5 * dp));
        speaker.setOnClickListener(v -> toggleMute());
        android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, 0, (int) (6 * dp), 0);
        picker.addView(speaker, slp);
        waveButton = new android.widget.ImageView(this);
        waveButton.setImageResource(R.drawable.ic_wave);
        waveButton.setPadding((int) (12 * dp), (int) (5 * dp), (int) (12 * dp), (int) (5 * dp));
        waveButton.setOnClickListener(v -> toggleWave());
        picker.addView(waveButton, slp);
        peakButton = new android.widget.ImageView(this);
        peakButton.setImageResource(R.drawable.ic_motion);
        peakButton.setPadding((int) (12 * dp), (int) (5 * dp), (int) (12 * dp), (int) (5 * dp));
        peakButton.setOnClickListener(v -> togglePeaking());
        android.widget.LinearLayout.LayoutParams plp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, 0, (int) (6 * dp), 0);
        picker.addView(peakButton, plp);
        tuneButton = new android.widget.ImageView(this);
        tuneButton.setImageResource(R.drawable.ic_tune);
        tuneButton.setPadding((int) (12 * dp), (int) (5 * dp), (int) (12 * dp), (int) (5 * dp));
        tuneButton.setOnClickListener(v -> {
            tunerOpen = !tunerOpen;
            updatePicker();
        });
        picker.addView(tuneButton);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        FrameLayout.LayoutParams pickerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        pickerLp.setMargins((int) (16 * dp), (int) (16 * dp), 0, 0);
        controls = new android.widget.LinearLayout(this);
        controls.setOrientation(android.widget.LinearLayout.VERTICAL);
        // Explicit wrap: the default (match parent) would squeeze the row to the drop-down's width.
        controls.addView(picker, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Drop-down under the tune button. Every slider: right = more sensitive.
        tuner = new android.widget.LinearLayout(this);
        tuner.setOrientation(android.widget.LinearLayout.VERTICAL);
        tuner.setPadding((int) (6 * dp), (int) (4 * dp), (int) (12 * dp), (int) (4 * dp));
        android.graphics.drawable.GradientDrawable tbg = new android.graphics.drawable.GradientDrawable();
        tbg.setColor(0x99000000);
        tbg.setCornerRadius(12 * dp);
        tuner.setBackground(tbg);
        int sens = DEFAULT_SENSITIVITY;
        try {
            sens = getSharedPreferences("acam", 0).getInt("sensitivity", DEFAULT_SENSITIVITY);
        } catch (RuntimeException ignored) { }
        motion.setSensitivity(sens);
        // Movement highlight.
        highlightReadout = tunerRow("Highlight", "sensitivity", sens, DEFAULT_SENSITIVITY, 0xFF00E5FF, value -> {
            motion.setSensitivity(value);
            highlightReadout.setText(String.valueOf(value));
        });
        highlightReadout.setText(String.valueOf(sens));
        soundReadout = tunerRow("Sound alarm", "soundAlarm", soundAlarm, DEFAULT_SOUND_ALARM, 0xFFFF3B30, value -> {
            soundAlarm = value;
            updateAlarms();
        });
        soundChip = rowLabel;
        soundBar = rowBar;
        soundChip.setOnClickListener(v -> {
            soundOn = !soundOn;
            try {
                getSharedPreferences("acam", 0).edit().putBoolean("soundOn", soundOn).apply();
            } catch (RuntimeException ignored) { }
            updateAlarms();
            syncAudio(); // the alarm may be the only listener
        });
        motionReadout = tunerRow("Move alarm", "motionAlarm", motionAlarm, DEFAULT_MOTION_ALARM, 0xFFFF3B30, value -> {
            motionAlarm = value;
            updateAlarms();
        });
        motionChip = rowLabel;
        motionBar = rowBar;
        motionChip.setOnClickListener(v -> {
            motionOn = !motionOn;
            try {
                getSharedPreferences("acam", 0).edit().putBoolean("motionOn", motionOn).apply();
            } catch (RuntimeException ignored) { }
            if (!peaking) motion.reset(); // analysis starts / stops
            updateAlarms();
        });
        android.widget.LinearLayout.LayoutParams tunerLp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tunerLp.setMargins(0, (int) (8 * dp), 0, 0);
        controls.addView(tuner, tunerLp);
        root.addView(controls, pickerLp);
        wave = new WaveView(this, levels, moves);
        root.addView(wave, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (72 * dp), Gravity.BOTTOM));
        flash = new View(this); // not clickable: touches fall through to the controls and the video
        flash.setBackgroundColor(0xFFFF0000);
        flash.setAlpha(0f);
        root.addView(flash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        flashAnim = android.animation.ObjectAnimator.ofFloat(flash, "alpha", 0f, 0.5f, 0f);
        flashAnim.setDuration(400);
        flashAnim.setRepeatCount(2); // three pulses
        flashAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                flash.setAlpha(0f);
                flashQuietUntil = android.os.SystemClock.elapsedRealtime() + FLASH_PAUSE_MS;
            }
        });
        updatePicker();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = (int) (16 * dp), right = (int) (16 * dp), left = (int) (16 * dp);
            if (android.os.Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                top += insets.getDisplayCutout().getSafeInsetTop();
                right += insets.getDisplayCutout().getSafeInsetRight();
                left += insets.getDisplayCutout().getSafeInsetLeft();
            }
            pickerLp.setMargins(left, top, 0, 0);
            controls.setLayoutParams(pickerLp);
            badgeLp.setMargins(0, top, right, 0);
            if (badge.getParent() != controls) badge.setLayoutParams(badgeLp);
            return insets;
        });
        updateBadge();
        badge.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (l != ol || r != or) v.post(this::sizeTuner); // the badge grew, shrank or moved
        });

        setContentView(root);
        if (cams.isEmpty()) root.post(() -> editCamera(-1)); // first run: straight to the form
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (r - l != or - ol || bt - t != ob - ot) {
                fitVideo();
                boolean tall = bt - t > r - l;
                v.post(() -> { // not while a layout pass is running
                    placeBadge(tall);
                    sizeTuner();
                });
            }
        });
        hideSystemBars();

        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float newZoom = clamp(zoom * d.getScaleFactor(), 1f, MAX_ZOOM);
                        float f = newZoom / zoom;
                        // keep the point under the fingers stationary
                        float fx = d.getFocusX() - stage.getWidth() / 2f;
                        float fy = d.getFocusY() - stage.getHeight() / 2f;
                        panX = fx - (fx - panX) * f;
                        panY = fy - (fy - panY) * f;
                        zoom = newZoom;
                        applyTransform();
                        return true;
                    }
                });

        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                surfaceReady = true;
                if (started) startStream();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
                fitVideo();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                surfaceReady = false;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        soundCheckedAt = android.os.SystemClock.uptimeMillis();
        handler.post(tick);
        handler.post(peek);
        if (surfaceReady) startStream();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        handler.removeCallbacks(restart);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(peek);
        flashAnim.cancel();
        releasePlayer();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void startStream() {
        handler.removeCallbacks(restart);
        releasePlayer();
        if (!started || !surfaceReady) return;
        if (cams.isEmpty()) {
            status.setText("Add a camera to begin");
            status.setVisibility(View.VISIBLE);
            return;
        }
        final Cam cam = cams.get(camera);
        status.setText("Connecting…");
        status.setVisibility(View.VISIBLE);
        frames.set(0);
        liveMs.set(0);
        lastFrameAt = 0;
        streamStartedAt = android.os.SystemClock.elapsedRealtime();
        prevFrames = 0;
        prevTickAt = streamStartedAt;
        levels.clear();
        moves.clear();
        motion.reset();
        audioLoaded = wantAudio();

        player = new ExoPlayer.Builder(this, new androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            @Override
            protected void buildAudioRenderers(android.content.Context ctx, int extensionMode,
                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector selector, boolean fallback,
                    androidx.media3.exoplayer.audio.AudioSink sink, Handler handler,
                    androidx.media3.exoplayer.audio.AudioRendererEventListener listener,
                    java.util.ArrayList<androidx.media3.exoplayer.Renderer> out) {
                out.add(new AacFixRenderer(ctx, selector, fallback, handler, listener, sink));
            }

            @Override
            protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(android.content.Context ctx,
                    boolean floatOutput, boolean playbackParams) {
                // Tap the decoded PCM on its way to the speaker to draw the level line.
                return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(ctx)
                        .setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[]{
                                new androidx.media3.exoplayer.audio.TeeAudioProcessor(levels)})
                        .build();
            }

            @Override
            protected void buildVideoRenderers(android.content.Context ctx, int extensionMode,
                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector selector, boolean fallback,
                    Handler handler, androidx.media3.exoplayer.video.VideoRendererEventListener listener,
                    long joiningMs, java.util.ArrayList<androidx.media3.exoplayer.Renderer> out) {
                out.add(new HevcCsdRenderer(new androidx.media3.exoplayer.video.MediaCodecVideoRenderer.Builder(ctx)
                        .setMediaCodecSelector(selector)
                        .setAllowedJoiningTimeMs(joiningMs)
                        .setEnableDecoderFallback(fallback)
                        .setEventHandler(handler)
                        .setEventListener(listener)
                        .setMaxDroppedFramesToNotify(50)));
            }
        }.setEnableDecoderFallback(true)).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                // Audio is loaded for the speaker, the level line or the sound alarm; with none, skip it
                // (lower latency).
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audioLoaded)
                .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
                // The camera offers AAC and Opus; Media3's RTSP Opus reader fails on it, AAC works.
                .setPreferredAudioMimeTypes(androidx.media3.common.MimeTypes.AUDIO_AAC)
                .setForceHighestSupportedBitrate(true)
                .build());
        player.setVideoTextureView(video);
        player.setVolume(muted ? 0f : 1f); // silent, but the level line still sees the sound
        // Never let audio focus pause a live view: another app's sound must not stall the stream.
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false);
        player.setVideoFrameMetadataListener((presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
            frames.incrementAndGet();
            long at = android.os.SystemClock.elapsedRealtime(), before = lastFrameAt;
            // Time counts only across frames that follow each other; a stall adds nothing.
            if (before != 0) liveMs.addAndGet(Math.min(200, at - before));
            lastFrameAt = at;
        });
        RtspMediaSource.Factory source = new RtspMediaSource.Factory().setForceUseRtpTcp(true);
        if (cam.url.regionMatches(true, 0, "rtsps:", 0, 6)) {
            // Media3's RTSP client has no TLS of its own. The certificate is remembered on the main
            // thread; the factory reports it from the network thread.
            source.setSocketFactory(new TlsSocketFactory(cam.pin, fingerprint -> handler.post(() -> {
                cam.pin = fingerprint;
                saveCams();
            })));
        }
        player.setMediaSource(source.createMediaSource(MediaItem.fromUri(Uri.parse(cam.url))));
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                if (size.width > 0 && size.height > 0) {
                    videoAspect = size.width * size.pixelWidthHeightRatio / size.height;
                    fitVideo();
                }
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) status.setVisibility(View.GONE);
            }
            @Override public void onPlayerError(PlaybackException error) {
                for (Throwable t = error; t != null; t = t.getCause()) {
                    if (t instanceof CertChangedException) {
                        certChanged(cam, ((CertChangedException) t).fingerprint);
                        return; // no retry until the user has decided
                    }
                }
                if (started) {
                    status.setText("Reconnecting…");
                    status.setVisibility(View.VISIBLE);
                    handler.removeCallbacks(restart);
                    handler.postDelayed(restart, RETRY_MS);
                }
            }
        });
        player.setPlayWhenReady(true);
        player.prepare();
    }

    /**
     * Upright there is no room for the badge beside the buttons, so it goes under them (and under
     * the drop-down while that is open); on its side the screen is wide and it sits top right.
     */
    private void placeBadge(boolean below) {
        float dp = getResources().getDisplayMetrics().density;
        ViewGroup want = below ? controls : (ViewGroup) controls.getParent();
        if (badge.getParent() == want) return;
        ((ViewGroup) badge.getParent()).removeView(badge);
        if (below) {
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, (int) (8 * dp), 0, 0);
            controls.addView(badge, lp);
        } else {
            want.addView(badge, want.indexOfChild(controls), badgeLp); // under the buttons, as before
        }
    }

    /**
     * The drop-down is as wide as there is room: on its side up to the badge at the top right,
     * upright the whole screen.
     */
    private void sizeTuner() {
        View root = (View) controls.getParent();
        if (root == null || root.getWidth() == 0) return;
        float dp = getResources().getDisplayMetrics().density;
        int end = badge.getParent() == controls ? root.getWidth() - (int) (16 * dp) : badge.getLeft() - (int) (8 * dp);
        int width = Math.max((int) (300 * dp), end - controls.getLeft());
        ViewGroup.LayoutParams lp = tuner.getLayoutParams();
        if (lp.width != width) {
            lp.width = width;
            tuner.setLayoutParams(lp);
        }
    }

    private void toggleMute() {
        muted = !muted;
        try {
            getSharedPreferences("acam", 0).edit().putBoolean("muted", muted).apply();
        } catch (RuntimeException ignored) { }
        updatePicker();
        syncAudio();
    }

    private void toggleWave() {
        waveOn = !waveOn;
        try {
            getSharedPreferences("acam", 0).edit().putBoolean("wave", waveOn).apply();
        } catch (RuntimeException ignored) { }
        updatePicker();
        syncAudio();
    }

    private boolean wantAudio() {
        return !muted || waveOn || soundOn;
    }

    /** After a change to who listens: reconnect with / without the audio track, or just set the volume. */
    private void syncAudio() {
        if (player == null || wantAudio() != audioLoaded) startStream();
        else player.setVolume(muted ? 0f : 1f);
    }

    /** Sound level (0..1 of the line's height) that sets the alarm off. */
    private float soundLimit() {
        return 1f - 0.95f * soundAlarm / 100f;
    }

    /**
     * Share of the picture, in percent, that has to be clearly moving to set the alarm off: 25% at
     * the insensitive end down to 0.25% at the other. Measured on the real camera, a still room
     * reads up to 0.2% (the clock in the corner), so the default of 1% leaves a wide margin.
     */
    private float motionLimit() {
        return 25f * (float) Math.pow(0.01, (motionAlarm - 1) / 99.0);
    }

    private void updateAlarms() {
        // The readouts are the sensitivity itself (it grows to the right), not the limit behind it.
        soundReadout.setText(String.valueOf(soundAlarm));
        motionReadout.setText(String.valueOf(motionAlarm));
        soundReadout.setAlpha(soundOn ? 1f : 0.35f); // dimmed with its slider while the alarm is off
        motionReadout.setAlpha(motionOn ? 1f : 0.35f);
        styleAlarm(soundChip, soundBar, soundOn);
        styleAlarm(motionChip, motionBar, motionOn);
        // Limit lines are shown while adjusting. The movement line is drawn whenever the picture is
        // being analysed, i.e. for the highlight or for the movement alarm.
        wave.setLimit(tunerOpen && soundOn ? soundLimit() : -1f);
        wave.setMoveLimit(tunerOpen && motionOn ? moveLevel(motionLimit()) : -1f);
        wave.setShown(waveOn, peaking || motionOn);
        wave.setVisibility(waveOn || peaking || motionOn ? View.VISIBLE : View.GONE);
    }

    /** An alarm's label is its switch: a blue pill while on, like the buttons in the top row. */
    private void styleAlarm(TextView chip, android.widget.SeekBar bar, boolean on) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(on ? 0xCC1E88E5 : 0x33FFFFFF);
        bg.setCornerRadius(10 * getResources().getDisplayMetrics().density);
        chip.setBackground(bg);
        chip.setTextColor(on ? Color.WHITE : 0xCCFFFFFF);
        bar.setAlpha(on ? 1f : 0.35f); // still adjustable while off
    }

    /**
     * Where a share of the picture that is moving (percent) sits on the graph, 0..1. Logarithmic,
     * 0.05% to 50%: a still room hovers near the bottom, and the alarm's whole range (0.25% to 25%)
     * lies between a quarter and nine tenths of the height.
     */
    private static float moveLevel(float percent) {
        return clamp((float) Math.log10(Math.max(percent, 0.05f) / 0.05f) / 3f, 0f, 1f);
    }

    private void flashAlarm() {
        if (flashAnim.isRunning() || android.os.SystemClock.elapsedRealtime() < flashQuietUntil) return;
        flashAnim.start();
    }

    /**
     * One labelled slider (0..100) in the drop-down; returns its readout on the right. The default is
     * marked with a dot on the line; the thumb snaps onto it, with a tick you can feel.
     */
    private TextView tunerRow(String label, String key, int value, int def, int color,
            java.util.function.IntConsumer onChange) {
        float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(0xCCFFFFFF);
        name.setTextSize(12);
        name.setPadding((int) (8 * dp), (int) (4 * dp), (int) (8 * dp), (int) (4 * dp));
        android.widget.LinearLayout.LayoutParams nlp = new android.widget.LinearLayout.LayoutParams(
                (int) (88 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(0, (int) (3 * dp), (int) (4 * dp), (int) (3 * dp));
        row.addView(name, nlp);
        rowLabel = name;
        // A bare dot on the left, a dot sending out waves on the right: right picks up more.
        android.widget.ImageView less = new android.widget.ImageView(this);
        less.setImageResource(R.drawable.ic_sens_low);
        less.setColorFilter(color);
        row.addView(less, new android.widget.LinearLayout.LayoutParams((int) (18 * dp), (int) (18 * dp)));
        DetentBar slider = new DetentBar(this, def);
        rowBar = slider;
        slider.setMax(100);
        slider.setProgress(value);
        android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(color);
        slider.setProgressTintList(tint);
        slider.setThumbTintList(tint);
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            private int last = value;
            @Override public void onProgressChanged(android.widget.SeekBar bar, int v, boolean fromUser) {
                if (fromUser && v != def && Math.abs(v - def) <= DETENT) {
                    bar.setProgress(def); // comes straight back here with the default
                    return;
                }
                if (v == def && last != def) {
                    bar.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                }
                last = v;
                onChange.accept(v);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                try {
                    getSharedPreferences("acam", 0).edit().putInt(key, bar.getProgress()).apply();
                } catch (RuntimeException ignored) { }
                syncAudio(); // the sound alarm may have been switched on or off
            }
        });
        // The slider takes whatever width the drop-down has: the longer the line, the finer the setting.
        row.addView(slider, new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.ImageView more = new android.widget.ImageView(this);
        more.setImageResource(R.drawable.ic_sens_high);
        more.setColorFilter(color);
        row.addView(more, new android.widget.LinearLayout.LayoutParams((int) (18 * dp), (int) (18 * dp)));
        TextView readout = new TextView(this);
        readout.setTextColor(color);
        readout.setTextSize(13);
        readout.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        readout.setMinWidth((int) (48 * dp));
        readout.setGravity(Gravity.END);
        row.addView(readout);
        tuner.addView(row);
        return readout;
    }

    private void analyseMotion() {
        // The part of the picture that is on screen, as fractions of the whole frame.
        View root = (View) stage.getParent();
        float sw = stage.getWidth(), sh = stage.getHeight();
        if (sw <= 0 || sh <= 0) return;
        float l = clamp(sw / 2f + (-root.getWidth() / 2f - panX) / zoom, 0f, sw) / sw;
        float r = clamp(sw / 2f + (root.getWidth() / 2f - panX) / zoom, 0f, sw) / sw;
        float t = clamp(sh / 2f + (-root.getHeight() / 2f - panY) / zoom, 0f, sh) / sh;
        float b = clamp(sh / 2f + (root.getHeight() / 2f - panY) / zoom, 0f, sh) / sh;
        motion.analyse(video, videoAspect, zoom, l, t, r, b);
        float moving = motion.moving();
        if (moving >= 0f) moves.push(moveLevel(moving), android.os.SystemClock.uptimeMillis());
        if (motionOn && moving >= motionLimit()) flashAlarm();
    }

    private void togglePeaking() {
        peaking = !peaking;
        try {
            getSharedPreferences("acam", 0).edit().putBoolean("peaking", peaking).apply();
        } catch (RuntimeException ignored) { }
        motion.reset();
        updatePicker();
    }

    private void loadCams() {
        cams.clear();
        try {
            org.json.JSONArray list = new org.json.JSONArray(
                    getSharedPreferences("acam", 0).getString("cameras", "[]"));
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject o = list.getJSONObject(i);
                Cam c = new Cam();
                c.name = o.getString("name");
                c.url = o.getString("url");
                c.pin = o.isNull("pin") ? null : o.getString("pin");
                cams.add(c);
            }
        } catch (org.json.JSONException | RuntimeException ignored) { }
    }

    private void saveCams() {
        try {
            org.json.JSONArray list = new org.json.JSONArray();
            for (Cam c : cams) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", c.name);
                o.put("url", c.url);
                if (c.pin != null) o.put("pin", c.pin);
                list.put(o);
            }
            getSharedPreferences("acam", 0).edit()
                    .putString("cameras", list.toString()).putInt("camera", camera).apply();
        } catch (org.json.JSONException | RuntimeException ignored) { }
    }

    /** The list behind the camera chip: tap a camera to watch it, or add / edit from the buttons. */
    private void chooseCamera() {
        String[] names = new String[cams.size()];
        for (int i = 0; i < names.length; i++) names[i] = cams.get(i).name;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Cameras")
                .setSingleChoiceItems(names, camera, (d, i) -> {
                    d.dismiss();
                    selectCamera(i);
                })
                .setPositiveButton("Add camera", (d, w) -> editCamera(-1))
                .setNeutralButton("Edit \u201C" + cams.get(camera).name + "\u201D", (d, w) -> editCamera(camera))
                .show();
    }

    /** Add a camera (index -1) or change / delete an existing one. */
    private void editCamera(int index) {
        float dp = getResources().getDisplayMetrics().density;
        Cam old = index < 0 ? null : cams.get(index);
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        form.setPadding((int) (22 * dp), (int) (8 * dp), (int) (22 * dp), 0);
        android.widget.EditText name = new android.widget.EditText(this);
        name.setHint("Name, e.g. Nursery");
        name.setSingleLine(true);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        android.widget.EditText url = new android.widget.EditText(this);
        url.setHint("rtsp://… or rtsps://…");
        url.setSingleLine(true);
        // The address usually carries a password or a secret token: keep it out of keyboard learning.
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        url.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (old != null) {
            name.setText(old.name);
            url.setText(old.url);
        }
        TextView hint = new TextView(this);
        hint.setText("The stream address from your camera or recorder. H.264 or H.265 video; "
                + "sound is tested with AAC audio.");
        hint.setTextSize(12);
        hint.setTextColor(0x99FFFFFF);
        hint.setPadding((int) (4 * dp), (int) (6 * dp), (int) (4 * dp), 0);
        form.addView(name);
        form.addView(url);
        form.addView(hint);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle(old == null ? "Add camera" : "Edit camera")
                .setView(form)
                .setPositiveButton("Save", null) // wired below, so a bad entry keeps the dialog open
                .setNegativeButton("Cancel", null);
        if (old != null) {
            b.setNeutralButton("Delete", (d, w) -> new android.app.AlertDialog.Builder(this)
                    .setMessage("Delete \u201C" + old.name + "\u201D?")
                    .setPositiveButton("Delete", (d2, w2) -> {
                        cams.remove(old);
                        camera = Math.max(0, Math.min(cams.size() - 1, camera > index ? camera - 1 : camera));
                        camerasChanged();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        }
        android.app.AlertDialog dialog = b.create();
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim(), u = url.getText().toString().trim();
            Uri parsed = Uri.parse(u);
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(java.util.Locale.US);
            if (n.isEmpty()) {
                name.setError("Give the camera a name");
            } else if (!(scheme.equals("rtsp") || scheme.equals("rtsps"))
                    || parsed.getHost() == null || parsed.getHost().isEmpty()) {
                url.setError("Must start with rtsp:// or rtsps://");
            } else {
                Cam c = old != null ? old : new Cam();
                if (old == null) {
                    cams.add(c);
                    camera = cams.size() - 1;
                } else if (!u.equals(old.url)) {
                    c.pin = null; // a different address is a different server: learn its certificate afresh
                }
                c.name = n;
                c.url = u;
                dialog.dismiss();
                camerasChanged();
            }
        }));
        dialog.show();
    }

    private void camerasChanged() {
        saveCams();
        zoom = 1f;
        panX = panY = 0f;
        applyTransform();
        updatePicker();
        startStream();
    }

    /**
     * An rtsps camera showed a certificate other than the one remembered for it. Cameras do that after
     * an update or a reset, but so would someone listening in, so the user decides; until then nothing
     * (in particular not the address with its password or token) is sent to that server.
     */
    private void certChanged(Cam cam, String fingerprint) {
        handler.removeCallbacks(restart);
        releasePlayer();
        status.setText("Certificate changed \u2014 not connected");
        status.setVisibility(View.VISIBLE);
        if (!started || (certDialog != null && certDialog.isShowing())) return;
        certDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Certificate changed")
                .setMessage("\u201C" + cam.name + "\u201D is identifying itself with a different security "
                        + "certificate than before.\n\nThat is normal after the camera or its recorder was "
                        + "updated or reset. If you did nothing of the kind, someone may be intercepting the "
                        + "connection.\n\nNew fingerprint (SHA-256):\n" + fingerprint.substring(0, 32)
                        + "\n" + fingerprint.substring(32))
                .setPositiveButton("Trust it", (d, w) -> {
                    cam.pin = fingerprint;
                    saveCams();
                    startStream();
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void selectCamera(int i) {
        if (i == camera) return;
        camera = i;
        camerasChanged();
    }

    private void updatePicker() {
        float dp = getResources().getDisplayMetrics().density;
        cameraChip.setText(cams.isEmpty() ? "Add camera" : cams.get(camera).name + "  \u25BE");
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setColor(cams.isEmpty() ? 0xCC1E88E5 : 0x99000000);
        cbg.setCornerRadius(12 * dp);
        cameraChip.setBackground(cbg);
        speaker.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        speaker.setColorFilter(muted ? 0xCCFFFFFF : Color.WHITE); // same tints as the camera labels
        android.graphics.drawable.GradientDrawable sbg = new android.graphics.drawable.GradientDrawable();
        sbg.setColor(muted ? 0x99000000 : 0xCC1E88E5);
        sbg.setCornerRadius(12 * dp);
        speaker.setBackground(sbg);
        waveButton.setColorFilter(waveOn ? Color.WHITE : 0xCCFFFFFF);
        android.graphics.drawable.GradientDrawable wbg = new android.graphics.drawable.GradientDrawable();
        wbg.setColor(waveOn ? 0xCC1E88E5 : 0x99000000);
        wbg.setCornerRadius(12 * dp);
        waveButton.setBackground(wbg);
        peakButton.setColorFilter(peaking ? Color.WHITE : 0xCCFFFFFF);
        android.graphics.drawable.GradientDrawable pbg = new android.graphics.drawable.GradientDrawable();
        pbg.setColor(peaking ? 0xCC1E88E5 : 0x99000000);
        pbg.setCornerRadius(12 * dp);
        peakButton.setBackground(pbg);
        motion.setVisibility(peaking ? View.VISIBLE : View.GONE);
        tuneButton.setColorFilter(tunerOpen ? Color.WHITE : 0xCCFFFFFF);
        android.graphics.drawable.GradientDrawable tbg = new android.graphics.drawable.GradientDrawable();
        tbg.setColor(tunerOpen ? 0xCC1E88E5 : 0x99000000);
        tbg.setCornerRadius(12 * dp);
        tuneButton.setBackground(tbg);
        tuner.setVisibility(tunerOpen ? View.VISIBLE : View.GONE);
        updateAlarms();
    }

    private void updateBadge() {
        long now = android.os.SystemClock.elapsedRealtime();
        long last = lastFrameAt;
        long count = frames.get();
        boolean live = last != 0 && now - last <= STALE_MS;
        if (live) {
            long dt = Math.max(1, now - prevTickAt);
            long fps = Math.round((count - prevFrames) * 1000.0 / dt);
            badgeText.setText(String.format(java.util.Locale.US, "● LIVE  %d fps", fps));
            badgeText.setTextColor(0xFF4CD964);
            odometer.setVisibility(View.VISIBLE);
            setBadgeBackground(0xAA000000);
        } else {
            long secs = (now - (last != 0 ? last : streamStartedAt)) / 1000;
            odometer.setVisibility(View.GONE);
            badgeText.setText(last != 0
                    ? String.format(java.util.Locale.US, "■ NOT LIVE  no frames %ds", secs)
                    : "■ NOT LIVE  connecting");
            badgeText.setTextColor(Color.WHITE);
            setBadgeBackground(0xEED32F2F);
            long stalledFor = last != 0 ? now - last : now - streamStartedAt;
            if (started && player != null && stalledFor > (last != 0 ? GIVE_UP_MS : GIVE_UP_MS + 5000)) {
                handler.post(restart); // hung connection without an error: start over
            }
        }
        prevFrames = count;
        prevTickAt = now;
    }

    private void setBadgeBackground(int color) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(12 * getResources().getDisplayMetrics().density);
        badge.setBackground(bg);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = e.getPointerId(0);
                lastX = e.getX();
                lastY = e.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (zoom > 1f && !scaleDetector.isInProgress()) {
                    int i = e.findPointerIndex(activePointer);
                    if (i >= 0) {
                        panX += e.getX(i) - lastX;
                        panY += e.getY(i) - lastY;
                        lastX = e.getX(i);
                        lastY = e.getY(i);
                        applyTransform();
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                if (e.getPointerId(idx) == activePointer) {
                    int n = idx == 0 ? 1 : 0;
                    activePointer = e.getPointerId(n);
                    lastX = e.getX(n);
                    lastY = e.getY(n);
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (e.getActionMasked() != MotionEvent.ACTION_POINTER_DOWN) {
                    activePointer = MotionEvent.INVALID_POINTER_ID;
                }
                break;
            default:
        }
        return true;
    }

    /** Letterbox the video view to the stream's aspect ratio inside the screen. */
    private void fitVideo() {
        View root = (View) stage.getParent();
        int rw = root.getWidth(), rh = root.getHeight();
        if (rw == 0 || rh == 0) return;
        int w = rw, h = Math.round(rw / videoAspect);
        if (h > rh) { h = rh; w = Math.round(rh * videoAspect); }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        stage.setLayoutParams(lp);
        stage.post(this::applyTransform);
    }

    private void applyTransform() {
        float maxX = stage.getWidth() * (zoom - 1f) / 2f;
        float maxY = stage.getHeight() * (zoom - 1f) / 2f;
        panX = clamp(panX, -maxX, maxX);
        panY = clamp(panY, -maxY, maxY);
        stage.setPivotX(stage.getWidth() / 2f);
        stage.setPivotY(stage.getHeight() / 2f);
        stage.setScaleX(zoom);
        stage.setScaleY(zoom);
        stage.setTranslationX(panX);
        stage.setTranslationY(panY);
        if (picker != null) picker.setVisibility(zoom <= 1.01f ? View.VISIBLE : View.GONE);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /**
     * Loudness history of the audio being played: one peak per {@link #SLICE_MS} of sound.
     * Filled on the playback thread, read by {@link WaveView} on the UI thread.
     */
    private static final class AudioLevels implements androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink {
        static final int SLICE_MS = 20;
        private static final int SIZE = 2048; // ~40 s of history
        // The camera's mic is very quiet (a silent room sits near -75 dBFS) and the room's own noise
        // moves by 25 dB between night and day, so a fixed scale is useless. Levels are shown relative
        // to the typical (median) loudness of the last seconds: ordinary room noise stays a flat line
        // and only what stands well clear of it rises.
        private static final float QUIET_DB = 6f;         // this far above typical is still drawn flat
        private static final float RANGE_DB = 30f;        // this much further = full height
        private static final float MAX_FLOOR_DB = -40f;   // never treat anything louder as background
        private static final float FLOOR_RISE_DB = 0.003f; // per slice (0.15 dB/s): long noise stays a spike
        private static final float FLOOR_FALL_DB = 0.04f;  // per slice (2 dB/s)
        private static final int SETTLE = 250;            // slices (5 s) of fast tracking after a start
        private final float[] recent = new float[512];    // ~10 s of slice loudness, for the median
        private final float[] sorted = new float[512];
        private int recentAt, recentN, pushed;
        private float floorDb, target;

        private final float[] level = new float[SIZE];
        private final long[] at = new long[SIZE]; // uptimeMillis the slice is due on screen
        private int head, count;

        private int channels = 1, encoding = C.ENCODING_INVALID, sliceFrames = 320;
        private int framesInSlice;
        private float peak;
        private double clock; // audio is delivered in bursts; slices are spaced by their real duration

        @Override public void flush(int sampleRateHz, int channelCount, int enc) {
            channels = Math.max(1, channelCount);
            encoding = enc;
            sliceFrames = Math.max(1, sampleRateHz * SLICE_MS / 1000);
            framesInSlice = 0;
            peak = 0f;
        }

        @Override public void handleBuffer(java.nio.ByteBuffer in) {
            java.nio.ByteBuffer b = in.duplicate().order(java.nio.ByteOrder.nativeOrder());
            boolean pcm16 = encoding == C.ENCODING_PCM_16BIT;
            if (!pcm16 && encoding != C.ENCODING_PCM_FLOAT) return;
            int frameBytes = channels * (pcm16 ? 2 : 4);
            while (b.remaining() >= frameBytes) {
                for (int c = 0; c < channels; c++) {
                    float v = Math.abs(pcm16 ? b.getShort() / 32768f : b.getFloat());
                    if (v > peak) peak = v;
                }
                if (++framesInSlice >= sliceFrames) {
                    push(peak);
                    framesInSlice = 0;
                    peak = 0f;
                }
            }
        }

        private synchronized void push(float amplitude) {
            long now = android.os.SystemClock.uptimeMillis();
            clock += SLICE_MS;
            if (clock < now - 300 || clock > now + 3000) clock = now; // first slice, or after a gap
            float db = Math.max(-96f, amplitude > 0f ? 20f * (float) Math.log10(amplitude) : -96f);
            recent[recentAt] = db;
            recentAt = (recentAt + 1) % recent.length;
            if (recentN < recent.length) recentN++;
            if (pushed % 25 == 0) { // twice a second is plenty
                System.arraycopy(recent, 0, sorted, 0, recentN);
                java.util.Arrays.sort(sorted, 0, recentN);
                target = Math.min(sorted[recentN / 2], MAX_FLOOR_DB);
                // Jump straight to it during the first second: a stream opens with a moment of digital
                // silence, and creeping up from there would read the room as a long full-height spike.
                if (pushed <= 50) floorDb = target;
            }
            boolean settling = pushed < SETTLE;
            floorDb += clamp(target - floorDb, settling ? -0.2f : -FLOOR_FALL_DB, settling ? 0.2f : FLOOR_RISE_DB);
            // Nothing is shown for the first second: the typical level is not known yet.
            level[head] = pushed < 50 ? 0f : clamp((db - floorDb - QUIET_DB) / RANGE_DB, 0f, 1f);
            pushed++;
            at[head] = (long) clock;
            head = (head + 1) % SIZE;
            if (count < SIZE) count++;
        }

        synchronized void clear() {
            count = 0;
            clock = 0;
            recentAt = recentN = pushed = 0;
        }

        /** Loudest slice due on screen after {@code from} and no later than {@code to} (uptimeMillis). */
        synchronized float peakBetween(long from, long to) {
            float max = 0f;
            for (int i = 0; i < count; i++) {
                int j = (head - 1 - i + SIZE) % SIZE;
                if (at[j] <= from) break;
                if (at[j] <= to && level[j] > max) max = level[j];
            }
            return max;
        }

        /** Copies the newest slices, newest first, into the arrays; returns how many. */
        synchronized int snapshot(float[] outLevel, long[] outAt) {
            int n = Math.min(count, outLevel.length);
            for (int i = 0; i < n; i++) {
                int j = (head - 1 - i + SIZE) % SIZE;
                outLevel[i] = level[j];
                outAt[i] = at[j];
            }
            return n;
        }
    }

    /**
     * Focus-peaking style overlay for movement: pixels whose brightness is slowly changing are tinted,
     * so the rise and fall of a sleeping baby's chest shows up even when it is too small to notice.
     * <p>
     * Each pixel is followed by a fast and a slow running average; their difference is what moved in
     * the last second or two. That is smoothed over a small area and over time, then compared with the
     * picture's own typical (median) level, so sensor noise and encoder shimmer stay dark. Only the part
     * of the picture on screen is analysed, at a finer grid when zoomed in: zooming on the cot makes the
     * detector more sensitive there. Tuned on the real camera: about 0.1 px of periodic movement shows.
     */
    private static final class MotionView extends View {
        static final long INTERVAL_MS = 100;             // analyse ~10 frames a second
        private static final int W = 320;                // analysis width of the whole frame at zoom 1
        private static final float FAST = 0.4f, SLOW = 0.06f; // running-average weights per analysed frame
        private static final int BLUR = 2;               // area smoothing radius (5x5)
        private static final float HOLD = 0.2f;          // smoothing over time; keeps the glow steady
        // Tint what moves this many times the typical level; set by the slider (0..100, 50 = 5.7).
        // Measured on the real camera in a still, daylit room: 3x tints a tenth of the picture
        // (encoder shimmer on textured cloth), 6x about 1%, 8x next to nothing.
        private static final float LEAST = 16f, MOST = 2f;
        private static final float MIN_THRESHOLD = 0.05f; // brightness steps (0..255) always ignored
        private static final int MAX_CAPTURE_W = 1280;   // largest frame copy worth making 10 times a second
        private float sensitivity = 5.7f;
        // For the alarm only what moves strongly counts. The tint above is relative to the picture's
        // own noise, so a still room is always a tenth tinted; that share says nothing about movement.
        private static final float STRONG = 2f;          // brightness steps (0..255) of change, at least
        private static final float STRONG_TIMES = 8f;    // ...and this many times the typical level
        private static final int SETTLE = 50;            // frames (5 s) before the alarm share is trusted
        private float moving = -1f;
        private static final int WARMUP = 15;            // frames before anything is shown after a change
        private static final int TINT = 0x00E5FF;        // cyan: stands out on colour and on night-vision grey

        private android.graphics.Bitmap frame, overlay;
        private int[] px, out;
        private float[] fast, slow, diff, tmp, act;
        private final int[] hist = new int[256];
        private int scale, over, cx, cy, cw, ch, seen;         // capture scale and the analysed crop, in capture pixels
        private final android.graphics.RectF where = new android.graphics.RectF(); // crop as fractions of the frame
        private final android.graphics.RectF dst = new android.graphics.RectF();
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);

        MotionView(android.content.Context ctx) {
            super(ctx);
        }

        /** Share of the analysed picture that is clearly moving, in percent, or -1 while settling. */
        float moving() {
            return moving;
        }

        void reset() {
            seen = 0;
            moving = -1f;
            if (overlay != null) {
                overlay.eraseColor(0);
                invalidate();
            }
        }

        /** Slider position 0..100; higher tints smaller movements. */
        void setSensitivity(int percent) {
            sensitivity = LEAST * (float) Math.pow(MOST / LEAST, percent / 100.0);
        }

        /**
         * Grab the current video frame (UI thread) and update the overlay for its visible part.
         * Returns the share of the analysed picture that is tinted, in percent, or -1 while warming up.
         */
        float analyse(TextureView video, float aspect, float zoom, float l, float t, float r, float b) {
            if (!video.isAvailable()) return -1f;
            int s = zoom < 1.5f ? 1 : zoom < 3f ? 2 : 4;
            // Copy the frame at twice the analysis grid where affordable and average 2x2: halves the noise.
            int o = W * s * 2 <= MAX_CAPTURE_W ? 2 : 1;
            int fw = W * s, fh = Math.max(8, Math.round(W / aspect) * s);
            int x0 = Math.max(0, (int) Math.floor(l * fw)), x1 = Math.min(fw, (int) Math.ceil(r * fw));
            int y0 = Math.max(0, (int) Math.floor(t * fh)), y1 = Math.min(fh, (int) Math.ceil(b * fh));
            int w = x1 - x0, h = y1 - y0;
            if (w < 16 || h < 16) return -1f;
            if (frame == null || frame.getWidth() != fw * o || frame.getHeight() != fh * o) {
                frame = android.graphics.Bitmap.createBitmap(fw * o, fh * o, android.graphics.Bitmap.Config.ARGB_8888);
            }
            if (s != scale || o != over || px == null || px.length != w * o * h * o || x0 != cx || y0 != cy || w != cw || h != ch) { // zoomed or panned: start over
                if (overlay == null || w != cw || h != ch) {
                    overlay = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                    int n = w * h;
                    out = new int[n];
                    fast = new float[n];
                    slow = new float[n];
                    diff = new float[n];
                    tmp = new float[n];
                    act = new float[n];
                }
                if (px == null || px.length != w * o * h * o) px = new int[w * o * h * o];
                scale = s; over = o; cx = x0; cy = y0; cw = w; ch = h;
                where.set(x0 / (float) fw, y0 / (float) fh, x1 / (float) fw, y1 / (float) fh);
                reset();
            }
            video.getBitmap(frame);
            int pw = w * o;
            frame.getPixels(px, 0, pw, x0 * o, y0 * o, pw, h * o);
            int n = w * h;
            boolean first = seen++ == 0;
            for (int i = 0, yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++, i++) {
                    int j = yy * o * pw + xx * o;
                    float y = o == 1 ? luma(px[j])
                            : (luma(px[j]) + luma(px[j + 1]) + luma(px[j + pw]) + luma(px[j + pw + 1])) * 0.25f;
                    if (first) {
                        fast[i] = slow[i] = y;
                        act[i] = 0f;
                    } else {
                        diff[i] = y; // this frame's brightness, turned into a difference below
                    }
                }
            }
            if (first) return -1f;
            for (int i = 0; i < n; i++) {
                float y = diff[i];
                fast[i] += FAST * (y - fast[i]);
                slow[i] += SLOW * (y - slow[i]);
                diff[i] = Math.abs(fast[i] - slow[i]);
            }
            // Real movement covers an area, noise is single pixels: box-blur, rows then columns.
            float norm = 1f / (2 * BLUR + 1);
            for (int y = 0; y < h; y++) {
                int row = y * w;
                float sum = 0f;
                for (int k = -BLUR; k <= BLUR; k++) sum += diff[row + Math.max(0, Math.min(w - 1, k))];
                for (int x = 0; x < w; x++) {
                    tmp[row + x] = sum * norm;
                    sum += diff[row + Math.min(w - 1, x + BLUR + 1)] - diff[row + Math.max(0, x - BLUR)];
                }
            }
            java.util.Arrays.fill(hist, 0);
            for (int x = 0; x < w; x++) {
                float sum = 0f;
                for (int k = -BLUR; k <= BLUR; k++) sum += tmp[Math.max(0, Math.min(h - 1, k)) * w + x];
                for (int y = 0; y < h; y++) {
                    int i = y * w + x;
                    act[i] += HOLD * (sum * norm - act[i]);
                    hist[Math.min(255, (int) (act[i] * 50f))]++; // 0.02 steps
                    sum += tmp[Math.min(h - 1, y + BLUR + 1) * w + x] - tmp[Math.max(0, y - BLUR) * w + x];
                }
            }
            if (seen < WARMUP) return -1f;
            int half = n / 2, bin = 0;
            for (int c = 0; bin < 255 && (c += hist[bin]) < half; bin++) { }
            float threshold = Math.max(MIN_THRESHOLD, sensitivity * (bin + 0.5f) / 50f);
            float strongAt = Math.max(STRONG, STRONG_TIMES * (bin + 0.5f) / 50f);
            int tinted = 0, strong = 0;
            for (int i = 0; i < n; i++) {
                if (act[i] > strongAt) strong++;
                float above = (act[i] - threshold) / threshold; // 0 at the threshold, 1 at twice it
                if (above <= 0f) {
                    out[i] = 0;
                } else {
                    // A wash, not a cover: 16% at the threshold up to 43%, so the picture stays readable.
                    out[i] = ((40 + (int) (Math.min(1f, above) * 70f)) << 24) | TINT;
                    tinted++;
                }
            }
            moving = seen < SETTLE ? -1f : 100f * strong / n;
            overlay.setPixels(out, 0, w, 0, 0, w, h);
            invalidate();
            return 100f * tinted / n;
        }

        private static float luma(int c) {
            return (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) / 256f;
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (overlay == null) return;
            dst.set(where.left * getWidth(), where.top * getHeight(),
                    where.right * getWidth(), where.bottom * getHeight());
            canvas.drawBitmap(overlay, null, dst, paint);
        }
    }

    /**
     * How long the stream has been playing, h:mm:ss, as a car's odometer: the last drum turns steadily
     * with the picture and each of the others turns over as the drum to its right comes round. The
     * time only advances while frames arrive, so a drum that has stopped means the picture has too.
     */
    private static final class Odometer extends View {
        private static final int[] UNIT = {1, 10, 60, 600, 3600};  // seconds per step, last drum first
        private static final int[] STEPS = {10, 6, 10, 6, 10};     // faces on each drum
        private static final int WRAP = 36_000;                    // back to 0:00:00 after ten hours
        private final java.util.concurrent.atomic.AtomicLong liveMs;
        private final android.graphics.Paint digit = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint drum = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF cell = new android.graphics.RectF();
        private final float cellW, cellH, gap, colonW, baseline, radius;
        private double shown = -1;   // seconds on the drums; eases towards the real time
        private long drawnAt;

        Odometer(android.content.Context ctx, java.util.concurrent.atomic.AtomicLong liveMs) {
            super(ctx);
            this.liveMs = liveMs;
            float dp = getResources().getDisplayMetrics().density;
            digit.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
            digit.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
            digit.setColor(0xFF4CD964);
            digit.setTextAlign(android.graphics.Paint.Align.CENTER);
            drum.setColor(0x33FFFFFF);
            android.graphics.Paint.FontMetrics fm = digit.getFontMetrics();
            cellW = digit.measureText("0") + 4 * dp;
            cellH = fm.descent - fm.ascent;
            baseline = -fm.ascent;
            gap = 1.5f * dp;
            colonW = 7 * dp;
            radius = 3 * dp;
        }

        /** Left edge of a drum; 0 is the seconds, 4 the hours. A colon sits before drums 3 and 1. */
        private float left(int p) {
            int i = UNIT.length - 1 - p; // position from the left
            return i * (cellW + gap) + (i >= 1 ? colonW : 0f) + (i >= 3 ? colonW : 0f);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension((int) Math.ceil(left(0) + cellW), (int) Math.ceil(cellH));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            long now = android.os.SystemClock.uptimeMillis();
            double target = liveMs.get() / 1000.0;
            if (shown < 0 || target < shown || target - shown > 2) {
                shown = target; // first frame, a new stream, or back from the background: no long spin
            } else {
                shown += (target - shown) * Math.min(1.0, (now - drawnAt) / 150.0); // glide, not steps
            }
            drawnAt = now;
            double v = shown % WRAP;
            for (int p = 0; p < UNIT.length; p++) {
                int d = (int) (v / UNIT[p]) % STEPS[p];
                // The last drum turns all the time; the others only during the final second before a carry.
                double turn = p == 0 ? v - Math.floor(v) : Math.max(0.0, v % UNIT[p] - (UNIT[p] - 1));
                float left = left(p);
                cell.set(left, 0, left + cellW, cellH);
                canvas.drawRoundRect(cell, radius, radius, drum);
                canvas.save();
                canvas.clipRect(cell);
                float y = baseline - (float) turn * cellH;
                canvas.drawText(String.valueOf(d), left + cellW / 2f, y, digit);
                canvas.drawText(String.valueOf((d + 1) % STEPS[p]), left + cellW / 2f, y + cellH, digit);
                canvas.restore();
                if (p == 1 || p == 3) canvas.drawText(":", left - gap / 2f - colonW / 2f, baseline, digit);
            }
            postInvalidateOnAnimation(); // keep rolling; stops by itself while hidden
        }
    }

    /** A slider with a small dot on its line at the default position. */
    private static final class DetentBar extends android.widget.SeekBar {
        private final int def;
        private final android.graphics.Paint dot = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        DetentBar(android.content.Context ctx, int def) {
            super(ctx);
            this.def = def;
            dot.setColor(Color.WHITE);
        }

        @Override
        protected synchronized void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (getProgress() == def) return; // the thumb is sitting on it
            float x = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight()) * def / (float) getMax();
            canvas.drawCircle(x, getHeight() / 2f, 2.5f * getResources().getDisplayMetrics().density, dot);
        }
    }

    /** A short history of a 0..1 value with the time of each reading. UI thread only. */
    private static final class Trace {
        private static final int SIZE = 512; // 20 s on screen at ten readings a second, with room to spare
        private final float[] level = new float[SIZE];
        private final long[] at = new long[SIZE];
        private int head, count;

        void push(float value, long uptimeMillis) {
            level[head] = value;
            at[head] = uptimeMillis;
            head = (head + 1) % SIZE;
            if (count < SIZE) count++;
        }

        void clear() {
            count = 0;
        }

        /** Copies the newest readings, newest first, into the arrays; returns how many. */
        int snapshot(float[] outLevel, long[] outAt) {
            int n = Math.min(count, outLevel.length);
            for (int i = 0; i < n; i++) {
                int j = (head - 1 - i + SIZE) % SIZE;
                outLevel[i] = level[j];
                outAt[i] = at[j];
            }
            return n;
        }
    }

    /** The sound level as a line scrolling right to left: flat and green when quiet, tall and red on a spike. */
    private static final class WaveView extends View {
        private static final float WINDOW_MS = 20_000f;          // time span across the screen
        private static final float WARM = 0.45f, HOT = 0.75f;    // level where the line turns yellow / red
        private static final int QUIET = 0xFF4CD964, MID = 0xFFFFCC00, LOUD = 0xFFFF3B30;

        private static final int MOVE = 0xFF00E5FF;               // the movement line: cyan, like the highlight
        private static final long MOVE_GAP_MS = 500;              // longer without a reading: leave a hole
        private final AudioLevels levels;
        private final Trace moves;
        private final float[] moveLevel = new float[Trace.SIZE];
        private final long[] moveAt = new long[Trace.SIZE];
        private final android.graphics.Paint moveMark = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float moveLimit = -1f; // movement alarm level to mark, below 0 = none
        private boolean showSound = true, showMoves;

        void setMoveLimit(float level) {
            moveLimit = level;
        }

        void setShown(boolean sound, boolean movement) {
            showSound = sound;
            showMoves = movement;
        }
        private final float[] level = new float[AudioLevels.SIZE];
        private final long[] at = new long[AudioLevels.SIZE];
        private final android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint shade = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint mark = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        private float limit = -1f; // sound alarm level to mark, below 0 = none

        void setLimit(float level) {
            limit = level;
        }

        WaveView(android.content.Context ctx, AudioLevels levels, Trace moves) {
            super(ctx);
            this.levels = levels;
            this.moves = moves;
            dp = getResources().getDisplayMetrics().density;
            line.setStyle(android.graphics.Paint.Style.STROKE);
            line.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            line.setStrokeWidth(1f * dp);
            mark.setStyle(android.graphics.Paint.Style.STROKE);
            mark.setStrokeWidth(1f * dp);
            mark.setColor(0xCCFF3B30);
            mark.setPathEffect(new android.graphics.DashPathEffect(new float[]{6f * dp, 4f * dp}, 0f));
            moveMark.set(mark);
            moveMark.setColor(0xCC00E5FF);
            // Offset dashes, so the two limit lines stay distinguishable where they coincide.
            moveMark.setPathEffect(new android.graphics.DashPathEffect(new float[]{6f * dp, 4f * dp}, 5f * dp));
            shade.setStyle(android.graphics.Paint.Style.STROKE);
            shade.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            shade.setStrokeWidth(2.5f * dp);
            shade.setColor(0x99000000); // dark outline keeps the line readable over bright video
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            int n = levels.snapshot(level, at);
            long now = android.os.SystemClock.uptimeMillis();
            float w = getWidth(), pad = 8f * dp;
            float base = getHeight() - pad, span = getHeight() - 2f * pad;
            float pxPerMs = w / WINDOW_MS;
            if (showMoves) {
                if (moveLimit >= 0f) {
                    canvas.drawLine(0f, base - moveLimit * span, w, base - moveLimit * span, moveMark);
                }
                // The movement line goes underneath: sound spikes are the briefer of the two.
                int m = moves.snapshot(moveLevel, moveAt);
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 0; i + 1 < m; i++) {
                        if (moveAt[i] - moveAt[i + 1] > MOVE_GAP_MS) continue;
                        float x1 = w - (now - moveAt[i]) * pxPerMs, x0 = w - (now - moveAt[i + 1]) * pxPerMs;
                        if (x1 < 0) break;
                        float y1 = base - moveLevel[i] * span, y0 = base - moveLevel[i + 1] * span;
                        if (pass == 0) {
                            shade.setStrokeWidth(3f * dp);
                            canvas.drawLine(x0, y0, x1, y1, shade);
                        } else {
                            line.setColor(MOVE);
                            line.setStrokeWidth(1.5f * dp);
                            canvas.drawLine(x0, y0, x1, y1, line);
                        }
                    }
                }
            }
            if (showSound && limit >= 0f) canvas.drawLine(0f, base - limit * span, w, base - limit * span, mark);
            for (int pass = 0; showSound && pass < 2; pass++) {
                for (int i = 0; i + 1 < n; i++) {
                    if (at[i] - at[i + 1] > 4 * AudioLevels.SLICE_MS) continue; // gap in the audio: leave a hole
                    float x1 = w - (now - at[i]) * pxPerMs, x0 = w - (now - at[i + 1]) * pxPerMs;
                    if (x0 > w) continue; // not due yet
                    if (x1 < 0) break;
                    float y1 = base - level[i] * span, y0 = base - level[i + 1] * span;
                    float v = Math.max(level[i], level[i + 1]);
                    // Hairline while quiet, growing as it turns red, so a spike catches the eye.
                    float width = (1f + 2f * clamp((v - WARM) / (HOT - WARM), 0f, 1f)) * dp;
                    if (pass == 0) {
                        shade.setStrokeWidth(width + 1.5f * dp);
                        canvas.drawLine(x0, y0, x1, y1, shade);
                    } else {
                        line.setColor(color(v));
                        line.setStrokeWidth(width);
                        canvas.drawLine(x0, y0, x1, y1, line);
                    }
                }
            }
            postInvalidateOnAnimation(); // keep scrolling; stops by itself while hidden
        }

        private static int color(float v) {
            if (v <= WARM) return mix(QUIET, MID, v / WARM * 0.35f);
            if (v >= HOT) return LOUD;
            return mix(mix(QUIET, MID, 0.35f), LOUD, (v - WARM) / (HOT - WARM));
        }

        private static int mix(int a, int b, float t) {
            int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
            int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
            int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
            return Color.rgb(r, g, bl);
        }
    }

    /** The server's certificate is not the one remembered for this camera. */
    private static final class CertChangedException extends java.io.IOException {
        final String fingerprint;

        CertChangedException(String fingerprint) {
            super("Certificate changed");
            this.fingerprint = fingerprint;
        }
    }

    /**
     * TLS sockets for rtsps, which Media3's RTSP client does not do itself.
     * <p>
     * Cameras and recorders almost always use a self-signed certificate, so the usual check against
     * the public authorities would reject them all. Instead a certificate is accepted when either it
     * does pass that check (and is for this host), or it is the one this camera showed the first time
     * it was connected ("trust on first use"). Anything else fails before a byte of RTSP is sent.
     */
    private static final class TlsSocketFactory extends javax.net.SocketFactory {
        private final javax.net.ssl.SSLSocketFactory ssl = handshakeOnlyFactory();
        private final java.util.function.Consumer<String> onFirstSeen;
        private volatile String pin;

        TlsSocketFactory(String pin, java.util.function.Consumer<String> onFirstSeen) {
            this.pin = pin;
            this.onFirstSeen = onFirstSeen;
        }

        /** Completes any handshake; the certificate is judged in {@link #wrap} once it is known. */
        private static javax.net.ssl.SSLSocketFactory handshakeOnlyFactory() {
            try {
                javax.net.ssl.TrustManager tm = new javax.net.ssl.X509TrustManager() {
                    @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
                    @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
                    @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                };
                javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
                ctx.init(null, new javax.net.ssl.TrustManager[]{tm}, null);
                return ctx.getSocketFactory();
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        private java.net.Socket wrap(java.net.Socket plain, String host, int port)
                throws java.io.IOException {
            javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) ssl.createSocket(plain, host, port, true);
            try {
                s.startHandshake();
                java.security.cert.Certificate[] chain = s.getSession().getPeerCertificates();
                String seen = fingerprint(chain[0]);
                String known = pin;
                if (seen.equals(known) || publiclyTrusted(chain, host, s.getSession())) return s;
                if (known == null) {
                    pin = seen;
                    onFirstSeen.accept(seen);
                    return s;
                }
                throw new CertChangedException(seen);
            } catch (java.io.IOException | RuntimeException e) {
                try {
                    s.close();
                } catch (java.io.IOException ignored) { }
                throw e;
            }
        }

        private static String fingerprint(java.security.cert.Certificate cert) throws java.io.IOException {
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) hex.append(String.format(java.util.Locale.US, "%02x", b));
                return hex.toString();
            } catch (java.security.GeneralSecurityException e) {
                throw new java.io.IOException(e);
            }
        }

        /** Would a browser accept this certificate for this host? */
        private static boolean publiclyTrusted(java.security.cert.Certificate[] chain, String host,
                javax.net.ssl.SSLSession session) {
            try {
                javax.net.ssl.TrustManagerFactory f = javax.net.ssl.TrustManagerFactory.getInstance(
                        javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                f.init((java.security.KeyStore) null);
                java.security.cert.X509Certificate[] x509 = new java.security.cert.X509Certificate[chain.length];
                for (int i = 0; i < chain.length; i++) x509[i] = (java.security.cert.X509Certificate) chain[i];
                for (javax.net.ssl.TrustManager tm : f.getTrustManagers()) {
                    if (tm instanceof javax.net.ssl.X509TrustManager) {
                        ((javax.net.ssl.X509TrustManager) tm).checkServerTrusted(x509,
                                x509[0].getPublicKey().getAlgorithm());
                        return javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session);
                    }
                }
            } catch (java.security.GeneralSecurityException | RuntimeException ignored) { }
            return false;
        }

        @Override public java.net.Socket createSocket() { return new java.net.Socket(); }
        @Override public java.net.Socket createSocket(String h, int p) throws java.io.IOException {
            return wrap(new java.net.Socket(h, p), h, p);
        }
        @Override public java.net.Socket createSocket(String h, int p, java.net.InetAddress la, int lp)
                throws java.io.IOException {
            return wrap(new java.net.Socket(h, p, la, lp), h, p);
        }
        @Override public java.net.Socket createSocket(java.net.InetAddress a, int p) throws java.io.IOException {
            return wrap(new java.net.Socket(a, p), a.getHostName(), p);
        }
        @Override public java.net.Socket createSocket(java.net.InetAddress a, int p, java.net.InetAddress la, int lp)
                throws java.io.IOException {
            return wrap(new java.net.Socket(a, p, la, lp), a.getHostName(), p);
        }
    }

    /**
     * The camera's SDP yields VPS/SPS/PPS as separate csd-0/1/2 buffers. Android's HEVC decoders
     * expect them concatenated in csd-0; software decoders tolerate the split, hardware ones fail.
     */
    private static final class HevcCsdRenderer extends androidx.media3.exoplayer.video.MediaCodecVideoRenderer {
        HevcCsdRenderer(androidx.media3.exoplayer.video.MediaCodecVideoRenderer.Builder b) {
            super(b);
        }

        @Override
        protected androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Configuration getMediaCodecConfiguration(
                androidx.media3.exoplayer.mediacodec.MediaCodecInfo info, androidx.media3.common.Format format,
                android.media.MediaCrypto crypto, float rate) {
            androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Configuration c =
                    super.getMediaCodecConfiguration(info, format, crypto, rate);
            android.media.MediaFormat mf = c.mediaFormat;
            if ("video/hevc".equals(mf.getString(android.media.MediaFormat.KEY_MIME)) && mf.containsKey("csd-1")) {
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                for (int i = 0; mf.containsKey("csd-" + i); i++) {
                    java.nio.ByteBuffer bb = mf.getByteBuffer("csd-" + i).duplicate();
                    byte[] part = new byte[bb.remaining()];
                    bb.get(part);
                    all.write(part, 0, part.length);
                    if (i > 0) mf.removeKey("csd-" + i);
                }
                mf.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(all.toByteArray()));
            }
            return c;
        }
    }

    /**
     * The camera's SDP carries "profile-level-id=15", which Media3 mislabels as AAC object type 15
     * (mp4a.40.15). No Android decoder accepts that, so the track is "unsupported" and the player
     * falls back to Opus, whose RTSP reader fails on this camera. The stream is plain AAC-LC.
     */
    private static final class AacFixRenderer extends androidx.media3.exoplayer.audio.MediaCodecAudioRenderer {
        AacFixRenderer(android.content.Context ctx, androidx.media3.exoplayer.mediacodec.MediaCodecSelector sel,
                boolean fallback, Handler handler, androidx.media3.exoplayer.audio.AudioRendererEventListener l,
                androidx.media3.exoplayer.audio.AudioSink sink) {
            super(ctx, sel, fallback, handler, l, sink);
        }

        private static androidx.media3.common.Format fix(androidx.media3.common.Format f) {
            return "mp4a.40.15".equals(f.codecs) ? f.buildUpon().setCodecs("mp4a.40.2").build() : f;
        }

        @Override
        protected int supportsFormat(androidx.media3.exoplayer.mediacodec.MediaCodecSelector sel,
                androidx.media3.common.Format f)
                throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {
            return super.supportsFormat(sel, fix(f));
        }

        @Override
        protected java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> getDecoderInfos(
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector sel, androidx.media3.common.Format f,
                boolean secure)
                throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {
            return super.getDecoderInfos(sel, fix(f), secure);
        }
    }
}
