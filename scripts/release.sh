#!/usr/bin/env bash
# Cuts an agent release from Luke's machine (ADR 0063):
#
#   scripts/release.sh X.Y.Z [--dry-run] [--allow-stale-bindings]
#
# Every check runs before any change. The script then commits `version=X.Y.Z`, tags `vX.Y.Z`,
# commits the next SNAPSHOT and pushes the branch and the tag in one atomic push; the tag starts
# the release workflow. `--dry-run` stops after the checks. `--allow-stale-bindings` skips the
# check that the server and the collector carry bindings at least as new as the proto, for a schema
# change nothing reads. Needs git, gh (signed in, with access to otherlode-server), buf and jq.
set -euo pipefail

BSR_MODULE='buf.build/gen/go/otherlode/otherlode/protocolbuffers/go'
BSR_SCHEMA='buf.build/otherlode/otherlode'
SERVER_REPO='otherlodehq/otherlode-server'
COLLECTOR_REPO='otherlodehq/otherlode-collector'
AGENT_REPO='otherlodehq/otherlode-agent'

die() {
  echo "release.sh: $*" >&2
  exit 1
}

# Reads a go.mod on stdin and prints "<timestamp> <commit>" from the version of the BSR bindings
# requirement: the schema commit's UTC creation time (YYYYMMDDHHMMSS) and the first twelve hex
# digits of its id, whether the requirement sits in a `require (...)` block or on one line.
bsr_pin_from_gomod() {
  local line version
  line=$(grep -F "$BSR_MODULE " | head -n 1) || true
  [ -n "$line" ] || return 1
  version=$(echo "$line" | awk -v m="$BSR_MODULE" '{ for (i = 1; i <= NF; i++) if ($i == m) { print $(i + 1); exit } }')
  [[ "$version" =~ -([0-9]{14})-([0-9a-f]{12}) ]] || return 1
  echo "${BASH_REMATCH[1]} ${BASH_REMATCH[2]}"
}

# Seconds since the epoch for a YYYYMMDDHHMMSS UTC timestamp, on GNU and BSD date alike.
epoch_from_timestamp() {
  local ts=$1
  if date -u -d "${ts:0:4}-${ts:4:2}-${ts:6:2} ${ts:8:2}:${ts:10:2}:${ts:12:2}" +%s 2>/dev/null; then
    return 0
  fi
  date -u -j -f '%Y%m%d%H%M%S' "$ts" +%s
}

gomod_at() { # repo ref
  gh api "repos/$1/contents/go.mod?ref=$2" --jq .content | base64 -d
}

# The collector's newest release tag: its latest release, else the highest v* tag.
latest_collector_tag() {
  local tag
  if tag=$(gh api "repos/$COLLECTOR_REPO/releases/latest" --jq .tag_name 2>/dev/null) && [ -n "$tag" ]; then
    echo "$tag"
    return 0
  fi
  tag=$(gh api "repos/$COLLECTOR_REPO/tags" --paginate --jq '.[].name' 2>/dev/null | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1) || true
  [ -n "$tag" ] || die "the collector has no release and no v* tag: cut the collector's first release before the agent's (ADR 0062)"
  echo "$tag"
}

