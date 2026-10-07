#!/usr/bin/env python3
"""Reproducibly wrap pinned mpv hooks as GLES 3.1 passes; preserve algorithm bodies."""
from pathlib import Path
import hashlib
import json
import re
import struct

ROOT = Path(__file__).resolve().parent
OUT = ROOT.parent / 'assets/upscaling/generated'
HEADER = '''#version 310 es
precision highp float;
precision highp int;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
uniform vec2 input_size;
uniform vec2 output_size;
const vec2 tex_offset = vec2(0.0);
'''


def generate(filename, prefix):
    source = (ROOT / 'upstream' / filename).read_text()
    # Directives start a pass or an embedded LUT. All other directives belong to it.
    blocks = re.split(r'(?=^//!(?:HOOK|TEXTURE) )', source, flags=re.M)[1:]
    passes, textures = [], []
    for block in blocks:
        lines = block.splitlines()
        directives, body = {}, []
        for line in lines:
            if line.startswith('//!'):
                key, _, value = line[3:].partition(' ')
                directives.setdefault(key, []).append(value)
            else:
                body.append(line)
        body = '\n'.join(body).strip() + '\n'
        if 'TEXTURE' in directives:
            name = directives['TEXTURE'][0]
            width, height = map(int, directives['SIZE'][0].split())
            assert directives['FORMAT'] == ['rgba16f']
            # mpv's hook hex uses native little-endian float32, regardless of GPU format.
            binary = bytes.fromhex(body)
            assert len(binary) == width * height * 4 * 4
            vals = struct.unpack('<' + 'f' * (len(binary)//4), binary)
            binary = struct.pack('<' + 'e' * len(vals), *vals)
            (OUT / (name + '.bin')).write_bytes(binary)
            textures.append(dict(name=name, width=width, height=height, file=name+'.bin'))
            continue
        binds = directives.get('BIND', [])
        glsl = HEADER
        for name in binds:
            glsl += f'''uniform highp sampler2D {name}_raw;
uniform vec2 {name}_size;
#define {name}_pt (1.0 / {name}_size)
#define {name}_pos in_TEXCOORD0.xy
#define {name}_mul 1.0
#define {name}_tex(p) texture({name}_raw, (p))
#define {name}_texOff(p) texture({name}_raw, {name}_pos + vec2(p) * {name}_pt)
'''
        # The CNN feature passes read integer neighbours on the original pixel
        # grid. Avoid normalized-coordinate interpolation and implicit LOD work;
        # retain linear sampling in the final x2/subpixel reconstruction pass.
        same_grid = (prefix == 'fsrcnnx'
                     and directives.get('WIDTH', ['HOOKED.w']) == ['HOOKED.w']
                     and directives.get('HEIGHT', ['HOOKED.h']) == ['HOOKED.h'])
        if same_grid:
            for name in binds:
                glsl += f'''#undef {name}_texOff
#define {name}_texOff(p) texelFetch({name}_raw, clamp(ivec2(gl_FragCoord.xy) + ivec2(p), ivec2(0), ivec2({name}_size) - 1), 0)
'''
        for name in binds:
            if name in re.findall(r'^//!TEXTURE (.+)$', source, re.M):
                glsl += f'#define {name} {name}_raw\n'
        glsl += body + '\nvoid main() { out_Target0 = hook(); }\n'
        asset = f'{prefix}_{len(passes):02}.frag'
        (OUT / asset).write_text(glsl)
        passes.append(dict(file=asset, binds=binds, hook=directives['HOOK'][0],
                           float32=prefix == 'ssim' and bool(directives.get('SAVE')),
                           save=directives.get('SAVE', [''])[0],
                           width=directives.get('WIDTH', ['HOOKED.w'])[0],
                           height=directives.get('HEIGHT', ['HOOKED.h'])[0],
                           components=int(directives.get('COMPONENTS', ['1' if prefix != 'ssim' else '4'])[0]),
                           description=directives.get('DESC', [''])[0]))
    (OUT / (prefix + '.json')).write_text(json.dumps(dict(passes=passes, textures=textures), indent=2)+'\n')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    generate('ravu-zoom-ar-r3.hook', 'ravu')
    generate('FSRCNNX_x2_16-0-4-1.glsl', 'fsrcnnx')
    generate('SSimSuperRes.glsl', 'ssim')
    sgsr = (ROOT / 'upstream/sgsr1_shader_mobile.frag').read_text()
    # RGBY is Qualcomm's supported mode for textures carrying luma in alpha.
    sgsr = sgsr.replace('#version 300 es', '#version 310 es').replace('#define OperationMode 1', '#define OperationMode 3')
    # Precision qualifiers on constructor expressions are not legal GLSL ES.
    sgsr = sgsr.replace('highp vec2(', 'vec2(').replace('int mode = OperationMode;', 'const int mode = OperationMode;')
    (OUT / 'sgsr1.frag').write_text(sgsr)
    files = sorted((ROOT / 'upstream').iterdir())
    (OUT.parent / 'SOURCE-SHA256.txt').write_text(''.join(f'{hashlib.sha256(f.read_bytes()).hexdigest()}  {f.name}\n' for f in files))


if __name__ == '__main__':
    main()
