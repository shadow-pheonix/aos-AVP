#!/usr/bin/env python3
"""Apply this branch's feature patches to the pinned official components."""
import json
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[2]
feature = root / 'tv/dolby-vision'
lock = json.loads((feature / 'sources.json').read_text())
for path, sha in lock['components'].items():
    actual = subprocess.check_output(['git', '-C', str(root / path), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != sha:
        raise SystemExit(f'{path}: expected official commit {sha}, found {actual}')
for patch, directory in [('Video.patch', 'Video'), ('avos.patch', 'native/avos')]:
    subprocess.run(['git', '-C', str(root / directory), 'apply', '--check', str(feature / 'patches' / patch)], check=True)
    subprocess.run(['git', '-C', str(root / directory), 'apply', str(feature / 'patches' / patch)], check=True)
for source in (feature / 'overlay').rglob('*'):
    if source.is_file():
        destination = root / source.relative_to(feature / 'overlay')
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)

# The official AVP snapshot omits libyuv's make target although its pinned
# MediaLib/avos snapshots require it. Restore the target for this build.
core = root / 'core.mk'
text = core.read_text()
text = text.replace('NATIVE_PKG_LIST := \\\n', 'NATIVE_PKG_LIST := native/libyuv \\\n', 1)
text += '\n.PHONY: native_libyuv\nnative_libyuv: native_build_native/libyuv\n'
core.write_text(text)
android_mk = root / 'native/avos/jni/Android.mk'
text = android_mk.read_text().replace('AUDIOCOMPRESS_DIR :=', 'LIBYUV_DIR := $(abspath $(AVOS_DIR)/../libyuv)\n\nAUDIOCOMPRESS_DIR :=', 1)
android_mk.write_text(text)
print('Applied Dolby Vision patches to verified official NOVA components.')
