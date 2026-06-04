#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/jre/bin/java" -jar "$SCRIPT_DIR/OrzRepacker.jar" "$@"
