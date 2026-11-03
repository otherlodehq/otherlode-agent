#!/usr/bin/env bash
# Downloads the OpenTelemetry Java agent jar into an empty directory and verifies it against values
# pinned here: size, SHA-1, SHA-256, and the PGP signature against the project's key fingerprint.
# Usage: fetch-otel-agent.sh <empty-dir>. Prints the jar's path. Exits non-zero on any mismatch.
set -euo pipefail

VERSION=2.32.0
BASE_URL="https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/$VERSION"
JAR_NAME="opentelemetry-javaagent-$VERSION.jar"
SIZE=26418479
SHA1=96c39c79dc17f75fc8af3531ddf2b1f011f65508
SHA256=f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82
FINGERPRINT=3F05DDA9F317301E927136D417A27CE7A60FF5F0
KEY_URL="https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$FINGERPRINT"

fail() {
  echo "fetch-otel-agent: $*" >&2
  exit 1
}

[ "$#" -eq 1 ] || fail "usage: $0 <empty-dir>"
dir=$1
if [ -e "$dir" ]; then
  [ -d "$dir" ] || fail "$dir exists and is not a directory"
  [ -z "$(ls -A "$dir")" ] || fail "$dir is not empty"
fi

if command -v sha1sum >/dev/null 2>&1; then
  sha1_of() { sha1sum "$1" | cut -d' ' -f1; }
  sha256_of() { sha256sum "$1" | cut -d' ' -f1; }
else
  sha1_of() { shasum -a 1 "$1" | cut -d' ' -f1; }
  sha256_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
fi
size_of() { wc -c <"$1" | tr -d ' '; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

for file in "$JAR_NAME" "$JAR_NAME.sha1" "$JAR_NAME.asc"; do
  curl -fsSL -o "$work/$file" "$BASE_URL/$file" || fail "could not download $BASE_URL/$file"
done

actual_size=$(size_of "$work/$JAR_NAME")
[ "$actual_size" = "$SIZE" ] || fail "size is $actual_size bytes, expected $SIZE"

actual_sha1=$(sha1_of "$work/$JAR_NAME")
published_sha1=$(tr -d '[:space:]' <"$work/$JAR_NAME.sha1" | cut -c1-40)
[ "$published_sha1" = "$actual_sha1" ] || fail "the published .sha1 is $published_sha1 but the jar's SHA-1 is $actual_sha1"
[ "$actual_sha1" = "$SHA1" ] || fail "SHA-1 is $actual_sha1, expected $SHA1"

actual_sha256=$(sha256_of "$work/$JAR_NAME")
[ "$actual_sha256" = "$SHA256" ] || fail "SHA-256 is $actual_sha256, expected $SHA256"

export GNUPGHOME="$work/gnupg"
mkdir -m 700 "$GNUPGHOME"
curl -fsSL -o "$work/key.asc" "$KEY_URL" || fail "could not download the key from $KEY_URL"
gpg --batch --quiet --import "$work/key.asc" 2>"$work/import.err" \
  || fail "could not import the key from $KEY_URL: $(tr '\n' ' ' <"$work/import.err")"
imported=$(gpg --batch --with-colons --fingerprint | awk -F: '$1 == "fpr" { print $10; exit }')
[ "$imported" = "$FINGERPRINT" ] || fail "the key from the keyserver has fingerprint ${imported:-none}, expected $FINGERPRINT"
gpg --batch --status-fd 1 --verify "$work/$JAR_NAME.asc" "$work/$JAR_NAME" >"$work/verify.status" 2>"$work/verify.err" \
  || fail "gpg --verify failed: $(tr '\n' ' ' <"$work/verify.err")"
grep -q "^\[GNUPG:\] VALIDSIG $FINGERPRINT " "$work/verify.status" \
  || fail "the signature is valid but not by $FINGERPRINT"

mkdir -p "$dir"
mv "$work/$JAR_NAME" "$dir/$JAR_NAME"
echo "size $actual_size, SHA-1 $actual_sha1, SHA-256 $actual_sha256, signed by $FINGERPRINT" >&2
printf '%s/%s\n' "$(cd "$dir" && pwd)" "$JAR_NAME"
