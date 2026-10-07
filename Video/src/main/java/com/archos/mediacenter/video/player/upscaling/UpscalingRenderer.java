package com.archos.mediacenter.video.player.upscaling;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES31;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.utils.PlaybackDiagnostics;

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
    private final Runnable renderTask = this::render;
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
    private final PlaybackDiagnostics recorder;
    private volatile String phase = "initializing", allocation = "no reconstruction graph";
    private volatile long phaseAt = SystemClock.uptimeMillis();
    private volatile double frameBudgetMs;
    private long fence, fenceAt, callbackCount, lastInputTimestamp, lastRecordAt;
    private double importMs, drawMs, swapMs, buildMs, maximumMs, completionMs;
    private long overBudgetFrames, renderFailures, lastFailureAt;
    private volatile boolean graphReady;
    private boolean warming;
    private boolean presentationTimeSupported, presentationTimeFailed;
    private long lastImportedAt, inputGapCount, timestampGapCount;
    private double inputAgeMs, lastInputGapMs, lastTimestampStepMs;
    private long presentationTimestampNs;

    public UpscalingRenderer(Context context, Surface output) {
        this.context = context.getApplicationContext();
        recorder = PlaybackDiagnostics.get(this.context);
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
                        recorder.failure("gpu_initialization_failed", e);
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
        graphReady = false;
        recorder.event("video_source", width + "x" + height + " " + color.describe());
        requestRedraw();
    }

    public void setFrameRate(double fps) {
        frameBudgetMs = Double.isFinite(fps) && fps > 0 ? 1000.0 / fps : 0;
        recorder.event("video_cadence", "fps=" + fps + " frame_budget_ms=" + frameBudgetMs);
    }

    public String telemetry() {
        long elapsed = SystemClock.uptimeMillis() - phaseAt;
        return diagnostics.describe()
                + "\nrequested="
                + selected.label
                + " phase="
                + phase
                + " phase_age_ms="
                + elapsed
                + "\n"
                + ((elapsed > 2000 && !"idle".equals(phase))
                        ? PlaybackDiagnostics.stack(thread)
                        : "");
    }

    private void phase(String next) {
        if (!phase.equals(next)) {
            phase = next;
            phaseAt = SystemClock.uptimeMillis();
        }
    }

    public boolean isReadyForPlayback() {
        return graphReady;
    }

    /**
     * Compile before audio starts, including a mode selected while paused. Never waits on the UI.
     */
    public void preparePipeline(Runnable ready) {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (closed) return;
                        try {
                            if (!graphReady || !hasFrame) {
                                if (!hasFrame) {
                                    GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0);
                                    GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);
                                    if (!EGL14.eglSwapBuffers(display, window))
                                        throw new IllegalStateException(
                                                "EGL warmup swap failed: 0x"
                                                        + Integer.toHexString(EGL14.eglGetError()));
                                }
                                warming = true;
                                try {
                                    render();
                                } finally {
                                    warming = false;
                                }
                            }
                            if (graphReady && completeGpuFrame()) {
                                phase("idle");
                                ready.run();
                            } else handler.postDelayed(this, 5);
                        } catch (RuntimeException e) {
                            failure = message(e);
                            graphReady = true;
                            diagnostics = snapshot(builtWidth, builtHeight, false, failure);
                            recorder.failure("gpu_warmup_failed", e);
                            recorder.capture("gpu_warmup_failed", telemetry());
                            // A broken fence must not prevent the player reporting its renderer
                            // fallback.
                            if (fence != 0) {
                                GLES31.glDeleteSync(fence);
                                fence = 0;
                            }
                            ready.run();
                        }
                    }
                });
    }

    private boolean completeGpuFrame() {
        if (fence == 0) return true;
        phase("waiting for GPU completion");
        int result = GLES31.glClientWaitSync(fence, 0, 0);
        if (result == GLES31.GL_TIMEOUT_EXPIRED) return false;
        if (result == GLES31.GL_WAIT_FAILED)
            throw new IllegalStateException("GPU completion fence failed");
        completionMs = (System.nanoTime() - fenceAt) / 1_000_000.0;
        GLES31.glDeleteSync(fence);
        fence = 0;
        return true;
    }

    public void requestRedraw() {
        if (!closed) handler.post(() -> queueRender(false));
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (UpscalingMode.PREFERENCE.equals(key)) {
            selected = UpscalingMode.fromPreference(prefs.getString(key, "ravu"));
            graphReady = false;
            recorder.event("mode_requested", selected.label);
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
                        readAsset("direct.frag")); // also verifies GLES 3.1 and external ESSL3
        // support
        backend =
                GLES31.glGetString(GLES31.GL_VERSION)
                        + " / "
                        + GLES31.glGetString(GLES31.GL_RENDERER);
        String eglExtensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS);
        presentationTimeSupported =
                eglExtensions != null && eglExtensions.contains("EGL_ANDROID_presentation_time");
        recorder.event(
                "egl_frame_timing", "presentation_time_supported=" + presentationTimeSupported);
        String extensions = GLES31.glGetString(GLES31.GL_EXTENSIONS);
        yuvExtension = extensions != null && extensions.contains("GL_EXT_YUV_target");
        int[] maximum = new int[1];
        GLES31.glGetIntegerv(GLES31.GL_MAX_TEXTURE_SIZE, maximum, 0);
        recorder.event(
                "gpu_capabilities",
                "backend="
                        + backend
                        + " vendor="
                        + GLES31.glGetString(GLES31.GL_VENDOR)
                        + " glsl="
                        + GLES31.glGetString(GLES31.GL_SHADING_LANGUAGE_VERSION)
                        + " max_texture="
                        + maximum[0]
                        + " yuv_target="
                        + yuvExtension
                        + " float_linear="
                        + (extensions != null && extensions.contains("GL_OES_texture_float_linear"))
                        + " color_buffer_float="
                        + (extensions != null && extensions.contains("GL_EXT_color_buffer_float")));
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
        phase("idle");
        recorder.capture("gpu_initialized", telemetry());
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
            callbackCount++;
            if (newFrame) coalesced++;
            newFrame = true;
        }
        if (!renderQueued) {
            renderQueued = true;
            handler.post(renderTask);
        }
    }

    private void render() {
        // Warmup and frame callbacks share one pending render/poll, even on a slow GPU.
        handler.removeCallbacks(renderTask);
        renderQueued = false;
        if (closed) return;
        try {
            // Bound submitted GPU work to one video frame. Poll rather than blocking the
            // decoder/UI; when it finishes, updateTexImage acquires the latest available image.
            if (!completeGpuFrame()) {
                renderQueued = true;
                handler.postDelayed(renderTask, 5);
                return;
            }
            long frameStart = System.nanoTime();
            phase("importing decoder frame");
            boolean acquiredImage = newFrame;
            if (newFrame) {
                newFrame = false;
                inputTexture.updateTexImage();
                inputTexture.getTransformMatrix(transform);
                long timestamp = inputTexture.getTimestamp();
                long importedAt = System.nanoTime();
                inputAgeMs = (importedAt - timestamp) / 1_000_000.0;
                lastInputGapMs =
                        lastImportedAt == 0 ? 0 : (importedAt - lastImportedAt) / 1_000_000.0;
                lastTimestampStepMs =
                        lastInputTimestamp == 0
                                ? 0
                                : (timestamp - lastInputTimestamp) / 1_000_000.0;
                double gapThreshold = Math.max(100, frameBudgetMs * 2.5);
                if (lastInputGapMs > gapThreshold
                        || lastTimestampStepMs > gapThreshold
                        || lastTimestampStepMs < 0) {
                    if (lastInputGapMs > gapThreshold) inputGapCount++;
                    if (lastTimestampStepMs > gapThreshold || lastTimestampStepMs < 0)
                        timestampGapCount++;
                    recorder.event(
                            "decoder_image_gap",
                            "mode="
                                    + selected.label
                                    + " arrival_gap_ms="
                                    + lastInputGapMs
                                    + " timestamp_step_ms="
                                    + lastTimestampStepMs
                                    + " timestamp_age_ms="
                                    + inputAgeMs
                                    + " (may include pause/seek; not a compositor-drop count)");
                }
                lastImportedAt = importedAt;
                lastInputTimestamp = timestamp;
                hasFrame = true;
            }
            importMs = (System.nanoTime() - frameStart) / 1_000_000.0;
            if (!hasFrame && !warming) {
                diagnostics =
                        snapshot(
                                builtWidth,
                                builtHeight,
                                false,
                                "waiting for graph preparation and decoded video");
                phase("idle");
                return;
            }
            int[] size = new int[2];
            if (!EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0)
                    || !EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 1))
                throw new IllegalStateException(
                        "EGL output size unavailable: 0x"
                                + Integer.toHexString(EGL14.eglGetError()));
            Source current = source;
            UpscalingMode mode = selected;
            int w = size[0], h = size[1];
            if (w <= 0 || h <= 0) throw new IllegalStateException("EGL output has zero size");
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
                allocation = "no reconstruction graph";
                usingYuv = false;
                phase("building shader graph");
                long buildStart = System.nanoTime();
                if (active)
                    buildPipeline(current, mode, w, h, yuvExtension && current.color.canSampleYuv);
                buildMs = (System.nanoTime() - buildStart) / 1_000_000.0;
                recorder.event(
                        "pipeline_built",
                        mode.label
                                + " source="
                                + current.width
                                + "x"
                                + current.height
                                + " output="
                                + w
                                + "x"
                                + h
                                + " active="
                                + (pipeline != null)
                                + " build_ms="
                                + buildMs
                                + " "
                                + allocation
                                + " failure="
                                + failure);
            }
            graphReady = source == current && selected == mode;
            if (!hasFrame) {
                diagnostics = snapshot(w, h, false, "graph prepared; waiting for decoded video");
                phase("idle");
                recorder.capture("gpu_warmup_complete", telemetry());
                return;
            }
            long start = System.nanoTime();
            phase("drawing video");
            if (pipeline != null) {
                try {
                    pipeline.render(texture, transform);
                } catch (RuntimeException e) {
                    recorder.failure("gpu_draw_failed", e);
                    boolean retryRgb = usingYuv;
                    pipeline.close();
                    pipeline = null;
                    drainErrors();
                    if (retryRgb) buildPipeline(current, mode, w, h, false);
                    if (pipeline != null) {
                        try {
                            pipeline.render(texture, transform);
                        } catch (RuntimeException rgbError) {
                            recorder.failure("rgb_fallback_draw_failed", rgbError);
                            pipeline.close();
                            pipeline = null;
                            failure = message(rgbError);
                            drainErrors();
                        }
                    } else if (!retryRgb) failure = message(e);
                    if (pipeline == null) drawDirect(w, h);
                }
            } else drawDirect(w, h);
            drawMs = (System.nanoTime() - start) / 1_000_000.0;
            // A fence records completion latency (queue + GPU + polling), not a GPU timer query.
            fence = GLES31.glFenceSync(GLES31.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GlProgram.check("GPU completion fence creation");
            fenceAt = System.nanoTime();
            GLES31.glFlush();
            phase("EGL presentation");
            long swapStart = System.nanoTime();
            presentationTimestampNs =
                    VideoFrameTiming.presentationTimeNs(
                            lastInputTimestamp, System.nanoTime(), acquiredImage);
            if (presentationTimeSupported
                    && !presentationTimeFailed
                    && !EGLExt.eglPresentationTimeANDROID(
                            display, window, presentationTimestampNs)) {
                presentationTimeFailed = true;
                recorder.event(
                        "egl_frame_timing_failed",
                        "error=0x" + Integer.toHexString(EGL14.eglGetError()));
            }
            if (!EGL14.eglSwapBuffers(display, window))
                throw new IllegalStateException(
                        "EGL swap failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            swapMs = (System.nanoTime() - swapStart) / 1_000_000.0;
            double elapsed = (System.nanoTime() - start) / 1_000_000.0;
            renderMs = renderMs < 0 ? elapsed : renderMs * 0.95 + elapsed * 0.05;
            maximumMs = Math.max(maximumMs, elapsed + importMs);
            if (frameBudgetMs > 0 && elapsed + importMs > frameBudgetMs) overBudgetFrames++;
            frames++;
            String reason =
                    !failure.isEmpty()
                            ? failure
                            : mode == UpscalingMode.OFF
                                    ? "disabled"
                                    : active ? "" : "source is at or above render resolution";
            diagnostics = snapshot(w, h, pipeline != null, reason);
            phase("idle");
            if (SystemClock.uptimeMillis() - lastRecordAt >= 5000) {
                lastRecordAt = SystemClock.uptimeMillis();
                recorder.capture("frame_progress", telemetry());
            }
            // Some EGL implementations latch a new native-window size only at swap.
            // Re-present a paused frame once at that size rather than leaving view scaling.
            int[] resized = new int[2];
            if (EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, resized, 0)
                    && EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, resized, 1)
                    && (resized[0] != w || resized[1] != h)) queueRender(false);
        } catch (RuntimeException e) {
            failure = message(e);
            graphReady = true; // finish the preparation attempt; keep the failure visible
            diagnostics = snapshot(builtWidth, builtHeight, false, failure);
            renderFailures++;
            if (SystemClock.uptimeMillis() - lastFailureAt >= 1000) {
                lastFailureAt = SystemClock.uptimeMillis();
                log.warn("Upscaling render failed: {}", failure, e);
                recorder.failure("gpu_render_failed", e);
                recorder.capture("gpu_failure", telemetry());
            }
            if (fence != 0) {
                GLES31.glDeleteSync(fence);
                fence = 0;
            }
            drainErrors();
            phase("idle");
        }
    }

    private void buildPipeline(Source s, UpscalingMode mode, int w, int h, boolean rawYuv) {
        try {
            pipeline =
                    new GpuUpscalingPipeline(
                            context.getAssets(), mode, s.width, s.height, w, h, s.color, rawYuv);
            usingYuv = rawYuv;
            allocation = pipeline.allocationSummary();
        } catch (Exception e) {
            recorder.failure(rawYuv ? "yuv_graph_failed_retrying_rgb" : "gpu_graph_failed", e);
            drainErrors();
            if (rawYuv) {
                buildPipeline(s, mode, w, h, false);
                return;
            }
            failure = "GPU upscaling unavailable: " + e.getMessage();
            log.warn("{}", failure);
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
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
                renderMs,
                String.format(
                        java.util.Locale.US,
                        "CPU stages: import=%.2f draw-submit=%.2f swap=%.2f ms; last graph"
                            + " build=%.2f ms\n"
                            + "GPU fence completion latency=%.2f ms (queue + GPU + polling; not GPU"
                            + " execution time)\n"
                            + "Frame budget=%.2f ms; max CPU render+swap=%.2f ms; over-budget"
                            + " draws=%d\n"
                            + "Callbacks=%d; last SurfaceTexture timestamp_ns=%d;"
                            + " render_failures=%d\n"
                            + "Decoder image age=%.2f ms; arrival gap=%.2f ms; timestamp step=%.2f"
                            + " ms\n"
                            + "Image gaps=%d; timestamp gaps=%d (may include pause/seek)\n"
                            + "EGL presentation timing=%s; last requested timestamp_ns=%d\n"
                            + "%s\n"
                            + "%s",
                        importMs,
                        drawMs,
                        swapMs,
                        buildMs,
                        completionMs,
                        frameBudgetMs,
                        maximumMs,
                        overBudgetFrames,
                        callbackCount,
                        lastInputTimestamp,
                        renderFailures,
                        inputAgeMs,
                        lastInputGapMs,
                        lastTimestampStepMs,
                        inputGapCount,
                        timestampGapCount,
                        presentationTimeSupported && !presentationTimeFailed
                                ? "decoder deadline preserved"
                                : "unavailable",
                        presentationTimestampNs,
                        allocation,
                        s.color.describe()));
    }

    private static void drainErrors() {
        for (int i = 0; i < 32 && GLES31.glGetError() != GLES31.GL_NO_ERROR; i++) {
            /* clear failed allocation/draw */
        }
    }

    private void destroy() {
        if (fence != 0) {
            GLES31.glDeleteSync(fence);
            fence = 0;
        }
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
        recorder.capture("gpu_closing", telemetry());
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
