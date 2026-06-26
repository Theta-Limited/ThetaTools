#!/bin/bash

# fix openssl created p12 keystores so they will work
# with java

KEYTOOL=/usr/java/latest/bin/keytool
CLIENT_IN=""
TRUST_IN=""
CLIENT_OUT="java-client.p12"
TRUST_OUT="java-trust.p12"
CLIENT_ALIAS="openathena-client"
TRUST_ALIAS_PREFIX="tak-ca"
FORCE="false"

usage() {
    cat <<EOF
Usage:
  $0 --client-in CLIENT.p12 --trust-in TRUST.p12 [options]

Options:
  --client-in FILE          Input OpenSSL-created client PKCS12
  --trust-in FILE           Input OpenSSL-created trust PKCS12
  --client-out FILE         Output Java-compatible client PKCS12 [default: java-client.p12]
  --trust-out FILE          Output Java-compatible trust PKCS12  [default: java-trust.p12]
  --client-alias ALIAS      Alias for client private key entry    [default: openathena-client]
  --trust-alias-prefix STR  Alias prefix for trusted cert entries [default: tak-ca]
  --force                   Overwrite output files if they exist
  -h, --help                Show help

Passwords may be supplied through environment variables:
  CLIENT_IN_PASS
  TRUST_IN_PASS
  OUT_PASS

If not supplied, the script prompts for them.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --client-in)
            CLIENT_IN="$2"
            shift 2
            ;;
        --trust-in)
            TRUST_IN="$2"
            shift 2
            ;;
        --client-out)
            CLIENT_OUT="$2"
            shift 2
            ;;
        --trust-out)
            TRUST_OUT="$2"
            shift 2
            ;;
        --client-alias)
            CLIENT_ALIAS="$2"
            shift 2
            ;;
        --trust-alias-prefix)
            TRUST_ALIAS_PREFIX="$2"
            shift 2
            ;;
        --force)
            FORCE="true"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [[ -z "$CLIENT_IN" || -z "$TRUST_IN" ]]; then
    echo "ERROR: --client-in and --trust-in are required" >&2
    usage >&2
    exit 1
fi

for cmd in openssl keytool awk; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "ERROR: required command not found: $cmd" >&2
        exit 1
    fi
done

if [[ ! -f "$CLIENT_IN" ]]; then
    echo "ERROR: client input file not found: $CLIENT_IN" >&2
    exit 1
fi

if [[ ! -f "$TRUST_IN" ]]; then
    echo "ERROR: trust input file not found: $TRUST_IN" >&2
    exit 1
fi

if [[ "$FORCE" != "true" ]]; then
    if [[ -e "$CLIENT_OUT" ]]; then
        echo "ERROR: output file already exists: $CLIENT_OUT"
        echo "Use --force to overwrite."
        exit 1
    fi

    if [[ -e "$TRUST_OUT" ]]; then
        echo "ERROR: output file already exists: $TRUST_OUT"
        echo "Use --force to overwrite."
        exit 1
    fi
fi

prompt_secret_if_empty() {
    local var_name="$1"
    local prompt="$2"

    if [[ -z "${!var_name:-}" ]]; then
        read -r -s -p "$prompt: " "$var_name"
        echo
    fi
}

prompt_secret_if_empty CLIENT_IN_PASS "Password for input client PKCS12"
prompt_secret_if_empty TRUST_IN_PASS  "Password for input trust PKCS12"
prompt_secret_if_empty OUT_PASS       "Password for output Java PKCS12 files"

TMPDIR="$(mktemp -d)"
chmod 700 "$TMPDIR"

cleanup() {
    rm -rf "$TMPDIR"
}
trap cleanup EXIT

CLIENT_CERT_PEM="$TMPDIR/client-cert.pem"
CLIENT_KEY_PEM="$TMPDIR/client-key.pem"
CLIENT_CHAIN_PEM="$TMPDIR/client-chain.pem"
TRUST_CERTS_PEM="$TMPDIR/trust-certs.pem"

