# Custom Nova build baseline

This fork is the canonical repository for the custom Nova build.

The official Nova repository structure and submodules are preserved. Custom changes are stored as exact replacement files under `custom-overrides/` and are applied only to the recorded upstream component bases during the custom build.

Recorded upstream bases:

- Video: `cab25da088a28601795c795c9ad6375f2054f60a`
- MediaLib: `3e904a4e657c2abc9a6b12192a095d52714041b7`
- native/avos: `c594ce3b4c8c2d1dc7e1dd050812801e2040560f`

Expected final component trees after applying `custom-overrides/`:

- Video: `1bc28f7532b520658b1c6cf7482924a9ef121f8e`
- MediaLib: `14bbdf89b76725df73b31116c0bf5eedeb83f650`
- native/avos: `675e0e04707905a605797fc314181ccc363ca0c3`

Migrated custom delta:

- Video: 11 files
- MediaLib: 1 file
- native/avos: 7 files

Current custom feature baseline includes handheld PiP, exportable diagnostics, local subtitle-file loading, and HDR-to-SDR decoder tone mapping.

The CI workflow must verify the reconstructed component tree hashes above before building. This guarantees that the fork produces the same custom source state that previously lived under `Android_Development/apps/Nova/`.
