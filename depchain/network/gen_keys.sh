#!/bin/bash
# Usage: ./gen_keys.sh pedro peter alice bob
# Generates key pairs for each process and creates a keystore directory

set -e

PROCESSES=("$@")
KEYSTORE_DIR="keystore"

mkdir -p "$KEYSTORE_DIR"

echo "==> Generating key pairs for: ${PROCESSES[*]}"

for PROC in "${PROCESSES[@]}"; do
    PROC_DIR="$KEYSTORE_DIR/$PROC"
    mkdir -p "$PROC_DIR"

    # Generate private key directly in PKCS8 format (required by Java's PKCS8EncodedKeySpec)
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$PROC_DIR/private.pem"

    # Derive public key
    openssl pkey -in "$PROC_DIR/private.pem" -pubout -out "$PROC_DIR/public.pem"

    echo "  [OK] $PROC: private.pem + public.pem"
done

# Each process gets a "trusted" folder with everyone else's public keys
echo ""
echo "==> Distributing public keys..."

for PROC in "${PROCESSES[@]}"; do
    TRUSTED_DIR="$KEYSTORE_DIR/$PROC/trusted"
    mkdir -p "$TRUSTED_DIR"

    for OTHER in "${PROCESSES[@]}"; do
        cp "$KEYSTORE_DIR/$OTHER/public.pem" "$TRUSTED_DIR/$OTHER.pem"
        echo "  [OK] $PROC/trusted/$OTHER.pem"
    done
done

echo ""
echo "==> Done. Structure:"
find "$KEYSTORE_DIR" -type f | sort