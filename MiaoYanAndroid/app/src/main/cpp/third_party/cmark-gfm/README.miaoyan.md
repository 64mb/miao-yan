# Vendored cmark-gfm

- Upstream: https://github.com/github/cmark-gfm
- Release tag: `0.29.0.gfm.13`
- Git commit: `587a12bb54d95ac37241377e6ddc93ea0e45439b`
- Source archive SHA-256: `5abc61798ebd9de5660bc076443c07abad2b8d15dbc11094a3a79644b8ad243a`
- License: BSD-2-Clause and bundled permissive notices; see `COPYING`.

The `src/` and `extensions/` directories are copied without modifications from
the release archive. MiaoYan's own deterministic target list and JNI adapter
live two directories above this file. Updating the dependency requires changing
the tag, commit, archive checksum, vendored sources, and CMake version constants
in the same commit.
