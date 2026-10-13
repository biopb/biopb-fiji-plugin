#!/usr/bin/env bash
# Guards Fiji's shared jars (netty, ...) around "mvn install -Dscijava.app.directory":
#   jars.sh before Fiji.app   records the jars Fiji ships (checksums) and checks that every `provided`
#                             dependency the pom pins to a version is in Fiji at exactly that version
#   jars.sh after  Fiji.app   fails unless the install ADDED exactly the jars in .github/expected-added-jars.txt
#                             and removed or changed none of Fiji's own; also fails on a duplicate artifact
#   jars.sh after  Fiji.app --update-expected   rewrites that list from the install (review the diff!)
# The plugin must only add jars Fiji lacks; replacing or duplicating a shared one can break other plugins.
set -euo pipefail
export LC_ALL=C
mode=$1; app=$2; opt=${3:-}
tmp=${RUNNER_TEMP:-/tmp}; before="$tmp/fiji-jars-before.txt"; expected=.github/expected-added-jars.txt
sums() { (cd "$app/jars" && find . -maxdepth 1 -type f -name '*.jar' -printf '%f\n' | sort | xargs sha256sum | sed 's/  */  /' | sort); }
# Artifact base name: the file name without its trailing version (-<digit>...jar).
base() { sed -E 's/-[0-9][^/]*\.jar$//'; }
case "$mode" in
  before)
    sums > "$before"; echo "$(wc -l < "$before") Fiji jars recorded"
    python3 - "$app/jars" <<'P'
import os, re, sys, xml.etree.ElementTree as ET
def fail(msg):
    print("::error::jars.sh: " + msg); sys.exit(1)
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
try:
    root = ET.parse("pom.xml").getroot()
except (OSError, ET.ParseError) as e:
    fail("cannot read pom.xml (run from the repository root): %s" % e)
props = {}
for e in root.findall("m:properties/*", ns):
    props[e.tag.split("}")[1]] = (e.text or "").strip()
def resolve(v, who):
    def sub(m):
        if not props.get(m.group(1)):
            fail("%s uses the property ${%s}, which is missing or empty in the pom" % (who, m.group(1)))
        return props[m.group(1)]
    return re.sub(r"\$\{(.+?)\}", sub, v)
jars = set(os.listdir(sys.argv[1]))
bad, unchecked = [], []
for d in root.findall("m:dependencies/m:dependency", ns):
    g, a, v, s = ((d.findtext("m:" + t, namespaces=ns) or "").strip() for t in ("groupId", "artifactId", "version", "scope"))
    if s != "provided":
        continue
    if not v:
        unchecked.append("%s:%s" % (g, a)); continue  # version managed by pom-scijava's BOM
    jar = "%s-%s.jar" % (a, resolve(v, "%s:%s" % (g, a)))
    if jar not in jars:
        bad.append(jar)
if bad:
    fail("the pom pins provided jars that this Fiji does not ship (Fiji changed them?): " + ", ".join(bad))
print("OK: every versioned provided dependency is in Fiji at the pinned version")
if unchecked:
    print("not checked (version from the BOM): " + ", ".join(unchecked))
P
    ;;
  after)
    now=$(mktemp); sums > "$now"
    changed=$(comm -23 "$before" "$now" | awk '{print $2}')
    if [ -n "$changed" ]; then
      echo "::error::the install removed, replaced or changed Fiji's own jars:"; echo "$changed"; exit 1
    fi
    # the added jars, with the plugin's own versioned name normalised
    comm -13 "$before" "$now" | awk '{print $2}' | sed -E 's/^biopb-fiji-plugin-.*\.jar$/biopb-fiji-plugin-VERSION.jar/' | sort > "$tmp/fiji-jars-added.txt"
    echo "Added jars:"; sed 's/^/  /' "$tmp/fiji-jars-added.txt"
    if [ "$opt" = "--update-expected" ]; then
      { grep '^#' "$expected"; cat "$tmp/fiji-jars-added.txt"; } > "$tmp/expected.new" && mv "$tmp/expected.new" "$expected"
      echo "updated $expected"; exit 0
    fi
    grep -v '^#' "$expected" | grep -v '^$' | sort > "$tmp/fiji-jars-expected.txt"
    if ! diff -u "$tmp/fiji-jars-expected.txt" "$tmp/fiji-jars-added.txt" > "$tmp/fiji-jars.diff"; then
      echo "::error::the install added a different set of jars than $expected (- expected, + actual):"
      cat "$tmp/fiji-jars.diff"
      echo "If the change is intended, run: .github/scripts/jars.sh after $app --update-expected  and commit the list."
      exit 1
    fi
    # secondary: an added jar must not share an artifact with another jar (Fiji's, or another added one)
    dupes=$({ awk '{print "F " $2}' "$before"; grep -v VERSION "$tmp/fiji-jars-added.txt" | sed 's/^/A /'; } |
      while read -r kind f; do echo "$(echo "$f" | base) $kind $f"; done |
      awk '{n[$1]++; l[$1] = l[$1] " " $3; if ($2 == "A") a[$1] = 1} END {for (k in n) if (n[k] > 1 && (k in a)) print k ":" l[k]}')
    if [ -n "$dupes" ]; then
      echo "::error::an artifact is on the classpath at more than one version:"; echo "$dupes"; exit 1
    fi
    echo "OK: the install added exactly the expected jars and changed none of Fiji's" ;;
  *) echo "usage: jars.sh before|after Fiji.app [--update-expected]" >&2; exit 2 ;;
esac
