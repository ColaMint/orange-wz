#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/jre/bin/java" -javaagent:"$SCRIPT_DIR/OrzRepacker.jar" -jar "$SCRIPT_DIR/OrzRepacker.jar" "$@"
