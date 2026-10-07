#version 310 es
precision highp float;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
uniform highp sampler2D source;
uniform highp sampler2D luma;
uniform vec2 source_size;
uniform int guard_luma;
float cubic(float x) {
    x=abs(x);
    if(x<1.0) return (1.5*x-2.5)*x*x+1.0;
    if(x<2.0) return ((-0.5*x+2.5)*x-4.0)*x+2.0;
    return 0.0;
}
void main(){
    vec2 p=in_TEXCOORD0.xy*source_size-0.5, base=floor(p), phase=fract(p);
    vec3 chroma=vec3(0),lo=vec3(1),hi=vec3(-1);
    float weights=0.0, min_luma=1.0, max_luma=0.0;
    for(int y=-1;y<=2;y++) for(int x=-1;x<=2;x++) {
        vec4 c=texture(source,(base+vec2(x,y)+0.5)/source_size);
        vec3 difference=c.rgb-vec3(c.a);
        min_luma=min(min_luma,c.a); max_luma=max(max_luma,c.a);
        float w=cubic(float(x)-phase.x)*cubic(float(y)-phase.y);
        chroma+=w*difference; weights+=w;
        if(x>=0 && x<=1 && y>=0 && y<=1){lo=min(lo,difference);hi=max(hi,difference);}
    }
    float Y=texture(luma,in_TEXCOORD0.xy).r;
    // The trained FSRCNNX residual can overshoot isolated edges. A source envelope
    // permits reconstructed texture inside its local range and limits new edge halos.
    if(guard_luma!=0) {
        float allowance=0.02*(max_luma-min_luma);
        Y=clamp(Y,min_luma-allowance,max_luma+allowance);
    }
    out_Target0=vec4(clamp(vec3(Y)+clamp(chroma/weights,lo,hi),0.0,1.0),Y);
}
