#version 310 es
precision highp float;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
uniform highp sampler2D source;
uniform vec2 source_size;
uniform vec2 output_size;
uniform vec2 axis;
float sinc(float x){ if(abs(x)<0.00001) return 1.0; x*=3.141592653589793; return sin(x)/x; }
void main(){
    vec2 pos=in_TEXCOORD0.xy*source_size-0.5;
    float center=dot(pos,axis), ratio=max(1.0,dot(source_size/output_size,axis));
    float radius=3.0*ratio;
    vec4 total=vec4(0),lo=vec4(1),hi=vec4(0); float weights=0.0;
    for(float i=ceil(center-radius);i<=floor(center+radius);i+=1.0){
        float d=(i-center)/ratio;
        float w=sinc(d)*sinc(d/3.0);
        vec4 c=texture(source,(pos+axis*(i-center)+0.5)/source_size);
        total+=w*c; weights+=w;
        if(abs(i-center)<=1.0){lo=min(lo,c);hi=max(hi,c);}
    }
    out_Target0=clamp(total/weights,lo,hi);
}
