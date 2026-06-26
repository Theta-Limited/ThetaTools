#!/bin/bash
set -euo pipefail

# HOST=34.133.221.26
HOST=34.123.208.252
PORT=8089

CLIENT_KEYSTORE=client-keystore.p12
CLIENT_KEYSTORE_PASSWORD='client-keystore-password'


# This is the password you want on the extracted private key PEM.
# It may or may not be the same as your Java client key password.
CLIENT_KEY_PASSWORD='client-private-key-password'

TRUSTSTORE=penguin-truststore-root.p12
TRUSTSTORE_PASSWORD='atakatak'
CLIENT_KEYSTORE=penguin-openathena.p12
CLIENT_KEYSTORE_PASSWORD='atakatak'
CLIENT_KEY_PASSWORD='atakatak'

VERIFY_NAME=""

usage() {
  echo "Usage: $0 [--verify-name hostname-or-ip]"
  echo
  echo "Examples:"
  echo "  $0"
  echo "  $0 --verify-name 34.133.221.26"
  echo "  $0 --verify-name opentakserver"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --verify-name)
      if [[ $# -lt 2 ]]; then
        echo "Error: --verify-name requires an argument" >&2
        usage >&2
        exit 2
      fi
      VERIFY_NAME="$2"
      shift 2
      ;;

    -h|--help)
      usage
      exit 0
      ;;

    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

rm -f client-cert.pem client-key.pem trust-ca.pem

openssl pkcs12 \
  -legacy \
  -in "$CLIENT_KEYSTORE" \
  -clcerts \
  -nokeys \
  -out client-cert.pem \
  -passin "pass:$CLIENT_KEYSTORE_PASSWORD"

openssl pkcs12 \
  -legacy \
  -in "$CLIENT_KEYSTORE" \
  -nocerts \
  -out client-key.pem \
  -passin "pass:$CLIENT_KEYSTORE_PASSWORD" \
  -passout "pass:$CLIENT_KEY_PASSWORD"

  openssl pkcs12 \
  -legacy \
  -in "$TRUSTSTORE" \
  -nokeys \
  -out trust-ca.pem \
  -passin "pass:$TRUSTSTORE_PASSWORD"

openssl s_client \
  -connect "$HOST:$PORT" \
  -servername "$HOST" \
  -CAfile trust-ca.pem \
  -verify_return_error \
  </dev/null 2>/dev/null \
| openssl x509 \
  -outform PEM \
  -out penguin-server-cert.pem

VERIFY_ARGS=()

if [[ -n "$VERIFY_NAME" ]]; then
  if [[ "$VERIFY_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    VERIFY_ARGS=(-verify_ip "$VERIFY_NAME")
  else
    VERIFY_ARGS=(-verify_hostname "$VERIFY_NAME")
  fi

  echo "TLS name verification enabled for: $VERIFY_NAME" >&2
else
  echo "TLS certificate chain verification enabled; hostname/IP verification disabled" >&2
fi

openssl s_client \
  -connect "$HOST:$PORT" \
  -servername "$HOST" \
  "${VERIFY_ARGS[@]}" \
  -CAfile trust-ca.pem \
  -cert client-cert.pem \
  -key client-key.pem \
  -pass "pass:$CLIENT_KEY_PASSWORD" \
  -showcerts \
  -quiet \
  -state \
  -no_ticket \
  -verify_return_error