echo "Extracting client certificate..."
openssl pkcs12 \
    -legacy \
    -in "$CLIENT_IN" \
    -clcerts \
    -nokeys \
    -out "$CLIENT_CERT_PEM" \
    -passin "pass:$CLIENT_IN_PASS"

echo "Extracting client private key..."
openssl pkcs12 \
    -legacy \
    -in "$CLIENT_IN" \
    -nocerts \
    -nodes \
    -out "$CLIENT_KEY_PEM" \
    -passin "pass:$CLIENT_IN_PASS"

echo "Extracting client CA chain, if present..."
set +e
openssl pkcs12 \
    -legacy \
    -in "$CLIENT_IN" \
    -cacerts \
    -nokeys \
    -out "$CLIENT_CHAIN_PEM" \
    -passin "pass:$CLIENT_IN_PASS" >/dev/null 2>&1
CHAIN_RC=$?
set -e

if [[ $CHAIN_RC -ne 0 || ! -s "$CLIENT_CHAIN_PEM" ]]; then
    echo "No separate client CA chain found in client PKCS12; continuing without -certfile."
    rm -f "$CLIENT_CHAIN_PEM"
fi

echo "Rebuilding Java-compatible client PKCS12: $CLIENT_OUT"
rm -f "$CLIENT_OUT"

if [[ -f "$CLIENT_CHAIN_PEM" ]]; then
    openssl pkcs12 \
        -export \
        -out "$CLIENT_OUT" \
        -name "$CLIENT_ALIAS" \
        -inkey "$CLIENT_KEY_PEM" \
        -in "$CLIENT_CERT_PEM" \
        -certfile "$CLIENT_CHAIN_PEM" \
        -passout "pass:$OUT_PASS" \
        -macalg SHA256 \
        -keypbe AES-256-CBC \
        -certpbe AES-256-CBC
else
    openssl pkcs12 \
        -export \
        -out "$CLIENT_OUT" \
        -name "$CLIENT_ALIAS" \
        -inkey "$CLIENT_KEY_PEM" \
        -in "$CLIENT_CERT_PEM" \
        -passout "pass:$OUT_PASS" \
        -macalg SHA256 \
        -keypbe AES-256-CBC \
        -certpbe AES-256-CBC
fi

echo "Extracting trust certificates..."
openssl pkcs12 \
    -legacy \
    -in "$TRUST_IN" \
    -nokeys \
    -out "$TRUST_CERTS_PEM" \
    -passin "pass:$TRUST_IN_PASS"

echo "Splitting trust certificates..."
awk -v dir="$TMPDIR" '
    /-----BEGIN CERTIFICATE-----/ {
        n++;
        file = sprintf("%s/trust-cert-%02d.pem", dir, n);
    }
    n > 0 {
        print > file;
    }
    /-----END CERTIFICATE-----/ {
        close(file);
    }
    END {
        if (n == 0) {
            exit 2;
        }
    }
' "$TRUST_CERTS_PEM"

echo "Rebuilding Java-compatible trust PKCS12: $TRUST_OUT"
rm -f "$TRUST_OUT"

count=0
for cert in "$TMPDIR"/trust-cert-*.pem; do
    [[ -f "$cert" ]] || continue
    count=$((count + 1))

    alias="${TRUST_ALIAS_PREFIX}-${count}"

    $KEYTOOL -importcert \
        -noprompt \
        -storetype PKCS12 \
        -keystore "$TRUST_OUT" \
        -storepass "$OUT_PASS" \
        -alias "$alias" \
        -file "$cert" >/dev/null
done

if [[ $count -eq 0 ]]; then
    echo "ERROR: no trusted certificates were imported" >&2
    exit 1
fi

echo
echo "Done."
echo "Client keystore: $CLIENT_OUT"
echo "Trust keystore:  $TRUST_OUT"
echo
echo "Verify client keystore:"
echo "  keytool -list -v -storetype PKCS12 -keystore \"$CLIENT_OUT\""
echo
echo "Verify trust keystore:"
echo "  keytool -list -v -storetype PKCS12 -keystore \"$TRUST_OUT\""
