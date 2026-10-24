Universal **release APK** of NOVA TV with Dolby Vision compatibility mode.

- Supports ARM32 (`armeabi-v7a`), ARM64 (`arm64-v8a`), x86 and x86_64.
- Built from clean official NOVA 6.4.29 components plus the Dolby Vision feature on `tv-dolby-vision`.
- Converts Dolby Vision Profile 7 metadata to Profile 8.1 during playback and discards the enhancement layer.
- Enable **Dolby Vision compatibility mode** in video settings, then reopen the video.
- Real FEL/MEL conversion tests pass. Playback on physical TV hardware still needs verification.

Download `Nova-TV-Dolby-Vision-6.4.29-universal.apk` directly below. `SHA256.txt`
contains its checksum. The source manifest and public signing certificate are
included for verification.

This APK uses NOVA's original application ID. Android requires matching signing
certificates for an in-place update; an earlier debug build or official NOVA
installation may need to be removed before installing this APK. See
`signing-info.txt` for the signing-key status of this build.
