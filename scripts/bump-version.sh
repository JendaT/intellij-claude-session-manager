#!/bin/bash
set -e

# Version bump script
# Usage: ./scripts/bump-version.sh <major|minor|patch> OR ./scripts/bump-version.sh <version>

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# Read current version
CURRENT=$(grep "^pluginVersion" gradle.properties | cut -d'=' -f2 | tr -d ' ')
echo "Current version: $CURRENT"

# Parse current version
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT"

# Determine new version
case "$1" in
    major)
        NEW_VERSION="$((MAJOR + 1)).0.0"
        ;;
    minor)
        NEW_VERSION="$MAJOR.$((MINOR + 1)).0"
        ;;
    patch)
        NEW_VERSION="$MAJOR.$MINOR.$((PATCH + 1))"
        ;;
    "")
        echo "Usage: $0 <major|minor|patch|X.Y.Z>"
        exit 1
        ;;
    *)
        # Assume it's an explicit version
        if [[ ! "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "Error: Invalid version format. Use X.Y.Z (e.g., 1.2.3)"
            exit 1
        fi
        NEW_VERSION="$1"
        ;;
esac

echo "New version: $NEW_VERSION"

# Update gradle.properties
if [[ "$(uname)" == "Darwin" ]]; then
    sed -i '' "s/^pluginVersion = .*/pluginVersion = $NEW_VERSION/" gradle.properties
else
    sed -i "s/^pluginVersion = .*/pluginVersion = $NEW_VERSION/" gradle.properties
fi

echo "Updated gradle.properties"

# Show the change
grep "^pluginVersion" gradle.properties
