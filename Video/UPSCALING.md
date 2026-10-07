# Nova GPU upscaling

Select **Player Settings → Upscaling** in application preferences or in the
phone/tablet playback menu. TV playback also has an Upscaling card. Choices are
Off, RAVU HQ (default), FSRCNNX Ultra, and SGSR1 Efficient. The selected value is
stored in `player_upscaling_mode` and retained across videos and app restarts.

This repository's canonical Nova implementation is in `Video/`, `MediaLib/`, and
`native/avos/`; there is no separate `apps/Nova/` tree.

## Rendering

The existing decoder renders into a source-sized `SurfaceTexture`. A dedicated
OpenGL ES 3.1 EGL thread imports the decoded external texture and presents to the
existing `SurfaceView`, whose buffer is sized to the fitted video area. EGL's
actual buffer size determines the requested reconstruction size. Subtitles and
controls remain separate overlays; neither is passed through the video shaders.
Decoder timestamps, audio, seeking, resume state, next/previous and the player
service remain owned by Nova's existing playback engine.

| Selection | GPU passes |
| --- | --- |
| Off | Existing Android external-texture color conversion → bilinear presentation |
| RAVU HQ | Chroma reconstruction → luma RAVU-Zoom **AR r3** at fractional output size → RGB combination → **SSimSuperRes** → output |
| FSRCNNX Ultra | Chroma reconstruction → luma **FSRCNNX x2 16-0-4-1** → RGB combination → separable **Lanczos3** with local anti-ringing → output |
| SGSR1 Efficient | Chroma reconstruction / RGBY import → Qualcomm **SGSR1 v1** directly at output size |

For 1920×1080 → 2560×1440, RAVU requests 2560×1440 directly; FSRCNNX reconstructs
3840×2160 before the final reduction. FSRCNNX's trained weights are bundled, not
substituted with a sharpening filter. Its RGB combination pass bounds reconstructed
luma to the local source envelope with a 2% contrast allowance to limit learned
edge overshoot. RAVU retains its upstream anti-ringing weights and SSimSuperRes
parameters. SGSR1 uses Qualcomm's RGBY operation mode, original 8/255 edge
threshold and 2.0 adaptive sharpness. No extra sharpening is added to SGSR1.

The original mpv/libplacebo-compatible RAVU, FSRCNNX and SSimSuperRes shaders are
pinned in `shaders/upstream/`. The GLES wrapper preserves their pass bodies and
binds their named intermediate textures. GLES fits the existing Android decoder
surface architecture without replacing the playback engine with mpv or adding a
Vulkan/libplacebo native dependency. Rebuild runtime assets with:

```sh
python3 Video/shaders/generate_upscaling.py
```

Feature maps use floating point render targets. SSimSuperRes variance intermediates
use 32-bit floats to preserve its small nonzero variance floors on GPUs that flush
half-float subnormals; these require floating-point rendering and linear filtering. The graph reuses dead intermediate
maps without aliasing live inputs. A mode change rebuilds only the shader graph on
the render thread, including while paused, and never reopens or seeks the decoder.

Reconstruction is bypassed if either render dimension is smaller than the source,
or both dimensions are equal. This also bypasses upscaling in a smaller PiP area.
The normal presenter remains in use so Off can be selected during playback without
changing the decoder surface. GPUs lacking GLES 3.1/external-texture support keep
Nova's original direct surface renderer; unavailable float targets or shader errors
fall back to the ordinary GPU presenter with an inactive diagnostic.

## Chroma and HDR boundaries

On devices exposing `GL_EXT_YUV_target`, and with known source matrix, range,
bit depth, subsampling and chroma location, the import shader reconstructs the
native chroma sample grid using anti-ringing Catmull-Rom interpolation before RGB
conversion. Both limited/full range and 8–16-bit code normalization are supported.
FFmpeg color metadata reaches Java through appended AVOS video metadata keys;
existing metadata key numbers are retained.

If the extension, metadata or decoder buffer format cannot support native YUV
sampling, the renderer uses Android's external RGB conversion. The trained luma
upscaler still runs and diagnostics identify the chroma fallback. Bicubic color
combination cannot recover chroma information already lost in platform conversion.

**HDR sources retain Nova's existing surface path, including its existing HDR→SDR
control. Upscaling is inactive for HDR sources in this implementation.** An Android
`color-transfer-request` alone does not prove that a decoder emitted SDR, so
routing those frames into an SDR EGL surface would risk wrong colors. Stereo
OpenGL effects also retain their existing renderer. Diagnostics report these
bypasses and retain the user's selected mode for subsequent SDR playback.

## Diagnostics

