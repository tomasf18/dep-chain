#!/bin/bash
# Builds the project and spawns 3F+1 servers and N clients in tmux.
# Run from the depchain/ directory (where config.json lives).
# Usage: ./run.sh [-c] [-b] [-n N] [-f F]
#   -c      recompile before starting
#   -b      build only (compile + key gen, no tmux session)
#   -n N    number of clients (default: 2)
#   -f F    number of tolerated faults (default: 1)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="config-dev.json"
SESSION="depchain"
RECOMPILE=false
BUILD_ONLY=false
NUM_CLIENTS=2
F=1

while [[ $# -gt 0 ]]; do
    case "$1" in
        -c) RECOMPILE=true; shift ;;
        -b) BUILD_ONLY=true; RECOMPILE=true; shift ;;
        -t) CONFIG="config-test.json"; shift ;;
        -n) NUM_CLIENTS="$2"; shift 2 ;;
        -f) F="$2"; shift 2 ;;
        *) echo "[WARN] Unknown argument: $1"; shift ;;
    esac
done

NUM_SERVERS=$(( 3 * F + 1 ))

if ! command -v tmux &>/dev/null; then
    echo "[ERROR] tmux is not installed. Install it with: sudo apt install tmux"
    exit 1
fi

cd "$SCRIPT_DIR"

generate_ec_keys() {
    echo "[INFO] Generating EC key pairs for all processes..."

    # Collect all process IDs
    SERVERS=()
    CLIENTS=()
    for (( i = 0; i < NUM_SERVERS; i++ )); do
        SERVERS+=("s${i}")
    done
    for (( i = 1; i <= NUM_CLIENTS; i++ )); do
        CLIENTS+=("client${i}")
    done
    ALL_PROCESSES=("${SERVERS[@]}" "${CLIENTS[@]}")

    # Generate key pairs in a temp directory
    TMPKEYS="$(mktemp -d)"
    for proc in "${ALL_PROCESSES[@]}"; do
        openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 \
            -out "$TMPKEYS/${proc}_private.pem" 2>/dev/null
        openssl pkey -in "$TMPKEYS/${proc}_private.pem" -pubout \
            -out "$TMPKEYS/${proc}_public.pem" 2>/dev/null
    done

    # Distribute keys: servers -> core/keystore/, clients -> client/keystore/
    # Also populate tests/keystore/ for both (tests run from tests/ directory)
    for proc in "${ALL_PROCESSES[@]}"; do
        KEYDIRS=()
        if [[ "$proc" == s* ]]; then
            KEYDIRS+=("$SCRIPT_DIR/core/keystore/$proc")
        else
            KEYDIRS+=("$SCRIPT_DIR/client/keystore/$proc")
        fi
        KEYDIRS+=("$SCRIPT_DIR/tests/keystore/$proc")

        for KEYDIR in "${KEYDIRS[@]}"; do
            mkdir -p "$KEYDIR/trusted"
            cp "$TMPKEYS/${proc}_private.pem" "$KEYDIR/private.pem"
            cp "$TMPKEYS/${proc}_public.pem" "$KEYDIR/public.pem"

            for peer in "${ALL_PROCESSES[@]}"; do
                if [[ "$peer" != "$proc" ]]; then
                    cp "$TMPKEYS/${peer}_public.pem" "$KEYDIR/trusted/${peer}.pem"
                fi
            done
        done
    done

    rm -rf "$TMPKEYS"
    echo "[INFO] EC keys generated and distributed."
}

if $RECOMPILE; then
    generate_ec_keys
    echo "[INFO] Building project..."
    mvn clean install -DskipTests -q
    echo "[INFO] Build complete."
else
    echo "[INFO] Skipping build (pass -c to recompile)."
fi

if $BUILD_ONLY; then
    echo "[INFO] Build-only mode: skipping tmux session."
    exit 0
fi

echo "[INFO] Starting $NUM_SERVERS servers (F=$F) and $NUM_CLIENTS client(s)."

# Kill any leftover session
tmux kill-session -t "$SESSION" 2>/dev/null || true

# === Servers window ===
tmux new-session -d -s "$SESSION" -x 220 -y 60 -n "servers"

for (( i = 1; i < NUM_SERVERS; i++ )); do
    tmux split-window -t "$SESSION:servers" -v
    tmux select-layout -t "$SESSION:servers" tiled
done

for (( i = 0; i < NUM_SERVERS; i++ )); do
    tmux send-keys -t "$SESSION:servers.$i" \
        "printf '\\033]2;s${i}\\033\\\\'; cd \"$SCRIPT_DIR/core\" && mvn exec:java -Dexec.mainClass=\"ist.depchain.core.ServerApp\" -Dexec.args=\"../$CONFIG s${i}\"" \
        Enter
done

tmux select-layout -t "$SESSION:servers" tiled

# === Clients window ===
tmux new-window -t "$SESSION" -n "clients"

for (( i = 1; i <= NUM_CLIENTS; i++ )); do
    if (( i > 1 )); then
        tmux split-window -t "$SESSION:clients" -v
        tmux select-layout -t "$SESSION:clients" tiled
    fi
    tmux send-keys -t "$SESSION:clients.$((i - 1))" \
        "printf '\\033]2;client${i}\\033\\\\'; sleep 2 && cd \"$SCRIPT_DIR/client\" && mvn exec:java -Dexec.mainClass=\"ist.depchain.client.ClientApp\" -Dexec.args=\"../$CONFIG client${i}\"" \
        Enter
done

tmux select-layout -t "$SESSION:clients" tiled

# Focus servers window on attach
tmux select-window -t "$SESSION:servers"

echo "[INFO] Servers in window 'servers' (Ctrl+b 0), clients in window 'clients' (Ctrl+b 1)."
echo "[INFO] Type 'exit' in any pane to shut it down."
echo ""

tmux attach-session -t "$SESSION"