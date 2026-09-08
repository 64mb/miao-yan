# Bundled Prettier

MiaoYan bundles only the browser files needed for offline Markdown formatting:

- `prettier` version: `3.9.6`
- source package: `https://registry.npmjs.org/prettier/-/prettier-3.9.6.tgz`
- npm integrity: `sha512-OpN0zzVdiaiAhxpuuj5efpIS4sY9j7bY6uR5mnj5yPzGkdkjNKSJeUThPb60Jw29QuAZgA4o+/iB49kFiaBX6g==`
- tarball SHA-1: `b3ea5146515d40fc53f18aa63f74dfab1e10dbf6`
- `standalone.js` SHA-256: `0c1acd3ad53d96bb66a4c530e4eb6240693f37dd0ecb81f51920edffa8c05567`
- `markdown.js` SHA-256: `587db246a6b8b62fe38bf7eb2aa703dfbeb2ac46a6579731d4487001e373579c`

Prettier is Copyright © James Long and contributors and is distributed under
the MIT License. The package's `LICENSE` and `THIRD-PARTY-NOTICES.md` are
included unchanged in this directory and are packaged into the APK.

The app does not fetch Prettier at build time or runtime. To update it, download
the pinned npm archive, verify its npm integrity, copy only `standalone.js`,
`plugins/markdown.js`, `LICENSE`, and `THIRD-PARTY-NOTICES.md`, then update the
version and hashes above. Run the formatter instrumentation tests before
accepting any output changes from a version update.
