#!/bin/bash
# test client using parallel number of threads

# get args
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <host> <port> <clients> <number-iterations>"
  exit 1
fi

HOST=$1
PORT=$2
CLIENTS=$3
ITERATIONS=$4

# Validate dtm-type
# case "$DTM_TYPE" in
#   cop30|eudtm|3dep|srtm|maritime|mixed) ;;
#   *)
#     echo "Invalid dtm-type: $DTM_TYPE. Valid types: cop30, eudtm, 3dep, srtm, maritime, mixed" >&2
#     exit 1
#     ;;
# esac

JSON_FILES=(
    image-3dep.json
    image-cop30.json
    image-eudtm.json
    image-maritime.json
    image-srtm.json
)
NUM_JSON=${#JSON_FILES[@]}

# run the geolocate script one time to get image cached and
# to make sure DEM is downloaded and in cache
for jf in "${JSON_FILES[@]}"; do
  echo "=== Testing image $jf ==="

  # run one lookup; let stdout/stderr show so you can see errors or responses
  OUTPUT=$(./geolocate.sh "$jf" "$HOST" "$PORT")
  RC=$?

  if (( RC != 0 )); then
    echo "Payload $jf failed with exit code $RC" >&2
    exit $RC
  fi

  echo "Response: $OUTPUT"
done

echo "Tests succeeded; starting iterations"

# get total API posts first
BEFORE_POSTS=$(./getTotalPosts.sh "$HOST" "$PORT")

# Compute total requests across all clients
TOTAL_REQUESTS=$(( ITERATIONS * CLIENTS ))

echo "Testing $CLIENTS clients, $ITERATIONS iterations/client, total: $TOTAL_REQUESTS"

for (( client=1; client<=CLIENTS; client++ )); do
    jf=${JSON_FILES[$(( (client-1) % NUM_JSON ))]}
    echo "Client $client $ITERATIONS using file $jf"
done


# record overall start time (ms since epoch)
START=$(date +%s%3N)

# launch each client in background
declare -a pids=()
for (( client=1; client<=CLIENTS; client++ )); do
  # pick JSON by round-robin
  jf=${JSON_FILES[$(( (client-1) % NUM_JSON ))]}

  (
    for (( i=1; i<=ITERATIONS; i++ )); do
      ./geolocate.sh "$jf" "$HOST" "$PORT" >/dev/null 2>&1
      rc=$?
      if (( rc != 0 )); then
        echo "Client $client, iter $i failed (exit $rc, file=$jf)" >&2
        exit $rc
      fi
    done
  ) &
  pids+=( "$!" )
done

# wait on all clients, capture any non-zero exit
exit_code=0
for pid in "${pids[@]}"; do
  wait "$pid" || exit_code=$?
done

# record overall end time and compute total elapsed

END=$(date +%s%3N)
TOTAL_ELAPSED=$(( END - START ))

AFTER_POSTS=$(./getTotalPosts.sh "$HOST" "$PORT")
NUMBER_POSTS=$(( AFTER_POSTS - BEFORE_POSTS ))

# report results
echo "Total requests: $TOTAL_REQUESTS"
echo "Total elapsed: $TOTAL_ELAPSED ms"
# average per request
AVG=$(echo "scale=2; $TOTAL_ELAPSED / $TOTAL_REQUESTS" | bc)
echo "Average per request: $AVG ms"
echo "Number posts before $BEFORE_POSTS, after $AFTER_POSTS, total $NUMBER_POSTS"
echo
echo


if [[ $exit_code -ne 0 ]]; then
  echo "One or more clients failed. Exit code: $exit_code" >&2
fi

exit $exit_code
