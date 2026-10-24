# NOVA TV — Dolby Vision compatibility mode

This branch starts at official NOVA AVP commit
`eacf19d5ff8de2e250f77a639dfe9c12429ed076`. It preserves that commit's exact
component submodule revisions; it does not inherit the fork's custom `nova`
branch. `sources.json` records the component revisions and the pinned libdovi
revision.

## Playback

Enable **Dolby Vision compatibility mode** in video preferences and reopen the
video. The option is off by default. **Disable Dolby Vision** takes precedence.

For HEVC streams identified as Dolby Vision Profile 7, the native FFmpeg parser
converts each RPU using libdovi mode 2 before queuing the packet to the Android
decoder. It removes the enhancement layer, preserving the base-layer video,
timestamps, and packet properties. Both hvcC length-prefixed packets and Annex B
packets are supported. Profile 5, Profile 8, AV1, and non-Dolby-Vision video do
not enter this conversion path. Metadata-only library scans do not convert data.

This follows [Kodi's compatibility-mode behavior](https://github.com/xbmc/xbmc/commit/cfcb8a2798fa3d84c765f0683121974d0113f584)
through NOVA-specific integration. It does not copy Kodi player source. The
MIT-licensed `dolby_vision` library performs the metadata conversion.

A Dolby Vision-capable playback device and display are still required. This
does not reconstruct the FEL image contribution, add Dolby Vision support to
unsupported hardware, or implement HDR10+ filtering. Invalid conversion data
stops parsing instead of silently mixing incompatible metadata into playback.
Playback on physical TV hardware still needs verification.

## APK build

The **TV Dolby Vision APK** Actions workflow runs only on `tv-dolby-vision`.
Its artifact `nova-tv-dolby-vision-apk` contains the universal debug APK, its
SHA-256 checksum, the branch commit, and the source manifest. The APK uses
NOVA's original application ID and an Android debug signing key; Android may
require uninstalling an existing differently signed NOVA installation first.

The workflow resolves submodule URLs to the official NOVA organization, checks
their exact commit IDs, applies the feature patches, builds libdovi for the host
and all four Android ABIs, tests real FEL/MEL reference RPUs, then assembles the
APK. It never performs a submodule `--remote` update.

The official AVP snapshot lacks a libyuv make target and path required by its
pinned MediaLib/avos components. `prepare.py` restores those build definitions.

For an equivalent local build with the Android SDK/NDK, Java 17, Rust, and
native build packages configured:

```sh
# Initialize pinned submodules using their official NOVA URLs, as in the workflow.
python3 tv/dolby-vision/prepare.py
bash tv/dolby-vision/build-libdovi.sh
cd Video
./gradlew --no-daemon -Puniversal assembleNoamazonDebug
```

`prepare.py` intentionally fails if source revisions differ or patches are
already applied. Use a fresh checkout for each build.
