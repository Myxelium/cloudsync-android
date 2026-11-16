# Changelog

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
