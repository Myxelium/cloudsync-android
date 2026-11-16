# Changelog

## [1.1.0](https://github.com/Myxelium/cloudsync-android/compare/v1.0.0...v1.1.0) (2026-11-16)


### Features

* add CloudSync Nextcloud client ([953dd5b](https://github.com/Myxelium/cloudsync-android/commit/953dd5b8f9900721593132c8f77142474175dbb3))


### Bug Fixes

* open Nextcloud folders from the files screen ([#3](https://github.com/Myxelium/cloudsync-android/issues/3)) ([d726dca](https://github.com/Myxelium/cloudsync-android/commit/d726dcaa482fc8e188224a883bb7248a98da087c))
* publish a release when the pull request is refused ([#4](https://github.com/Myxelium/cloudsync-android/issues/4)) ([4b95857](https://github.com/Myxelium/cloudsync-android/commit/4b958576316f68bcfbec68f064c5e5c082cea6ef))

## 1.0.0

First release.

- Nextcloud Login Flow v2 and app passwords, stored with Android Keystore AES-GCM.
- WebDAV browsing and file operations, including chunked upload for large files.
- Per-folder two-way, upload-only, and download-only synchronization.
- Network, charging, and battery rules enforced by the sync engine.
- WorkManager schedules and a data-sync foreground service.
- Local version history with restore.
- diff3 text merge, plus JSON, YAML, XML, and CSV merges when the text merge is not clean.
- Conflict review that keeps every original version.
- Debug APK builds on GitHub Actions. Signed release APKs attach to GitHub Releases when signing secrets are present.
