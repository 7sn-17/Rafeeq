#!/bin/sh
# Waits for the GitHub Actions run that belongs to the current commit and exits non-zero if it
# fails. An explicit run id is required for non-interactive use, so it is resolved from the SHA.
set -e

SHA=$(git rev-parse HEAD)

find_run() {
  gh run list --limit 30 --json databaseId,headSha \
    --jq ".[] | select(.headSha==\"$SHA\") | .databaseId" | head -n 1
}

RUN_ID=$(find_run)
if [ -z "$RUN_ID" ]; then
  echo "No workflow run found for $SHA yet; waiting 15s..."
  sleep 15
  RUN_ID=$(find_run)
fi

if [ -z "$RUN_ID" ]; then
  echo "No workflow run found for $SHA" >&2
  exit 1
fi

echo "Watching run $RUN_ID for $SHA"
gh run watch "$RUN_ID" --exit-status --compact
