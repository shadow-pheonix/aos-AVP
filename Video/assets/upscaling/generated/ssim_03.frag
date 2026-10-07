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
uniform highp sampler2D var_raw;
uniform vec2 var_size;
#define var_pt (1.0 / var_size)
#define var_pos in_TEXCOORD0.xy
#define var_mul 1.0
#define var_tex(p) texture(var_raw, (p))
#define var_texOff(p) texture(var_raw, var_pos + vec2(p) * var_pt)
#define oversharp   0.5

// -- Window Size --
#define taps        3.0
#define even        (taps - 2.0 * floor(taps / 2.0) == 0.0)
#define minX        int(1.0-ceil(taps/2.0))
#define maxX        int(floor(taps/2.0))

#define Kernel(x)   cos(acos(-1.0)*(x)/taps) // Hann kernel

// -- Input processing --
#define var(x,y)    var_tex(var_pt * (pos + vec2(x,y) + 0.5)).rg
#define GetL(x,y)   PREKERNEL_tex(PREKERNEL_pt * (pos + tex_offset + vec2(x,y) + 0.5)).rgb
#define GetH(x,y)   LOWRES_tex(LOWRES_pt * (pos + vec2(x,y) + 0.5))

#define Luma(rgb)   dot(rgb*rgb, vec3(0.2126, 0.7152, 0.0722))

vec4 hook() {
    vec4 c0 = HOOKED_texOff(0);

    vec2 pos = HOOKED_pos * LOWRES_size - vec2(0.5);
    vec2 offset = pos - (even ? floor(pos) : round(pos));
    pos -= offset;

    vec2 mVar = vec2(0.0);
    for (int X=-1; X<=1; X++)
    for (int Y=-1; Y<=1; Y++) {
        vec2 w = clamp(1.5 - abs(vec2(X,Y)), 0.0, 1.0);
        mVar += w.r * w.g * vec2(GetH(X,Y).a, 1.0);
    }
    mVar.r /= mVar.g;

    // Calculate faithfulness force
    float weightSum = 0.0;
    vec3 diff = vec3(0);

    for (int X = minX; X <= maxX; X++)
    for (int Y = minX; Y <= maxX; Y++)
    {
        float R = (-1.0 - oversharp) * sqrt(var(X,Y).r / (var(X,Y).g + mVar.r));

        vec2 krnl = Kernel(vec2(X,Y) - offset);
        float weight = krnl.r * krnl.g / (Luma((c0.rgb - GetH(X,Y).rgb)) + GetH(X,Y).a);

        diff += weight * (GetL(X,Y) + GetH(X,Y).rgb * R + (-1.0 - R) * (c0.rgb));
        weightSum += weight;
    }
    diff /= weightSum;

    c0.rgb = ((c0.rgb) + diff);

    return c0;
}

void main() { out_Target0 = hook(); }
