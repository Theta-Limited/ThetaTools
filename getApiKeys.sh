#!/bin/bash

# get OACore RESTful API keys 

# pull in API key from env variable
# to obtain stats, api key must have admin permissions

API_KEY=$OPENATHENA_API_KEY

URL="http://localhost:8000/api/v1/openathena/admin/apikeys?apikey=$API_KEY"

CURL_CMD="curl -s $URL"

echo "Command is: $CURL_CMD"

output=$(eval $CURL_CMD)
echo "$output" | jq 'to_entries | sort_by(.key) | from_entries'

