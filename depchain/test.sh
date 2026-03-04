#!/bin/bash
# Builds the project and spawns all 4 servers in a tmux 2x2 grid.
# Run from the depchain/ directory (where config.json lives).
# Usage: ./test.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="config.json"
SESSION="depchain"
RECOMPILE=false

for arg in "$@"; do
    if [[ "$arg" == "-c" ]]; then
        RECOMPILE=true
    fi
done

if ! command -v tmux &>/dev/null; then
    echo "[ERROR] tmux is not installed. Install it with: sudo apt install tmux"
    exit 1
fi

cd "$SCRIPT_DIR"

if $RECOMPILE; then
    echo "[INFO] Building project..."
    mvn clean install -DskipTests -q
    echo "[INFO] Build complete."
else
    echo "[INFO] Skipping build (pass -c to recompile)."
fi

# Kill any leftover session
tmux kill-session -t "$SESSION" 2>/dev/null || true

# Create a detached session; the first pane will run s1
tmux new-session -d -s "$SESSION" -x 220 -y 60

# Split into 2x2 grid
# After new-session: pane 0 exists (left half top)
tmux split-window   -h -t "$SESSION:0.0"   # pane 1: right half
tmux split-window   -v -t "$SESSION:0.0"   # pane 2: left half bottom
tmux split-window   -v -t "$SESSION:0.1"   # pane 3: right half bottom

# Give each pane a descriptive title and start its server
for i in 1 2 3 4; do
    PANE=$((i - 1))
    tmux send-keys -t "$SESSION:0.$PANE" \
        "printf '\\033]2;s${i}\\033\\\\'; mvn exec:java -pl core -Dexec.mainClass=\"ist.depchain.core.ServerApp\" -Dexec.args=\"$CONFIG s${i}\"" \
        Enter
done

# Even out the pane sizes
tmux select-layout -t "$SESSION" tiled

echo "[INFO] All 4 servers starting in tmux session '$SESSION'."
echo "[INFO] Each pane accepts:  <destination> <message>  (non-sX destination broadcasts)"
echo "[INFO] Type 'exit' in a pane to shut down that server."
echo ""

tmux attach-session -t "$SESSION"
