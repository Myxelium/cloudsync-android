#!/usr/bin/env bash
set -uo pipefail

attempt=1
while [ "$attempt" -le 10 ]; do
  if adb shell settings put global window_animation_scale 0 \
    && adb shell settings put global transition_animation_scale 0 \
    && adb shell settings put global animator_duration_scale 0; then
    break
  fi
  echo "Settings service was not ready (attempt ${attempt})."
  attempt=$((attempt + 1))
  sleep 3
done

set -e
./gradlew connectedDebugAndroidTest --stacktrace
