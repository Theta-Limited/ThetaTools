#!/bin/bash

# Number of times to run the curl command
# take from command-line arg
# run 0th time to see the request/reply
# NUM_REQUESTS=10000
# NUM_REQUESTS=10

NUM_REQUESTS="$1"

if (( $# != 1)); then
    echo "Usage: runXtimesPersistent.sh numRequests"
    exit
fi

# pull in API key from env variable
API_KEY=$OPENATHENA_API_KEY

JSON_FILE="image.json"
URL="http://localhost:8000/api/v1/openathena/locationsimple?apikey=$API_KEY"

CURL_CMD_OUTPUT="curl -s -X POST -H 'Content-Type: application/json' -d @${JSON_FILE} '$URL' "

echo "Curl command is $CURL_CMD"

output=$(eval $CURL_CMD_OUTPUT)
echo $output

CFG_FILE=$(mktemp)

echo "Config file is $CFG_FILE"

cat > "$CFG_FILE" <<EOF
silent
show-error

# HTTP method: POST
request = "POST"

# Headers
header = "Content-Type: application/json"
# comment/remove this if no auth header:
header = "Authorization: Bearer $API_KEY"

# Body: read from the JSON_FILE each time
data-binary = "@$JSON_FILE"
EOF

# Add URL once per request
for ((i=1; i<=NUM_REQUESTS; i++)); do
    echo "url = \"$URL\"" >> "$CFG_FILE"
done

START_TIME=$(date +%s)

# For debugging, temporarily add -v to see request/response
curl --config "$CFG_FILE" > /dev/null
# rl --config "$CFG_FILE"

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

rm -f "$CFG_FILE"

echo "Time taken for $NUM_REQUESTS POSTs: $DURATION seconds"
