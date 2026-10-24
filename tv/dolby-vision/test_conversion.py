#!/usr/bin/env python3
"""Exercise the actual C conversion with libdovi's FEL/MEL reference RPUs."""
import ctypes as C
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(sys.argv[1]).resolve()
assets = root / 'native/dovi-compat/source/assets/tests'
include = root / 'native/avos/Include'
source = root / 'native/avos/Source/dovi_compat.c'
with tempfile.TemporaryDirectory() as work:
    library = Path(work) / 'convert.so'
    subprocess.run(['cc', '-shared', '-fPIC', '-std=c11', '-Wall', '-Wextra', '-Werror',
                    '-I', str(include), str(source),
                    str(root / 'native/dovi-compat/lib/host/libdolby_vision.a'),
                    '-lpthread', '-ldl', '-lm', '-o', str(library)], check=True)
    lib = C.CDLL(str(library))
    free = C.CDLL(None).free
    free.argtypes = [C.c_void_p]
    lib.nova_dovi_convert.argtypes = [C.c_void_p, C.c_size_t, C.c_uint,
                                     C.POINTER(C.c_void_p), C.POINTER(C.c_size_t)]
    lib.nova_dovi_convert.restype = C.c_int

    def convert(data, length_size, valid=True):
        out, size = C.c_void_p(), C.c_size_t()
        ret = lib.nova_dovi_convert(data, len(data), length_size, C.byref(out), C.byref(size))
        if not valid:
            assert ret < 0 and not out.value and size.value == 0, (ret, size.value)
            return
        assert ret == 0, ret
        result = C.string_at(out, size.value)
        free(out)
        return result

    def fixture(name):
        data = (assets / name).read_bytes()
        assert data[:4] == b'\x00\x00\x00\x01'
        return b'\x7c\x01' + data[4:]

    base = b'\x26\x01\xaa\xbb'  # base-layer slice, must remain byte exact
    el = b'\x7e\x01\x11\x22'    # encapsulated enhancement layer
    layer_one = b'\x26\x09\x11\x22'
    for original, expected in [('fel_orig.bin', 'fel_to_81.bin'),
                                ('mel_orig.bin', 'mel_to_81.bin')]:
        rpu, p81 = fixture(original), fixture(expected)
        for n in (1, 2, 3, 4):
            # FEL cannot be represented by a one-byte length prefix.
            if max(len(rpu), len(p81)) >= 1 << (8 * n):
                continue
            frame = lambda nal: len(nal).to_bytes(n, 'big') + nal
            data = frame(base) + frame(rpu) + frame(el) + frame(layer_one)
            assert convert(data, n) == frame(base) + frame(p81), (original, n)
        for code in (b'\x00\x00\x01', b'\x00\x00\x00\x01'):
            data = code + base + code + rpu + code + el + code + layer_one
            assert convert(data, 0) == code + base + code + p81, original
    for n in (1, 2, 3, 4):
        data = len(base).to_bytes(n, 'big') + base
        assert convert(data, n) == data
        convert(b'\x00' * n, n, valid=False)
        convert((20).to_bytes(n, 'big') + base, n, valid=False)
    assert convert(b'\x00\x00\x01' + el, 0) == b''
    for data, n in [(b'', 0), (b'bad', 0), (b'\x00\x00\x01', 0),
                    (b'\x00\x00\x01\x7c\x01\xff', 0),
                    (b'\x00\x00\x00\x02\x26\x00', 4), (b'abcd', 5)]:
        convert(data, n, valid=False)
print('PASS: real FEL/MEL -> Profile 8.1 golden fixtures, all length prefixes, Annex B,')
print('      base-layer preservation, EL removal, and malformed-packet rejection.')
