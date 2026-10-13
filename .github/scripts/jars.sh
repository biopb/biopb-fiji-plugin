#!/usr/bin/env bash
# Guards Fiji's shared jars (netty, grpc-netty, ...) around "mvn install -Dscijava.app.directory":
#   jars.sh before Fiji.app   records the jars Fiji ships
#   jars.sh after  Fiji.app   fails if any of them is gone or was replaced by another version
# The plugin must only add jars Fiji lacks; replacing a shared one can break other plugins.
set -euo pipefail
mode=$1; app=$2; list="${RUNNER_TEMP:-/tmp}/fiji-jars-before.txt"
case "$mode" in
  before) (cd "$app/jars" && ls -p | grep -v / | sort) > "$list"; wc -l < "$list" ;;
  after)
    missing=$(comm -23 "$list" <(cd "$app/jars" && ls -p | grep -v / | sort))
    if [ -n "$missing" ]; then
      echo "::error::the install removed or replaced Fiji's own jars:"; echo "$missing"; exit 1
    fi
    echo "OK: no Fiji jar was removed or replaced" ;;
  *) echo "usage: jars.sh before|after Fiji.app" >&2; exit 2 ;;
esac
