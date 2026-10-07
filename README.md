# Nova Upscaling timing and FSRCNNX test update

[Download Nova-Upscaling-sync3-debug.apk](https://github.com/shadow-pheonix/aos-AVP/raw/refs/heads/upscaling-apk-2026-10-07/Nova-Upscaling-sync3-debug.apk)

Version 6.5.4-upscaling-sync3 (6050006). Install over the previous test APK; the signing key is unchanged.

Off now uses the original Android video renderer. GPU modes forward decoder presentation timestamps, and FSRCNNX uses integer sampling for its source-grid convolutions without reducing its model. Additional decoder/frame gap logs are included in the existing Export diagnostics option.

56 selected tests, lint, GPU shader/graph checks and APK verification passed. A/V sync and FSRCNNX speed still require Tab S9 testing. Test Off first, then the GPU modes, and export after reproducing a freeze/desync.

[Matching source](https://github.com/shadow-pheonix/aos-AVP/tree/a5208c8d6974ec00eb9735c64a4f4d26ff08caa9) · [Build details and checksum](Nova-Upscaling-sync3-build.txt)

---

# Nova Upscaling diagnostics update

[Download Nova-Upscaling-diag2-debug.apk](https://github.com/shadow-pheonix/aos-AVP/raw/refs/heads/upscaling-apk-2026-10-07/Nova-Upscaling-diag2-debug.apk)

Version 6.5.4-upscaling-diag2 (6050005), universal debug build, signed with the same key as the previous test APK. It adds persistent playback/native logs and renderer timing, prepares shaders before audio starts, and limits in-flight GPU frames. Tab S9 A/V sync needs device validation.

Export after reproducing the issue: the existing Export diagnostics option. Upload the ZIP and describe whether audio leads/trails video and whether drift grows.

[Matching source](https://github.com/shadow-pheonix/aos-AVP/tree/ce115f45cdb7dc1c197689ca048884749fde2d3d) · [Build details and checksum](Nova-Upscaling-diag2-build.txt)

---

# Nova Upscaling test APK

Download `Nova-Upscaling-6.5.4-debug.apk` from this branch. This is the verified,
debug-signed universal build for ARM64 (including Galaxy Tab S9), ARM32, x86 and
x86_64. Android 6.0 or later is required.

Matching source and shader licenses:
https://github.com/shadow-pheonix/aos-AVP/tree/174ff71e92888784f2368b032e19e5244219ac67

Implementation and device validation guide:
https://github.com/shadow-pheonix/aos-AVP/blob/174ff71e92888784f2368b032e19e5244219ac67/Video/UPSCALING.md

See `Nova-Upscaling-6.5.4-debug-build.txt` for verification and SHA-256.
HDR sources bypass upscaling. Galaxy Tab S9 playback/visual testing remains pending.
This debug signing certificate cannot update an app signed with a different key.
