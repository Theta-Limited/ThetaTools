#!/bin/bash

# get OACore number of RESTful API post calls
# get args

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <host> <port>"
  exit 1
fi

HOST=$1
PORT=$2

# pull in API key from env variable
# to obtain stats, api key must have admin permissions

API_KEY=$OPENATHENA_API_KEY

URL="http://$HOST:$PORT/api/v1/openathena/admin/stats?apikey=$API_KEY"

CURL_CMD="curl -s $URL"

# echo "Command is: $CURL_CMD"

output=$(eval $CURL_CMD)

# echo "$output" | jq 'to_entries | sort_by(.key) | from_entries'

total=$(jq -r '.numRESTPosts' <<< "$output" )
echo "$total"
