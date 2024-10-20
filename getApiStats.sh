#!/bin/bash

# get API stats

# pull in API key from env variable
API_KEY=$OPENATHENA_API_KEY

URL="http://localhost:8000/api/v1/openathena/admin/stats?apikey=$API_KEY"

CURL_CMD="curl -s $URL"

echo "Command is: $CURL_CMD"

output=$(eval $CURL_CMD)
echo "$output" | jq .
