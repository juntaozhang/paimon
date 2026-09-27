#!/usr/bin/env python3
"""Merge bench files into a per-branch speedup table.

Each file has lines like:
    === branch OBJECT_LEFT (L=8) ===
       hit%       ns/row   (ns/row, R=100000)
         0%       10475.7
       100%       14807.4
Each file is the ns/row timing for one config (w/o cache or with cache); the
comparison and speedup are produced here by pairing files.
Speedup = w/o_cache_ns / with_cache_ns  (>1 means with cache wins).

Usage: compare.py [WO_CACHE_FILE] [WITH_CACHE_FILE]
        compare.py WO1 WITH1 WO2 WITH2 ...   (reports median across the runs)
"""
import re
import sys
import statistics


def parse(path):
    out = {}
    cur = None
    with open(path) as f:
        for line in f:
            m = re.match(r"=== branch (\S+) \(L=(\d+)\) ===", line)
            if m:
                cur = (m.group(1), int(m.group(2)))
                out[cur] = {}
                continue
            m = re.match(r"\s*(\d+)%\s+([\d.]+)(.*)", line)
            if m and cur:
                hit = int(m.group(1))
                ns = float(m.group(2))
                gcm = re.search(r"\*GC\((\w+),\s*\+(\d+)ms\)", m.group(3))
                gc = "%s+%sms" % (gcm.group(1), gcm.group(2)) if gcm else "-"
                out[cur][hit] = (ns, gc)
    return out


def single(wocache, withcache):
    branches = sorted(set(wocache) | set(withcache), key=lambda b: (b[0], b[1]))
    all_hits = set()
    for d in list(wocache.values()) + list(withcache.values()):
        all_hits.update(d.keys())
    hits = sorted(all_hits)

    print("%-20s %7s %12s %7s %12s %7s %9s" %
          ("branch", "hit%", "w/o ns", "GC", "with ns", "GC", "speedup"))
    print("-" * 82)
    for b in branches:
        for h in hits:
            o = wocache.get(b, {}).get(h)
            n = withcache.get(b, {}).get(h)
            if o is None or n is None:
                continue
            ons, ogc = o
            nns, ngc = n
            sp = ons / nns if nns else float("nan")
            print("%-20s %6d%% %12.1f %7s %12.1f %7s %8.2fx" %
                  (b[0] + "(L=%d)" % b[1], h, ons, ogc, nns, ngc, sp))
        print()


def aggregate(pairs):
    # pairs: list of (wocache_path, withcache_path); report median per (branch, hit).
    runs_wo = [parse(o) for o, _ in pairs]
    runs_wc = [parse(n) for _, n in pairs]
    n = len(pairs)

    branches = sorted({b for d in runs_wo + runs_wc for b in d}, key=lambda b: (b[0], b[1]))
    all_hits = set()
    for d in runs_wo + runs_wc:
        for h in d.values():
            all_hits.update(h.keys())
    hits = sorted(all_hits)

    print("median of %d runs" % n)
    print("%-20s %7s %12s %7s %12s %7s %9s" %
          ("branch", "hit%", "w/o ns", "GC", "with ns", "GC", "speedup"))
    print("-" * 82)
    for b in branches:
        for h in hits:
            o_list = [runs_wo[r].get(b, {}).get(h) for r in range(n)]
            n_list = [runs_wc[r].get(b, {}).get(h) for r in range(n)]
            o_list = [x for x in o_list if x is not None]
            n_list = [x for x in n_list if x is not None]
            if not o_list or not n_list:
                continue
            mo = statistics.median([x[0] for x in o_list])
            mn = statistics.median([x[0] for x in n_list])
            sp_ratios = [x[0] / y[0] for x, y in zip(o_list, n_list) if y[0]]
            sp_ratio = statistics.median(sp_ratios) if sp_ratios else float("nan")
            ogc = statistics.median([int(x[1].split("+")[1][:-2]) for x in o_list if x[1] != "-"]) if any(x[1] != "-" for x in o_list) else 0
            ngc = statistics.median([int(x[1].split("+")[1][:-2]) for x in n_list if x[1] != "-"]) if any(x[1] != "-" for x in n_list) else 0

            def fmt(g):
                return "-+%dms" % int(g) if g else "-"
            print("%-20s %6d%% %12.1f %7s %12.1f %7s %8.2fx" %
                  (b[0] + "(L=%d)" % b[1], h, mo, fmt(ogc), mn, fmt(ngc), sp_ratio))
        print()


def main():
    args = sys.argv[1:]
    if len(args) == 2:
        single(parse(args[0]), parse(args[1]))
    elif len(args) >= 4 and len(args) % 2 == 0:
        pairs = [(args[i], args[i + 1]) for i in range(0, len(args), 2)]
        aggregate(pairs)
    else:
        print("usage: compare.py WO_CACHE WITH_CACHE  |  compare.py WO1 WITH1 WO2 WITH2 ...", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
