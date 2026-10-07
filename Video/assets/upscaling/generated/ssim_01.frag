#version 310 es
precision highp float;
precision highp int;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
uniform vec2 input_size;
uniform vec2 output_size;
const vec2 tex_offset = vec2(0.0);
uniform highp sampler2D LOWRES_raw;
uniform vec2 LOWRES_size;
#define LOWRES_pt (1.0 / LOWRES_size)
#define LOWRES_pos in_TEXCOORD0.xy
#define LOWRES_mul 1.0
#define LOWRES_tex(p) texture(LOWRES_raw, (p))
#define LOWRES_texOff(p) texture(LOWRES_raw, LOWRES_pos + vec2(p) * LOWRES_pt)
#define axis        0

#define offset      vec2(0,0)

#define MN(B,C,x)   (x < 1.0 ? ((2.-1.5*B-(C))*x + (-3.+2.*B+C))*x*x + (1.-(B)/3.) : (((-(B)/6.-(C))*x + (B+5.*C))*x + (-2.*B-8.*C))*x+((4./3.)*B+4.*C))
#define Kernel(x)   MN(0.334, 0.333, abs(x))
#define taps        2.0

#define Luma(rgb)   dot(rgb*rgb, vec3(0.2126, 0.7152, 0.0722))

vec4 hook() {
    float low  = ceil((LOWRES_pos - taps/input_size) * LOWRES_size - offset - 0.5)[axis];
    float high = floor((LOWRES_pos + taps/input_size) * LOWRES_size - offset - 0.5)[axis];

    float W = 0.0;
    vec4 avg = vec4(0);
    vec2 pos = LOWRES_pos;
    vec4 tex;

    for (float k = low; k <= high; k++) {
        pos[axis] = LOWRES_pt[axis] * (k - offset[axis] + 0.5);
        float rel = (pos[axis] - LOWRES_pos[axis])*input_size[axis];
        float w = Kernel(rel);

        tex.rgb = textureLod(LOWRES_raw, pos, 0.0).rgb * LOWRES_mul;
        tex.a = Luma(tex.rgb);
        avg += w * tex;
        W += w;
    }
    avg /= W;

    return vec4(avg.rgb, max(abs(avg.a - Luma(avg.rgb)), 5e-7) + LOWRES_texOff(0).a);
}

void main() { out_Target0 = hook(); }