**Player Settings → Upscaling diagnostics**, the TV diagnostics card, and the
existing exported diagnostics bundle show selection, source/render dimensions,
active state and fallback reason, GL backend/device, chroma path, presented draws,
coalesced frame notifications and average CPU render + EGL swap time. Coalesced
notifications are not a decoder dropped-frame count. Decoder dropped-frame and
GPU timer-query measurements are reported as unavailable; CPU time is not labeled
as GPU execution time. Open diagnostics again to refresh the snapshot.

## Validation

Automated checks performed in the cloud workspace:

- Complete `assembleNoamazonDebug` build with `-Puniversal`, including both
  existing native `ndkBuild` tasks and Android lint. AVOS and the file-access JNI
  libraries were compiled from this working tree using Android NDK r27c
  (27.2.12479018); codec dependencies use the repository's pinned prebuilts.
- APK signature verification passes for v1, v2 and v3. ZIP integrity passes;
  all upscaling assets match the source bytes. Each of the four supported ABIs
  includes 22 native libraries with all non-system runtime dependencies present.
- All 43 selected JUnit tests pass: six new upscaling checks plus 37 existing
  resume, route recovery and subtitle-selection regressions. The new checks cover
  fractional tablet scaling, native/higher-resolution
  bypass, PiP/mixed-axis bypass, preference restoration, HDR/unknown chroma gating,
  chroma siting and 10-bit normalization.
- C syntax checks of the AVOS metadata writer and FFmpeg parser using the pinned
  FFmpeg headers, plus native/Java metadata-key consistency.
- Headless Mesa GLES compilation/linking of all 38 testable programs, execution
  of each reconstruction graph at 96×54 → 128×72, finite/bounded outputs,
  flat-field stability, distinction from bilinear, and mid-gray edge ringing.
- YUV shader math/range tests for 8-bit and 10-bit limited-range buffers using a
  2D texture surrogate. Mesa does not expose Android's external YUV extension.

Run the GPU checks with Python 3, NumPy and Mesa EGL:

```sh
python3 Video/shaders/test_gpu.py
```

Run the Android policy tests in a configured build environment:

```sh
cd Video
./gradlew -Puniversal testNoamazonDebugUnitTest \
  --tests com.archos.mediacenter.video.player.upscaling.UpscalingPolicyTest
```

The signed universal test APK is generated at
`Video/build/outputs/apk/noamazon/debug/org.courville.nova-6050004-6.5.4-debug.apk`.
Build it in a configured Android SDK/NDK environment with:

```sh
cd Video
./gradlew -Puniversal assembleNoamazonDebug --no-daemon
```

This is a debug-signed test build using package `org.courville.nova`, not an
official release-signed update. Android requires a matching signing certificate
to update an existing installation; preserve its data before changing installs.

Actual Tab S9 playback/visual comparison remains unvalidated in this workspace;
no Android device is attached. Build the native libraries from this source along
with Java: reusing older prebuilt AVOS libraries would omit the new chroma metadata.
No battery, Adreno GPU timing, temporal stability or perceptual equivalence to
native 1440p claim follows from the headless checks.

### Galaxy Tab S9 comparison procedure

1. Use a clean/high-bitrate 1920×1080 **SDR** test clip and a matching native 1440p
   reference if available. Keep aspect ratio, display brightness/color settings
   and frame-rate switching identical. Hide playback controls. Check diagnostics
   for the real fitted render dimensions rather than assuming a 2560×1440 panel.
   A Tab S9's 2560×1600 panel normally fits 16:9 video into 2560×1440.
2. Play the same segments in Off, RAVU HQ, FSRCNNX Ultra and SGSR1 Efficient. Record
   backend, active state, chroma path and render timing for each. If a mode falls
   back, resolve the reported cause before treating it as an algorithm comparison.
3. Compare paused fine textures, hair, faces, foliage, small text and diagonal
   edges. Check bright/dark boundaries for halos and flat areas for altered tone.
   Compare motion too: pans, foliage and fine fabric reveal shimmer that paused
   images cannot establish. A spatial upscaler cannot recover missing real detail.
4. Repeat Off → each mode → Off without seeking. Confirm the position progresses
   normally, audio stays aligned and a paused frame updates without resuming.
5. Check 1440p/native and 4K sources remain inactive, and rotate/resize the player.
   Enter/exit PiP; check reduced-area bypass and restoration. Verify subtitles,
   resume, next/previous, seeks, audio/subtitle track changes and diagnostics export.
6. Verify HDR→SDR on/off still follows Nova's existing behavior, and HDR diagnostics
   show inactive upscaling. Check unsupported GPU/device fallback separately.
7. Run sustained playback to observe GPU load, thermals and frame delivery before
   choosing a quality mode. Headless CPU/Mesa times do not predict Tab S9 performance.
