package io.github.infestdead.acam;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Camera mode's picture and sound: the back camera into an H.264 encoder and the microphone into
 * an AAC encoder, both handed to a {@link CamServer}.
 *
 * The camera does not draw straight into the encoder. Its frames come out the way the sensor is
 * mounted, whichever way up the phone stands, so they pass through a small OpenGL step that turns
 * them upright and thins them to the wanted frame rate.
 */
final class CamCapture {
    static final int SAMPLE_RATE = 44100;
    private static final int WIDTH = 1280, HEIGHT = 720; // as the sensor sees it, i.e. lying on its side
    private static final int FPS = 15;                   // plenty for a cot, and it lets the camera expose longer in the dark
    private static final int VIDEO_BITRATE = 1_200_000;
    private static final int AUDIO_BITRATE = 64_000;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private final Context context;
    private final CamServer server;
    private final String cameraId;
    private final boolean front;     // the selfie camera: its picture comes mirrored and is turned back
    private final int torchMax;      // 0: no torch; 1: on or off; more: that many strengths
    private final boolean sound;
    private final int turned;        // how far the phone is turned clockwise from upright: 0, 90, 180 or 270
    private final Runnable onFailure;

    private final HandlerThread thread, encodeThread;
    private final Handler handler;
    private volatile boolean released;
    private Thread audioThread;

    // Everything below belongs to the capture thread.
    private CameraDevice camera;
    private CameraCaptureSession session;
    private Range<Integer> fps;
    private int torch;               // 0 off, else the strength wanted
    private MediaCodec encoder;
    private Surface encoderSurface, cameraSurface;
    private SurfaceTexture texture;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int program, textureId, uTex, aPos, aTex;
    private final float[] textureMatrix = new float[16], turn = new float[16], combined = new float[16];
    private final FloatBuffer quad = floats(-1, -1, 1, -1, -1, 1, 1, 1), corners = floats(0, 0, 1, 0, 0, 1, 1, 1);
    private long lastDrawnNs, clockOffsetNs = Long.MIN_VALUE;

