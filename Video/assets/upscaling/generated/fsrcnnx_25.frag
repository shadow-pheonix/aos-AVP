#version 310 es
precision highp float;
precision highp int;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
uniform vec2 input_size;
uniform vec2 output_size;
const vec2 tex_offset = vec2(0.0);
uniform highp sampler2D SUBCONV1_raw;
uniform vec2 SUBCONV1_size;
#define SUBCONV1_pt (1.0 / SUBCONV1_size)
#define SUBCONV1_pos in_TEXCOORD0.xy
#define SUBCONV1_mul 1.0
#define SUBCONV1_tex(p) texture(SUBCONV1_raw, (p))
#define SUBCONV1_texOff(p) texture(SUBCONV1_raw, SUBCONV1_pos + vec2(p) * SUBCONV1_pt)
vec4 hook()
{
vec2 fcoord = fract(SUBCONV1_pos * SUBCONV1_size);
vec2 base = SUBCONV1_pos + (vec2(0.5) - fcoord) * SUBCONV1_pt;
ivec2 index = ivec2(fcoord * vec2(2));
vec4 res = SUBCONV1_tex(base);
return vec4(res[index.x * 2 + index.y], 0, 0, 1);
}

void main() { out_Target0 = hook(); }
