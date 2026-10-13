#!/usr/bin/env bash
# Guards Fiji's shared jars (netty, ...) around "mvn install -Dscijava.app.directory":
#   jars.sh before Fiji.app   records the jars Fiji ships (checksums), and checks that every
#                             io.netty artifact the pom declares `provided` is in Fiji at that version
#   jars.sh after  Fiji.app   fails if a recorded jar is gone or changed, or if an added jar is another
#                             version of an artifact Fiji already has; prints the added jars
# The plugin must only add jars Fiji lacks; replacing or duplicating a shared one can break other plugins.
set -euo pipefail
export LC_ALL=C
mode=$1; app=$2; list="${RUNNER_TEMP:-/tmp}/fiji-jars-before.txt"
sums() { (cd "$app/jars" && find . -maxdepth 1 -type f -name '*.jar' -printf '%f\n' | sort | xargs sha256sum | sed 's/ \*\?/ /' | sort); }
case "$mode" in
  before)
    sums > "$list"; echo "$(wc -l < "$list") Fiji jars recorded"
    python3 - "$app/jars" <<'P'
import os, re, sys, xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse("pom.xml").getroot()
props = {e.tag.split("}")[1]: e.text.strip() for e in root.find("m:properties", ns)}
jars = set(os.listdir(sys.argv[1]))
bad = []
for d in root.findall("m:dependencies/m:dependency", ns):
    g, a, v, s = (d.findtext("m:" + t, namespaces=ns) for t in ("groupId", "artifactId", "version", "scope"))
    if g == "io.netty" and s == "provided":
        v = re.sub(r"\$\{(.+?)\}", lambda m: props[m.group(1)], v)
        if "%s-%s.jar" % (a, v) not in jars:
            bad.append("%s-%s.jar" % (a, v))
if bad:
    print("::error::the pom declares provided jars that this Fiji does not ship (Fiji changed netty?): " + ", ".join(bad))
    sys.exit(1)
print("OK: every provided netty jar is in Fiji at the declared version")
P
    ;;
  after)
    now=$(mktemp); sums > "$now"
    changed=$(comm -23 "$list" "$now" | awk '{print $2}')
    if [ -n "$changed" ]; then
      echo "::error::the install removed, replaced or changed Fiji's own jars:"; echo "$changed"; exit 1
    fi
    added=$(comm -13 "$list" "$now" | awk '{print $2}')
    echo "Added jars:"; echo "$added" | sed 's/^/  /'
    dupes=$(awk -v added="$added" '
      function base(f) { sub(/-[0-9][^\/]*\.jar$/, "", f); return f }
      NR == FNR { had[base($2)] = $2; next }
      END { n = split(added, a, "\n"); for (i = 1; i <= n; i++) { b = base(a[i]); if (b in had && had[b] != a[i]) print a[i] " vs " had[b] } }
    ' "$list" "$list")
    if [ -n "$dupes" ]; then
      echo "::error::added jars duplicate an artifact Fiji already has at another version:"; echo "$dupes"; exit 1
    fi
    echo "OK: no Fiji jar was removed, replaced or duplicated" ;;
  *) echo "usage: jars.sh before|after Fiji.app" >&2; exit 2 ;;
esac
