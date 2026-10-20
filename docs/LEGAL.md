# LEGAL

- Upstream code (recloudstream/cloudstream) is GPL-3.0. This derivative keeps GPL-3.0.
- `upstream/` is an unmodified git submodule; no upstream files are modified.
- Files ported from upstream (e.g. the app-module `Plugin` port, Phase 2) keep
  their original copyright headers.
- Third-party vendored tools (enjarify, dex-tools) are downloaded at bootstrap
  with their own licenses noted in THIRD_PARTY_NOTICE.md (Phase 2).
- No content sources are bundled. The seeded repo catalog mirrors the list
  published by upstream at recloudstream/cs-repos; users opt in to add more
  repositories (arbitrary repos execute third-party code on this host).
- DRM-protected streams and torrents are explicitly unsupported.
