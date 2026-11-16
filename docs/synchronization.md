# Synchronization

## Folder settings

Each synchronized folder stores:

- remote path and local root (an app directory, or a Storage Access Framework tree URI)
- direction: `TWO_WAY`, `UPLOAD_ONLY`, `DOWNLOAD_ONLY`
- network mode: Wi-Fi only, mobile only, or both
- separate upload and download permission for Wi-Fi and for mobile data
- charging requirement and minimum battery percent
- schedule, from manual through 15 minutes, 30 minutes, 1, 3, 6, 12 hours, and daily
- exclude patterns and whether subfolders are included
- deletion policy: `SAFE_RECOVERY` (default) or `PROPAGATE`
- pause, which cancels the periodic job

Removing a folder deletes the sync configuration and its tracked rows. It does not delete the local directory or the remote folder.

## What the planner will do

For a tracked file, the engine compares the local SHA-256 with the stored base hash, and the remote ETag with the stored base ETag. The hash is recomputed when the size or modified time differs from the tracked record. A reconciliation scan runs on every sync, so a missed filesystem event is recovered on the next run.

| Local | Remote | Direction | Result |
| --- | --- | --- | --- |
| unchanged | unchanged | any | remember in sync |
| changed | unchanged | upload allowed | upload with `If-Match` of the base ETag |
| changed | unchanged | download-only | remember dirty, do not upload |
| unchanged | changed | download allowed | download to a temporary file, then replace |
| unchanged | changed | upload-only | remember remote dirty, do not download |
| both changed | text | two-way | three-way merge |
| both changed | otherwise | any | conflict, neither side overwritten |
| no common base yet | both exist | any | conflict, neither side overwritten |

A download aborts if the local hash changes while it is in progress. An upload that receives HTTP 412 records a conflict and does not advance the base ETag.

## Deletions

`SAFE_RECOVERY` does not delete the surviving copy. A missing local file is marked `LOCAL_MISSING`. A missing remote file is marked `REMOTE_MISSING`.

`PROPAGATE` deletes only in the allowed direction, and only when the surviving copy still matches the base. A remote delete of an unchanged local file moves that file to `{localRoot}/.cloudsync/recovery`. If one side was deleted and the other was edited, the result is a conflict.

Paths under `.cloudsync/` are ignored by the folder filter, so the recovery area is not uploaded.

## Network gate

`NetworkGate` is the authority. Wi-Fi only means unmetered Wi-Fi. Mobile only means cellular or a metered network that is not unmetered Wi-Fi. Charging and battery percent are checked here. A null battery reading does not block.

WorkManager constraints are only a coarse filter: unmetered network for Wi-Fi-only folders, otherwise any connected network, plus charging when required. The engine can still return `WAITING_NETWORK`, `WAITING_CHARGING`, or `WAITING_BATTERY` after it wakes.

## Schedule

`SyncSchedule.intervalMinutes` is the value given to `PeriodicWorkRequest`. Manual folders are not scheduled. Immediate mode and the 15-minute mode both use 15 minutes, because that is WorkManager's minimum, and the runtime is inexact. While the app is open, a user can sync a folder or every folder immediately, subject to the network gate.

Retries use exponential backoff, capped at 15 minutes, and stop after 8 attempts. Permanent errors (authentication, 412, 404, 403, 409, storage) are not retried forever. The worker itself retries retryable failures up to 5 runs.

## Transfers

Each upload and download inserts a `transfers` row. The transfer screen reads that table. Interrupted `RUNNING` rows are set back to `QUEUED` on startup. Chunked uploads use Nextcloud's v2 upload protocol for files larger than 5 MiB: `MKCOL` on `/remote.php/dav/uploads/{user}/{uploadId}`, numbered `PUT` chunks, then `MOVE` of `.file` with `OC-Total-Length` and an optional `If-Match`.
