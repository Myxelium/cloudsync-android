# ADR 0001: Libraries for WebDAV, diff, and background work

## Nextcloud Android library

The official Nextcloud Android project is a full client, not a library with a supported embeddable API. Depending on it would pull that app's architecture, resources, and release cycle into CloudSync. WebDAV and the small OCS surface this app needs are implemented on OkHttp instead. The wire format follows Nextcloud's public WebDAV and Login Flow v2 behavior, and the tests pin that behavior with MockWebServer.

## JGit

JGit's diff and merge code is the usual Java implementation of diff3, but the library is built around packfiles, `java.nio` file locking, and a large transitive surface that is a poor fit for an Android APK. CloudSync needs a three-way line merge and content hashes, not a Git object database.

The merge in `:merge` uses Myers diff from `java-diff-utils` and a region merger that keeps non-overlapping edits and refuses to pick a side when regions overlap. Structured merging for JSON, YAML, XML, and CSV sits on top of that and is used only when it validates. This is not JGit and it is not described as JGit. The merge tests cover the non-overlapping case, same-line conflicts, insertions, deletions, empty files, large files, bad encodings, and invalid structured documents.

## Background work

WorkManager is the persistent scheduler because it survives process death and reboot within Android's limits. It cannot express mobile-only or per-direction network policy, so `NetworkGate` enforces those after a job starts. Long user-visible transfers use a `dataSync` foreground service, which is what current Android releases require for ongoing network work the user should see.

## Credentials

Android Keystore holds the AES-GCM key. The ciphertext is an app-private file. `AesGcmCipher` is covered by a JVM test with a generated key. The instrumented test checks the Keystore path on a device.
