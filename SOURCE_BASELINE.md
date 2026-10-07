# Custom Nova source baseline

This fork is the canonical repository for the custom Nova build.

The custom source is vendored directly in the repository:
- `Video/`
- `MediaLib/`
- `native/avos/`

Initial vendored migration baseline:
- Video tree: `1bc28f7532b520658b1c6cf7482924a9ef121f8e`
- MediaLib tree: `14bbdf89b76725df73b31116c0bf5eedeb83f650`
- native/avos tree: `675e0e04707905a605797fc314181ccc363ca0c3`

Those trees were reconstructed from the recorded upstream bases plus the previous custom Nova changes and verified byte-for-byte before vendoring.

Recorded upstream bases for that migration:
- Video: `cab25da088a28601795c795c9ad6375f2054f60a`
- MediaLib: `3e904a4e657c2abc9a6b12192a095d52714041b7`
- native/avos: `c594ce3b4c8c2d1dc7e1dd050812801e2040560f`

Future upstream changes must be reviewed and merged deliberately into these vendored custom trees. They are not automatically overlaid or pulled into the custom build.

GPU upscaling implementation and device validation notes: [Video/UPSCALING.md](Video/UPSCALING.md).
