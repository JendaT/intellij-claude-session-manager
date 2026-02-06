#!/bin/bash
set -e

# Release script for Claude Session Manager plugin
# Usage: ./scripts/release.sh [--draft]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# Parse arguments
DRAFT_FLAG=""
if [[ "$1" == "--draft" ]]; then
    DRAFT_FLAG="--draft"
fi

# Read version from gradle.properties
VERSION=$(grep "^pluginVersion" gradle.properties | cut -d'=' -f2 | tr -d ' ')
if [[ -z "$VERSION" ]]; then
    echo "Error: Could not read version from gradle.properties"
    exit 1
fi

echo "=== Releasing Claude Session Manager v$VERSION ==="

# Check for uncommitted changes
if [[ -n $(git status --porcelain) ]]; then
    echo "Error: Uncommitted changes detected. Commit or stash them first."
    exit 1
fi

# Check if tag already exists
if git rev-parse "v$VERSION" >/dev/null 2>&1; then
    echo "Error: Tag v$VERSION already exists. Bump version in gradle.properties first."
    exit 1
fi

# Check GitHub CLI is installed
if ! command -v gh &> /dev/null; then
    echo "Error: GitHub CLI (gh) is not installed. Install it with: brew install gh"
    exit 1
fi

# Check GitHub auth
if ! gh auth status &> /dev/null; then
    echo "Error: Not authenticated with GitHub. Run: gh auth login"
    exit 1
fi

# Build the plugin
echo "Building plugin..."
./gradlew clean buildPlugin

# Verify the artifact exists
ARTIFACT="build/distributions/claude-session-manager-$VERSION.zip"
if [[ ! -f "$ARTIFACT" ]]; then
    echo "Error: Expected artifact not found: $ARTIFACT"
    exit 1
fi

echo "Built: $ARTIFACT ($(du -h "$ARTIFACT" | cut -f1))"

# Create git tag
echo "Creating tag v$VERSION..."
git tag -a "v$VERSION" -m "Release v$VERSION"

# Push tag
echo "Pushing tag..."
git push origin "v$VERSION"

# Create GitHub release
echo "Creating GitHub release..."
gh release create "v$VERSION" \
    $DRAFT_FLAG \
    --title "v$VERSION" \
    --generate-notes \
    "$ARTIFACT"

echo ""
echo "=== Release v$VERSION complete! ==="
echo "GitHub release: $(gh release view "v$VERSION" --json url -q .url)"
