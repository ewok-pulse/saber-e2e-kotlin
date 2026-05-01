#!/usr/bin/env bash

set -eu

KOTLINC_BINARY_NAME=kotlinc-native-image

# Based on findScalaHome() from scalac script
findKotlinHome() {
    local source="${BASH_SOURCE[0]}"
    while [ -h "$source" ] ; do
        local linked="$(readlink "$source")"
        local dir="$(cd -P $(dirname "$source") && cd -P $(dirname "$linked") && pwd)"
        source="$dir/$(basename "$linked")"
    done
    (cd -P "$(dirname "$source")/.." && pwd)
}

KOTLINC_HOME_DIR="$(findKotlincHome)"
KOTLINC_BINARY_DIR="${KOTLINC_HOME_DIR}/bin"

if [ -z "$JAVA_HOME" ]; then
  echo "error: JAVA_HOME is not set; ${KOTLINC_BINARY_NAME} requires JAVA_HOME environment variable" >&2
  exit 1
fi

exec "${KOTLINC_BINARY_DIR}/${KOTLINC_BINARY_NAME}" \
  -Djava.home="${JAVA_HOME}" \
  -Dkotlin.home="${KOTLINC_HOME_DIR}" \
  "$@"
