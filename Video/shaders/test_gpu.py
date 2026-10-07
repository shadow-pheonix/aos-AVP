#!/usr/bin/env python3
"""Compile and execute bundled GLES shaders on Mesa EGL (no Android device required).

Checks GPU pass plumbing, x2/fractional geometry, finite/bounded output and flat-field
stability. It cannot validate MediaCodec external YUV, HDR, lifecycle or Adreno timing.
"""
import ctypes as C
from pathlib import Path
import json
import re
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'assets/upscaling'
E = C.CDLL('libEGL.so.1')
E.eglGetProcAddress.argtypes = [C.c_char_p]
E.eglGetProcAddress.restype = C.c_void_p


def proc(name, result, *args):
    pointer = E.eglGetProcAddress(name.encode())
    if not pointer:
        raise RuntimeError('Missing GL entry point ' + name)
    return C.CFUNCTYPE(result, *args)(pointer)


def context():
    get_display = proc('eglGetPlatformDisplayEXT', C.c_void_p, C.c_uint, C.c_void_p, C.c_void_p)
    display = get_display(0x31DD, None, None)  # EGL_PLATFORM_SURFACELESS_MESA
    E.eglInitialize.argtypes = [C.c_void_p, C.c_void_p, C.c_void_p]
    assert E.eglInitialize(display, None, None)
    E.eglBindAPI(0x30A0)  # EGL_OPENGL_ES_API
    E.eglChooseConfig.argtypes = [C.c_void_p, C.c_void_p, C.c_void_p, C.c_int, C.c_void_p]
    config, count = C.c_void_p(), C.c_int()
    attrib = (C.c_int * 13)(0x3040, 0x40, 0x3033, 1, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 0, 0x3038)
    assert E.eglChooseConfig(display, attrib, C.byref(config), 1, C.byref(count)) and count.value
    E.eglCreateContext.argtypes = [C.c_void_p, C.c_void_p, C.c_void_p, C.c_void_p]
    E.eglCreateContext.restype = C.c_void_p
    ctx = E.eglCreateContext(display, config, None, (C.c_int * 3)(0x3098, 3, 0x3038))
    E.eglMakeCurrent.argtypes = [C.c_void_p, C.c_void_p, C.c_void_p, C.c_void_p]
    assert ctx and E.eglMakeCurrent(display, None, None, ctx)
    return display, ctx


GLuint, GLint = C.c_uint, C.c_int
create_shader = proc('glCreateShader', GLuint, GLuint)
shader_source = proc('glShaderSource', None, GLuint, GLint, C.POINTER(C.c_char_p), C.c_void_p)
compile_shader = proc('glCompileShader', None, GLuint)
shader_status = proc('glGetShaderiv', None, GLuint, GLuint, C.POINTER(GLint))
shader_log = proc('glGetShaderInfoLog', None, GLuint, GLint, C.c_void_p, C.c_void_p)
create_program = proc('glCreateProgram', GLuint)
attach = proc('glAttachShader', None, GLuint, GLuint)
link = proc('glLinkProgram', None, GLuint)
program_status = proc('glGetProgramiv', None, GLuint, GLuint, C.POINTER(GLint))
program_log = proc('glGetProgramInfoLog', None, GLuint, GLint, C.c_void_p, C.c_void_p)
use = proc('glUseProgram', None, GLuint)
location = proc('glGetUniformLocation', GLint, GLuint, C.c_char_p)
uniform1 = proc('glUniform1i', None, GLint, GLint)
uniform2 = proc('glUniform2f', None, GLint, C.c_float, C.c_float)
uniform4 = proc('glUniform4f', None, GLint, C.c_float, C.c_float, C.c_float, C.c_float)
uniform_matrix = proc('glUniformMatrix4fv',None,GLint,GLint,C.c_ubyte,C.POINTER(C.c_float))
gen_tex = proc('glGenTextures', None, GLint, C.POINTER(GLuint))
bind_tex = proc('glBindTexture', None, GLuint, GLuint)
active_tex = proc('glActiveTexture', None, GLuint)
tex_parameter = proc('glTexParameteri', None, GLuint, GLuint, GLint)
tex_image = proc('glTexImage2D', None, GLuint, GLint, GLint, GLint, GLint, GLint, GLuint, GLuint, C.c_void_p)
gen_fbo = proc('glGenFramebuffers', None, GLint, C.POINTER(GLuint))
bind_fbo = proc('glBindFramebuffer', None, GLuint, GLuint)
attach_tex = proc('glFramebufferTexture2D', None, GLuint, GLuint, GLuint, GLuint, GLint)
fbo_status = proc('glCheckFramebufferStatus', GLuint, GLuint)
viewport = proc('glViewport', None, GLint, GLint, GLint, GLint)
draw = proc('glDrawArrays', None, GLuint, GLint, GLint)
read_pixels = proc('glReadPixels', None, GLint, GLint, GLint, GLint, GLuint, GLuint, C.c_void_p)
invalidate_fbo = proc('glInvalidateFramebuffer', None, GLuint, GLint, C.POINTER(GLuint))
get_error = proc('glGetError', GLuint)
get_string = proc('glGetString', C.c_char_p, GLuint)
gen_vao = proc('glGenVertexArrays', None, GLint, C.POINTER(GLuint))
bind_vao = proc('glBindVertexArray', None, GLuint)

