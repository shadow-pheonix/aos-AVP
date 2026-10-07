#version 310 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
uniform highp samplerExternalOES video;
uniform mat4 transform;
void main() {
    vec3 rgb = texture(video, (transform * vec4(in_TEXCOORD0.xy,0,1)).xy).rgb;
    out_Target0 = vec4(rgb, 1.0);
}
