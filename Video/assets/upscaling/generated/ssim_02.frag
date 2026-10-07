#version 310 es
precision highp float;
precision highp int;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
uniform vec2 input_size;
uniform vec2 output_size;
const vec2 tex_offset = vec2(0.0);
uniform highp sampler2D PREKERNEL_raw;
uniform vec2 PREKERNEL_size;
#define PREKERNEL_pt (1.0 / PREKERNEL_size)
#define PREKERNEL_pos in_TEXCOORD0.xy
#define PREKERNEL_mul 1.0
#define PREKERNEL_tex(p) texture(PREKERNEL_raw, (p))
#define PREKERNEL_texOff(p) texture(PREKERNEL_raw, PREKERNEL_pos + vec2(p) * PREKERNEL_pt)
uniform highp sampler2D LOWRES_raw;
uniform vec2 LOWRES_size;
#define LOWRES_pt (1.0 / LOWRES_size)
#define LOWRES_pos in_TEXCOORD0.xy
#define LOWRES_mul 1.0
#define LOWRES_tex(p) texture(LOWRES_raw, (p))
#define LOWRES_texOff(p) texture(LOWRES_raw, LOWRES_pos + vec2(p) * LOWRES_pt)
#define spread      1.0 / 4.0

#define GetL(x,y)   PREKERNEL_tex(PREKERNEL_pt * (PREKERNEL_pos * input_size + tex_offset + vec2(x,y))).rgb
#define GetH(x,y)   LOWRES_texOff(vec2(x,y)).rgb

#define Luma(rgb)   dot(rgb*rgb, vec3(0.2126, 0.7152, 0.0722))
#define diff(x,y)   vec2(Luma((GetL(x,y) - meanL)), Luma((GetH(x,y) - meanH)))

vec4 hook() {
    vec3 meanL = GetL(0,0);
    vec3 meanH = GetH(0,0);
    for (int X=-1; X<=1; X+=2) {
        meanL += GetL(X,0) * spread;
        meanH += GetH(X,0) * spread;
    }
    for (int Y=-1; Y<=1; Y+=2) {
        meanL += GetL(0,Y) * spread;
        meanH += GetH(0,Y) * spread;
    }
    meanL /= (1.0 + 4.0*spread);
    meanH /= (1.0 + 4.0*spread);

    vec2 var = diff(0,0);
    for (int X=-1; X<=1; X+=2)
        var += diff(X,0) * spread;

    for (int Y=-1; Y<=1; Y+=2)
        var += diff(0,Y) * spread;

    return vec4(max(var / (1.0 + 4.0*spread), vec2(1e-6)), 0, 0);
}

void main() { out_Target0 = hook(); }
