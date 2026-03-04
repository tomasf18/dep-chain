# dep-chain

A fault-tolerant blockchain built on the HotStuff BFT consensus protocol.

## Requirements

- Java 17+
- Maven 3.8+
- `tmux` (for the test script) — install with `sudo apt install tmux`

## Running the servers

### With the test script (recommended)

From the `depchain/` directory:

```bash
# Launch all 4 servers (no recompile)
./test.sh

# Recompile first, then launch
./test.sh -c
```

This opens a tmux session with a 2×2 grid — one pane per server (s1–s4). Each pane is interactive.

**tmux quick reference:**
- Switch panes: `Ctrl-b` then arrow keys
- Detach (leave running): `Ctrl-b d`
- Kill session: `Ctrl-b :kill-session`

### Manually

From the `depchain/` directory:

```bash
mvn exec:java -pl core -Dexec.mainClass="ist.depchain.core.ServerApp" -Dexec.args="config.json s1"
```

Replace `s1` with `s2`, `s3`, or `s4` for the other servers.

## ServerApp interactive input

Once a server is running it reads commands from stdin.

| Input | Effect |
|---|---|
| `s1 hello` | Sends `hello` to server `s1` |
| `s2 some message` | Sends `some message` to server `s2` |
| `anything hello` | Broadcasts `anything hello` to all servers |
| `exit` | Shuts down this server |

**Rule:** if the line starts with `s1`–`s4` (followed by a space), it is a unicast to that server and the message is everything after the space. Any other prefix triggers a broadcast of the whole line.

## Configuration

Network topology, fault injection, and crypto settings live in `depchain/config.json`.

```json
{
  "networkConfig": {
    "N": 4,
    "f": 1,
    "resendPeriodMillis": 1000,
    "processes": {
      "s1": { "host": "localhost", "port": 5001 },
      ...
    }
  },
  "faultConfig": {
    "dropProbability": 0.0,
    "duplicateProbability": 0.0,
    "tamperProbability": 0.0,
    "maxDelayMs": 200
  }
}
```

Increase `dropProbability` / `tamperProbability` to stress-test the authenticated perfect link.
