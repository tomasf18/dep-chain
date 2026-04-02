#!/bin/bash

# Runs every JUnit test class in its own Maven invocation.
# This keeps each class isolated so one test class cannot leak JVM state into the next.
#
# Usage:
#   ./run_tests_isolated.sh
#   ./run_tests_isolated.sh BlockValidationAndExecutionTest TransactionExecutionTest
#   ./run_tests_isolated.sh --fail-fast
#   ./run_tests_isolated.sh --integration
#   ./run_tests_isolated.sh --unit

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TEST_DIR="$SCRIPT_DIR/src/test/java/ist/depchain/tests/"
INTEGRATION_DIR="$TEST_DIR/integration"
UNIT_DIR="$TEST_DIR/unit"

FAIL_FAST=false
ONLY_SCOPE="all"
SELECTED_CLASSES=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --fail-fast)
            FAIL_FAST=true
            shift
            ;;
        --integration)
            ONLY_SCOPE="integration"
            shift
            ;;
        --unit)
            ONLY_SCOPE="unit"
            shift
            ;;
        -h|--help)
            cat <<'USAGE'
Runs JUnit test classes one at a time using Maven.

Usage:
  ./run_tests_isolated.sh
  ./run_tests_isolated.sh [--fail-fast] [--integration|--unit] [TestClassName ...]

If no class names are provided, the script runs every file matching *Test.java
under tests/src/test/java/ist/depchain/tests/, recursively through the
integration/ and unit/ folders.
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
    case "$ONLY_SCOPE" in
        integration)
            DISCOVERY_ROOT="$INTEGRATION_DIR"
            ;;
        unit)
            DISCOVERY_ROOT="$UNIT_DIR"
            ;;
        *)
            DISCOVERY_ROOT="$TEST_DIR"
            ;;
    esac

    while IFS= read -r test_file; do
        test_class="$(basename "$test_file" .java)"
        SELECTED_CLASSES+=("$test_class")
    done < <(find "$DISCOVERY_ROOT" -type f -name '*Test.java' | sort)
fi

if [[ ${#SELECTED_CLASSES[@]} -eq 0 ]]; then
    echo "No JUnit test classes found under $TEST_DIR" >&2
    exit 1
fi

echo "[INFO] Running ${#SELECTED_CLASSES[@]} JUnit test class(es) in isolation"
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
echo "[INFO] All JUnit test classes passed in isolation."