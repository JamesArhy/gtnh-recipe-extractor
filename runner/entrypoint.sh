#!/usr/bin/env bash
set -euo pipefail

: "${GTNH_SERVER_ZIP_URL:?Set GTNH_SERVER_ZIP_URL to a direct GTNH server zip URL}"
: "${DUMPER_JAR_PATH:=/dumper/RecipeDumper.jar}"

OUT_DIR="${OUT_DIR:-/work/out}"
JAVA_XMS="${JAVA_XMS:-2G}"
JAVA_XMX="${JAVA_XMX:-6G}"
DUMP_PATH_REL="${DUMP_PATH_REL:-config/recipedumper/recipes.json}"
DUMP_MACHINE_INDEX_REL="${DUMP_MACHINE_INDEX_REL:-config/recipedumper/machine_index.json}"
DUMP_MACHINE_INDEX_DEBUG_REL="${DUMP_MACHINE_INDEX_DEBUG_REL:-config/recipedumper/machine_index_debug.json}"
DUMP_MACHINE_INDEX_REQUIRED="${DUMP_MACHINE_INDEX_REQUIRED:-1}"
DUMP_TIMEOUT_SEC="${DUMP_TIMEOUT_SEC:-2400}"     # 40 min
FORCE_KILL_AFTER_SEC="${FORCE_KILL_AFTER_SEC:-60}"

# ---- Paths / caching ----
SERVER_DIR="${SERVER_DIR:-/work/server}"          # extracted server
CACHE_DIR="${CACHE_DIR:-/work/cache}"             # cached downloads
mkdir -p "$SERVER_DIR" "$CACHE_DIR" "$OUT_DIR"

# Cache zip keyed by URL so different versions don't collide
URL_SHA="$(printf '%s' "$GTNH_SERVER_ZIP_URL" | sha256sum | awk '{print $1}')"
SERVER_ZIP="${SERVER_ZIP:-$CACHE_DIR/server-${URL_SHA}.zip}"

echo "==> Using cached zip: $SERVER_ZIP"

# ---- Download (only if missing) ----
if [ -f "$SERVER_ZIP" ]; then
  echo "==> Zip already cached; skipping download."
else
  echo "==> Downloading GTNH server zip (first time)..."
  TMP_ZIP="${SERVER_ZIP}.tmp"
  curl -L --fail --retry 3 --retry-delay 2 -o "$TMP_ZIP" "$GTNH_SERVER_ZIP_URL"
  mv -f "$TMP_ZIP" "$SERVER_ZIP"
fi

# Optional: verify it’s a zip (catches HTML/CDN error pages)
if ! file "$SERVER_ZIP" | grep -qi 'zip archive'; then
  echo "ERROR: cached file is not a zip: $SERVER_ZIP"
  head -c 200 "$SERVER_ZIP" || true
  exit 3
fi

