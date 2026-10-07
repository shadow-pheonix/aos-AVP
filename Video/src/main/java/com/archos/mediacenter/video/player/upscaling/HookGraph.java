package com.archos.mediacenter.video.player.upscaling;

import android.content.res.AssetManager;
import android.opengl.GLES31;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Executes the pinned mpv/libplacebo-compatible shader bodies as a GLES pass graph. */
final class HookGraph implements AutoCloseable {
    private static final class Ref {
        final int width, height, components;
        int lastUse;
        boolean float32;
        GlTarget target;

        Ref(int w, int h, int c) {
            width = w;
            height = h;
            components = c;
        }

        Ref(GlTarget t) {
            this(t.width, t.height, t.components);
            target = t;
            float32 = t.float32;
        }
    }

    private static final class Step {
        final GlProgram program;
        final Map<String, Ref> inputs;
        final Ref output;

        Step(GlProgram p, Map<String, Ref> inputs, Ref output) {
            program = p;
            this.inputs = inputs;
            this.output = output;
        }
    }

    private static final class Slot {
        final GlTarget target;
        int availableAfter;

        Slot(GlTarget t, int end) {
            target = t;
            availableAfter = end;
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();
    private final List<GlTarget> luts = new ArrayList<>();
    private final int sourceWidth, sourceHeight, outputWidth, outputHeight;
    private Ref result;

    HookGraph(
            AssetManager assets,
            String name,
            GlTarget source,
            GlTarget prekernel,
            int outputWidth,
            int outputHeight)
            throws Exception {
        sourceWidth = prekernel.width;
        sourceHeight = prekernel.height;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        try {
            JSONObject manifest =
                    new JSONObject(GlProgram.asset(assets, "generated/" + name + ".json"));
            Map<String, Ref> env = new HashMap<>();
            env.put("LUMA", new Ref(source));
            env.put("POSTKERNEL", new Ref(source));
            env.put("PREKERNEL", new Ref(prekernel));
            JSONArray textures = manifest.getJSONArray("textures");
            for (int i = 0; i < textures.length(); i++) {
                JSONObject t = textures.getJSONObject(i);
                int w = t.getInt("width"), h = t.getInt("height");
                ByteBuffer pixels =
                        ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder());
                try (InputStream in = assets.open("upscaling/generated/" + t.getString("file"))) {
                    byte[] data = new byte[16384];
                    int count;
                    while ((count = in.read(data)) != -1) pixels.put(data, 0, count);
                }
                pixels.flip();
                GlTarget target = new GlTarget(w, h, 4, pixels, false);
                luts.add(target);
                env.put(t.getString("name"), new Ref(target));
            }
            JSONArray passes = manifest.getJSONArray("passes");
            for (int i = 0; i < passes.length(); i++) {
                JSONObject pass = passes.getJSONObject(i);
                String hook = pass.getString("hook");
                env.put("HOOKED", env.get(hook));
                int w = dimension(pass.getString("width"), env),
                        h = dimension(pass.getString("height"), env);
                Ref output = new Ref(w, h, pass.getInt("components"));
                output.lastUse = i;
                output.float32 = pass.optBoolean("float32", false);
                Map<String, Ref> inputs = new HashMap<>();
                JSONArray binds = pass.getJSONArray("binds");
                for (int j = 0; j < binds.length(); j++) {
                    String key = binds.getString(j);
                    Ref ref = env.get(key);
                    if (ref == null) throw new IllegalStateException("Missing hook input " + key);
                    ref.lastUse = Math.max(ref.lastUse, i);
                    inputs.put(key, ref);
                }
                GlProgram program =
                        new GlProgram(
                                GlProgram.asset(assets, "generated/" + pass.getString("file")));
                steps.add(new Step(program, inputs, output));
                String save = pass.getString("save");
                env.put(save.isEmpty() ? hook : save, output);
                result = env.get(hook);
            }
            result.lastUse = steps.size(); // output must survive until compositing
            // Interval allocation reuses dead feature/variance maps. Never alias an input with its
            // output.
            for (int i = 0; i < steps.size(); i++) {
                Ref output = steps.get(i).output;
                Slot found = null;
                for (Slot slot : slots)
                    if (slot.availableAfter < i
                            && slot.target.width == output.width
                            && slot.target.height == output.height
                            && slot.target.components == output.components
                            && slot.target.float32 == output.float32) {
                        found = slot;
                        break;
                    }
                if (found == null) {
                    found =
                            new Slot(
                                    new GlTarget(
                                            output.width,
                                            output.height,
                                            output.components,
                                            output.float32),
                                    output.lastUse);
                    slots.add(found);
                }
                found.availableAfter = output.lastUse;
                output.target = found.target;
            }
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private int dimension(String expression, Map<String, Ref> env) {
        ArrayDeque<Double> stack = new ArrayDeque<>();
        for (String token : expression.split("\\s+")) {
            if ("+-*/".contains(token) && token.length() == 1) {
                double right = stack.pop(), left = stack.pop();
                stack.push(
                        token.equals("+")
                                ? left + right
                                : token.equals("-")
                                        ? left - right
                                        : token.equals("*") ? left * right : left / right);
            } else if (token.contains(".")) {
                int dot = token.lastIndexOf('.');
                String key = token.substring(0, dot), axis = token.substring(dot + 1);
                if (key.equals("OUTPUT"))
                    stack.push((double) (axis.equals("w") ? outputWidth : outputHeight));
                else if (key.equals("NATIVE_CROPPED"))
                    stack.push((double) (axis.equals("w") ? sourceWidth : sourceHeight));
                else if (env.containsKey(key)) {
                    Ref ref = env.get(key);
                    stack.push((double) (axis.equals("w") ? ref.width : ref.height));
                } else stack.push(Double.parseDouble(token));
            } else stack.push(Double.parseDouble(token));
        }
        if (stack.size() != 1)
            throw new IllegalArgumentException("Invalid hook dimension " + expression);
        return Math.max(1, (int) Math.round(stack.pop()));
    }

    GlTarget render() {
        for (Step step : steps) {
            step.output.target.bind();
            step.program.use();
            step.program.vec2("input_size", sourceWidth, sourceHeight);
            step.program.vec2("output_size", outputWidth, outputHeight);
            int unit = 0;
            for (Map.Entry<String, Ref> input : step.inputs.entrySet()) {
                GlTarget t = input.getValue().target;
                String key = input.getKey();
                step.program.sampler(key + "_raw", unit++, GLES31.GL_TEXTURE_2D, t.texture);
                step.program.vec2(key + "_size", t.width, t.height);
            }
            GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3);
        }
        GlProgram.check("upscaling hook graph");
        return result.target;
    }

    @Override
    public void close() {
        for (Step step : steps) step.program.close();
        steps.clear();
        for (Slot slot : slots) slot.target.close();
        slots.clear();
        for (GlTarget lut : luts) lut.close();
        luts.clear();
    }
}
