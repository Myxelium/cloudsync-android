# CloudSync

CloudSync is an Android client for Nextcloud. It browses files over WebDAV, synchronizes selected folders, keeps a local version history, and merges independent text edits instead of dropping one side.

The sync engine does not live in the UI. Direction, network rules, and conflict handling are enforced in the same planner whether a sync is started from the screen or by WorkManager.

## What you can do

- Sign in with Nextcloud Login Flow v2, or with an app password. The app password is stored with Android Keystore-backed AES-GCM. The account password is not kept.
- Browse, search, upload, download, create, rename, move, copy, and delete files. Text files open in the built-in editor. Other files open in another app.
- Choose folders to synchronize. Each folder has its own direction (two-way, upload-only, or download-only), network rules, charging rule, battery floor, schedule, exclude patterns, and deletion policy.
- Upload-only folders never download file contents. Download-only folders never upload local edits, including a resolved conflict.
- Review transfers, conflicts, and version history. Restore a stored snapshot. Overlapping text edits can be merged per section or edited by hand.
- Keep working after the app is closed. Periodic work is scheduled with WorkManager. Long transfers run in a data-sync foreground service. Running transfers are queued again if the process is killed.

## Requirements

- Android 8.0 (API 26) or newer. The app targets API 36 and compiles against Android 37.
- JDK 21 to build. Bytecode is Java 17.
- A Nextcloud server reached over HTTPS. Certificate checks are the platform defaults. Cleartext is disabled.

## Build

```bash
# Android SDK with platforms;android-37.0 and build-tools;36.0.0
export ANDROID_HOME="$HOME/android-sdk"
./gradlew assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Its application id is `dev.cloudsync.app.debug`.

Release builds are signed only when these environment variables are set:

- `CLOUDSYNC_KEYSTORE_PATH`
- `CLOUDSYNC_KEYSTORE_PASSWORD`
- `CLOUDSYNC_KEY_ALIAS`
- `CLOUDSYNC_KEY_PASSWORD`

`assembleRelease` fails if any of them is missing. Do not commit a keystore.

`version.properties` holds `VERSION_NAME`. `versionCode` is `major * 100000000 + minor * 100000 + patch`, so it increases for every distinct version and is never reused for a different version name.

## Tests

```bash
./gradlew test testDebugUnitTest
./gradlew connectedDebugAndroidTest   # emulator or device
```

JVM tests cover hashing, URL checks, diff3 merging, structured JSON, YAML, XML, and CSV merges, WebDAV requests against MockWebServer, login flow polling, the sync planner, direction limits, network gates, deletion recovery, and an interrupted merge. Android tests cover the Keystore credential round trip and notification channels. They need a device.

A live Nextcloud integration test is not part of the default Gradle run. See `docs/nextcloud-test-server.md`.

## Limitations that are intentional

- WorkManager periodic work is inexact. The shortest interval is 15 minutes, and Immediate mode uses that same floor while the app is in the background. The sync screen says so.
- WorkManager cannot express “mobile data only” or separate upload and download permissions. Those rules are enforced inside the sync engine. A job may wake and then wait.
- Office documents, PDF, and other binary files are not text-merged. Both copies are kept. DOCX and ODT merging is not implemented.
- Text merge refuses files larger than 8 MiB. Version snapshots are omitted above 32 MiB; the version row remains.
- Nextcloud server-side versions are read when the server exposes them. CloudSync does not depend on that history.
- There is no Collabora or ONLYOFFICE session. That is real-time editing, which this offline merge is not.
- A force-stopped app will not resume work until it is opened again. That is an Android restriction.

## Documentation

- [Architecture](docs/architecture.md)
- [Authentication](docs/authentication.md)
- [Synchronization](docs/synchronization.md)
- [Conflicts and merge](docs/conflicts.md)
- [Version history](docs/versions.md)
- [GitHub Actions and signing](docs/github-actions.md)
- [Nextcloud test server](docs/nextcloud-test-server.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Library decisions](docs/adr/0001-libraries.md)

## License

Apache License 2.0. See [LICENSE](LICENSE).
