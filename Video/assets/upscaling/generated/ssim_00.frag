#version 310 es
precision highp float;
precision highp int;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
uniform vec2 input_size;
uniform vec2 output_size;
const vec2 tex_offset = vec2(0.0);
uniform highp sampler2D HOOKED_raw;
uniform vec2 HOOKED_size;
#define HOOKED_pt (1.0 / HOOKED_size)
#define HOOKED_pos in_TEXCOORD0.xy
#define HOOKED_mul 1.0
#define HOOKED_tex(p) texture(HOOKED_raw, (p))
#define HOOKED_texOff(p) texture(HOOKED_raw, HOOKED_pos + vec2(p) * HOOKED_pt)
#define axis        1

#define offset      vec2(0,0)

#define MN(B,C,x)   (x < 1.0 ? ((2.-1.5*B-(C))*x + (-3.+2.*B+C))*x*x + (1.-(B)/3.) : (((-(B)/6.-(C))*x + (B+5.*C))*x + (-2.*B-8.*C))*x+((4./3.)*B+4.*C))
#define Kernel(x)   MN(0.334, 0.333, abs(x))
#define taps        2.0

#define Luma(rgb)   dot(rgb*rgb, vec3(0.2126, 0.7152, 0.0722))

vec4 hook() {
    float low  = ceil((HOOKED_pos - taps/input_size) * HOOKED_size - offset - 0.5)[axis];
    float high = floor((HOOKED_pos + taps/input_size) * HOOKED_size - offset - 0.5)[axis];

    float W = 0.0;
    vec4 avg = vec4(0);
    vec2 pos = HOOKED_pos;
    vec4 tex;

    for (float k = low; k <= high; k++) {
        pos[axis] = HOOKED_pt[axis] * (k - offset[axis] + 0.5);
        float rel = (pos[axis] - HOOKED_pos[axis])*input_size[axis];
        float w = Kernel(rel);

        tex.rgb = textureLod(HOOKED_raw, pos, 0.0).rgb * HOOKED_mul;
        tex.a = Luma(tex.rgb);
        avg += w * tex;
        W += w;
    }
    avg /= W;

    return vec4(avg.rgb, max(abs(avg.a - Luma(avg.rgb)), 5e-7));
}

void main() { out_Target0 = hook(); }
