#!/usr/bin/env bash
# Prints the newest stable release of each framework line the endpoint modules' tests run against,
# as Gradle -P arguments (the otherlode.framework.* properties the modules' build files read).
# Pre-releases (-M, -RC, -beta, -alpha, -eap, -SNAPSHOT and any other suffixed version) are never
# picked. A newer major than any supported line gets a warning annotation, not a failure:
# supporting a new major is a decision, not drift.
#
#   newest-frameworks.sh            prints the arguments
#   newest-frameworks.sh --github   writes them to $GITHUB_OUTPUT as `args=...` as well
set -euo pipefail

central="${MAVEN_CENTRAL:-https://repo1.maven.org/maven2}"

# All versions of group/artifact, from maven-metadata.xml.
versions() {
  curl --fail --silent --show-error --location --retry 3 "$central/$1/maven-metadata.xml" \
    | sed -n 's|.*<version>\(.*\)</version>.*|\1|p'
}

# The newest purely numeric version (digits and dots) matching the grep pattern $2.
newest() {
  # grep finds nothing for a line with no stable release; the caller then names the line.
  versions "$1" | { grep -E "$2" || true; } | sort -V | tail -n 1
}

# Fails when a line resolves to nothing.
require() {
  if [[ -z "$1" ]]; then
    echo "no stable release found for $2" >&2
    exit 1
  fi
}

# Warns when $1 (group/artifact) has a stable release in a major above $2.
warn_newer_major() {
  local above
  above="$(versions "$1" | grep -E '^[0-9]+(\.[0-9]+)*$' | awk -F. -v max="$2" '$1 > max' | sort -V | tail -n 1)"
  if [[ -n "$above" ]]; then
    echo "::warning title=Unsupported $3 major::$3 $above is released and no supported line covers it. Supporting it is a decision, not drift."
  fi
}

spring="org/springframework/spring-webmvc"
ktor="io/ktor/ktor-server-core-jvm"
jersey="org/glassfish/jersey/containers/jersey-container-jdk-http"
otelApi="io/opentelemetry/instrumentation/opentelemetry-instrumentation-api"
otelSdk="io/opentelemetry/opentelemetry-sdk"

spring53="$(newest "$spring" '^5\.3\.[0-9]+$')"
spring6="$(newest "$spring" '^6\.[0-9]+\.[0-9]+$')"
spring7="$(newest "$spring" '^7\.[0-9]+\.[0-9]+$')"
ktor2="$(newest "$ktor" '^2\.[0-9]+\.[0-9]+$')"
ktor3="$(newest "$ktor" '^3\.[0-9]+\.[0-9]+$')"
jersey4="$(newest "$jersey" '^4\.[0-9]+\.[0-9]+$')"
jersey3="$(newest "$jersey" '^3\.1\.[0-9]+$')"
jersey2="$(newest "$jersey" '^2\.[0-9]+(\.[0-9]+)?$')"
otelApiVersion="$(newest "$otelApi" '^2\.[0-9]+\.[0-9]+$')"
otelSdkVersion="$(newest "$otelSdk" '^1\.[0-9]+\.[0-9]+$')"

require "$spring53" "Spring Framework 5.3"
require "$spring6" "Spring Framework 6"
require "$spring7" "Spring Framework 7"
require "$ktor2" "Ktor 2"
require "$ktor3" "Ktor 3"
require "$jersey4" "Jersey 4"
require "$jersey3" "Jersey 3.1"
require "$jersey2" "Jersey 2"
require "$otelApiVersion" "opentelemetry-instrumentation-api 2"
require "$otelSdkVersion" "opentelemetry-sdk 1"

warn_newer_major "$spring" 7 "Spring Framework"
warn_newer_major "$ktor" 3 "Ktor"
warn_newer_major "$jersey" 4 "Jersey"
warn_newer_major "$otelApi" 2 "opentelemetry-instrumentation-api"
warn_newer_major "$otelSdk" 1 "opentelemetry-sdk"

args="-Potherlode.framework.spring53=$spring53 -Potherlode.framework.spring6=$spring6"
args+=" -Potherlode.framework.spring7=$spring7 -Potherlode.framework.ktor2=$ktor2"
args+=" -Potherlode.framework.ktor3=$ktor3 -Potherlode.framework.jersey3=$jersey3"
args+=" -Potherlode.framework.jersey4=$jersey4 -Potherlode.framework.jersey2=$jersey2"
args+=" -Potherlode.framework.otelInstrumentationApi=$otelApiVersion"
args+=" -Potherlode.framework.otelSdk=$otelSdkVersion"
echo "$args"
if [[ "${1:-}" == "--github" ]]; then
  echo "args=$args" >> "$GITHUB_OUTPUT"
fi
