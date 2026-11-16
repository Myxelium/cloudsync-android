# Contributing

## Commits

Use Conventional Commits. release-please reads them to bump `version.properties` and `CHANGELOG.md`.

- `feat:` a user-visible capability
- `fix:` a correction
- `docs:` documentation only
- `test:` tests only
- `chore:` tooling

Breaking changes use `feat!:` or a `BREAKING CHANGE:` footer. That bumps the major version.

Do not commit keystores, passwords, API tokens, or `local.properties`.

## Checks before you open a change

```bash
./gradlew spotlessApply
./gradlew spotlessCheck lintDebug test testDebugUnitTest assembleDebug
```

`spotlessCheck` runs ktlint. Wildcard imports are allowed. The line length is 140.

## Where code goes

| Module | Responsibility |
| --- | --- |
| `core` | Models, paths, hashing, errors, the sync and file ports |
| `network` | OkHttp and WebDAV |
| `nextcloud` | Login Flow v2 and OCS |
| `synchronization` | Planner, network gate, sync engine |
| `merge` | diff3 and structured merges |
| `versioning` | Version records and retention |
| `database` | Room |
| `security` | Keystore credentials |
| `storage` | Local files and Storage Access Framework |
| `ui` | Compose theme and shared panes |
| `app` | Screens, WorkManager, foreground service |

The sync engine must stay free of Android UI types. New sync behavior needs a JVM test in `:synchronization` or `:merge`.

## Data rules

- Do not overwrite a file whose ETag or content hash changed underneath the operation.
- Do not upload from a download-only folder, and do not download into an upload-only folder.
- Do not log secrets or file contents. `LogRedactor` is a backstop, not a reason to log them.
- Prefer moving a deleted file into `.cloudsync/recovery` unless the folder explicitly uses propagate-deletes.
