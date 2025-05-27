#!/bin/bash
# test client using parallel number of threads

# get args

if [[ $# -ne 5 ]]; then
  echo "Usage: $0 <json-file> <host> <port> <clients> <number-iterations>"
  exit 1
fi

JSON_FILE=$1
HOST=$2
PORT=$3
CLIENTS=$4
ITERATIONS=$5

# Validate dtm-type
# case "$DTM_TYPE" in
#   cop30|eudtm|3dep|srtm|maritime|mixed) ;;
#   *)
#     echo "Invalid dtm-type: $DTM_TYPE. Valid types: cop30, eudtm, 3dep, srtm, maritime, mixed" >&2
#     exit 1
#     ;;
# esac

# assume DEMs are already downloaded and cache is refreshed and current?

# run the geolocate script one time to get image cached and
# to make sure DEM is downloaded and in cache
STATUS=$("./geolocate.sh" "$JSON_FILE" "$HOST" "$PORT" > /dev/null 2>/dev/null)
RC=$?
if [[ $RC -ne 0 ]]; then
  echo "ERROR: test call failed (exit code $RC)" >&2
  exit $RC
fi
echo "Test call succeeded; starting iterations"

# get total API posts first
BEFORE_POSTS=$(./getTotalPosts.sh "$HOST" "$PORT")

# Compute total requests across all clients
TOTAL_REQUESTS=$(( ITERATIONS * CLIENTS ))

echo "$JSON_FILE clients: $CLIENTS clients, $ITERATIONS iterations/client, total: $TOTAL_REQUESTS" 

# record overall start time (ms since epoch)
START=$(date +%s%3N)

# launch each client in background
declare -a pids=()
for client_id in $(seq 1 "$CLIENTS"); do
  (
    for i in $(seq 1 "$ITERATIONS"); do
      # call geolocate.sh, hide all its output
      ./geolocate.sh "$JSON_FILE" "$HOST" "$PORT" >/dev/null 2>&1
      rc=$?
      if [[ $rc -ne 0 ]]; then
        echo "Client $client_id: iteration $i failed (exit code $rc)" >&2
        exit $rc
      fi
    done
  ) &

  pid=$!
  pids+=( "$pid" )
  
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
echo "total_requests: $TOTAL_REQUESTS"
echo "total_elapsed: $TOTAL_ELAPSED ms"
# average per request
AVG=$(echo "scale=2; $TOTAL_ELAPSED / $TOTAL_REQUESTS" | bc)
echo "Average per request: $AVG ms"
echo "Number posts is $BEFORE_POSTS $AFTER_POSTS $NUMBER_POSTS"

if [[ $exit_code -ne 0 ]]; then
  echo "One or more clients failed. Exit code: $exit_code" >&2
fi

exit $exit_code
