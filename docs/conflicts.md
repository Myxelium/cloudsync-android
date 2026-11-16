# Conflicts and merge

A conflict means CloudSync will not choose a side for you.

## Three versions

- **Base** is the last version both sides had successfully synchronized.
- **Local** is the current device copy.
- **Remote** is the current Nextcloud copy.

`Diff3Merger` diffs base-to-local and base-to-remote with Myers diff (`java-diff-utils`). Edits that do not overlap are combined. Edits that overlap and do not produce the same text stay as conflicts. The Stockholm example is covered by `MergeTest`: Tuesday from the device and Gothenburg from the server both survive.

This is not a vendored copy of JGit. See `docs/adr/0001-libraries.md`.

## Structured files

If the line merge is clean, JSON, YAML, XML, and CSV are validated. An invalid clean merge is rejected. If the line merge conflicts, a structural merge is attempted and used only when it is itself clean and valid.

- JSON and YAML objects merge by key. Arrays merge by a stable string `id` when every element has one, otherwise by another line diff of their canonical form.
- XML merges attributes and child elements by `id` or by a unique tag. External entities and doctypes are disabled.
- CSV merges cells when the first column is a unique id. The same cell changed on both sides is a conflict.

Binary files, office extensions, invalid UTF-8, and NUL bytes are not text-merged. The conflict screen offers the device copy or the server copy. The other copy remains in history.

Files larger than 8 MiB are treated as binary for merge purposes.

## Resolving

The conflict screen shows base, device, and server text for each overlapping section. Choosing a side, or editing the result, does not discard the stored versions. Saving:

1. Records a `MERGE` version whose parents are the base, local, and remote versions.
2. Uploads with `If-Match` of the remote ETag captured for that session, when the folder allows uploads.
3. Writes the merged bytes locally after the upload succeeds, and only if the local file still matches the snapshotted local version.
4. Updates the common base ETag only after that success.
5. On HTTP 412, aborts the session, leaves the original local bytes in place, and opens the conflict again.

Download-only folders write the resolution locally and do not upload it. The tracked base ETag is left unchanged, so a later sync will not push that edit.

Cancelling the screen does not delete the local version, the remote version, or the base.
