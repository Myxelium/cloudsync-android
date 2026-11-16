# Version history

Versions are an application-level history. Nextcloud does not need a Git repository.

Each `file_versions` row stores:

- id
- file key
- parent ids
- time
- SHA-256 and size
- origin: `LOCAL`, `REMOTE`, `DOWNLOAD`, `UPLOAD`, or `MERGE`
- optional conflict id, description, remote ETag, and merge metadata
- whether a snapshot blob was stored

Blobs live in `version_blobs`. A snapshot larger than 32 MiB is omitted and the description says so. Retention keeps at most 50 versions per file and 512 MiB of snapshots overall. The current base, versions referenced by an open conflict, and versions referenced by an uncommitted merge are not pruned.

The history screen lists versions for a file key, diffs two text snapshots, and restores a snapshot through `SyncEngine.restore`:

- The current local bytes are snapshotted first.
- The restored bytes are written locally.
- Download-only folders stop there and do not upload.
- Otherwise the restored bytes are uploaded with `If-Match` of the base ETag. The base advances only if that upload succeeds and the local hash is still the restored hash.
- HTTP 412 leaves the restored bytes on the device, does not change the server, and records a conflict.

Where the server exposes them, WebDAV `PROPFIND` on `/remote.php/dav/versions/{user}/versions/{fileId}` can list server versions, and `MOVE` can restore one. That history is best-effort. The local database is the one CloudSync relies on after a reboot.