VERTEX = '''#version 310 es
precision highp float;
layout(location=0) out highp vec4 in_TEXCOORD0;
void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));
in_TEXCOORD0=vec4(p,0,1);gl_Position=vec4(p*2.0-1.0,0,1);}
'''
CACHE = {}


def shader(kind, source):
    obj = create_shader(kind)
    encoded = C.c_char_p(source.encode())
    shader_source(obj, 1, C.byref(encoded), None)
    compile_shader(obj)
    result = GLint()
    shader_status(obj, 0x8B81, C.byref(result))
    if not result.value:
        buffer = C.create_string_buffer(16384)
        shader_log(obj, len(buffer), None, buffer)
        raise AssertionError(buffer.value.decode())
    return obj


def program(filename, override=None):
    if filename not in CACHE:
        vertex = shader(0x8B31, VERTEX)
        pixel = shader(0x8B30, override if override is not None else (ASSETS / filename).read_text())
        obj = create_program()
        attach(obj, vertex); attach(obj, pixel); link(obj)
        result = GLint(); program_status(obj, 0x8B82, C.byref(result))
        if not result.value:
            buffer = C.create_string_buffer(16384)
            program_log(obj, len(buffer), None, buffer)
            raise AssertionError(filename + ': ' + buffer.value.decode())
        CACHE[filename] = obj
    return CACHE[filename]


class Target:
    def __init__(self, w, h, components=4, pixels=None, float32=False):
        self.w, self.h = w, h
        tex = GLuint(); gen_tex(1, C.byref(tex)); self.texture = tex.value
        bind_tex(0x0DE1, self.texture)
        for pname, value in [(0x2801, 0x2601), (0x2800, 0x2601), (0x2802, 0x812F), (0x2803, 0x812F)]:
            tex_parameter(0x0DE1, pname, value)
        internal = ({1:0x822E,2:0x8230,4:0x8814} if float32 else {1:0x822D,2:0x822F,4:0x881A})[components]
        fmt = {1: 0x1903, 2: 0x8227, 4: 0x1908}[components]
        tex_image(0x0DE1, 0, internal, w, h, 0, fmt, 0x140B if pixels is not None and pixels.dtype == np.float16 else 0x1406,
                  None if pixels is None else pixels.ctypes.data)
        fbo = GLuint(); gen_fbo(1, C.byref(fbo)); self.fbo = fbo.value
        self.bind(); attach_tex(0x8D40, 0x8CE0, 0x0DE1, self.texture, 0)
        assert fbo_status(0x8D40) == 0x8CD5
        assert get_error() == 0

    def bind(self):
        bind_fbo(0x8D40, self.fbo); viewport(0, 0, self.w, self.h)

    def read(self):
        self.bind()
        out = np.empty((self.h, self.w, 4), dtype=np.float32)
        read_pixels(0, 0, self.w, self.h, 0x1908, 0x1406, out.ctypes.data)
        assert get_error() == 0
        return out


def run(filename, target, inputs, vec2s=None, vec4s=None, ints=None):
    target.bind()
    assert all(t.texture != target.texture for t in inputs.values()), 'Output aliases an input'
    invalidate_fbo(0x8D40, 1, (GLuint * 1)(0x8CE0))
    obj = program(filename); use(obj)
    for unit, (key, texture) in enumerate(inputs.items()):
        active_tex(0x84C0 + unit); bind_tex(0x0DE1, texture.texture)
        uniform1(location(obj, key.encode()), unit)
    for key, values in (vec2s or {}).items(): uniform2(location(obj, key.encode()), *values)
    for key, values in (vec4s or {}).items(): uniform4(location(obj, key.encode()), *values)
    for key, value in (ints or {}).items(): uniform1(location(obj,key.encode()),value)
    draw(4, 0, 3)
    assert get_error() == 0, filename
    return target


