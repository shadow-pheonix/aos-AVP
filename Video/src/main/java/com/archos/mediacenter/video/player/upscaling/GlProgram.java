package com.archos.mediacenter.video.player.upscaling;

import android.content.res.AssetManager;
import android.opengl.GLES31;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** All methods are called on the EGL rendering thread. */
final class GlProgram implements AutoCloseable {
    static final String VERTEX =
            "#version 310 es\n"
                    + "precision highp float;\nlayout(location=0) out highp vec4 in_TEXCOORD0;\n"
                    + "void main(){ vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));"
                    + "in_TEXCOORD0=vec4(p,0,1);gl_Position=vec4(p*2.0-1.0,0,1); }\n";
    private final Map<String, Integer> locations = new HashMap<>();
    final int id;

    GlProgram(String fragment) {
        int vertex = compile(GLES31.GL_VERTEX_SHADER, VERTEX);
        int pixel = 0;
        int program = 0;
        try {
            pixel = compile(GLES31.GL_FRAGMENT_SHADER, fragment);
            program = GLES31.glCreateProgram();
            GLES31.glAttachShader(program, vertex);
            GLES31.glAttachShader(program, pixel);
            GLES31.glLinkProgram(program);
            int[] status = new int[1];
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0);
            if (status[0] == 0)
                throw new IllegalStateException(GLES31.glGetProgramInfoLog(program));
            id = program;
        } catch (RuntimeException e) {
            if (program != 0) GLES31.glDeleteProgram(program);
            throw e;
        } finally {
            GLES31.glDeleteShader(vertex);
            if (pixel != 0) GLES31.glDeleteShader(pixel);
        }
    }

    private static int compile(int kind, String source) {
        int shader = GLES31.glCreateShader(kind);
        GLES31.glShaderSource(shader, source);
        GLES31.glCompileShader(shader);
        int[] status = new int[1];
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String error = GLES31.glGetShaderInfoLog(shader);
            GLES31.glDeleteShader(shader);
            throw new IllegalStateException(error);
        }
        return shader;
    }

    void use() {
        GLES31.glUseProgram(id);
    }

    int location(String name) {
        Integer location = locations.get(name);
        if (location == null) {
            location = GLES31.glGetUniformLocation(id, name);
            locations.put(name, location);
        }
        return location;
    }

    void vec2(String name, float x, float y) {
        GLES31.glUniform2f(location(name), x, y);
    }

    void sampler(String name, int unit, int target, int texture) {
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + unit);
        GLES31.glBindTexture(target, texture);
        GLES31.glUniform1i(location(name), unit);
    }

    static String asset(AssetManager assets, String path) throws IOException {
        try (InputStream stream = assets.open("upscaling/" + path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int count;
            while ((count = stream.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void check(String operation) {
        int error = GLES31.glGetError();
        if (error != GLES31.GL_NO_ERROR)
            throw new IllegalStateException(operation + ": GL 0x" + Integer.toHexString(error));
    }

    @Override
    public void close() {
        GLES31.glDeleteProgram(id);
    }
}
