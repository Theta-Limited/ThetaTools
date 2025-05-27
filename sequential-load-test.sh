#!/bin/bash
# test client that is single threaded

# get args

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <json-file> <host> <port> <iterations>"
  exit 1
fi

JSON_FILE=$1
HOST=$2
PORT=$3
ITERATIONS=$4

# run the geolocate script one time to get image cached and
# to make sure DEM is downloaded and in cache
STATUS=$("./geolocate.sh" "$JSON_FILE" "$HOST" "$PORT" > /dev/null 2>/dev/null)
RC=$?
if [[ $RC -ne 0 ]]; then
  echo "ERROR: test call failed (exit code $RC)" >&2
  exit $RC
fi
echo "Test call completed; starting iterations"

# record start time in milliseconds
START=$(date +%s%3N)

for i in $(seq 1 "$ITERATIONS"); do

  # invoke your curl-wrapper; it must output only the HTTP status code
  STATUS=$("./geolocate.sh" "$JSON_FILE" "$HOST" "$PORT" > /dev/null 2>/dev/null)
  RC=$?
  if [[ $RC -ne 0 ]]; then
    echo "ERROR: iteration $i failed (exit code $RC)" >&2
    exit $RC
  fi 

  # emit CSV line
  # echo "$i,$STATUS,$ELAPSED"
done

# record end time & compute elapsed
END=$(date +%s%3N)
TOTAL_ELAPSED=$((END - START))
AVG=$(echo "scale=2; $TOTAL_ELAPSED / $ITERATIONS" | bc)

echo "Total elapsed: $TOTAL_ELAPSED ms"
echo "Avg time: $AVG ms"

