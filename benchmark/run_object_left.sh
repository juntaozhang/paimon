#!/usr/bin/env bash
# Focused OBJECT_LEFT scaling bench: L=64, hit targets on a clean 10-step ladder (0..100).
# Usage:
#   bash run_object_left.sh [TAG] [--with=DIR] [--wo=DIR]
#   TAG makes output filenames unique so runs don't overwrite.
#   WITH/WO default to the paths below; override via flags (--with/--wo) or env vars (WITH/WO),
#   e.g.  WITH=/other/nxt bash run_object_left.sh mytag
#         bash run_object_left.sh mytag --with=/other/nxt --wo=/other/plain
# Output: bench_withcache_ol_<TAG>.txt and bench_wocache_ol_<TAG>.txt, written next to this script.
set -e

TAG=${TAG:-latest}
WITH=${WITH:-/usr/local/src/github.com/apache/paimon-nxt}
WO=${WO:-/usr/local/src/github.com/apache/paimon}

# Parse flags; a bare positional arg is still treated as TAG (back-compat).
while [ $# -gt 0 ]; do
  case "$1" in
    --with=*) WITH="${1#*=}" ;;
    --wo=*)   WO="${1#*=}" ;;
    --tag=*)  TAG="${1#*=}" ;;
    --*)      echo "unknown flag: $1" >&2; exit 1 ;;
    *)        TAG="$1" ;;
  esac
  shift
done

LOCAL="$(cd "$(dirname "$0")" && pwd)"
WITH_COMMON=$WITH/paimon-common/target/classes
WO_COMMON=$WO/paimon-common/target/classes

( cd "$WITH" && mvn -q -o -pl paimon-common dependency:build-classpath -Dmdep.outputFile="$LOCAL/cp_withcache.txt" )
( cd "$WO" && mvn -q -o -pl paimon-common dependency:build-classpath -Dmdep.outputFile="$LOCAL/cp_wocache.txt" )
WITH_CP="$WITH_COMMON:$(cat $LOCAL/cp_withcache.txt)"
WO_CP="$WO_COMMON:$(cat $LOCAL/cp_wocache.txt)"

rm -rf "$LOCAL/classes_withcache" "$LOCAL/classes_wocache"
mkdir -p "$LOCAL/classes_withcache" "$LOCAL/classes_wocache"

echo ">> compiling with-cache bench"
javac -cp "$WITH_CP" -d "$LOCAL/classes_withcache" "$LOCAL/BranchKit.java" "$LOCAL/WithCacheVariantBranchBench.java"

echo ">> compiling w/o-cache bench"
javac -cp "$WO_CP" -d "$LOCAL/classes_wocache" "$LOCAL/BranchKit.java" "$LOCAL/WithoutCacheVariantBranchBench.java"

F="-Dfilter=OBJECT_LEFT_L64 -DhitStep=10"
GC="-XX:+UseG1GC -Xms6g -Xmx6g"

echo ">> WITH CACHE (OBJECT_LEFT_L64, R=500k, warm=20k, G1 6g, TAG=$TAG)"
java $GC $F -Dr=500000 -DwhRows=20000 -Dbench.file="$LOCAL/bench_withcache_ol_$TAG.txt" -cp "$WITH_CP:$LOCAL/classes_withcache" org.apache.paimon.data.variant.WithCacheVariantBranchBench

echo ">> W/O CACHE (OBJECT_LEFT_L64, R=500k, warm=20k, G1 6g, TAG=$TAG)"
java $GC $F -Dr=500000 -DwhRows=20000 -Dbench.file="$LOCAL/bench_wocache_ol_$TAG.txt" -cp "$WO_CP:$LOCAL/classes_wocache" org.apache.paimon.data.variant.WithoutCacheVariantBranchBench

echo ">> done; results in bench_wocache_ol_$TAG.txt and bench_withcache_ol_$TAG.txt"
