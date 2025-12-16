#!/usr/bin/env bash
set -euo pipefail

: "${GTNH_SERVER_ZIP_URL:?Set GTNH_SERVER_ZIP_URL to a direct GTNH server zip URL}"
: "${DUMPER_JAR_PATH:=/dumper/RecipeDumper.jar}"

SERVER_DIR="${SERVER_DIR:-/work/server}"
OUT_DIR="${OUT_DIR:-/work/out}"
JAVA_XMS="${JAVA_XMS:-2G}"
JAVA_XMX="${JAVA_XMX:-6G}"
DUMP_PATH_REL="${DUMP_PATH_REL:-config/recipedumper/recipes.json}"
DUMP_TIMEOUT_SEC="${DUMP_TIMEOUT_SEC:-2400}"     # 40 min
FORCE_KILL_AFTER_SEC="${FORCE_KILL_AFTER_SEC:-60}"

mkdir -p "$SERVER_DIR" "$OUT_DIR"
cd "$SERVER_DIR"

echo "==> Downloading GTNH server zip..."
curl -L --fail -o server.zip "$GTNH_SERVER_ZIP_URL"

echo "==> Unzipping server..."
unzip -q server.zip
rm -f server.zip

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
OUT_ABS="$OUT_DIR/recipes.json"

if [ -f "$DUMP_ABS" ]; then
  echo "==> Found existing dump; copying to out/ and exiting."
  cp "$DUMP_ABS" "$OUT_ABS"
  exit 0
fi

# Pick a launch command
SERVER_CMD=""
if [ -f "./startserver.sh" ]; then
  chmod +x ./startserver.sh
  SERVER_CMD="./startserver.sh"
elif [ -f "./ServerStart.sh" ]; then
  chmod +x ./ServerStart.sh
  SERVER_CMD="./ServerStart.sh"
elif ls *forge*.jar >/dev/null 2>&1; then
  JAR="$(ls -S *forge*.jar | head -n1)"
  SERVER_CMD="java -Xms${JAVA_XMS} -Xmx${JAVA_XMX} -jar ${JAR} nogui"
elif ls *.jar >/dev/null 2>&1; then
  JAR="$(ls -S *.jar | head -n1)"
  SERVER_CMD="java -Xms${JAVA_XMS} -Xmx${JAVA_XMX} -jar ${JAR} nogui"
else
  echo "ERROR: couldn't find start script or jar"
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
    echo "==> Dump generated!"
    break
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

  echo "==> Converting raw dump to Parquet..."
  RAW_JSON_PATH="$DUMP_ABS" PARQUET_OUT_DIR="$OUT_DIR/parquet" python3 /convert_to_parquet.py

  # Optional: remove raw json from out to keep artifacts lean
  rm -f "$OUT_ABS" || true
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
