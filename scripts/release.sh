#!/usr/bin/env bash
# Cut a new release: bump the version, fold the changelog, build the signed APK,
# commit, tag, and (with --push) push HEAD + tag to trigger the Android Release
# workflow. Runs directly through a normal shell (and the Claude Code agent)
# without PowerShell.
#
# Usage: scripts/release.sh <patch|minor|major> [--push] [--skip-build]
set -euo pipefail

PART="${1:-}"
PUSH=0
SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    -Push|--push|-p) PUSH=1 ;;
    --skip-build|-SkipBuild) SKIP_BUILD=1 ;;
  esac
done

case "$PART" in
  patch|minor|major) ;;
  *) echo "Usage: scripts/release.sh <patch|minor|major> [--push] [--skip-build]" >&2; exit 1 ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GRADLE_FILE="$ROOT/android_app/app/build.gradle"
CHANGELOG="$ROOT/CHANGELOG.md"
cd "$ROOT"

if [ -n "$(git status --porcelain)" ]; then
  echo "Working tree must be clean before releasing. Commit or stash changes first." >&2
  exit 1
fi

current_version="$(grep -E 'versionName' "$GRADLE_FILE" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
current_code="$(grep -E 'versionCode' "$GRADLE_FILE" | grep -oE '[0-9]+' | head -1)"
if [ -z "$current_version" ] || [ -z "$current_code" ]; then
  echo "Could not read versionName/versionCode from $GRADLE_FILE" >&2
  exit 1
fi

IFS='.' read -r major minor patch <<< "$current_version"
case "$PART" in
  major) major=$((major + 1)); minor=0; patch=0 ;;
  minor) minor=$((minor + 1)); patch=0 ;;
  patch) patch=$((patch + 1)) ;;
esac
next_version="$major.$minor.$patch"
next_code=$((current_code + 1))
tag="v$next_version"
date="$(date +%Y-%m-%d)"

if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  echo "Tag $tag already exists." >&2
  exit 1
fi

echo "Releasing $current_version -> $next_version (versionCode $current_code -> $next_code)"

# Bump versionName + versionCode (single line each in build.gradle). Use a
# stdout redirect rather than perl -i: in-place edit fails on Git-for-Windows perl
# ("Cannot make temp name").
edit_inplace() { # <file> <perl-expr>
  perl -pe "$2" "$1" > "$1.tmp" && mv "$1.tmp" "$1"
}
edit_inplace "$GRADLE_FILE" "s/versionCode(\\s*=?\\s*)\\d+/versionCode\${1}$next_code/"
edit_inplace "$GRADLE_FILE" "s/versionName(\\s*=?\\s*)\"\\d+\\.\\d+\\.\\d+\"/versionName\${1}\"$next_version\"/"

# Fold "## Unreleased" into a dated version section (keeping the empty Unreleased
# heading for next time); append a section if no Unreleased heading exists.
if grep -qE '^## Unreleased[[:space:]]*$' "$CHANGELOG"; then
  # Tolerate a trailing CR: on CRLF files the line is "## Unreleased\r\n", and a
  # bare [ \t]*$ would not consume the \r, so the fold would silently no-op.
  edit_inplace "$CHANGELOG" "s/^## Unreleased[ \\t\\r]*\$/## Unreleased\\n\\n## $tag - $date/"
else
  printf '\n## %s - %s\n' "$tag" "$date" >> "$CHANGELOG"
fi

# Extract the new section's bullets for the commit message body.
release_notes="$(awk -v hdr="## $tag - $date" '
  index($0, hdr) == 1 { found = 1; next }
  found && /^## / { exit }
  found { print }
' "$CHANGELOG" | sed '/^[[:space:]]*$/d')"
if [ -z "$release_notes" ]; then
  release_notes="No changelog entries recorded."
fi

if [ "$SKIP_BUILD" -eq 0 ]; then
  # The default JAVA_HOME here points at a missing JDK 8, so probe known-good JDK
  # 17 locations and override unless the current JAVA_HOME already has a java.
  for jdk in "${JAVA_HOME:-}" "/c/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot"; do
    [ -n "$jdk" ] || continue
    if [ -x "$jdk/bin/java" ] || [ -x "$jdk/bin/java.exe" ]; then
      export JAVA_HOME="$jdk"
      break
    fi
  done
  ( cd "$ROOT/android_app" && gradle assembleRelease --console=plain --offline --no-daemon )
fi

msg_file="$(mktemp)"
printf 'Release %s\n\n%s\n' "$tag" "$release_notes" > "$msg_file"
git add android_app/app/build.gradle CHANGELOG.md
git commit -F "$msg_file"
rm -f "$msg_file"
git tag -a "$tag" -m "Release $tag"

if [ "$PUSH" -eq 1 ]; then
  git push origin HEAD
  git push origin "$tag"
  echo "Pushed release commit and tag $tag to origin."
else
  echo "Push skipped. Run: git push origin HEAD && git push origin $tag"
fi

echo "Prepared release $tag"
