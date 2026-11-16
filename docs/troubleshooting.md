# Troubleshooting

## The server URL is rejected

CloudSync allows HTTPS only, with no username in the URL and no localhost. Use the public name of the server. A certificate the device does not trust fails closed; there is no option to accept it.

## Sign-in sits on “waiting”

Login Flow v2 stays on HTTP 404 until the browser page is approved. The app polls for about five minutes. Approve the page, or start again. A 401 after that means the secret was rejected.

## A folder says it is waiting

Read the folder error line. It is one of: no validated network, not the allowed network type, upload or download not permitted on this network, not charging, or battery below the folder minimum. The schedule text on the sync screen also explains that Android may delay a periodic run.

## An edit was not uploaded

The folder may be download-only, paused, or waiting on a network rule. A download-only editor save stays in the editor and is not uploaded. A file outside every sync folder is uploaded directly when you save it from the editor.

## A conflict came back after I saved

The server ETag changed between the merge and the upload. CloudSync aborted the upload and kept your previous local bytes. Open the conflict again; it now includes the newer server copy.

## A large file has history but no snapshot

Snapshots stop at 32 MiB so the database cannot fill the device. The version row is still there. Text merge also stops at 8 MiB and asks you to pick a side.

## Sync did not resume after a restart

Opening the app requeues transfers that were `RUNNING` and re-registers schedules. A force-stop blocks broadcast receivers until the next manual launch. WorkManager also will not run exact 15-minute alarms.

## Release build fails immediately

`assembleRelease` requires the four `CLOUDSYNC_*` signing variables. Debug does not. See `docs/github-actions.md`.

## Logs

Settings shows recent diagnostic events. They are redacted. If a line still contains a secret, treat that as a bug and do not paste the raw line into an issue.
