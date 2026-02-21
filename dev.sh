#!/bin/bash
# Development workflow for Claude Assist JetBrains Plugin
#
# HOW IT WORKS:
# 1. Starts a sandboxed IntelliJ IDE with the plugin installed
# 2. autoReloadPlugins is enabled in build.gradle.kts
# 3. After making code changes, run in another terminal:
#      ./gradlew buildPlugin
#    The sandbox IDE will auto-reload the plugin (no restart needed for most changes)
#
# USAGE:
#   ./dev.sh          - Run with IntelliJ Community (fast, default)
#   ./dev.sh ps       - Run with PHPStorm
#   ./dev.sh build    - Just rebuild (run in second terminal while IDE is open)

set -e
cd "$(dirname "$0")"

case "${1:-run}" in
    ps)
        echo "Starting sandbox PHPStorm with Claude Assist plugin..."
        ./gradlew runIde -PideType=PS
        ;;
    build)
        echo "Rebuilding plugin (sandbox IDE will auto-reload)..."
        ./gradlew classes instrumentCode prepareSandbox
        ;;
    run|*)
        echo "Starting sandbox IntelliJ with Claude Assist plugin..."
        echo ""
        echo "  TIP: After code changes, run in another terminal:"
        echo "    ./dev.sh build"
        echo "  The IDE will auto-reload the plugin."
        echo ""
        ./gradlew runIde
        ;;
esac
