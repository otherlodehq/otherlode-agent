#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
central="${MAVEN_CENTRAL:-https://repo1.maven.org/maven2}"

versions() {
  curl --fail --silent --show-error --location --retry 3 "$central/$1/maven-metadata.xml" \
    | sed -n 's|.*<version>\(.*\)</version>.*|\1|p'
}

newest() {
  # grep finds nothing for a line with no stable release; the caller then names the line.
  versions "$1" | { grep -E "$2" || true; } | sort -V | tail -n 1
}

property() {
  sed -n "s/^$1=//p" "$root/gradle.properties"
}

extend() {
  local list="$1" release="$2"
  if [[ -z "$release" ]]; then
    echo "no stable release found for $3" >&2
    exit 1
  fi
  case ",$list," in
    *",$release,"*) echo "$list" ;;
    *) echo "$list,$release" ;;
  esac
}

kotlinc="$(newest org/jetbrains/kotlin/kotlin-compiler-embeddable '^[0-9]+\.[0-9]+\.[0-9]+$')"
scala212="$(newest org/scala-lang/scala-compiler '^2\.12\.[0-9]+$')"
scala213="$(newest org/scala-lang/scala-compiler '^2\.13\.[0-9]+$')"
scala3="$(newest org/scala-lang/scala3-compiler_3 '^3\.[0-9]+\.[0-9]+$')"

scalac="$(property otherlode.matrix.scalac)"
scalac="$(extend "$scalac" "$scala212" scalac-2.12)"
scalac="$(extend "$scalac" "$scala213" scalac-2.13)"
scalac="$(extend "$scalac" "$scala3" scalac-3)"
kotlincList="$(extend "$(property otherlode.matrix.kotlinc)" "$kotlinc" kotlinc)"

echo "newest kotlinc $kotlinc, scalac 2.12 $scala212, scalac 2.13 $scala213, scalac 3 $scala3" >&2

args="-Potherlode.matrix.kotlinc=$kotlincList -Potherlode.matrix.scalac=$scalac"
echo "$args"
if [[ "${1:-}" == "--github" ]]; then
  echo "args=$args" >> "$GITHUB_OUTPUT"
fi
