#!/usr/bin/env bash
# Puts [apk] into the v2.0 release as 4KPlusTV-AndroidTV.apk, which is the file the owner's
# Downloader code installs. Uploads under a temporary name first and only then removes the old
# file and renames the new one, so the link is never without a file. Needs GH_TOKEN with
# contents: write, and runs on a GitHub Actions runner (gh is preinstalled there).
set -euo pipefail

apk="$1"
tag="v2.0"
name="4KPlusTV-AndroidTV.apk"
temp="4KPlusTV-AndroidTV-uploading.apk"
repo="${GITHUB_REPOSITORY}"

test -s "$apk"
staging=$(mktemp -d)
cp "$apk" "$staging/$temp"

# --clobber also clears a temporary file left by an earlier run that failed half way.
gh release upload "$tag" "$staging/$temp" --repo "$repo" --clobber

release=$(gh api "repos/$repo/releases/tags/$tag" --jq .id)
new=$(gh api "repos/$repo/releases/$release/assets" --jq ".[] | select(.name == \"$temp\") | .id")
test -n "$new"

gh release delete-asset "$tag" "$name" --repo "$repo" --yes || true
gh api -X PATCH "repos/$repo/releases/assets/$new" -f name="$name" --jq '"\(.name) \(.size) bytes"'
