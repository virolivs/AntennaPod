#!/usr/bin/env bash
set -u

APP="AntennaPod"
VERSION="mutant-m1-snackbar"
RUNS=10

APP_PACKAGE="de.danoeh.antennapod.debug"

# FPS dos frames extraídos do vídeo
EXTRACT_FPS=15

TEST_CLASSES=(
  "de.test.antennapod.ui.AboutFragmentTest"
  "de.test.antennapod.ui.AddFeedFragmentTest"
  "de.test.antennapod.ui.BottomNavigationTest"
  "de.test.antennapod.ui.DownloadLogTest"
  "de.test.antennapod.ui.FeedSettingsTest"
  "de.test.antennapod.ui.NavigationDrawerTest"
  "de.test.antennapod.ui.PreferencesTest"
  "de.test.antennapod.ui.QueueFragmentTest"
  "de.test.antennapod.ui.TextOnlyFeedsTest"
)

get_current_focus() {
  adb shell dumpsys window 2>/dev/null \
    | grep -E "mCurrentFocus|mFocusedApp" \
    | head -n 1 \
    | tr -d '\r'
}

is_app_foreground() {
  FOCUS="$1"
  echo "$FOCUS" | grep -q "$APP_PACKAGE"
}

for TEST_CLASS in "${TEST_CLASSES[@]}"; do
  TEST_NAME="${TEST_CLASS##*.}"
  IS_AFFECTED=false

  for i in $(seq -w 1 $RUNS); do
    RUN_ID="run_$i"
    OUT="dataset/$APP/$TEST_NAME/$VERSION/$RUN_ID"
    FRAMES="$OUT/frames"

    mkdir -p "$FRAMES"

    VIDEO_REMOTE="/sdcard/${TEST_NAME}_${VERSION}_${RUN_ID}.mp4"
    VIDEO_LOCAL="$OUT/screenrecord.mp4"

    echo "timestamp,event,current_focus,reason" > "$OUT/events.csv"

    echo "======================================"
    echo "Starting: $TEST_NAME | $VERSION | $RUN_ID"
    echo "Output: $OUT"
    echo "App package: $APP_PACKAGE"
    echo "Extract FPS: $EXTRACT_FPS"
    echo "======================================"

    adb logcat -c
    adb shell rm -f "$VIDEO_REMOTE"

    adb logcat -v time > "$OUT/logcat.txt" &
    LOGCAT_PID=$!

    ./gradlew connectedFreeDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
      > "$OUT/gradle_output.txt" 2>&1 &

    TEST_PID=$!
    CAPTURE_STARTED=false
    SCREENRECORD_STARTED=false

    while kill -0 "$TEST_PID" 2>/dev/null; do
      FOCUS=$(get_current_focus)
      TIMESTAMP=$(date +%s.%N)

      if [ "$CAPTURE_STARTED" = false ]; then
        if is_app_foreground "$FOCUS"; then
          CAPTURE_STARTED=true
          SCREENRECORD_STARTED=true

          echo "$TIMESTAMP,app_opened_start_screenrecord,\"$FOCUS\",app_foreground" >> "$OUT/events.csv"
          echo "App opened. Starting screenrecord for $TEST_NAME | $RUN_ID"

          adb shell screenrecord "$VIDEO_REMOTE" &
          SCREENRECORD_PID=$!
        else
          echo "$TIMESTAMP,waiting_for_app_foreground,\"$FOCUS\",not_started" >> "$OUT/events.csv"
        fi
      else
        if is_app_foreground "$FOCUS"; then
          echo "$TIMESTAMP,app_foreground,\"$FOCUS\",recording" >> "$OUT/events.csv"
        else
          echo "$TIMESTAMP,app_not_foreground_stop_screenrecord,\"$FOCUS\",stopping_recording" >> "$OUT/events.csv"
          echo "App left foreground. Stopping screenrecord for $TEST_NAME | $RUN_ID"

          adb shell pkill -l INT screenrecord 2>/dev/null || true
          SCREENRECORD_STARTED=false

          break
        fi
      fi

      sleep 0.05
    done

    wait "$TEST_PID"
    EXIT_CODE=$?

    if [ "$SCREENRECORD_STARTED" = true ]; then
      echo "Stopping screenrecord..."
      adb shell pkill -l INT screenrecord 2>/dev/null || true
      sleep 1
      SCREENRECORD_STARTED=false
    fi

    if [ "$CAPTURE_STARTED" = true ]; then
      adb pull "$VIDEO_REMOTE" "$VIDEO_LOCAL" >/dev/null 2>&1 || true
      adb shell rm -f "$VIDEO_REMOTE"
    fi

    kill "$LOGCAT_PID" 2>/dev/null || true
    wait "$LOGCAT_PID" 2>/dev/null || true

    if [ "$EXIT_CODE" -eq 0 ]; then
      STATUS="pass"
    else
      STATUS="fail"
    fi

    NUM_FRAMES=0

    if [ -f "$VIDEO_LOCAL" ]; then
      echo "Extracting frames from video..."

      rm -f "$FRAMES"/*.png

      ffmpeg -y \
        -i "$VIDEO_LOCAL" \
        -vf "fps=$EXTRACT_FPS" \
        "$FRAMES/frame_%06d.png" \
        > "$OUT/ffmpeg_output.txt" 2>&1

      NUM_FRAMES=$(find "$FRAMES" -name "*.png" | wc -l)
    else
      echo "[WARN] Video not found: $VIDEO_LOCAL"
    fi

    cat > "$OUT/result.json" <<EOF
{
  "app": "$APP",
  "app_package": "$APP_PACKAGE",
  "test_class": "$TEST_CLASS",
  "test_name": "$TEST_NAME",
  "version": "$VERSION",
  "run": "$RUN_ID",
  "is_affected": $IS_AFFECTED,
  "status": "$STATUS",
  "exit_code": $EXIT_CODE,
  "capture_started_after_app_opened": $CAPTURE_STARTED,
  "screenrecord_started": $SCREENRECORD_STARTED,
  "video_local": "$VIDEO_LOCAL",
  "extract_fps": $EXTRACT_FPS,
  "num_frames": $NUM_FRAMES
}
EOF

    echo "Finished: $TEST_NAME | $RUN_ID | $STATUS | frames=$NUM_FRAMES"
  done
done