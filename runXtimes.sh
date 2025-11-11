#!/bin/bash

# Number of times to run the curl command
# take from command-line arg
# run 0th time to see the request/reply
# NUM_REQUESTS=10000
# NUM_REQUESTS=10

NUM_REQUESTS="$1"

if (( $# != 1)); then
    echo "Usage: runXtimes.sh numRequests"
    exit
fi

# pull in API key from env variable
API_KEY=$OPENATHENA_API_KEY

JSON_FILE="image.json"
URL="http://localhost:8000/api/v1/openathena/locationsimple?apikey=$API_KEY"

CURL_CMD_NOOUT="curl -s -X POST -H 'Content-Type: application/json' -d @${JSON_FILE} '$URL' >& /dev/null "
CURL_CMD_OUTPUT="curl -s -X POST -H 'Content-Type: application/json' -d @${JSON_FILE} '$URL' "

echo "Curl command is $CURL_CMD"

output=$(eval $CURL_CMD_OUTPUT)
echo $output

# Start time
START_TIME=$(date +%s)

# Run curl command multiple times
for ((i=1; i<=NUM_REQUESTS; i++))
do
    output=$(eval $CURL_CMD_NOOUT)
    # echo $output
done

# End time
END_TIME=$(date +%s)

# Calculate duration
DURATION=$((END_TIME - START_TIME))

# Print duration
echo "Time taken for $NUM_REQUESTS requests: $DURATION seconds"