def graph(name, source, native, out_w, out_h):
    env = dict(LUMA=source, POSTKERNEL=source, PREKERNEL=native)
    manifest = json.loads((ASSETS / f'generated/{name}.json').read_text())
    for lut in manifest['textures']:
        pixels = np.fromfile(ASSETS / ('generated/' + lut['file']), dtype='<f2')
        env[lut['name']] = Target(lut['width'], lut['height'], pixels=pixels)
    for stage in manifest['passes']:
        env['HOOKED'] = env[stage['hook']]
        def dimension(expression):
            stack = []
            for token in expression.split():
                if token in ['+', '-', '*', '/']:
                    b, a = stack.pop(), stack.pop(); stack.append({'+': lambda:a+b, '-': lambda:a-b, '*': lambda:a*b, '/': lambda:a/b}[token]())
                elif '.' in token and token.split('.')[0] in [*env, 'OUTPUT', 'NATIVE_CROPPED']:
                    key, axis = token.split('.')
                    dimensions = (out_w, out_h) if key == 'OUTPUT' else (native.w, native.h) if key == 'NATIVE_CROPPED' else (env[key].w, env[key].h)
                    stack.append(dimensions[axis == 'h'])
                else: stack.append(float(token))
            assert len(stack) == 1
            return round(stack[0])
        target = Target(dimension(stage['width']), dimension(stage['height']), stage['components'],float32=stage.get('float32',False))
        inputs = {key + '_raw': env[key] for key in stage['binds']}
        dims = {key + '_size': (env[key].w, env[key].h) for key in stage['binds']}
        dims.update(input_size=(native.w, native.h), output_size=(out_w, out_h))
        run('generated/' + stage['file'], target, inputs, dims)
        env[stage['save'] or stage['hook']] = target
    return env[manifest['passes'][-1]['hook']]


def render(mode, pixels, out_w, out_h):
    h, w = pixels.shape[:2]
    rgb = Target(w, h, pixels=np.ascontiguousarray(pixels))
    if mode == 'off': return run('present.frag', Target(out_w,out_h), dict(source=rgb)).read()
    if mode == 'sgsr1': return run('generated/sgsr1.frag', Target(out_w,out_h), dict(ps0=rgb), vec4s={'ViewportInfo[0]': (1/w,1/h,w,h)}).read()
    luma = run('luma.frag',Target(w,h,1),dict(source=rgb))
    rw, rh = (out_w,out_h) if mode == 'ravu' else (w*2,h*2)
    reconstructed = graph(mode,luma,rgb,rw,rh)
    assert (reconstructed.w,reconstructed.h)==(rw,rh)
    combined = run('combine.frag',Target(rw,rh),dict(source=rgb,luma=reconstructed),dict(source_size=(w,h)),ints=dict(guard_luma=mode=='fsrcnnx'))
    if mode == 'ravu': result = graph('ssim',combined,rgb,out_w,out_h)
    else:
        mid = run('lanczos.frag',Target(out_w,rh),dict(source=combined),dict(source_size=(rw,rh),output_size=(out_w,rh),axis=(1,0)))
        result = run('lanczos.frag',Target(out_w,out_h),dict(source=mid),dict(source_size=(out_w,rh),output_size=(out_w,out_h),axis=(0,1)))
    return run('present.frag',Target(out_w,out_h),dict(source=result)).read()


def check_cnn_sampling_equivalence():
    # Compare the full trained x2 graph and downscale against the prior
    # normalized/linear implementation, including odd dimensions and borders.
    original_program = program
    def normalized_reference(filename, override=None):
        if filename.startswith('generated/fsrcnnx_'):
            source = (ASSETS / filename).read_text()
            source = re.sub(r'#undef \w+_texOff\n#define [^\n]+\n', '', source)
            return original_program(filename + '-normalized-reference', source)
        return original_program(filename, override)
    rng = np.random.default_rng(195)
    for w, h in [(96, 54), (97, 55), (193, 81)]:
        y, x = np.mgrid[:h, :w]
        gray = np.clip(.5 + .15*np.sin(x*.9 + y*.7) + .1*rng.standard_normal((h, w)), 0, 1).astype(np.float32)
        pixels = np.stack([gray]*4, -1)
        optimized = render('fsrcnnx', pixels, round(w*4/3), round(h*4/3))
        globals()['program'] = normalized_reference
        try:
            reference = render('fsrcnnx', pixels, round(w*4/3), round(h*4/3))
        finally:
            globals()['program'] = original_program
        error = np.abs(optimized-reference)
        assert error.max() < .003 and error.mean() < .0002, (w, h, error.max(), error.mean())
        print(f'FSRCNNX integer sampling {w}×{h}: max reference error={error.max():.6f}, mean={error.mean():.6f}', flush=True)


