#!/usr/bin/env bash
set -u

APP="AntennaPod"
RUNS=10

APP_PACKAGE="de.danoeh.antennapod.debug"

EXTRACT_FPS=15

GRADLE_TASK=":app:connectedPlayDebugAndroidTest"

OPERATORS=(
  "IPR"
  "ITR"
  "MDL"
  "ECR"
  "ETR"
  "APD"
  "BWD"
  "TWD"
  "BWS"
  "FON"
  "ORL"
)

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
  "de.test.antennapod.dialogs.ShareDialogTest"
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

has_mutation_marker() {
  MARKER_X="$2"
  MARKER_Y="$3"

  RGB=$(ffmpeg -v error \
    -i "$1" \
    -vf "crop=12:12:$MARKER_X:$MARKER_Y,scale=1:1,format=rgb24" \
    -frames:v 1 \
    -f rawvideo - 2>/dev/null \
    | od -An -tu1 -N 3)

  set -- $RGB
  if [ "$#" -lt 3 ]; then
    return 1
  fi

  [ "$1" -ge 180 ] && [ "$2" -le 90 ] && [ "$3" -ge 180 ]
}

for OPERATOR in "${OPERATORS[@]}"; do
  VERSION="visual-mutation-$OPERATOR"

  for TEST_CLASS in "${TEST_CLASSES[@]}"; do
    TEST_NAME="${TEST_CLASS##*.}"

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
      echo "Operator: $OPERATOR"
      echo "Is affected: pending"
      echo "Output: $OUT"
      echo "App package: $APP_PACKAGE"
      echo "Extract FPS: $EXTRACT_FPS"
      echo "======================================"

      adb logcat -c
      adb shell rm -f "$VIDEO_REMOTE"

      adb logcat -v time > "$OUT/logcat.txt" &
      LOGCAT_PID=$!

      ./gradlew --console=plain "$GRADLE_TASK" \
        -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
        -Pandroid.testInstrumentationRunnerArguments.visualMutationOperator="$OPERATOR" \
        > "$OUT/gradle_output.txt" 2>&1 &

      TEST_PID=$!
      CAPTURE_STARTED=false
      SCREENRECORD_STARTED=false
      RECORDING_STARTED_MS=""

      while kill -0 "$TEST_PID" 2>/dev/null; do
        FOCUS=$(get_current_focus)
        TIMESTAMP=$(date +%s.%N)

        if [ "$CAPTURE_STARTED" = false ]; then
          if is_app_foreground "$FOCUS"; then
            CAPTURE_STARTED=true
            SCREENRECORD_STARTED=true

            echo "$TIMESTAMP,app_opened_start_screenrecord,\"$FOCUS\",app_foreground" >> "$OUT/events.csv"
            echo "App opened. Starting screenrecord for $TEST_NAME | $VERSION | $RUN_ID"

            RECORDING_STARTED_MS=$(date +%s%3N)
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
            echo "App left foreground. Stopping screenrecord for $TEST_NAME | $VERSION | $RUN_ID"

            adb shell pkill -l INT screenrecord 2>/dev/null || true
            SCREENRECORD_STARTED=false

            break
          fi
        fi

        sleep 0.05
      done

      wait "$TEST_PID"
      EXIT_CODE=$?
      TEST_FINISHED_AT=$(date +%s.%N)
      echo "$TEST_FINISHED_AT,test_finished,\"$(get_current_focus)\",closing_app" >> "$OUT/events.csv"

      if [ "$SCREENRECORD_STARTED" = true ]; then
        echo "Stopping screenrecord..."
        adb shell pkill -l INT screenrecord 2>/dev/null || true
        sleep 1
        SCREENRECORD_STARTED=false
      fi

      adb shell am force-stop "$APP_PACKAGE" >/dev/null 2>&1 || true
      echo "$(date +%s.%N),app_force_stopped,\"$(get_current_focus)\",test_finished" >> "$OUT/events.csv"

      if [ "$CAPTURE_STARTED" = true ]; then
        adb pull "$VIDEO_REMOTE" "$VIDEO_LOCAL" >/dev/null 2>&1 || true
        adb shell rm -f "$VIDEO_REMOTE"
      fi

      kill "$LOGCAT_PID" 2>/dev/null || true
      wait "$LOGCAT_PID" 2>/dev/null || true

      MUTATION_LOG_LINE=$(grep "visual_mutation_visible operator=" "$OUT/logcat.txt" | head -n 1 || true)
      MUTATION_BOUNDS=$(echo "$MUTATION_LOG_LINE" | sed -n 's/.* bounds=\([^ ]*\).*/\1/p')
      MUTATION_SCREEN=$(echo "$MUTATION_LOG_LINE" | sed -n 's/.* screen=\([^ ]*\).*/\1/p')
      MUTATION_MARKER=$(echo "$MUTATION_LOG_LINE" | sed -n 's/.* marker=\([^ ]*\).*/\1/p')
      MUTATION_LEFT=""
      MUTATION_TOP=""
      MUTATION_RIGHT=""
      MUTATION_BOTTOM=""
      MUTATION_MARKER_LEFT=0
      MUTATION_MARKER_TOP=0
      MUTATION_MARKER_RIGHT=12
      MUTATION_MARKER_BOTTOM=12
      MUTATION_DETECTED=false
      MUTATION_TYPE=""
      MUTATION_BOUNDS_JSON=null

      if [ -n "$MUTATION_BOUNDS" ]; then
        IFS=',' read -r MUTATION_LEFT MUTATION_TOP MUTATION_RIGHT MUTATION_BOTTOM <<< "$MUTATION_BOUNDS"
        MUTATION_DETECTED=true
        MUTATION_TYPE="$OPERATOR"
        MUTATION_BOUNDS_JSON="{\"left\":$MUTATION_LEFT,\"top\":$MUTATION_TOP,\"right\":$MUTATION_RIGHT,\"bottom\":$MUTATION_BOTTOM}"
      fi

      if [ -n "$MUTATION_MARKER" ]; then
        IFS=',' read -r MUTATION_MARKER_LEFT MUTATION_MARKER_TOP MUTATION_MARKER_RIGHT MUTATION_MARKER_BOTTOM <<< "$MUTATION_MARKER"
      fi

      echo "has_mutation,mutation_type,bounds_left,bounds_top,bounds_right,bounds_bottom,screen,log_line" > "$OUT/mutation_location.csv"
      echo "$MUTATION_DETECTED,$MUTATION_TYPE,$MUTATION_LEFT,$MUTATION_TOP,$MUTATION_RIGHT,$MUTATION_BOTTOM,$MUTATION_SCREEN,\"$MUTATION_LOG_LINE\"" >> "$OUT/mutation_location.csv"

      MUTATION_INTERVALS_CSV="$OUT/mutation_intervals.csv"
      echo "start_ms,end_ms,mutation_type,bounds_left,bounds_top,bounds_right,bounds_bottom,screen" > "$MUTATION_INTERVALS_CSV"

      CURRENT_START_MS=""
      CURRENT_TYPE=""
      CURRENT_LEFT=""
      CURRENT_TOP=""
      CURRENT_RIGHT=""
      CURRENT_BOTTOM=""
      CURRENT_SCREEN=""

      while IFS= read -r LINE; do
        if [[ "$LINE" == *"visual_mutation_visible operator="* ]]; then
          if [ -n "$CURRENT_START_MS" ]; then
            echo "$CURRENT_START_MS,,$CURRENT_TYPE,$CURRENT_LEFT,$CURRENT_TOP,$CURRENT_RIGHT,$CURRENT_BOTTOM,$CURRENT_SCREEN" >> "$MUTATION_INTERVALS_CSV"
          fi

          CURRENT_START_MS=$(echo "$LINE" | sed -n 's/.* wall_ms=\([0-9]*\).*/\1/p')
          CURRENT_TYPE=$(echo "$LINE" | sed -n 's/.* operator=\([^ ]*\).*/\1/p')
          CURRENT_BOUNDS=$(echo "$LINE" | sed -n 's/.* bounds=\([^ ]*\).*/\1/p')
          CURRENT_SCREEN=$(echo "$LINE" | sed -n 's/.* screen=\([^ ]*\).*/\1/p')
          CURRENT_LEFT=""
          CURRENT_TOP=""
          CURRENT_RIGHT=""
          CURRENT_BOTTOM=""

          if [ -n "$CURRENT_BOUNDS" ]; then
            IFS=',' read -r CURRENT_LEFT CURRENT_TOP CURRENT_RIGHT CURRENT_BOTTOM <<< "$CURRENT_BOUNDS"
          fi
        elif [[ "$LINE" == *"visual_mutation_hidden operator="* ]]; then
          if [ -n "$CURRENT_START_MS" ]; then
            CURRENT_END_MS=$(echo "$LINE" | sed -n 's/.* wall_ms=\([0-9]*\).*/\1/p')
            echo "$CURRENT_START_MS,$CURRENT_END_MS,$CURRENT_TYPE,$CURRENT_LEFT,$CURRENT_TOP,$CURRENT_RIGHT,$CURRENT_BOTTOM,$CURRENT_SCREEN" >> "$MUTATION_INTERVALS_CSV"
            CURRENT_START_MS=""
          fi
        fi
      done < "$OUT/logcat.txt"

      if [ -n "$CURRENT_START_MS" ]; then
        echo "$CURRENT_START_MS,,$CURRENT_TYPE,$CURRENT_LEFT,$CURRENT_TOP,$CURRENT_RIGHT,$CURRENT_BOTTOM,$CURRENT_SCREEN" >> "$MUTATION_INTERVALS_CSV"
      fi

      cat > "$OUT/mutation.json" <<EOF
{
  "has_mutation": $MUTATION_DETECTED,
  "mutation_type": "$MUTATION_TYPE",
  "mutation_bounds": $MUTATION_BOUNDS_JSON,
  "mutation_bounds_csv": "$MUTATION_BOUNDS",
  "mutation_screen": "$MUTATION_SCREEN",
  "mutation_marker": "$MUTATION_MARKER",
  "mutation_intervals_csv": "$MUTATION_INTERVALS_CSV"
}
EOF

      if [ "$EXIT_CODE" -eq 0 ]; then
        STATUS="pass"
      else
        STATUS="fail"
      fi

      NUM_FRAMES=0
      FRAMES_MUTATION_CSV="$OUT/frames_mutation.csv"
      FRAMES_MUTATION_JSONL="$OUT/frames_mutation.jsonl"
      MUTATION_FRAME_INTERVALS_CSV="$OUT/mutation_frame_intervals.csv"

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

      echo "frame_file,frame_index,frame_time_ms,has_mutation,mutation_type,bounds_left,bounds_top,bounds_right,bounds_bottom,screen" > "$FRAMES_MUTATION_CSV"
      : > "$FRAMES_MUTATION_JSONL"
      echo "start_frame_file,end_frame_file,start_frame_index,end_frame_index,mutation_type,bounds_left,bounds_top,bounds_right,bounds_bottom,screen" > "$MUTATION_FRAME_INTERVALS_CSV"

      if [ "$NUM_FRAMES" -gt 0 ]; then
        INTERVAL_STARTS=()
        INTERVAL_ENDS=()
        INTERVAL_TYPES=()
        INTERVAL_LEFTS=()
        INTERVAL_TOPS=()
        INTERVAL_RIGHTS=()
        INTERVAL_BOTTOMS=()
        INTERVAL_SCREENS=()

        while IFS=',' read -r START_MS END_MS TYPE LEFT TOP RIGHT BOTTOM SCREEN; do
          if [ "$START_MS" = "start_ms" ] || [ -z "$START_MS" ]; then
            continue
          fi

          INTERVAL_STARTS+=("$START_MS")
          INTERVAL_ENDS+=("$END_MS")
          INTERVAL_TYPES+=("$TYPE")
          INTERVAL_LEFTS+=("$LEFT")
          INTERVAL_TOPS+=("$TOP")
          INTERVAL_RIGHTS+=("$RIGHT")
          INTERVAL_BOTTOMS+=("$BOTTOM")
          INTERVAL_SCREENS+=("$SCREEN")
        done < "$MUTATION_INTERVALS_CSV"

        FRAME_SEGMENT_INDEX=-1
        PREVIOUS_FRAME_HAS_MUTATION=false
        PREVIOUS_FRAME_NAME=""
        PREVIOUS_FRAME_INDEX=""
        CURRENT_SEGMENT_START_FRAME=""
        CURRENT_SEGMENT_START_INDEX=""
        CURRENT_SEGMENT_TYPE=""
        CURRENT_SEGMENT_LEFT=""
        CURRENT_SEGMENT_TOP=""
        CURRENT_SEGMENT_RIGHT=""
        CURRENT_SEGMENT_BOTTOM=""
        CURRENT_SEGMENT_SCREEN=""

        for FRAME in "$FRAMES"/*.png; do
          FRAME_NAME="${FRAME##*/}"
          FRAME_INDEX="${FRAME_NAME#frame_}"
          FRAME_INDEX="${FRAME_INDEX%.png}"
          FRAME_NUMBER=$((10#$FRAME_INDEX))
          FRAME_TIME_MS=$(( (FRAME_NUMBER - 1) * 1000 / EXTRACT_FPS ))
          FRAME_HAS_MUTATION=false
          FRAME_MUTATION_TYPE=""
          FRAME_MUTATION_LEFT=""
          FRAME_MUTATION_TOP=""
          FRAME_MUTATION_RIGHT=""
          FRAME_MUTATION_BOTTOM=""
          FRAME_MUTATION_SCREEN=""
          FRAME_MUTATION_BOUNDS_JSON=null

          if has_mutation_marker "$FRAME" "$MUTATION_MARKER_LEFT" "$MUTATION_MARKER_TOP"; then
            FRAME_HAS_MUTATION=true

            if [ "$PREVIOUS_FRAME_HAS_MUTATION" = false ]; then
              FRAME_SEGMENT_INDEX=$((FRAME_SEGMENT_INDEX + 1))
              CURRENT_SEGMENT_START_FRAME="$FRAME_NAME"
              CURRENT_SEGMENT_START_INDEX="$FRAME_INDEX"
            fi

            if [ "$FRAME_SEGMENT_INDEX" -ge 0 ] && [ "$FRAME_SEGMENT_INDEX" -lt "${#INTERVAL_TYPES[@]}" ]; then
              FRAME_MUTATION_TYPE="${INTERVAL_TYPES[$FRAME_SEGMENT_INDEX]}"
              FRAME_MUTATION_LEFT="${INTERVAL_LEFTS[$FRAME_SEGMENT_INDEX]}"
              FRAME_MUTATION_TOP="${INTERVAL_TOPS[$FRAME_SEGMENT_INDEX]}"
              FRAME_MUTATION_RIGHT="${INTERVAL_RIGHTS[$FRAME_SEGMENT_INDEX]}"
              FRAME_MUTATION_BOTTOM="${INTERVAL_BOTTOMS[$FRAME_SEGMENT_INDEX]}"
              FRAME_MUTATION_SCREEN="${INTERVAL_SCREENS[$FRAME_SEGMENT_INDEX]}"
            else
              FRAME_MUTATION_TYPE="$MUTATION_TYPE"
              FRAME_MUTATION_LEFT="$MUTATION_LEFT"
              FRAME_MUTATION_TOP="$MUTATION_TOP"
              FRAME_MUTATION_RIGHT="$MUTATION_RIGHT"
              FRAME_MUTATION_BOTTOM="$MUTATION_BOTTOM"
              FRAME_MUTATION_SCREEN="$MUTATION_SCREEN"
            fi

            if [ -n "$FRAME_MUTATION_LEFT" ]; then
              FRAME_MUTATION_BOUNDS_JSON="{\"left\":$FRAME_MUTATION_LEFT,\"top\":$FRAME_MUTATION_TOP,\"right\":$FRAME_MUTATION_RIGHT,\"bottom\":$FRAME_MUTATION_BOTTOM}"
            fi

            CURRENT_SEGMENT_TYPE="$FRAME_MUTATION_TYPE"
            CURRENT_SEGMENT_LEFT="$FRAME_MUTATION_LEFT"
            CURRENT_SEGMENT_TOP="$FRAME_MUTATION_TOP"
            CURRENT_SEGMENT_RIGHT="$FRAME_MUTATION_RIGHT"
            CURRENT_SEGMENT_BOTTOM="$FRAME_MUTATION_BOTTOM"
            CURRENT_SEGMENT_SCREEN="$FRAME_MUTATION_SCREEN"
          elif [ "$PREVIOUS_FRAME_HAS_MUTATION" = true ]; then
            echo "$CURRENT_SEGMENT_START_FRAME,$PREVIOUS_FRAME_NAME,$CURRENT_SEGMENT_START_INDEX,$PREVIOUS_FRAME_INDEX,$CURRENT_SEGMENT_TYPE,$CURRENT_SEGMENT_LEFT,$CURRENT_SEGMENT_TOP,$CURRENT_SEGMENT_RIGHT,$CURRENT_SEGMENT_BOTTOM,$CURRENT_SEGMENT_SCREEN" >> "$MUTATION_FRAME_INTERVALS_CSV"
          fi

          echo "$FRAME_NAME,$FRAME_INDEX,$FRAME_TIME_MS,$FRAME_HAS_MUTATION,$FRAME_MUTATION_TYPE,$FRAME_MUTATION_LEFT,$FRAME_MUTATION_TOP,$FRAME_MUTATION_RIGHT,$FRAME_MUTATION_BOTTOM,$FRAME_MUTATION_SCREEN" >> "$FRAMES_MUTATION_CSV"
          echo "{\"frame_file\":\"$FRAME_NAME\",\"frame_index\":$FRAME_NUMBER,\"frame_time_ms\":$FRAME_TIME_MS,\"has_mutation\":$FRAME_HAS_MUTATION,\"mutation_type\":\"$FRAME_MUTATION_TYPE\",\"mutation_bounds\":$FRAME_MUTATION_BOUNDS_JSON,\"mutation_screen\":\"$FRAME_MUTATION_SCREEN\"}" >> "$FRAMES_MUTATION_JSONL"

          PREVIOUS_FRAME_HAS_MUTATION="$FRAME_HAS_MUTATION"
          PREVIOUS_FRAME_NAME="$FRAME_NAME"
          PREVIOUS_FRAME_INDEX="$FRAME_INDEX"
        done

        if [ "$PREVIOUS_FRAME_HAS_MUTATION" = true ]; then
          echo "$CURRENT_SEGMENT_START_FRAME,$PREVIOUS_FRAME_NAME,$CURRENT_SEGMENT_START_INDEX,$PREVIOUS_FRAME_INDEX,$CURRENT_SEGMENT_TYPE,$CURRENT_SEGMENT_LEFT,$CURRENT_SEGMENT_TOP,$CURRENT_SEGMENT_RIGHT,$CURRENT_SEGMENT_BOTTOM,$CURRENT_SEGMENT_SCREEN" >> "$MUTATION_FRAME_INTERVALS_CSV"
        fi
      fi

      cat > "$OUT/result.json" <<EOF
{
  "app": "$APP",
  "app_package": "$APP_PACKAGE",
  "test_class": "$TEST_CLASS",
  "test_name": "$TEST_NAME",
  "version": "$VERSION",
  "visual_mutation_operator": "$OPERATOR",
  "run": "$RUN_ID",
  "status": "$STATUS",
  "assert_status": "$STATUS",
  "exit_code": $EXIT_CODE,
  "capture_started_after_app_opened": $CAPTURE_STARTED,
  "screenrecord_started": $SCREENRECORD_STARTED,
  "recording_started_ms": "$RECORDING_STARTED_MS",
  "video_local": "$VIDEO_LOCAL",
  "has_mutation": $MUTATION_DETECTED,
  "mutation_type": "$MUTATION_TYPE",
  "mutation_location_csv": "$OUT/mutation_location.csv",
  "mutation_json": "$OUT/mutation.json",
  "mutation_intervals_csv": "$MUTATION_INTERVALS_CSV",
  "mutation_frame_intervals_csv": "$MUTATION_FRAME_INTERVALS_CSV",
  "frames_mutation_csv": "$FRAMES_MUTATION_CSV",
  "frames_mutation_jsonl": "$FRAMES_MUTATION_JSONL",
  "frame_labels_source": "visual_marker",
  "mutation_bounds": $MUTATION_BOUNDS_JSON,
  "mutation_bounds_csv": "$MUTATION_BOUNDS",
  "mutation_screen": "$MUTATION_SCREEN",
  "extract_fps": $EXTRACT_FPS,
  "num_frames": $NUM_FRAMES
}
EOF

      echo "Finished: $TEST_NAME | $VERSION | $RUN_ID | assert_status=$STATUS | frames=$NUM_FRAMES"
    done
  done
done
