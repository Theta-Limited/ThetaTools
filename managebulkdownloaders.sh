#!/usr/bin/env bash
set -euo pipefail

# ------------------------------------------------------------
# managebulkdownloaders.sh
# Copyright 2026 Theta Informatics LLC
# Apache 2.0 license
#
# CLI for OpenAthena Bulk DEM Downloader REST API
#
# Auth:
#   API key is REQUIRED and is passed on the URL as ?apikey=KEY
#   Read from environment variable: OPENATHENA_API_KEY
#
# Endpoints assumed:
#   POST /api/v1/openathena/bulkdemdownload               (create; body required)
#   GET  /api/v1/openathena/bulkdemdownload               (list)
#   GET  /api/v1/openathena/bulkdemdownload/{id}          (get one)
#   POST /api/v1/openathena/bulkdemdownload/{id}/stop     (stop/cancel)
#
# Global:
#   --url http(s)://host:port     (REQUIRED)
# ------------------------------------------------------------

PROG="$(basename "$0")"

API_PREFIX="${API_PREFIX:-/api/v1/openathena}"
CONNECT_TIMEOUT="${CONNECT_TIMEOUT:-5}"
MAX_TIME="${MAX_TIME:-60}"

OPENATHENA_API_KEY="${OPENATHENA_API_KEY:-}"

have_cmd() { command -v "$1" >/dev/null 2>&1; }

die() {
  echo "[$PROG] ERROR: $*" >&2
  exit 1
}

usage() {
  cat <<EOF
$PROG - manage OpenAthena Bulk DEM Downloader tasks

Usage:
  $PROG --url <http(s)://host:port> list [--json]
  $PROG --url <http(s)://host:port> get  <taskId> [--json]
  $PROG --url <http(s)://host:port> stop <taskId> [--json]
  $PROG --url <http(s)://host:port> create <lat1> <lon1> <lat2> <lon2> <length_m> <overlap_0_to_1> <dataset> [--json] [--dry-run]

Options:
  --json      Pretty-print JSON output (requires jq)
  --dry-run   For create: print the request URL/body without sending
  -h, --help  Show this help

Environment variables:
  OPENATHENA_API_KEY   REQUIRED. API key appended to URL as ?apikey=...
  API_PREFIX           Default: /api/v1/openathena
  CONNECT_TIMEOUT      Default: 5   (seconds)
  MAX_TIME             Default: 60  (seconds)

Examples:
  $PROG --url http://localhost:8000 list --json
  $PROG --url http://localhost:8000 get  123 --json
  $PROG --url http://localhost:8000 stop 123 --json
  $PROG --url http://localhost:8000 create 34.58 -98.78 34.77 -98.25 25000 0.2 COP30 --json

Notes:
  - create sends request parameters in the POST BODY (currently stubbed as form data).
  - If your API expects JSON, switch content_type/body construction in cmd_create.
EOF
}

# Append apikey query parameter to a URL, handling existing query strings.
with_apikey() {
  local url="$1"
  if [[ "$url" == *\?* ]]; then
    echo "${url}&apikey=${OPENATHENA_API_KEY}"
  else
    echo "${url}?apikey=${OPENATHENA_API_KEY}"
  fi
}

# Generic request helper:
#   curl_req METHOD URL [BODY] [CONTENT_TYPE]
curl_req() {
  local method="$1"; shift
  local url="$1"; shift
  local body="${1:-}"; shift || true
  local content_type="${1:-}"; shift || true

  url="$(with_apikey "$url")"

  local -a cmd=(
    curl -sS
    --connect-timeout "$CONNECT_TIMEOUT"
    --max-time "$MAX_TIME"
    -X "$method"
    "$url"
    -H "Accept: application/json"
  )

  if [[ -n "$body" ]]; then
    [[ -n "$content_type" ]] && cmd+=(-H "Content-Type: $content_type")
    cmd+=(--data "$body")
  fi

  "${cmd[@]}"
}

print_resp() {
  # print_resp WANT_JSON RESPONSE_STRING
  local want_json="$1"
  local resp="$2"

  if [[ "$want_json" == "1" ]] && have_cmd jq; then
    echo "$resp" | jq . 2>/dev/null || {
      echo "[$PROG] WARNING: response was not valid JSON; printing raw:" >&2
      echo "$resp"
    }
  else
    echo "$resp"
  fi
}