    CamCapture(Context context, CamServer server, String cameraId, boolean front, int torchMax, int torch,
            boolean sound, int turned, Runnable onFailure) {
        this.context = context;
        this.server = server;
        this.cameraId = cameraId;
        this.front = front;
        this.torchMax = torchMax;
        this.torch = torch;
        this.sound = sound;
        this.turned = turned;
        this.onFailure = onFailure;
        // Ready before start(), so the torch and key-frame requests always have somewhere to go.
        thread = new HandlerThread("acam-capture");
        thread.start();
        encodeThread = new HandlerThread("acam-encode");
        encodeThread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Torch off (0) or on at a strength from 1 up to the camera's maximum. */
    void setTorch(int level) {
        handler.post(() -> {
            if (torch == level) return;
            torch = level;
            try {
                repeat();
            } catch (Exception e) {
                fail();
            }
        });
    }

    void start() {
        handler.post(() -> {
            try {
                open();
            } catch (Exception e) { // no permission, camera in use, no usable encoder, …
                fail();
            }
        });
        if (sound) {
            audioThread = new Thread(this::recordSound, "acam-sound");
            audioThread.start();
        }
    }

    /** Blocks briefly until the camera and the encoders are let go. */
    void stop() {
        released = true;
        CountDownLatch done = new CountDownLatch(1);
        handler.post(() -> {
            close();
            done.countDown();
        });
        try {
            done.await(3, TimeUnit.SECONDS);
            if (audioThread != null) audioThread.join(1000);
        } catch (InterruptedException ignored) { }
        thread.quitSafely();
        encodeThread.quitSafely();
    }

    /** A viewer joined: give it a picture it can start from now, not at the next scheduled one. */
    void requestKeyFrame() {
        handler.post(() -> {
            if (encoder == null) return;
            try {
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                encoder.setParameters(b);
            } catch (IllegalStateException ignored) { }
        });
    }

    private void fail() {
        if (!released) onFailure.run();
    }

    @SuppressWarnings("MissingPermission") // the service is only started once the camera permission is held
    private void open() throws Exception {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String id = cameraId;
        CameraCharacteristics about = manager.getCameraCharacteristics(id);
        Size size = pickSize(about.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
        Integer mounted = about.get(CameraCharacteristics.SENSOR_ORIENTATION);
        // With the texture's own matrix applied the picture is upright for a phone held upright,
        // which on nearly every phone means the sensor's long side has become the height.
        boolean sideways = mounted != null && mounted % 180 != 0;
        int uprightW = sideways ? size.getHeight() : size.getWidth();
        int uprightH = sideways ? size.getWidth() : size.getHeight();
        boolean quarter = turned % 180 != 0;
        int outW = quarter ? uprightH : uprightW, outH = quarter ? uprightW : uprightH;

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.setCallback(new MediaCodec.Callback() {
            @Override public void onInputBufferAvailable(MediaCodec codec, int index) { }

            @Override public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                try {
                    ByteBuffer b = codec.getOutputBuffer(index);
                    if (b != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        b.position(info.offset).limit(info.offset + info.size);
                        server.video(b, info.presentationTimeUs, (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                    }
                    codec.releaseOutputBuffer(index, false);
                } catch (IllegalStateException ignored) { } // stopped meanwhile
            }

            @Override public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                fail();
            }

            @Override public void onOutputFormatChanged(MediaCodec codec, MediaFormat f) {
                ByteBuffer sps = f.getByteBuffer("csd-0"), pps = f.getByteBuffer("csd-1");
                if (sps != null && pps != null) server.setVideoConfig(bytes(sps), bytes(pps));
            }
        }, new Handler(encodeThread.getLooper()));
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        encoder.start();

        startGl(outW, outH);
        texture = new SurfaceTexture(textureId);
        texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        texture.setOnFrameAvailableListener(t -> draw(), handler);
        cameraSurface = new Surface(texture);

        fps = pickFps(about.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
        manager.openCamera(id, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice device) {
                if (released) {
                    device.close();
                    return;
                }
                camera = device;
                try {
                    startSession();
                } catch (Exception e) {
                    fail();
                }
            }

            @Override public void onDisconnected(CameraDevice device) { // another app took the camera
                device.close();
                if (camera == device) camera = null;
                fail();
            }

            @Override public void onError(CameraDevice device, int error) {
                device.close();
                if (camera == device) camera = null;
                fail();
            }
        }, handler);
    }

    @SuppressWarnings("deprecation") // the list-of-surfaces form is the one that exists on every supported version
    private void startSession() throws Exception {
        camera.createCaptureSession(Collections.singletonList(cameraSurface), new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession s) {
                if (released || camera == null) return;
                session = s;
                try {
                    repeat();
                } catch (Exception e) {
                    fail();
                }
            }

            @Override public void onConfigureFailed(CameraCaptureSession s) {
                fail();
            }
        }, handler);
    }

    /** (Re)issue what the camera does continuously: frames into the texture, with the torch as wanted. */
    private void repeat() throws Exception {
        if (session == null || camera == null) return;
        CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        request.addTarget(cameraSurface);
        if (fps != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
        if (torchMax > 0 && torch > 0) {
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON); // the flash is ours to drive
            request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            if (torchMax > 1 && Build.VERSION.SDK_INT >= 35) {
                request.set(CaptureRequest.FLASH_STRENGTH_LEVEL, Math.min(torch, torchMax));
            }
        }
        session.setRepeatingRequest(request.build(), null, handler);
    }

    /** The largest 16:9 size not above the wanted one; failing that, the wanted one and hope. */
    private static Size pickSize(StreamConfigurationMap map) {
        Size best = null;
        Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        if (sizes != null) {
            for (Size s : sizes) {
                if (s.getWidth() > WIDTH || s.getHeight() > HEIGHT || s.getWidth() * 9 != s.getHeight() * 16) continue;
                if (s.getWidth() % 16 != 0 || s.getHeight() % 2 != 0) continue;
                if (best == null || s.getWidth() > best.getWidth()) best = s;
            }
        }
        return best != null ? best : new Size(WIDTH, HEIGHT);
    }

    /**
     * The exposure range that tops out nearest the wanted rate and reaches lowest: in a dark room
     * the camera may then slow down and gather more light instead of showing black.
     */
    private static Range<Integer> pickFps(Range<Integer>[] ranges) {
        Range<Integer> best = null;
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                if (r.getUpper() < FPS) continue;
                if (best == null || r.getUpper() < best.getUpper()
                        || (r.getUpper().equals(best.getUpper()) && r.getLower() < best.getLower())) {
                    best = r;
                }
            }
        }
        return best;
    }

    private void startGl(int width, int height) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("no EGL");
        int[] wanted = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw new IllegalStateException("no EGL config for the encoder");
        }
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, new int[]{EGL14.EGL_NONE}, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT || eglSurface == EGL14.EGL_NO_SURFACE
                || !EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
            throw new IllegalStateException("EGL setup failed");
        }
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTex; varying vec2 vTex;"
                        + "void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }"));
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;"
                        + "void main() { gl_FragColor = texture2D(sTex, vTex); }"));
        GLES20.glLinkProgram(program);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("shader link failed");
        GLES20.glUseProgram(program);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        textureId = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, corners);
        // Turning the picture clockwise means looking it up turned the other way, about its centre.
        Matrix.setIdentityM(turn, 0);
        Matrix.translateM(turn, 0, 0.5f, 0.5f, 0f);
        Matrix.rotateM(turn, 0, turned, 0f, 0f, 1f);
        Matrix.translateM(turn, 0, -0.5f, -0.5f, 0f);
        if (front) {
            // The selfie camera's frames arrive mirrored, like a mirror; show the room as it is.
            float[] unmirror = new float[16];
            Matrix.setIdentityM(unmirror, 0);
            Matrix.translateM(unmirror, 0, 1f, 0f, 0f);
            Matrix.scaleM(unmirror, 0, -1f, 1f, 1f);
            float[] both = new float[16];
            Matrix.multiplyMM(both, 0, turn, 0, unmirror, 0);
            System.arraycopy(both, 0, turn, 0, 16);
        }
    }

    private static int shader(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private void draw() {
        if (released || texture == null) return;
        try {
            texture.updateTexImage();
            long at = texture.getTimestamp();
            // A camera that will not go as slow as wanted has its extra frames left out here.
            if (lastDrawnNs != 0 && at - lastDrawnNs < 800_000_000L / FPS) return;
            lastDrawnNs = at;
            // Sound is stamped with System.nanoTime(); put the picture on the same clock.
            if (clockOffsetNs == Long.MIN_VALUE) clockOffsetNs = System.nanoTime() - at;
            texture.getTransformMatrix(textureMatrix);
            Matrix.multiplyMM(combined, 0, textureMatrix, 0, turn, 0);
            GLES20.glUniformMatrix4fv(uTex, 1, false, combined, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, at + clockOffsetNs);
            EGL14.eglSwapBuffers(display, eglSurface);
        } catch (RuntimeException e) {
            fail();
        }
    }

    private void close() {
        try { if (session != null) session.close(); } catch (RuntimeException ignored) { }
        try { if (camera != null) camera.close(); } catch (RuntimeException ignored) { }
        session = null;
        camera = null;
        if (encoder != null) {
            try { encoder.stop(); } catch (RuntimeException ignored) { }
            encoder.release();
            encoder = null;
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
            display = EGL14.EGL_NO_DISPLAY;
        }
        if (cameraSurface != null) cameraSurface.release();
        if (texture != null) texture.release();
        if (encoderSurface != null) encoderSurface.release();
        cameraSurface = encoderSurface = null;
        texture = null;
    }

    @SuppressWarnings("MissingPermission") // sound is only asked for once the microphone permission is held
    private void recordSound() {
        AudioRecord recorder = null;
        MediaCodec aac = null;
        try {
            int smallest = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(smallest * 2, 8192));
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
            aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            aac.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aac.start();
            recorder.startRecording();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long startUs = 0, samples = -1;
            while (!released) {
                int in = aac.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer b = aac.getInputBuffer(in);
                    b.clear();
                    int n = recorder.read(b, 2048); // one AAC frame of 16-bit mono
                    if (n < 0) break;
                    long nowUs = System.nanoTime() / 1000, lengthUs = n / 2 * 1_000_000L / SAMPLE_RATE;
                    // Time advances by samples read, which is smooth; the wall clock only corrects a real slip.
                    if (samples < 0 || Math.abs(startUs + samples * 1_000_000L / SAMPLE_RATE + lengthUs - nowUs) > 200_000) {
                        startUs = nowUs - lengthUs;
                        samples = 0;
                    }
                    aac.queueInputBuffer(in, 0, n, startUs + samples * 1_000_000L / SAMPLE_RATE, 0);
                    samples += n / 2;
                }
                for (int out; (out = aac.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER; ) {
                    if (out < 0) continue; // format or buffers changed: nothing to do
                    ByteBuffer b = aac.getOutputBuffer(out);
                    if (b != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        b.position(info.offset).limit(info.offset + info.size);
                        server.audio(b, info.presentationTimeUs);
                    }
                    aac.releaseOutputBuffer(out, false);
                }
            }
        } catch (Exception e) {
            // No sound then; the picture carries on.
        } finally {
            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) { }
                recorder.release();
            }
            if (aac != null) {
                try { aac.stop(); } catch (RuntimeException ignored) { }
                aac.release();
            }
        }
    }

    private static byte[] bytes(ByteBuffer b) {
        ByteBuffer copy = b.duplicate();
        byte[] out = new byte[copy.remaining()];
        copy.get(out);
        return out;
    }

    private static FloatBuffer floats(float... values) {
        FloatBuffer b = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(values).position(0);
        return b;
    }
}
