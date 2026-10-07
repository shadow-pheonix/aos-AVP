#version 310 es
#extension GL_EXT_YUV_target : require
precision highp float;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
uniform highp __samplerExternal2DY2YEXT video;
uniform mat4 transform;
uniform vec2 source_size;
uniform vec2 chroma_scale;
uniform vec2 chroma_center;
uniform vec2 kr_kb;
uniform int full_range;
uniform vec2 code_range; // maximum integer code, shift from 8-bit reference codes
vec3 sampleYuv(vec2 p) { return texture(video, (transform*vec4(p,0,1)).xy).rgb; }
float cubic(float x) {
    x=abs(x);
    if(x<1.0) return (1.5*x-2.5)*x*x+1.0;
    if(x<2.0) return ((-0.5*x+2.5)*x-4.0)*x+2.0;
    return 0.0;
}
void main() {
    vec2 p=in_TEXCOORD0.xy;
    vec2 position=(p*source_size-chroma_center)/chroma_scale;
    vec2 base=floor(position), phase=position-base;
    vec2 chroma=vec2(0), lo=vec2(1), hi=vec2(0);
    float sum=0.0;
    for(int y=-1;y<=2;y++) for(int x=-1;x<=2;x++) {
        vec2 index=base+vec2(x,y);
        // Clamp to chroma sample centers, before applying the producer crop/rotation transform.
        vec2 cp=clamp((index*chroma_scale+chroma_center)/source_size,
                      chroma_center/source_size, (source_size-chroma_scale+chroma_center)/source_size);
        vec2 c=sampleYuv(cp).gb;
        float w=cubic(float(x)-phase.x)*cubic(float(y)-phase.y);
        chroma+=w*c; sum+=w;
        if(x>=0 && x<=1 && y>=0 && y<=1) { lo=min(lo,c); hi=max(hi,c); }
    }
    chroma=clamp(chroma/sum,lo,hi); // Catmull-Rom reconstruction with local anti-ringing
    float luma=sampleYuv(p).r;
    float max_code=code_range.x, step=code_range.y;
    if(full_range==0) {
        luma=(luma-16.0*step/max_code)*(max_code/(219.0*step));
        chroma=(chroma-128.0*step/max_code)*(max_code/(224.0*step));
    } else chroma-=vec2(128.0*step/max_code);
    float kr=kr_kb.x, kb=kr_kb.y, kg=1.0-kr-kb;
    float r=luma+2.0*(1.0-kr)*chroma.y;
    float b=luma+2.0*(1.0-kb)*chroma.x;
    float g=(luma-kr*r-kb*b)/kg;
    vec3 rgb=clamp(vec3(r,g,b),0.0,1.0);
    out_Target0=vec4(rgb,dot(rgb,vec3(0.2126,0.7152,0.0722)));
}