# Parse trailing common flags: --json, --dry-run
# Usage:
#   parse_flags "$@"
#   -> sets WANT_JSON, DRY_RUN, and leaves positionals in POSITIONALS array
WANT_JSON="0"
DRY_RUN="0"
POSITIONALS=()

parse_flags() {
  WANT_JSON="0"
  DRY_RUN="0"
  POSITIONALS=()

  while (($#)); do
    case "$1" in
      --json) WANT_JSON="1"; shift ;;
      --dry-run) DRY_RUN="1"; shift ;;
      -h|--help) usage; exit 0 ;;
      *) POSITIONALS+=("$1"); shift ;;
    esac
  done
}

# ------------------------
# Commands
# ------------------------

cmd_list() {
  parse_flags "$@"
  if ((${#POSITIONALS[@]} != 0)); then
    die "list takes no arguments"
  fi

  local url="${API_BASE_URL}${API_PREFIX}/bulkdemdownload"
  local resp
  resp="$(curl_req "GET" "$url")"
  print_resp "$WANT_JSON" "$resp"
}

cmd_get() {
  parse_flags "$@"
  if ((${#POSITIONALS[@]} != 1)); then
    die "get requires: <taskId>"
  fi

  local id="${POSITIONALS[0]}"
  local url="${API_BASE_URL}${API_PREFIX}/bulkdemdownload/${id}"
  local resp
  resp="$(curl_req "GET" "$url")"
  print_resp "$WANT_JSON" "$resp"
}

cmd_stop() {
  parse_flags "$@"
  if ((${#POSITIONALS[@]} != 1)); then
    die "stop requires: <taskId>"
  fi

  local id="${POSITIONALS[0]}"
  local url="${API_BASE_URL}${API_PREFIX}/bulkdemdownload/${id}/stop"
  local resp
  resp="$(curl_req "POST" "$url")"
  print_resp "$WANT_JSON" "$resp"
}

cmd_create() {
  parse_flags "$@"

  if ((${#POSITIONALS[@]} != 7)); then
    die "create requires: <lat1> <lon1> <lat2> <lon2> <length_m> <overlap_0_to_1> <dataset>"
  fi

  local lat1="${POSITIONALS[0]}"
  local lon1="${POSITIONALS[1]}"
  local lat2="${POSITIONALS[2]}"
  local lon2="${POSITIONALS[3]}"
  local length_m="${POSITIONALS[4]}"
  local overlap="${POSITIONALS[5]}"
  local dataset="${POSITIONALS[6]}"

  local url="${API_BASE_URL}${API_PREFIX}/bulkdemdownload"

  # ------------------------------------------------------------
  # POST BODY (stubbed as form-encoded)
  # Rename keys to match your server implementation as needed.
  # ------------------------------------------------------------
  local content_type="application/application/json"

  # Build JSON body
  local body
  body=$(cat <<EOF
{
  "lat1": $lat1,
  "lon1": $lon1,
  "lat2": $lat2,
  "lon2": $lon2,
  "len": $length_m,
  "overlap": $overlap,
  "dataset": "$dataset"
}
EOF
)

  # TODO: add your real request fields here (if any)
  # body+="&maxConcurrent=TODO"
  # body+="&outputDir=TODO"

  if [[ "$DRY_RUN" == "1" ]]; then
    echo "POST $(with_apikey "$url")"
    echo "Content-Type: $content_type"
    echo
    echo "$body"
    return 0
  fi

  
  local resp
  resp="$(curl_req "POST" "$url" "$body" "$content_type")"
  print_resp "$WANT_JSON" "$resp"
}

main() {
  if (($# == 0)); then
    usage
    exit 1
  fi

  API_BASE_URL=""

  # Global args
  while (($#)); do
    case "$1" in
      --url)
        API_BASE_URL="${2:-}"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        break
        ;;
    esac
  done

  [[ -n "$API_BASE_URL" ]] || die "Missing required --url http(s)://host:port"
  [[ "$API_BASE_URL" =~ ^https?:// ]] || die "--url must start with http:// or https://"
  [[ -n "$OPENATHENA_API_KEY" ]] || die "Missing OPENATHENA_API_KEY environment variable"
  [[ $# -ge 1 ]] || die "Missing command (create, list, get, stop)"

  local cmd="$1"; shift

  case "$cmd" in
    create) cmd_create "$@" ;;
    list)   cmd_list "$@" ;;
    get)    cmd_get "$@" ;;
    stop|cancel) cmd_stop "$@" ;;
    help) usage ;;
    *) die "Unknown command: $cmd (use --help)" ;;
  esac
}

main "$@"
