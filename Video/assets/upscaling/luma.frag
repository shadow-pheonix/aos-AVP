#version 310 es
precision highp float;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
uniform highp sampler2D source;
void main(){ out_Target0=vec4(texture(source,in_TEXCOORD0.xy).a,0,0,1); }