# Passes when a consumer's pinned bindings are the schema's newest BSR commit, or one created no
# earlier. Both times are BSR's, so no git clock is compared with a registry clock.
check_binding() { # label repo ref newest_commit newest_epoch
  local label=$1 repo=$2 ref=$3 newest=$4 newest_epoch=$5 gomod pin ts commit
  gomod=$(gomod_at "$repo" "$ref") || die "cannot read go.mod of $repo at $ref through gh"
  pin=$(echo "$gomod" | bsr_pin_from_gomod) || die "no BSR bindings requirement in go.mod of $repo at $ref"
  ts=${pin% *}
  commit=${pin#* }
  echo "  $label ($repo at $ref) pins schema commit $commit from $ts UTC"
  if [ "${newest:0:12}" = "$commit" ]; then return 0; fi
  local pin_epoch
  pin_epoch=$(epoch_from_timestamp "$ts") || die "cannot read the time $ts in $label's bindings version"
  [[ "$pin_epoch" =~ ^[0-9]+$ && "$newest_epoch" =~ ^[0-9]+$ ]] || die "cannot compare BSR times for $label"
  if [ "$pin_epoch" -lt "$newest_epoch" ]; then
    die "$label pins bindings older than the schema's newest BSR commit ${newest:0:12}: bump it and wait for its master (or release) to carry them, or pass --allow-stale-bindings if nothing reads the change"
  fi
}

# Skipped when no proto changed since the last release. Otherwise BSR's master label must already
# carry the agent's proto (the buf workflow pushes it on every master commit that touches it), and
# the server's master and the collector's latest release must pin that commit or a newer one.
check_bindings() { # last_tag
  local last=$1 label_json commit_json newest created source tag newest_epoch
  if [ -n "$last" ] && git diff --quiet "$last" HEAD -- src/main/proto; then
    echo "Bindings check: no proto change since $last, skipped"
    return 0
  fi
  label_json=$(buf registry module label info "$BSR_SCHEMA:master" --format json) || die "cannot read $BSR_SCHEMA:master through buf"
  newest=$(echo "$label_json" | jq -r .commit)
  commit_json=$(buf registry module commit info "$BSR_SCHEMA:$newest" --format json) || die "cannot read BSR commit $newest through buf"
  created=$(echo "$commit_json" | jq -r .create_time)
  source=$(echo "$commit_json" | jq -r '.source_control_url // ""' | sed -n 's#.*/commit/\([0-9a-f]*\)$#\1#p')
  [ -n "$source" ] || die "BSR commit $newest names no agent commit"
  git cat-file -e "$source^{commit}" 2>/dev/null || die "BSR's master label names agent commit $source, which this clone does not have"
  git diff --quiet "$source" HEAD -- src/main/proto ||
    die "BSR's master label is at agent commit ${source:0:12}, but src/main/proto changed after it: wait for the buf workflow to push the schema"
  # create_time is RFC 3339 UTC with fractional seconds; whole seconds are enough here.
  created=$(echo "$created" | sed -E 's/^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2}).*/\1\2\3\4\5\6/')
  [[ "$created" =~ ^[0-9]{14}$ ]] || die "cannot read the creation time of BSR commit $newest"
  newest_epoch=$(epoch_from_timestamp "$created") || die "cannot convert BSR time $created"
  echo "Bindings check: the schema's newest BSR commit is ${newest:0:12}, from $created UTC (agent ${source:0:12})"
  check_binding 'server' "$SERVER_REPO" master "$newest" "$newest_epoch"
  tag=$(latest_collector_tag) || exit 1
  check_binding 'collector' "$COLLECTOR_REPO" "$tag" "$newest" "$newest_epoch"
}

main() {
  local version='' dry_run=0 allow_stale=0 arg
  for arg in "$@"; do
    case "$arg" in
      --dry-run) dry_run=1 ;;
      --allow-stale-bindings) allow_stale=1 ;;
      -*) die "unknown option $arg" ;;
      *)
        [ -z "$version" ] || die "more than one version given"
        version=$arg
        ;;
    esac
  done
  [[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
    die "usage: scripts/release.sh X.Y.Z [--dry-run] [--allow-stale-bindings] (digits only, no leading zeros)"
  local major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]}
  local tag="v$version" next="$major.$minor.$((patch + 1))-SNAPSHOT"

  cd "$(git rev-parse --show-toplevel)"
  local tool
  for tool in git gh jq; do command -v "$tool" >/dev/null || die "$tool is not installed"; done
  if [ "$allow_stale" -eq 0 ]; then command -v buf >/dev/null || die "buf is not installed"; fi
  gh auth status >/dev/null 2>&1 || die "gh is not signed in: run gh auth login"

  [ "$(git rev-parse --abbrev-ref HEAD)" = master ] || die "not on master"
  [ -z "$(git status --porcelain --untracked-files=all)" ] || die "the working tree is not clean (tracked or untracked changes)"
  git fetch --quiet --tags origin
  [ "$(git rev-parse master)" = "$(git rev-parse origin/master)" ] || die "local master differs from origin/master: pull or push first"
  if git rev-parse --quiet --verify "refs/tags/$tag" >/dev/null; then die "tag $tag already exists locally"; fi
  if [ -n "$(git ls-remote --tags origin "refs/tags/$tag")" ]; then die "tag $tag already exists on origin"; fi
  local declared last
  declared=$(sed -n 's/^version=//p' gradle.properties)
  [ "$declared" = "$version-SNAPSHOT" ] ||
    die "gradle.properties declares $declared; releasing $version needs $version-SNAPSHOT there first"
  last=$(git tag --list 'v*.*.*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1 || true)
  if [ -n "$last" ] && [ "$(printf '%s\n%s\n' "${last#v}" "$version" | sort -V | tail -n 1)" != "$version" ]; then
    die "$version is not above the last release, $last"
  fi

  [ -f CHANGELOG.md ] || die "CHANGELOG.md is missing"
  local section
  section=$(scripts/changelog-section.sh "$version")
  [[ "$section" =~ [^[:space:]] ]] || die "CHANGELOG.md has no non-empty '## [$version]' section"

  if [ "$allow_stale" -eq 1 ]; then
    echo "WARNING: --allow-stale-bindings given, the server and collector bindings are NOT checked." >&2
  else
    check_bindings "$last"
  fi

  if [ "$dry_run" -eq 1 ]; then
    cat <<EOT
Dry run: no file, commit or tag changed (the fetch updated remote-tracking refs). Without --dry-run this would:
  - commit version=$version in gradle.properties as "Release $version"
  - tag $tag ("otherlode-agent $version") on that commit
  - commit version=${next} as "Begin ${next%-SNAPSHOT}"
  - git push --atomic origin master $tag
EOT
    return 0
  fi

  sed -i.bak -E "s/^version=.*/version=$version/" gradle.properties && rm gradle.properties.bak
  git add gradle.properties
  git commit --quiet -m "Release $version"
  git tag -a "$tag" -m "otherlode-agent $version"
  sed -i.bak -E "s/^version=.*/version=$next/" gradle.properties && rm gradle.properties.bak
  git add gradle.properties
  git commit --quiet -m "Begin ${next%-SNAPSHOT}"
  if ! git push --atomic origin master "$tag"; then
    if [ -n "$(git ls-remote --tags origin "refs/tags/$tag" 2>/dev/null)" ]; then
      die "the push reported a failure but $tag is on origin, so it went through: check the release workflow, and do not reset"
    fi
    die "the push failed and $tag is not on origin. To undo locally: git tag -d $tag && git reset --hard origin/master"
  fi

  cat <<EOT

Pushed $tag. The release workflow runs at:
  https://github.com/$AGENT_REPO/actions/workflows/release.yml
While publishing is USER_MANAGED: publish the deployment on central.sonatype.com, then publish the draft GitHub release.
EOT
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
