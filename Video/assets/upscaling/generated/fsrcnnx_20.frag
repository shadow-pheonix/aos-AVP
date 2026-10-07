#version 310 es
precision highp float;
precision highp int;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
uniform vec2 input_size;
uniform vec2 output_size;
const vec2 tex_offset = vec2(0.0);
uniform highp sampler2D MODEL1_raw;
uniform vec2 MODEL1_size;
#define MODEL1_pt (1.0 / MODEL1_size)
#define MODEL1_pos in_TEXCOORD0.xy
#define MODEL1_mul 1.0
#define MODEL1_tex(p) texture(MODEL1_raw, (p))
#define MODEL1_texOff(p) texture(MODEL1_raw, MODEL1_pos + vec2(p) * MODEL1_pt)
uniform highp sampler2D MODEL2_raw;
uniform vec2 MODEL2_size;
#define MODEL2_pt (1.0 / MODEL2_size)
#define MODEL2_pos in_TEXCOORD0.xy
#define MODEL2_mul 1.0
#define MODEL2_tex(p) texture(MODEL2_raw, (p))
#define MODEL2_texOff(p) texture(MODEL2_raw, MODEL2_pos + vec2(p) * MODEL2_pt)
uniform highp sampler2D MODEL3_raw;
uniform vec2 MODEL3_size;
#define MODEL3_pt (1.0 / MODEL3_size)
#define MODEL3_pos in_TEXCOORD0.xy
#define MODEL3_mul 1.0
#define MODEL3_tex(p) texture(MODEL3_raw, (p))
#define MODEL3_texOff(p) texture(MODEL3_raw, MODEL3_pos + vec2(p) * MODEL3_pt)
uniform highp sampler2D MODEL4_raw;
uniform vec2 MODEL4_size;
#define MODEL4_pt (1.0 / MODEL4_size)
#define MODEL4_pos in_TEXCOORD0.xy
#define MODEL4_mul 1.0
#define MODEL4_tex(p) texture(MODEL4_raw, (p))
#define MODEL4_texOff(p) texture(MODEL4_raw, MODEL4_pos + vec2(p) * MODEL4_pt)
uniform highp sampler2D FEATURE1_raw;
uniform vec2 FEATURE1_size;
#define FEATURE1_pt (1.0 / FEATURE1_size)
#define FEATURE1_pos in_TEXCOORD0.xy
#define FEATURE1_mul 1.0
#define FEATURE1_tex(p) texture(FEATURE1_raw, (p))
#define FEATURE1_texOff(p) texture(FEATURE1_raw, FEATURE1_pos + vec2(p) * FEATURE1_pt)
#undef MODEL1_texOff
#define MODEL1_texOff(p) texelFetch(MODEL1_raw, clamp(ivec2(gl_FragCoord.xy) + ivec2(p), ivec2(0), ivec2(MODEL1_size) - 1), 0)
#undef MODEL2_texOff
#define MODEL2_texOff(p) texelFetch(MODEL2_raw, clamp(ivec2(gl_FragCoord.xy) + ivec2(p), ivec2(0), ivec2(MODEL2_size) - 1), 0)
#undef MODEL3_texOff
#define MODEL3_texOff(p) texelFetch(MODEL3_raw, clamp(ivec2(gl_FragCoord.xy) + ivec2(p), ivec2(0), ivec2(MODEL3_size) - 1), 0)
#undef MODEL4_texOff
#define MODEL4_texOff(p) texelFetch(MODEL4_raw, clamp(ivec2(gl_FragCoord.xy) + ivec2(p), ivec2(0), ivec2(MODEL4_size) - 1), 0)
#undef FEATURE1_texOff
#define FEATURE1_texOff(p) texelFetch(FEATURE1_raw, clamp(ivec2(gl_FragCoord.xy) + ivec2(p), ivec2(0), ivec2(FEATURE1_size) - 1), 0)
vec4 hook()
{
vec4 res = vec4(-0.0218873005360365,-0.0149576151743531,-0.0256180185824633,-0.0858701169490814);
res += mat4(0.0221681538969278,-0.2356450855731964,0.0643408298492432,-1.1138032674789429,0.1064624637365341,-0.8914715647697449,-0.0294173005968332,-0.0022653567139059,-0.0252699330449104,-0.1230333074927330,-0.0858245044946671,0.0581396929919720,0.0181231293827295,0.1111536994576454,0.0935193970799446,-0.0742835476994514) * MODEL1_texOff(0);
res += mat4(0.0736283138394356,0.0147979715839028,0.0464740432798862,-0.2148900032043457,0.4483453333377838,0.2830028533935547,0.0562076941132545,0.3613960742950439,0.0668433532118797,-0.2692199349403381,0.0970326364040375,0.1248507574200630,0.0208180323243141,0.0532030761241913,-0.0098368674516678,0.0498967394232750) * MODEL2_texOff(0);
res += mat4(-0.0427469126880169,0.1918861865997314,-0.0468214377760887,-0.1154606193304062,-0.8018212318420410,0.3941400647163391,0.0562537945806980,0.2459305673837662,-0.0034576300531626,-0.4044605493545532,0.0631769001483917,0.2570861279964447,0.3880051076412201,0.2102442532777786,0.1035031601786613,-0.1845092624425888) * MODEL3_texOff(0);
res += mat4(-0.0736504569649696,-0.4329134225845337,-0.0331883579492569,0.4859155714511871,0.0300663169473410,-0.1431413143873215,0.0900689288973808,0.0027509836945683,-0.0178727619349957,-0.1037448570132256,-0.0429062619805336,0.0104557601734996,-0.0787484720349312,0.0334280952811241,-0.0210850816220045,-0.2790249288082123) * MODEL4_texOff(0);
res += FEATURE1_texOff(0);
res = max(res, vec4(0.0)) + vec4(-0.0009421827271581,0.0372365266084671,0.5457454919815063,-0.0045580286532640) * min(res, vec4(0.0));
return res;
}

void main() { out_Target0 = hook(); }