def main():
    context(); vao=GLuint();gen_vao(1,C.byref(vao));bind_vao(vao.value)
    print(get_string(0x1F02).decode(),get_string(0x1F01).decode(),flush=True)
    extensions = get_string(0x1F03).decode()
    for asset in sorted(ASSETS.rglob('*.frag')):
        if asset.name == 'import_yuv.frag' and 'GL_EXT_YUV_target' not in extensions:
            print('SKIP external YUV shader: Mesa lacks GL_EXT_YUV_target (requires Android device)');continue
        program(str(asset.relative_to(ASSETS)))
    print(f'Compiled and linked {len(CACHE)} shader programs',flush=True)
    y,x=np.mgrid[0:54,0:96]
    v=(0.25+0.3*x/95+0.1*np.sin(x*0.9+y*0.7)).astype(np.float32)
    pixels=np.stack([v,v,v,v],axis=-1)
    flat=np.full_like(pixels,0.5)
    off=render('off',pixels,128,72)
    for mode in ['ravu','fsrcnnx','sgsr1']:
        output=render(mode,pixels,128,72)
        constant=render(mode,flat,128,72)
        assert output.shape==(72,128,4) and np.isfinite(output).all(),mode
        assert output[...,:3].min()>=0 and output[...,:3].max()<=1,mode
        error=float(abs(constant[...,:3]-0.5).max())
        assert error<0.01,(mode,'flat-field error',error)
        difference=float(abs(output[...,:3]-off[...,:3]).mean())
        assert difference>1e-5,(mode,'identical to bilinear')
        print(f'{mode}: fractional output 128×72, finite/bounded; flat max error={error:.6f}; bilinear difference={difference:.6f}',flush=True)
    step=np.where(x>48,0.75,0.25).astype(np.float32)
    edges=np.stack([step,step,step,step],axis=-1)
    for mode in ['ravu','fsrcnnx','sgsr1']:
        edge=render(mode,edges,128,72)[...,:3]
        print(f'{mode}: mid-gray edge extrema {edge.min():.6f}..{edge.max():.6f}',flush=True)
        assert edge.min()>0.20 and edge.max()<0.80,(mode,'excessive edge ringing')
    check_cnn_sampling_equivalence()
    # Validate the YUV shader math using a 2D texture surrogate. Android's external
    # sampler extension itself still needs a real MediaCodec/device test.
    yuv_shader=(ASSETS/'import_yuv.frag').read_text().replace('#extension GL_EXT_YUV_target : require','').replace('__samplerExternal2DY2YEXT','sampler2D')
    obj=program('yuv-surrogate',yuv_shader)
    identity=np.identity(4,dtype=np.float32)
    for depth in [8,10]:
        maximum=2**depth-1; scale=2**(depth-8)
        for level in [16,128,235]:
            plane=np.empty((54,96,4),dtype=np.float32)
            plane[:]=[level*scale/maximum,128*scale/maximum,128*scale/maximum,1]
            source=Target(96,54,pixels=plane);target=Target(96,54)
            use(obj);uniform_matrix(location(obj,b'transform'),1,0,identity.ctypes.data_as(C.POINTER(C.c_float)))
            result=run('yuv-surrogate',target,dict(video=source),dict(source_size=(96,54),chroma_scale=(2,2),chroma_center=(0.5,1),kr_kb=(0.2126,0.0722),code_range=(maximum,scale)),ints=dict(full_range=0)).read()
            expected=(level-16)/219
            assert abs(result[...,:3]-expected).max()<0.004,(depth,level,'YUV range/matrix bias')
        print(f'{depth}-bit limited-range YUV shader math passed (2D surrogate)',flush=True)
    print('GPU shader validation passed')


if __name__ == '__main__': main()
