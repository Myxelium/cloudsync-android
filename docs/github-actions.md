# GitHub Actions

Workflows live in `.github/workflows`. They run when this project is on GitHub. Forks need their own Actions permission.

## Continuous integration

`ci.yml` on every push and pull request:

1. Checks out the repository.
2. Installs Temurin 21.
3. Installs Android command-line tools, `platforms;android-37.0`, and build-tools `36.0.0`.
4. Restores the Gradle cache through `gradle/actions/setup-gradle`.
5. Runs `spotlessCheck`, `lintDebug`, `test`, `testDebugUnitTest`, and `assembleDebug`.
6. Writes a SHA-256 checksum for the debug APK and uploads the APK plus the checksum as artifacts.

A second job starts an API 34 emulator and runs `connectedDebugAndroidTest`. That covers the Keystore round trip and notification channels. It needs KVM on the runner. A failure there does not mean the JVM suite failed; the first job is the one that gates the APK.

Debug builds do not read signing secrets.

## Versions

`version.properties` is the version source, marked for release-please. `versionCode` is derived from `MAJOR.MINOR.PATCH` and always increases: major 0–21, minor 0–999, patch 0–99999. Do not publish two different APKs with the same version name.

`release-please.yml` opens a release pull request from Conventional Commits on `main`. Merging it tags the version and opens the GitHub Release.

## Signed releases

`release.yml` runs when a GitHub Release is published.

Repository secrets:

| Secret | Purpose |
| --- | --- |
| `CLOUDSYNC_KEYSTORE_BASE64` | Base64 of the upload keystore |
| `CLOUDSYNC_KEYSTORE_PASSWORD` | Keystore password |
| `CLOUDSYNC_KEY_ALIAS` | Key alias |
| `CLOUDSYNC_KEY_PASSWORD` | Key password |

The workflow writes the keystore to a temporary file, sets `CLOUDSYNC_KEYSTORE_PATH`, and runs `assembleRelease`. If any secret is missing, the workflow fails before it uploads an artifact. The Gradle task `requireReleaseSigning` fails the release build on its own when the variables are absent, including on a laptop.

The job attaches the release APK and `SHA256SUMS` to the GitHub Release. The checksum file names the tag and the commit SHA that was built.

Create a keystore locally, without committing it:

```bash
keytool -genkeypair -v -keystore upload.jks -alias cloudsync -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 upload.jks
```

Store the base64 output as the Actions secret. Delete the local copy from any directory that might be committed. `*.jks` and `*.keystore` are gitignored.
