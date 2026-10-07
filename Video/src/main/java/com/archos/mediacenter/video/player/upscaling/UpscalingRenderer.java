package com.archos.mediacenter.video.player.upscaling;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES31;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import androidx.preference.PreferenceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns decoder input and SurfaceView output. Mode changes never replace either surface. */
public final class UpscalingRenderer
        implements AutoCloseable, SharedPreferences.OnSharedPreferenceChangeListener {
    private static final Logger log = LoggerFactory.getLogger(UpscalingRenderer.class);

    private static final class Source {
        final int width, height;
        final VideoColorInfo color;

        Source(int width, int height, VideoColorInfo color) {
            this.width = width;
            this.height = height;
            this.color = color;
        }
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final HandlerThread thread = new HandlerThread("Nova-upscaling");
    private final Handler handler;
    private volatile Source source = new Source(0, 0, VideoColorInfo.UNKNOWN);
    private volatile UpscalingMode selected;
    private volatile UpscalingDiagnostics diagnostics;
    private volatile boolean closed;
    private SurfaceTexture inputTexture;
    private Surface inputSurface;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private GlProgram direct;
    private GpuUpscalingPipeline pipeline;
    private int texture, vao;
    private final float[] transform = new float[16];
    private String backend = "OpenGL ES 3.1", failure = "";
    private boolean yuvExtension, hasFrame, newFrame, renderQueued, usingYuv;
    private Source builtSource;
    private UpscalingMode builtMode;
    private int builtWidth, builtHeight;
    private long frames, coalesced;
    private double renderMs = -1;

    public UpscalingRenderer(Context context, Surface output) {
        this.context = context.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
        selected =
                UpscalingMode.fromPreference(
                        preferences.getString(UpscalingMode.PREFERENCE, "ravu"));
        diagnostics = snapshot(0, 0, false, "waiting for decoded video");
        thread.start();
        handler = new Handler(thread.getLooper());
        CountDownLatch ready = new CountDownLatch(1);
        RuntimeException[] error = new RuntimeException[1];
        handler.post(
                () -> {
                    try {
                        initialize(output);
                    } catch (RuntimeException e) {
                        error[0] = e;
                        destroy();
                    } finally {
                        ready.countDown();
                    }
                });
        try {
            if (!ready.await(10, TimeUnit.SECONDS)) {
                close();
                throw new IllegalStateException("GPU renderer initialization timed out");
            }
        } catch (InterruptedException e) {
            close();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (error[0] != null) {
            close();
            throw error[0];
        }
        preferences.registerOnSharedPreferenceChangeListener(this);
    }

    public Surface getDecoderSurface() {
        return inputSurface;
    }

    public UpscalingDiagnostics getDiagnostics() {
        return diagnostics;
    }

    public void setSource(int width, int height, VideoColorInfo color) {
        Source current = source;
        if (current.width == width && current.height == height && current.color.equals(color))
            return;
        source = new Source(width, height, color);
        requestRedraw();
    }

    public void requestRedraw() {
        if (!closed) handler.post(() -> queueRender(false));
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (UpscalingMode.PREFERENCE.equals(key)) {
            selected = UpscalingMode.fromPreference(prefs.getString(key, "ravu"));
            requestRedraw();
        }
    }

    private void initialize(Surface output) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1))
            throw new IllegalStateException("EGL initialization failed");
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        int[] attributes = {
            EGL14.EGL_RENDERABLE_TYPE,
            0x40,
            EGL14.EGL_SURFACE_TYPE,
            EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE,
            8,
            EGL14.EGL_GREEN_SIZE,
            8,
            EGL14.EGL_BLUE_SIZE,
            8,
            EGL14.EGL_ALPHA_SIZE,
            0,
            EGL14.EGL_NONE
        };
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                || count[0] == 0) throw new IllegalStateException("No GLES 3 EGL window config");
        eglContext =
                EGL14.eglCreateContext(
                        display,
                        configs[0],
                        EGL14.EGL_NO_CONTEXT,
                        new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE},
                        0);
        window =
                EGL14.eglCreateWindowSurface(
                        display, configs[0], output, new int[] {EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(display, window, window, eglContext))
            throw new IllegalStateException("EGL window unavailable");
        direct =
                new GlProgram(
                        readAsset(
                                "direct.frag")); // also verifies GLES 3.1 and external ESSL3
                                                 // support
        backend =
                GLES31.glGetString(GLES31.GL_VERSION)
                        + " / "
                        + GLES31.glGetString(GLES31.GL_RENDERER);
        String extensions = GLES31.glGetString(GLES31.GL_EXTENSIONS);
        yuvExtension = extensions != null && extensions.contains("GL_EXT_YUV_target");
        int[] name = new int[1];
        GLES31.glGenVertexArrays(1, name, 0);
        vao = name[0];
        GLES31.glBindVertexArray(vao);
        GLES31.glGenTextures(1, name, 0);
        texture = name[0];
        GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES31.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR);
        GLES31.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR);
        GLES31.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES31.GL_TEXTURE_WRAP_S,
                GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES31.GL_TEXTURE_WRAP_T,
                GLES31.GL_CLAMP_TO_EDGE);
        inputTexture = new SurfaceTexture(texture);
        inputSurface = new Surface(inputTexture);
        inputTexture.setOnFrameAvailableListener(st -> queueRender(true), handler);
        GLES31.glDisable(GLES31.GL_BLEND);
        GLES31.glDisable(GLES31.GL_DEPTH_TEST);
        GLES31.glClearColor(0, 0, 0, 1);
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);
        EGL14.eglSwapBuffers(display, window);
        GlProgram.check("decoder surface initialization");
    }

    private String readAsset(String name) {
        try {
            return GlProgram.asset(context.getAssets(), name);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void queueRender(boolean frame) {
        if (closed) return;
        if (frame) {
            if (newFrame) coalesced++;
            newFrame = true;
        }
        if (!renderQueued) {
            renderQueued = true;
            handler.post(this::render);
        }
    }

    private void render() {
        renderQueued = false;
        if (closed) return;
        try {
            if (newFrame) {
                newFrame = false;
                inputTexture.updateTexImage();
                inputTexture.getTransformMatrix(transform);
                hasFrame = true;
            }
            if (!hasFrame) {
                diagnostics = snapshot(builtWidth, builtHeight, false, "waiting for decoded video");
                return;
            }
            int[] size = new int[2];
            if (!EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0)
                    || !EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 1)) return;
            Source current = source;
            UpscalingMode mode = selected;
            int w = size[0], h = size[1];
            if (w <= 0 || h <= 0) return;
            boolean active =
                    mode != UpscalingMode.OFF
                            && UpscalingMode.needsUpscaling(current.width, current.height, w, h);
            if (builtSource != current
                    || builtMode != mode
                    || builtWidth != w
                    || builtHeight != h) {
                if (pipeline != null) {
                    pipeline.close();
                    pipeline = null;
                }
                builtSource = current;
                builtMode = mode;
                builtWidth = w;
                builtHeight = h;
                failure = "";
                usingYuv = false;
                if (active)
                    buildPipeline(current, mode, w, h, yuvExtension && current.color.canSampleYuv);
            }
            long start = System.nanoTime();
            if (pipeline != null) {
                try {
                    pipeline.render(texture, transform);
                } catch (RuntimeException e) {
                    boolean retryRgb = usingYuv;
                    pipeline.close();
                    pipeline = null;
                    drainErrors();
                    if (retryRgb) buildPipeline(current, mode, w, h, false);
                    if (pipeline != null) {
                        try {
                            pipeline.render(texture, transform);
                        } catch (RuntimeException rgbError) {
                            pipeline.close();
                            pipeline = null;
                            failure = rgbError.getMessage();
                            drainErrors();
                        }
                    } else if (!retryRgb) failure = e.getMessage();
                    if (pipeline == null) drawDirect(w, h);
                }
            } else drawDirect(w, h);
            if (!EGL14.eglSwapBuffers(display, window))
                throw new IllegalStateException("EGL swap failed");
            double elapsed = (System.nanoTime() - start) / 1_000_000.0;
            renderMs = renderMs < 0 ? elapsed : renderMs * 0.95 + elapsed * 0.05;
            frames++;
            String reason =
                    !failure.isEmpty()
                            ? failure
                            : mode == UpscalingMode.OFF
                                    ? "disabled"
                                    : active ? "" : "source is at or above render resolution";
            diagnostics = snapshot(w, h, pipeline != null, reason);
            // Some EGL implementations latch a new native-window size only at swap.
            // Re-present a paused frame once at that size rather than leaving view scaling.
            int[] resized = new int[2];
            if (EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, resized, 0)
                    && EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, resized, 1)
                    && (resized[0] != w || resized[1] != h)) queueRender(false);
        } catch (RuntimeException e) {
            failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            diagnostics = snapshot(builtWidth, builtHeight, false, failure);
            log.warn("Upscaling render failed: {}", failure);
            drainErrors();
        }
    }

    private void buildPipeline(Source s, UpscalingMode mode, int w, int h, boolean rawYuv) {
        try {
            pipeline =
                    new GpuUpscalingPipeline(
                            context.getAssets(), mode, s.width, s.height, w, h, s.color, rawYuv);
            usingYuv = rawYuv;
        } catch (Exception e) {
            drainErrors();
            if (rawYuv) {
                buildPipeline(s, mode, w, h, false);
                return;
            }
            failure = "GPU upscaling unavailable: " + e.getMessage();
            log.warn("{}", failure);
        }
    }

    private void drawDirect(int w, int h) {
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0);
        GLES31.glViewport(0, 0, w, h);
        direct.use();
        direct.sampler("video", 0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES31.glUniformMatrix4fv(direct.location("transform"), 1, false, transform, 0);
        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3);
        GlProgram.check("direct video presentation");
    }

    private UpscalingDiagnostics snapshot(int w, int h, boolean active, String reason) {
        Source s = source;
        return new UpscalingDiagnostics(
                selected,
                s.width,
                s.height,
                w,
                h,
                active,
                backend,
                active && usingYuv
                        ? "YUV Catmull-Rom, anti-ringing"
                        : "Android external texture conversion",
                reason,
                frames,
                coalesced,
                renderMs);
    }

    private static void drainErrors() {
        for (int i = 0; i < 32 && GLES31.glGetError() != GLES31.GL_NO_ERROR; i++) {
            /* clear failed allocation/draw */
        }
    }

    private void destroy() {
        if (inputTexture != null) inputTexture.setOnFrameAvailableListener(null);
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (inputTexture != null) {
            inputTexture.release();
            inputTexture = null;
        }
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        if (direct != null) {
            direct.close();
            direct = null;
        }
        if (texture != 0) {
            GLES31.glDeleteTextures(1, new int[] {texture}, 0);
            texture = 0;
        }
        if (vao != 0) {
            GLES31.glDeleteVertexArrays(1, new int[] {vao}, 0);
            vao = 0;
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext);
            EGL14.eglTerminate(display);
            EGL14.eglReleaseThread();
            display = EGL14.EGL_NO_DISPLAY;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        handler.post(
                () -> {
                    try {
                        destroy();
                    } finally {
                        thread.quitSafely();
                    }
                });
        if (Thread.currentThread() == thread) return;
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
