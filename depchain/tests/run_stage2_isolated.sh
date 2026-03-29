#!/bin/bash

# Runs every Stage 2 JUnit test class in its own Maven invocation.
# This keeps each class isolated so one test class cannot leak JVM state into the next.
#
# Usage:
#   ./run_stage2_isolated.sh
#   ./run_stage2_isolated.sh BlockValidationAndExecutionTest TransactionExecutionTest
#   ./run_stage2_isolated.sh --fail-fast

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TEST_DIR="$SCRIPT_DIR/src/test/java/ist/depchain/tests/stage2"

FAIL_FAST=false
SELECTED_CLASSES=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --fail-fast)
            FAIL_FAST=true
            shift
            ;;
        -h|--help)
            cat <<'USAGE'
Runs Stage 2 test classes one at a time using Maven.

Usage:
  ./run_stage2_isolated.sh
  ./run_stage2_isolated.sh [--fail-fast] [TestClassName ...]

If no class names are provided, the script runs every file matching *Test.java
under tests/src/test/java/ist/depchain/tests/stage2.
USAGE
            exit 0
            ;;
        *)
            SELECTED_CLASSES+=("$1")
            shift
            ;;
    esac
done

if [[ ${#SELECTED_CLASSES[@]} -eq 0 ]]; then
    while IFS= read -r test_file; do
        test_class="$(basename "$test_file" .java)"
        SELECTED_CLASSES+=("$test_class")
    done < <(find "$TEST_DIR" -maxdepth 1 -type f -name '*Test.java' | sort)
fi

if [[ ${#SELECTED_CLASSES[@]} -eq 0 ]]; then
    echo "No Stage 2 test classes found under $TEST_DIR" >&2
    exit 1
fi

echo "[INFO] Running ${#SELECTED_CLASSES[@]} Stage 2 test class(es) in isolation"
echo "[INFO] Repo root: $PROJECT_ROOT"

failed_classes=()

for test_class in "${SELECTED_CLASSES[@]}"; do
    echo
    echo "[INFO] Running $test_class"
    if mvn -q -f "$PROJECT_ROOT/pom.xml" -pl tests -am -Dtest="$test_class" -Dsurefire.failIfNoSpecifiedTests=false test; then
        echo "[OK] $test_class"
    else
        echo "[FAIL] $test_class" >&2
        failed_classes+=("$test_class")
        if [[ "$FAIL_FAST" == true ]]; then
            break
        fi
    fi
done

if [[ ${#failed_classes[@]} -ne 0 ]]; then
    echo
    echo "[ERROR] Failed test class(es): ${failed_classes[*]}" >&2
    exit 1
fi

echo
echo "[INFO] All Stage 2 test classes passed in isolation."