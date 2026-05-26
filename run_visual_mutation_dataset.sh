#!/usr/bin/env bash
set -u

APP="AntennaPod"
RUNS=1

APP_PACKAGE="de.danoeh.antennapod.debug"

EXTRACT_FPS=15
MUTATION_LABEL_TAIL_FRAMES=1

GRADLE_TASK=":app:connectedPlayDebugAndroidTest"

OPERATORS=(
  "COMPONENT_OCCLUSION"
  "TEXT_TRUNCATION"
  "TEXT_OVERLAP"
  "COMPONENT_SWAP"
  "FONT_SCALE_CHANGE"
  "ICON_DELETION"
  "COMPONENT_RESIZE"
  "LOW_CONTRAST_TEXT"
  "PADDING_SHIFT"
  "TEXT_COLOR_CHANGE"
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

pull_mutation_masks() {
  MASKS_TMP="$MUTATION_MASKS_JSONL.tmp"
  adb exec-out run-as "$APP_PACKAGE" cat files/visual_mutation_masks.jsonl > "$MASKS_TMP" 2>/dev/null || true
  if [ -s "$MASKS_TMP" ]; then
    mv "$MASKS_TMP" "$MUTATION_MASKS_JSONL"
  else
    rm -f "$MASKS_TMP"
  fi
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
      MUTATION_MASKS_JSONL="$OUT/mutation_masks.jsonl"

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
      RECORDING_STARTED_DEVICE_MS=""
      LAST_MASK_PULL_MS=0

      while kill -0 "$TEST_PID" 2>/dev/null; do
        FOCUS=$(get_current_focus)
        TIMESTAMP=$(date +%s.%N)
        NOW_MS=$(date +%s%3N)

        if [ "$CAPTURE_STARTED" = false ]; then
          if is_app_foreground "$FOCUS"; then
            CAPTURE_STARTED=true
            SCREENRECORD_STARTED=true

            echo "$TIMESTAMP,app_opened_start_screenrecord,\"$FOCUS\",app_foreground" >> "$OUT/events.csv"
            echo "App opened. Starting screenrecord for $TEST_NAME | $VERSION | $RUN_ID"

            RECORDING_STARTED_MS=$(date +%s%3N)
            RECORDING_STARTED_DEVICE_MS=$(adb shell date +%s%3N 2>/dev/null | tr -d '\r' || true)
            if [ -z "$RECORDING_STARTED_DEVICE_MS" ]; then
              RECORDING_STARTED_DEVICE_MS="$RECORDING_STARTED_MS"
            fi
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

        if [ "$CAPTURE_STARTED" = true ] && [ $((NOW_MS - LAST_MASK_PULL_MS)) -ge 1000 ]; then
          pull_mutation_masks
          LAST_MASK_PULL_MS="$NOW_MS"
        fi

        sleep 0.05
      done

      pull_mutation_masks

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

      if [ ! -f "$MUTATION_MASKS_JSONL" ]; then
        : > "$MUTATION_MASKS_JSONL"
      fi

      kill "$LOGCAT_PID" 2>/dev/null || true
      wait "$LOGCAT_PID" 2>/dev/null || true

      MUTATION_LOG_LINE=$(grep "visual_mutation_visible operator=" "$OUT/logcat.txt" | head -n 1 || true)
      MUTATION_BOUNDS=$(echo "$MUTATION_LOG_LINE" | sed -n 's/.* bounds=\([^ ]*\).*/\1/p')
      MUTATION_SCREEN=$(echo "$MUTATION_LOG_LINE" | sed -n 's/.* screen=\([^ ]*\).*/\1/p')
      MUTATION_LEFT=""
      MUTATION_TOP=""
      MUTATION_RIGHT=""
      MUTATION_BOTTOM=""
      MUTATION_DETECTED=false
      MUTATION_TYPE=""
      MUTATION_BOUNDS_JSON=null

      if [ -n "$MUTATION_BOUNDS" ]; then
        IFS=',' read -r MUTATION_LEFT MUTATION_TOP MUTATION_RIGHT MUTATION_BOTTOM <<< "$MUTATION_BOUNDS"
        MUTATION_DETECTED=true
        MUTATION_TYPE="$OPERATOR"
        MUTATION_BOUNDS_JSON="{\"left\":$MUTATION_LEFT,\"top\":$MUTATION_TOP,\"right\":$MUTATION_RIGHT,\"bottom\":$MUTATION_BOTTOM}"
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
  "mutation_masks_jsonl": "$MUTATION_MASKS_JSONL",
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

      echo "frame_file,frame_index,frame_time_ms,has_mutation,mutation_type,mask_id,bounds_left,bounds_top,bounds_right,bounds_bottom,screen" > "$FRAMES_MUTATION_CSV"
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

        declare -A MASK_TYPES=()
        declare -A MASK_LEFTS=()
        declare -A MASK_TOPS=()
        declare -A MASK_RIGHTS=()
        declare -A MASK_BOTTOMS=()
        declare -A MASK_SCREENS=()

        while IFS= read -r MASK_LINE; do
          MASK_ID=$(echo "$MASK_LINE" | sed -n 's/.*"mask_id":\([0-9]*\).*/\1/p')
          if [ -z "$MASK_ID" ]; then
            continue
          fi
          MASK_TYPES[$MASK_ID]=$(echo "$MASK_LINE" | sed -n 's/.*"mutation_type":"\([^"]*\)".*/\1/p')
          MASK_LEFTS[$MASK_ID]=$(echo "$MASK_LINE" | sed -n 's/.*"left":\([0-9]*\).*/\1/p')
          MASK_TOPS[$MASK_ID]=$(echo "$MASK_LINE" | sed -n 's/.*"top":\([0-9]*\).*/\1/p')
          MASK_RIGHTS[$MASK_ID]=$(echo "$MASK_LINE" | sed -n 's/.*"right":\([0-9]*\).*/\1/p')
          MASK_BOTTOMS[$MASK_ID]=$(echo "$MASK_LINE" | sed -n 's/.*"bottom":\([0-9]*\).*/\1/p')
          MASK_SCREENS[$MASK_ID]=$(echo "$MASK_LINE" | sed -n 's/.*"screen":\[\([^]]*\)\].*/\1/p' | tr ',' 'x')
        done < "$MUTATION_MASKS_JSONL"

        LOG_MASK_IDS=()
        LOG_WALL_MS=()
        LOG_TYPES=()
        LOG_LEFTS=()
        LOG_TOPS=()
        LOG_RIGHTS=()
        LOG_BOTTOMS=()
        LOG_SCREENS=()

        while IFS= read -r LINE; do
          LOG_MASK_ID=$(echo "$LINE" | sed -n 's/.* mask_id=\([0-9]*\).*/\1/p')
          LOG_WALL=$(echo "$LINE" | sed -n 's/.* wall_ms=\([0-9]*\).*/\1/p')
          LOG_TYPE=$(echo "$LINE" | sed -n 's/.* operator=\([^ ]*\).*/\1/p')
          LOG_BOUNDS=$(echo "$LINE" | sed -n 's/.* bounds=\([^ ]*\).*/\1/p')
          LOG_SCREEN=$(echo "$LINE" | sed -n 's/.* screen=\([^ ]*\).*/\1/p')

          if [ -z "$LOG_MASK_ID" ] || [ -z "$LOG_WALL" ]; then
            continue
          fi

          LOG_LEFT=""
          LOG_TOP=""
          LOG_RIGHT=""
          LOG_BOTTOM=""
          if [ -n "$LOG_BOUNDS" ]; then
            IFS=',' read -r LOG_LEFT LOG_TOP LOG_RIGHT LOG_BOTTOM <<< "$LOG_BOUNDS"
          fi

          LOG_MASK_IDS+=("$LOG_MASK_ID")
          LOG_WALL_MS+=("$LOG_WALL")
          LOG_TYPES+=("$LOG_TYPE")
          LOG_LEFTS+=("$LOG_LEFT")
          LOG_TOPS+=("$LOG_TOP")
          LOG_RIGHTS+=("$LOG_RIGHT")
          LOG_BOTTOMS+=("$LOG_BOTTOM")
          LOG_SCREENS+=("$LOG_SCREEN")
        done < <(grep "visual_mutation_frame operator=" "$OUT/logcat.txt" || true)

        FRAME_SEGMENT_INDEX=-1
        PREVIOUS_FRAME_HAS_MUTATION=false
        PREVIOUS_FRAME_NAME=""
        PREVIOUS_FRAME_INDEX=""
        MUTATION_TAIL_REMAINING=0
        LAST_MUTATION_TYPE=""
        LAST_MUTATION_LEFT=""
        LAST_MUTATION_TOP=""
        LAST_MUTATION_RIGHT=""
        LAST_MUTATION_BOTTOM=""
        LAST_MUTATION_SCREEN=""
        LAST_MUTATION_BOUNDS_JSON=null
        LAST_MASK_ID=""
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
          FRAME_MASK_ID=""

          FRAME_WALL_MS=$(( RECORDING_STARTED_DEVICE_MS + FRAME_TIME_MS ))
          BEST_LOG_INDEX=-1
          BEST_LOG_DELTA=999999999

          for LOG_INDEX in "${!LOG_WALL_MS[@]}"; do
            DELTA=$(( FRAME_WALL_MS - LOG_WALL_MS[$LOG_INDEX] ))
            if [ "$DELTA" -lt 0 ]; then
              DELTA=$(( -DELTA ))
            fi
            if [ "$DELTA" -lt "$BEST_LOG_DELTA" ]; then
              BEST_LOG_DELTA="$DELTA"
              BEST_LOG_INDEX="$LOG_INDEX"
            fi
          done

          if [ "$BEST_LOG_INDEX" -ge 0 ] && [ "$BEST_LOG_DELTA" -le 180 ]; then
            FRAME_MASK_ID="${LOG_MASK_IDS[$BEST_LOG_INDEX]}"
          fi

          FRAME_IS_TAIL=false
          if [ -n "$FRAME_MASK_ID" ]; then
            FRAME_HAS_MUTATION=true
          elif [ "$MUTATION_TAIL_REMAINING" -gt 0 ]; then
            FRAME_HAS_MUTATION=true
            FRAME_IS_TAIL=true
            MUTATION_TAIL_REMAINING=$((MUTATION_TAIL_REMAINING - 1))
          fi

          if [ "$FRAME_HAS_MUTATION" = true ]; then

            if [ "$PREVIOUS_FRAME_HAS_MUTATION" = false ]; then
              FRAME_SEGMENT_INDEX=$((FRAME_SEGMENT_INDEX + 1))
              CURRENT_SEGMENT_START_FRAME="$FRAME_NAME"
              CURRENT_SEGMENT_START_INDEX="$FRAME_INDEX"
            fi

            if [ "$FRAME_IS_TAIL" = true ]; then
              FRAME_MASK_ID="$LAST_MASK_ID"
              FRAME_MUTATION_TYPE="$LAST_MUTATION_TYPE"
              FRAME_MUTATION_LEFT="$LAST_MUTATION_LEFT"
              FRAME_MUTATION_TOP="$LAST_MUTATION_TOP"
              FRAME_MUTATION_RIGHT="$LAST_MUTATION_RIGHT"
              FRAME_MUTATION_BOTTOM="$LAST_MUTATION_BOTTOM"
              FRAME_MUTATION_SCREEN="$LAST_MUTATION_SCREEN"
              FRAME_MUTATION_BOUNDS_JSON="$LAST_MUTATION_BOUNDS_JSON"
            elif [ -n "${MASK_TYPES[$FRAME_MASK_ID]:-}" ]; then
              FRAME_MUTATION_TYPE="${MASK_TYPES[$FRAME_MASK_ID]}"
              FRAME_MUTATION_LEFT="${MASK_LEFTS[$FRAME_MASK_ID]}"
              FRAME_MUTATION_TOP="${MASK_TOPS[$FRAME_MASK_ID]}"
              FRAME_MUTATION_RIGHT="${MASK_RIGHTS[$FRAME_MASK_ID]}"
              FRAME_MUTATION_BOTTOM="${MASK_BOTTOMS[$FRAME_MASK_ID]}"
              FRAME_MUTATION_SCREEN="${MASK_SCREENS[$FRAME_MASK_ID]}"
            else
              FRAME_MUTATION_TYPE="${LOG_TYPES[$BEST_LOG_INDEX]}"
              FRAME_MUTATION_LEFT="${LOG_LEFTS[$BEST_LOG_INDEX]}"
              FRAME_MUTATION_TOP="${LOG_TOPS[$BEST_LOG_INDEX]}"
              FRAME_MUTATION_RIGHT="${LOG_RIGHTS[$BEST_LOG_INDEX]}"
              FRAME_MUTATION_BOTTOM="${LOG_BOTTOMS[$BEST_LOG_INDEX]}"
              FRAME_MUTATION_SCREEN="${LOG_SCREENS[$BEST_LOG_INDEX]}"
            fi

            if [ -n "$FRAME_MUTATION_LEFT" ]; then
              FRAME_MUTATION_BOUNDS_JSON="{\"left\":$FRAME_MUTATION_LEFT,\"top\":$FRAME_MUTATION_TOP,\"right\":$FRAME_MUTATION_RIGHT,\"bottom\":$FRAME_MUTATION_BOTTOM}"
            fi

            if [ "$FRAME_IS_TAIL" = false ]; then
              MUTATION_TAIL_REMAINING="$MUTATION_LABEL_TAIL_FRAMES"
              LAST_MASK_ID="$FRAME_MASK_ID"
              LAST_MUTATION_TYPE="$FRAME_MUTATION_TYPE"
              LAST_MUTATION_LEFT="$FRAME_MUTATION_LEFT"
              LAST_MUTATION_TOP="$FRAME_MUTATION_TOP"
              LAST_MUTATION_RIGHT="$FRAME_MUTATION_RIGHT"
              LAST_MUTATION_BOTTOM="$FRAME_MUTATION_BOTTOM"
              LAST_MUTATION_SCREEN="$FRAME_MUTATION_SCREEN"
              LAST_MUTATION_BOUNDS_JSON="$FRAME_MUTATION_BOUNDS_JSON"
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

          echo "$FRAME_NAME,$FRAME_INDEX,$FRAME_TIME_MS,$FRAME_HAS_MUTATION,$FRAME_MUTATION_TYPE,$FRAME_MASK_ID,$FRAME_MUTATION_LEFT,$FRAME_MUTATION_TOP,$FRAME_MUTATION_RIGHT,$FRAME_MUTATION_BOTTOM,$FRAME_MUTATION_SCREEN" >> "$FRAMES_MUTATION_CSV"
          echo "{\"frame_file\":\"$FRAME_NAME\",\"frame_index\":$FRAME_NUMBER,\"frame_time_ms\":$FRAME_TIME_MS,\"has_mutation\":$FRAME_HAS_MUTATION,\"mutation_type\":\"$FRAME_MUTATION_TYPE\",\"mutation_bounds\":$FRAME_MUTATION_BOUNDS_JSON,\"mutation_screen\":\"$FRAME_MUTATION_SCREEN\",\"mask_id\":\"$FRAME_MASK_ID\",\"mask_rle_ref\":\"$MUTATION_MASKS_JSONL\"}" >> "$FRAMES_MUTATION_JSONL"

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
  "recording_started_device_ms": "$RECORDING_STARTED_DEVICE_MS",
  "video_local": "$VIDEO_LOCAL",
  "has_mutation": $MUTATION_DETECTED,
  "mutation_type": "$MUTATION_TYPE",
  "mutation_location_csv": "$OUT/mutation_location.csv",
  "mutation_json": "$OUT/mutation.json",
  "mutation_masks_jsonl": "$MUTATION_MASKS_JSONL",
  "mutation_intervals_csv": "$MUTATION_INTERVALS_CSV",
  "mutation_frame_intervals_csv": "$MUTATION_FRAME_INTERVALS_CSV",
  "frames_mutation_csv": "$FRAMES_MUTATION_CSV",
  "frames_mutation_jsonl": "$FRAMES_MUTATION_JSONL",
  "frame_labels_source": "logcat_wall_time_mask_id",
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
