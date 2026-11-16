#!/usr/bin/env bash
# release-please prepares a commit on release-please--branches--main and then
# opens a pull request. This token cannot open that pull request, so the call
# fails after the commit exists. Publish that commit as the release instead.
set -euo pipefail

created=false
tag_name=""

finish() {
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf 'created=%s\n' "$created" >> "$GITHUB_OUTPUT"
    printf 'tag_name=%s\n' "$tag_name" >> "$GITHUB_OUTPUT"
  else
    printf 'created=%s\n' "$created"
    printf 'tag_name=%s\n' "$tag_name"
  fi
}
trap finish EXIT

log=$(mktemp)
set +e
npx --yes release-please@17.3.0 manifest-pr \
  --repo-url="${GITHUB_REPOSITORY}" \
  --target-branch=main \
  --token="${GH_TOKEN}" \
  --config-file=release-please-config.json \
  --manifest-file=.release-please-manifest.json \
  >"$log" 2>&1
status=$?
set -e
cat "$log"

if [ "$status" -eq 0 ]; then
  exit 0
fi

# The pull request is refused either because Actions may not open pull requests,
# or because this token has no pull-request permission. Both happen after the
# release commit is already on release-please--branches--main.
if ! git fetch origin release-please--branches--main; then
  echo "release-please did not prepare a release branch."
  exit "$status"
fi
after=$(git rev-parse FETCH_HEAD)
base="${GITHUB_SHA}"

if [ "$after" = "$base" ] || ! git merge-base --is-ancestor "$base" "$after"; then
  echo "No release commit was prepared on top of ${base}."
  exit "$status"
fi

echo "The release pull request was not opened. Publishing the prepared commit."

version=$(git show "${after}:.release-please-manifest.json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["."])')
packaged=$(git show "${after}:version.properties" | sed -n 's/^VERSION_NAME=//p' | tr -d '[:space:]')
if [ "$packaged" != "$version" ]; then
  echo "version.properties is ${packaged}, but the release commit is ${version}."
  exit 1
fi

tag="v${version}"
if gh release view "$tag" >/dev/null 2>&1; then
  echo "Release ${tag} already exists."
  exit 0
fi

notes=$(mktemp)
git show "${after}:CHANGELOG.md" | awk 'BEGIN {found=0} /^## / { if (found) exit; found=1 } found { print }' >"$notes"
git push origin "${after}:refs/heads/main"
gh release create "$tag" --target "$after" --title "$tag" --notes-file "$notes"
created=true
tag_name="$tag"
