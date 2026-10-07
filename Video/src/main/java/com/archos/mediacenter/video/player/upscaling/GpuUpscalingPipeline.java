package com.archos.mediacenter.video.player.upscaling;

import android.content.res.AssetManager;
import android.opengl.GLES11Ext;
import android.opengl.GLES31;

import java.util.ArrayList;
import java.util.List;

/**
 * Source-sized import, trained luma reconstruction, chroma combination, final-size presentation.
 */
final class GpuUpscalingPipeline implements AutoCloseable {
    private final AssetManager assets;
    private final List<GlTarget> targets = new ArrayList<>();
    private final List<GlProgram> programs = new ArrayList<>();
    private final int outputWidth, outputHeight;
    private final UpscalingMode mode;
    private final boolean rawYuv;
    private final VideoColorInfo color;
    private GlProgram importer, extract, combine, lanczos, sgsr, present;
    private GlTarget rgb, luma, combined, intermediate, scaled;
    private HookGraph reconstruction, ssim;

    GpuUpscalingPipeline(
            AssetManager assets,
            UpscalingMode mode,
            int w,
            int h,
            int outW,
            int outH,
            VideoColorInfo color,
            boolean rawYuv)
            throws Exception {
        this.assets = assets;
        this.mode = mode;
        this.outputWidth = outW;
        this.outputHeight = outH;
        this.rawYuv = rawYuv;
        this.color = color;
        try {
            importer = program(rawYuv ? "import_yuv.frag" : "import_rgb.frag");
            present = program("present.frag");
            rgb = target(w, h, 4);
            if (mode == UpscalingMode.OFF) return;
            if (mode == UpscalingMode.SGSR1) {
                sgsr = program("generated/sgsr1.frag");
                return;
            }
            luma = target(w, h, 1);
            extract = program("luma.frag");
            combine = program("combine.frag");
            int reconstructedW = mode == UpscalingMode.FSRCNNX ? w * 2 : outW;
            int reconstructedH = mode == UpscalingMode.FSRCNNX ? h * 2 : outH;
            reconstruction =
                    new HookGraph(
                            assets,
                            mode == UpscalingMode.RAVU ? "ravu" : "fsrcnnx",
                            luma,
                            rgb,
                            reconstructedW,
                            reconstructedH);
            combined = target(reconstructedW, reconstructedH, 4);
            if (mode == UpscalingMode.RAVU)
                ssim = new HookGraph(assets, "ssim", combined, rgb, outW, outH);
            else if (reconstructedW != outW || reconstructedH != outH) {
                lanczos = program("lanczos.frag");
                intermediate = target(outW, reconstructedH, 4);
                scaled = target(outW, outH, 4);
            }
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private GlProgram program(String asset) throws Exception {
        GlProgram p = new GlProgram(GlProgram.asset(assets, asset));
        programs.add(p);
        return p;
    }

    private GlTarget target(int w, int h, int components) {
        GlTarget t = new GlTarget(w, h, components);
        targets.add(t);
        return t;
    }

    private void draw() {
        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3);
    }

    void render(int externalTexture, float[] transform) {
        rgb.bind();
        importer.use();
        importer.sampler("video", 0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
        GLES31.glUniformMatrix4fv(importer.location("transform"), 1, false, transform, 0);
        if (rawYuv) {
            importer.vec2("source_size", rgb.width, rgb.height);
            importer.vec2("chroma_scale", color.chromaScaleX, color.chromaScaleY);
            importer.vec2("chroma_center", color.chromaCenterX, color.chromaCenterY);
            importer.vec2("kr_kb", color.kr, color.kb);
            importer.vec2("code_range", color.codeMax, color.codeStep);
            GLES31.glUniform1i(importer.location("full_range"), color.fullRange ? 1 : 0);
        }
        draw();
        GlTarget finalFrame = rgb;
        if (mode == UpscalingMode.SGSR1) {
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0);
            GLES31.glViewport(0, 0, outputWidth, outputHeight);
            sgsr.use();
            sgsr.sampler("ps0", 0, GLES31.GL_TEXTURE_2D, rgb.texture);
            GLES31.glUniform4f(
                    sgsr.location("ViewportInfo[0]"),
                    1f / rgb.width,
                    1f / rgb.height,
                    rgb.width,
                    rgb.height);
            draw();
            GlProgram.check("SGSR1");
            return;
        }
        if (reconstruction != null) {
            luma.bind();
            extract.use();
            extract.sampler("source", 0, GLES31.GL_TEXTURE_2D, rgb.texture);
            draw();
            GlTarget reconstructed = reconstruction.render();
            combined.bind();
            combine.use();
            combine.sampler("source", 0, GLES31.GL_TEXTURE_2D, rgb.texture);
            combine.sampler("luma", 1, GLES31.GL_TEXTURE_2D, reconstructed.texture);
            combine.vec2("source_size", rgb.width, rgb.height);
            GLES31.glUniform1i(
                    combine.location("guard_luma"), mode == UpscalingMode.FSRCNNX ? 1 : 0);
            draw();
            finalFrame = combined;
            if (ssim != null) finalFrame = ssim.render();
            else if (scaled != null) {
                resample(combined, intermediate, 1, 0);
                resample(intermediate, scaled, 0, 1);
                finalFrame = scaled;
            }
        }
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0);
        GLES31.glViewport(0, 0, outputWidth, outputHeight);
        present.use();
        present.sampler("source", 0, GLES31.GL_TEXTURE_2D, finalFrame.texture);
        draw();
        GlProgram.check("upscaling presentation");
    }

    private void resample(GlTarget input, GlTarget output, float x, float y) {
        output.bind();
        lanczos.use();
        lanczos.sampler("source", 0, GLES31.GL_TEXTURE_2D, input.texture);
        lanczos.vec2("source_size", input.width, input.height);
        lanczos.vec2("output_size", output.width, output.height);
        lanczos.vec2("axis", x, y);
        draw();
    }

    String allocationSummary() {
        long bytes = 0;
        for (GlTarget target : targets) bytes += target.estimatedBytes();
        if (reconstruction != null) bytes += reconstruction.estimatedBytes();
        if (ssim != null) bytes += ssim.estimatedBytes();
        int passes =
                reconstruction == null
                        ? 2
                        : 4
                                + reconstruction.passCount()
                                + (ssim == null ? (scaled == null ? 0 : 2) : ssim.passCount());
        return "passes="
                + passes
                + " allocated_texture_bytes="
                + bytes
                + " (estimate excludes decoder, EGL buffers and driver overhead)";
    }

    @Override
    public void close() {
        if (reconstruction != null) {
            reconstruction.close();
            reconstruction = null;
        }
        if (ssim != null) {
            ssim.close();
            ssim = null;
        }
        for (GlProgram p : programs) p.close();
        programs.clear();
        for (GlTarget t : targets) t.close();
        targets.clear();
    }
}
