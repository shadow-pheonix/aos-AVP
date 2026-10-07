package com.archos.mediacenter.video.player.upscaling;

import android.opengl.GLES31;

import java.nio.Buffer;

final class GlTarget implements AutoCloseable {
    final int width, height, components, texture, framebuffer;
    final boolean float32;

    GlTarget(int width, int height, int components) {
        this(width, height, components, null, true);
    }

    GlTarget(int width, int height, int components, boolean float32) {
        this(width, height, components, null, true, float32);
    }

    GlTarget(int width, int height, int components, Buffer pixels, boolean renderable) {
        this(width, height, components, pixels, renderable, false);
    }

    GlTarget(
            int width,
            int height,
            int components,
            Buffer pixels,
            boolean renderable,
            boolean float32) {
        this.width = width;
        this.height = height;
        this.components = components;
        this.float32 = float32;
        if (float32
                && !GLES31.glGetString(GLES31.GL_EXTENSIONS)
                        .contains("GL_OES_texture_float_linear"))
            throw new IllegalStateException(
                    "SSimSuperRes requires linear filtering of 32-bit variance textures");
        int[] names = new int[1];
        GLES31.glGenTextures(1, names, 0);
        texture = names[0];
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture);
        GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR);
        GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR);
        GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE);
        int format =
                components == 1 ? GLES31.GL_RED : components == 2 ? GLES31.GL_RG : GLES31.GL_RGBA;
        int internal =
                float32
                        ? components == 1
                                ? GLES31.GL_R32F
                                : components == 2 ? GLES31.GL_RG32F : GLES31.GL_RGBA32F
                        : components == 1
                                ? GLES31.GL_R16F
                                : components == 2 ? GLES31.GL_RG16F : GLES31.GL_RGBA16F;
        GLES31.glTexImage2D(
                GLES31.GL_TEXTURE_2D,
                0,
                internal,
                width,
                height,
                0,
                format,
                float32 ? GLES31.GL_FLOAT : GLES31.GL_HALF_FLOAT,
                pixels);
        GLES31.glGenFramebuffers(1, names, 0);
        framebuffer = names[0];
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, framebuffer);
        GLES31.glFramebufferTexture2D(
                GLES31.GL_FRAMEBUFFER,
                GLES31.GL_COLOR_ATTACHMENT0,
                GLES31.GL_TEXTURE_2D,
                texture,
                0);
        try {
            GlProgram.check("float texture allocation");
            if (renderable
                    && GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER)
                            != GLES31.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException(
                        "GPU does not support floating point render targets");
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    void bind() {
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, framebuffer);
        GLES31.glViewport(0, 0, width, height);
    }

    long estimatedBytes() {
        return (long) width * height * (components <= 2 ? components : 4) * (float32 ? 4 : 2);
    }

    @Override
    public void close() {
        GLES31.glDeleteFramebuffers(1, new int[] {framebuffer}, 0);
        GLES31.glDeleteTextures(1, new int[] {texture}, 0);
    }
}