# ---- Unpack fresh each run (but don’t delete the cached zip) ----
echo "==> Unzipping server..."
rm -rf "$SERVER_DIR"/*
unzip -oq "$SERVER_ZIP" -d "$SERVER_DIR"

cd "$SERVER_DIR"

echo "==> Server dir contents:"
ls -la

# Normalize if zip unpacks into a single folder
TOP_COUNT="$(find . -maxdepth 1 -type d -not -name '.' | wc -l | tr -d ' ')"
if [ "$TOP_COUNT" = "1" ]; then
  ONE_DIR="$(find . -maxdepth 1 -type d -not -name '.' | head -n1)"
  shopt -s dotglob
  mv "$ONE_DIR"/* .
  rmdir "$ONE_DIR" || true
  shopt -u dotglob
fi

mkdir -p mods config/recipedumper

echo "==> Copying dumper mod into mods/ ..."
cp "$DUMPER_JAR_PATH" mods/

# EULA
if [ ! -f eula.txt ]; then
  echo "eula=true" > eula.txt
else
  sed -i 's/eula=false/eula=true/g' eula.txt || true
  grep -q 'eula=true' eula.txt || echo "eula=true" >> eula.txt
fi

DUMP_ABS="$SERVER_DIR/$DUMP_PATH_REL"
MACHINE_INDEX_ABS="$SERVER_DIR/$DUMP_MACHINE_INDEX_REL"
MACHINE_INDEX_DEBUG_ABS="$SERVER_DIR/$DUMP_MACHINE_INDEX_DEBUG_REL"
OUT_ABS="$OUT_DIR/recipes.json"
OUT_MACHINE_INDEX_ABS="$OUT_DIR/machine_index.json"
OUT_MACHINE_INDEX_DEBUG_ABS="$OUT_DIR/machine_index_debug.json"

if [ -f "$DUMP_ABS" ] && [ -f "$MACHINE_INDEX_ABS" ] && [ -f "$MACHINE_INDEX_DEBUG_ABS" ]; then
  echo "==> Found existing dump; copying to out/ and exiting."
  cp "$DUMP_ABS" "$OUT_ABS"
  cp "$MACHINE_INDEX_ABS" "$OUT_MACHINE_INDEX_ABS"
  cp "$MACHINE_INDEX_DEBUG_ABS" "$OUT_MACHINE_INDEX_DEBUG_ABS"
  exit 0
fi

SERVER_CMD=""
RFB_LOADER="-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader"
if ls *forge*.jar >/dev/null 2>&1; then
  JAR="$(ls -S *forge*.jar | head -n1)"
  SERVER_CMD="java ${RFB_LOADER} -Xms${JAVA_XMS} -Xmx${JAVA_XMX} -jar ${JAR} nogui"
elif ls *.jar >/dev/null 2>&1; then
  JAR="$(ls -S *.jar | head -n1)"
  SERVER_CMD="java ${RFB_LOADER} -Xms${JAVA_XMS} -Xmx${JAVA_XMX} -jar ${JAR} nogui"
else
  echo "ERROR: couldn't find a server jar to launch directly."
  ls -la
  exit 2
fi

echo "==> Starting server: $SERVER_CMD"
set +e
bash -lc "$SERVER_CMD" &
SERVER_PID=$!
set -e

START_TS=$(date +%s)
echo "==> Server PID: $SERVER_PID"
echo "==> Waiting for dump: $DUMP_ABS"

while true; do
  if [ -f "$DUMP_ABS" ]; then
    if [ "$DUMP_MACHINE_INDEX_REQUIRED" = "0" ] || [ -f "$MACHINE_INDEX_ABS" ]; then
      echo "==> Dump generated!"
      break
    fi
  fi

  if ! kill -0 "$SERVER_PID" >/dev/null 2>&1; then
    echo "ERROR: server exited before dump appeared."
    break
  fi

  NOW=$(date +%s)
  if [ $((NOW - START_TS)) -gt "$DUMP_TIMEOUT_SEC" ]; then
    echo "ERROR: timed out waiting for dump after ${DUMP_TIMEOUT_SEC}s"
    break
  fi

  sleep 2
done

if [ -f "$DUMP_ABS" ]; then
  cp "$DUMP_ABS" "$OUT_ABS"
  echo "==> Copied dump to $OUT_ABS"
  if [ -f "$MACHINE_INDEX_ABS" ]; then
    cp "$MACHINE_INDEX_ABS" "$OUT_MACHINE_INDEX_ABS"
    echo "==> Copied machine index to $OUT_MACHINE_INDEX_ABS"
  fi
  if [ -f "$MACHINE_INDEX_DEBUG_ABS" ]; then
    cp "$MACHINE_INDEX_DEBUG_ABS" "$OUT_MACHINE_INDEX_DEBUG_ABS"
    echo "==> Copied machine index debug to $OUT_MACHINE_INDEX_DEBUG_ABS"
  fi

  echo "==> Converting raw dump to Parquet..."
  RAW_JSON_PATH="$DUMP_ABS" MACHINE_INDEX_JSON_PATH="$MACHINE_INDEX_ABS" PARQUET_OUT_DIR="$OUT_DIR/parquet" python /convert_to_parquet.py

  # Optional: remove raw json from out to keep artifacts lean
  #rm -f "$OUT_ABS" || true
else
  echo "==> Dump missing. Copying logs for debugging..."
  find . -maxdepth 3 -type f \( -name "latest.log" -o -name "*.log" \) -print0 \
    | xargs -0 -I{} cp "{}" "$OUT_DIR/" || true
fi

echo "==> Stopping server..."
kill -TERM "$SERVER_PID" >/dev/null 2>&1 || true

STOP_TS=$(date +%s)
while kill -0 "$SERVER_PID" >/dev/null 2>&1; do
  NOW=$(date +%s)
  if [ $((NOW - STOP_TS)) -gt "$FORCE_KILL_AFTER_SEC" ]; then
    echo "==> Forcing server kill..."
    kill -KILL "$SERVER_PID" >/dev/null 2>&1 || true
    break
  fi
  sleep 1
done

echo "==> Done."
