#!/bin/bash

# Number of times to run the curl command
NUM_REQUESTS=10
# NUM_REQUESTS=100000

# pull in API key from env variable
API_KEY=$OPENATHENA_API_KEY

JSON_FILE="image.json"
JSON_FILE1="image1.json"
JSON_FILE2="image2.json"
URL="http://localhost:8000/api/v1/openathena/locationsimple?apikey=$API_KEY"

CURL_CMD="curl -s -X POST -H 'Content-Type: application/json' -d @${JSON_FILE} '$URL' >& /dev/null "
CURL_CMD1="curl -s -X POST -H 'Content-Type: application/json' -d @${JSON_FILE1} '$URL' >& /dev/null "
CURL_CMD2="curl -s -X POST -H 'Content-Type: application/json' -d @${JSON_FILE2} '$URL' >& /dev/null "

echo "Curl command is $CURL_CMD"

# Start time
START_TIME=$(date +%s)

# Run curl command multiple times
for ((i=1; i<=NUM_REQUESTS; i++))
do
    output=$(eval $CURL_CMD)
    output=$(eval $CURL_CMD1)
    output=$(eval $CURL_CMD2)        

done

# End time
END_TIME=$(date +%s)

# Calculate duration
DURATION=$((END_TIME - START_TIME))

# Print duration
echo "Time taken for $NUM_REQUESTS requests: $DURATION seconds"
