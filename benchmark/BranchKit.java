/*
 * Shared harness for the variant metadata cache benchmark.
 * Compiled twice (w/o cache = no cache, with cache = VariantMetadata): may only use APIs present in BOTH commits
 * and must NOT mention VariantMetadata; the cache difference lives in the driver benches.
 * Branches via real rebuild: UNSHREDDED/SCALAR/OBJECT_FULL never touch the cache; OBJECT_LEFT does.
 */
package org.apache.paimon.data.variant;

import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.variant.ShreddingUtils.ShreddedRow;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class BranchKit {

    // Timed rows per point; override with -Dr=N.
    static final int R = Integer.getInteger("r", 100_000);
    static final int WARMUP_ROWS = Integer.getInteger("whRows", 10_000); // warmup rows per point

    // In-program GC perception via the synchronous JMX GarbageCollectorMXBean counters
    // (getCollectionCount / getCollectionTime). We snapshot them around each timed pass and diff,
    // so we know whether that pass was polluted by a GC and whether it was a heavy old-generation
    // (full-equivalent on G1) collection -- no external JVM flags or async notifications needed.
    static final GcMonitor GC = new GcMonitor();

    /** The only commit-dependent seam: a real ShreddingUtils.rebuild call of the given commit. */
    public interface Rebuild {
        Variant rebuild(ShreddedRow row, VariantSchema schema);
    }

    // A "row shape": one fixed metadata dict (L field names) + a pool of value buffers sharing it.
    // Both the metadata dict and every value buffer are generated up front when the shape is built.
    static final class Shape {
        final byte[] meta;
        final int L;
        final java.util.List<String> names; // kept for reference
        // One representative object value for the shape. Its content does not affect the metadata
        // cache being benchmarked; it only needs to be a valid object variant whose keys live in
        // `meta` so the rebuild decodes the dictionary. A single buffer is reused for all rows.
        final byte[] value;

        Shape(byte[] meta, java.util.List<String> names, int L) {
            this.meta = meta;
            this.names = names;
            this.L = L;
            this.value = makeValue(names, L);
        }

        byte[] value(int j) {
            return value;
        }
    }

    // Builds a single object-variant value for a shape: an object with the shape's L keys, so the
    // rebuild must decode those keys from `meta` (the cache-critical path). Field values are constant
    // because the value content is irrelevant to the metadata cache being measured.
    static byte[] makeValue(java.util.List<String> names, int L) {
        StringBuilder vj = new StringBuilder("{");
        for (int k = 0; k < L; k++) {
            if (k > 0) {
                vj.append(',');
            }
            vj.append("\"").append(names.get(k)).append("\":0");
        }
        vj.append('}');
        return GenericVariant.fromJson(vj.toString()).value();
    }

    // Holds up to NUM_SHAPES shapes. All shapes are generated up front when the Data is built, so a
    // dataset is fully materialized on construction (buildData pre-generates); the only deferral is
    // whether runAll builds the dataset at all (it only builds the ones a branch actually uses).
    static final class Data {
        final int medianL;
        final Shape[] shapes;

        Data(int medianL) {
            this.medianL = medianL;
            this.shapes = new Shape[NUM_SHAPES];
            for (int id = 0; id < NUM_SHAPES; id++) {
                double sigma = medianL / 4.0;
                java.util.Random rnd =
                        new java.util.Random(0x9E3779B1L * (id + 1) ^ (medianL * 0x9E3779B1L));
                int Lr = (int) Math.round(medianL + sigma * rnd.nextGaussian());
                Lr = Math.max(4, Math.min(256, Lr));
                shapes[id] = buildShape(Lr, 0x1234ABCDL + id * 7919L + medianL * 31L);
            }
        }

        Shape shape(int id) {
            return shapes[id];
        }
    }

    // Sets the three columns (typed_value, value, metadata). `value` is a per-row buffer from the
    // shape's pool; `meta` is the shape's fixed top-level dictionary.
    interface SetRow {
        void apply(GenericRow row, byte[] value, byte[] meta);
    }

    static final class Branch {
        final String name;
        final VariantSchema schema;
        final SetRow setRow;
        final Data data;
        final int L; // median field count, for display only
        final int K; // number of distinct row shapes

        Branch(String name, VariantSchema schema, SetRow setRow, Data data, int L) {
            this.name = name;
            this.schema = schema;
            this.setRow = setRow;
            this.data = data;
            this.L = L;
            this.K = data.shapes.length;
        }
    }

    // A deferred branch builder: holds the branch name and a factory that constructs the Branch (and
    // thereby its dataset) only when invoked, so filtered-out branches never generate their data.
    static final class BranchSpec {
        final String name;
        final Supplier<Branch> make;

        BranchSpec(String name, Supplier<Branch> make) {
            this.name = name;
            this.make = make;
        }
    }

    public static void runAll(Supplier<Rebuild> factory, String file) throws Exception {
        PrintWriter out = new PrintWriter(new FileWriter(file));
        try {
            // Datasets are deferred at the dataset granularity: buildData pre-generates every shape
            // and value of a dataset, but runAll only materializes the datasets a branch actually
            // uses (e.g. d64 is built only if OBJECT_LEFT_L64 runs).
            java.util.Map<Integer, Data> datasets = new java.util.HashMap<>();
            java.util.function.Function<Integer, Data> dataFor =
                    (L) -> datasets.computeIfAbsent(L, k -> buildData(k));

            // Each spec builds its Branch (and thus its dataset) only when invoked, so branches that
            // are filtered out never generate their data.
            List<BranchSpec> specs = new ArrayList<>();
            specs.add(new BranchSpec("UNSHREDDED", () -> new Branch("UNSHREDDED",
                    new VariantSchema(-1, 0, 2, 2, null, null, null),
                    (row, value, meta) -> {
                        Data d = dataFor.apply(8);
                        row.setField(1, null);
                        row.setField(0, d.shape(0).value(0));
                        row.setField(2, d.shape(0).meta); // top-level metadata column must be non-null
                    },
                    dataFor.apply(8), 8)));
            specs.add(new BranchSpec("SCALAR", () -> new Branch("SCALAR",
                    new VariantSchema(0, 1, 2, 3,
                            new VariantSchema.IntegralType(VariantSchema.IntegralSize.LONG), null, null),
                    (row, value, meta) -> {
                        Data d = dataFor.apply(8);
                        row.setField(1, null);
                        row.setField(0, 42L);
                        row.setField(2, d.shape(0).meta);
                    },
                    dataFor.apply(8), 8)));
            // Partial shredding: schema says SCALAR but this row's typed_value is null and the whole
            // variant lives in `value`. Must still adopt() (hits metadata.buffer).
            specs.add(new BranchSpec("SCALAR_PARTIAL", () -> new Branch("SCALAR_PARTIAL",
                    new VariantSchema(0, 1, 2, 3,
                            new VariantSchema.IntegralType(VariantSchema.IntegralSize.LONG), null, null),
                    (row, value, meta) -> {
                        Data d = dataFor.apply(8);
                        row.setField(0, null);
                        row.setField(1, d.shape(0).value(0));
                        row.setField(2, d.shape(0).meta);
                    },
                    dataFor.apply(8), 8)));
            specs.add(new BranchSpec("OBJECT_LEFT", () -> objectLeft("OBJECT_LEFT", dataFor.apply(8), 8)));
            specs.add(new BranchSpec("OBJECT_FULL", () -> new Branch("OBJECT_FULL",
                    new VariantSchema(0, 1, 2, 3, null, objectFields(8), null),
                    (row, value, meta) -> {
                        Data d = dataFor.apply(8);
                        row.setField(0, buildLongStruct(8));
                        row.setField(1, null);
                        row.setField(2, d.shape(0).meta);
                    },
                    dataFor.apply(8), 8)));
            specs.add(new BranchSpec("OBJECT_LEFT_L32", () -> objectLeft("OBJECT_LEFT_L32", dataFor.apply(32), 32)));
            specs.add(new BranchSpec("OBJECT_LEFT_L64", () -> objectLeft("OBJECT_LEFT_L64", dataFor.apply(64), 64)));

            String filter = System.getProperty("filter");
            List<Branch> branches = new ArrayList<>();
            for (BranchSpec s : specs) {
                if (filter != null && !s.name.contains(filter)) {
                    continue;
                }
                branches.add(s.make.get());
            }

            GenericRow probe = new GenericRow(3);
            for (Branch b : branches) {
                assertConsistent(factory, b, probe);
            }

            // Hit-rate targets to sweep over. Priority:
            //   -DrandomHits=N  -> N scrambled targets sampled uniformly from [0,100] with a FIXED
            //                      seed, so every run/wave benchmarks the same set (compare.py can
            //                      pair by hit%); the order is randomized to break sweep-position/GC
            //                      correlation.
            //   -DhitStep       -> clean from/to/step ladder.
            //   (neither)       -> fixed default set.
            int[] hitTargets;
            {
                String randomHitsStr = System.getProperty("randomHits");
                if (randomHitsStr != null) {
                    int n = Integer.parseInt(randomHitsStr);
                    java.util.Random rnd = new java.util.Random(0x5EED);
                    // n random targets in [0,100]; then force 0% in at a random position so the
                    // zero-reuse (0% cache) point is always present even though it rarely samples it.
                    hitTargets = new int[n + 1];
                    for (int i = 0; i < n; i++) {
                        hitTargets[i] = rnd.nextInt(101);
                    }
                    int zp = rnd.nextInt(n + 1);
                    hitTargets[n] = hitTargets[zp];
                    hitTargets[zp] = 0;
                } else {
                    String stepStr = System.getProperty("hitStep");
                    if (stepStr != null) {
                        int from = Integer.parseInt(System.getProperty("hitFrom", "0"));
                        int to = Integer.parseInt(System.getProperty("hitTo", "100"));
                        int step = Integer.parseInt(stepStr);
                        List<Integer> list = new ArrayList<>();
                        for (int h = from; h <= to; h += step) {
                            list.add(h);
                        }
                        if (list.get(list.size() - 1) != to) {
                            list.add(to);
                        }
                        hitTargets = new int[list.size()];
                        for (int i = 0; i < hitTargets.length; i++) {
                            hitTargets[i] = list.get(i);
                        }
                    } else {
                        hitTargets = new int[] {0, 5, 15, 30, 50, 100};
                    }
                }
            }
            // Single warmup pass across all branches before any timing, so the JIT / code cache is
            // steady for every branch. This replaces the old per-hit-point warmup (which re-warmed
            // inside the timed loop). One mid hit-rate sequence exercises both the cache-hit (reuse)
            // and miss paths of each branch's rebuild.
            for (Branch b : branches) {
                Rebuild r = factory.get();
                int[] warmSeq = seqFor(50, b.K);
                runBranch(probe, r, b, warmSeq, WARMUP_ROWS);
            }

            for (Branch b : branches) {
                String head = "=== branch " + b.name + " (L=" + b.L + ") ===";
                System.out.println(head);
                out.println(head);
                String fmt = String.format("%8s %12s   (ns/row, R=%d)", "hit%", "ns/row", R);
                System.out.println(fmt);
                out.println(fmt);
                int branchMinor = 0, branchMajor = 0;
                for (int t : hitTargets) {
                    int[] seq = seqFor(t, b.K);
                    BenchResult res = bench(() -> runBranch(probe, factory.get(), b, seq));
                    String line = String.format("%7d%% %12.1f%s", t, res.nsPerRow, res.gcMarker);
                    System.out.println(line);
                    out.println(line);
                    if (res.gcSeverity == 2) {
                        branchMajor++;
                    } else if (res.gcSeverity == 1) {
                        branchMinor++;
                    }
                }
                String summary = String.format(
                        "GC summary: %d/%d points minor, %d/%d points MAJOR",
                        branchMinor, hitTargets.length, branchMajor, hitTargets.length);
                System.out.println(summary);
                out.println(summary);
            }
            System.out.println("saved to " + file);
            out.println("saved to " + file);
        } finally {
            out.close();
        }
    }

    // The timed path: one Rebuild instance per config (so a shared cache slot, if any, persists
    // across the R rows). The factory decides w/o cache (no cache) vs with cache (VariantMetadata). For each row we
    // pick the shape from the hit-rate sequence, then a distinct value from that shape's pool, so the
    // metadata is reused (cache hit) whenever consecutive shapes match, but the values always differ.
    static long runBranch(GenericRow row, Rebuild r, Branch b, int[] seq) {
        return runBranch(row, r, b, seq, R);
    }

    static long runBranch(GenericRow row, Rebuild r, Branch b, int[] seq, int count) {
        long sink = 0;
        for (int i = 0; i < count; i++) {
            Shape sp = b.data.shape(seq[i]);
            byte[] val = sp.value(0);
            b.setRow.apply(row, val, sp.meta);
            Variant v = r.rebuild(new PaimonShreddingUtils.PaimonShreddedRow(row), b.schema);
            sink += v.value().length;
        }
        return sink;
    }

    static void assertConsistent(Supplier<Rebuild> factory, Branch b, GenericRow row) {
        Shape sp = b.data.shape(0);
        b.setRow.apply(row, sp.value(0), sp.meta);
        Rebuild r1 = factory.get();
        Rebuild r2 = factory.get();
        Variant n = r1.rebuild(new PaimonShreddingUtils.PaimonShreddedRow(row), b.schema);
        Variant o = r2.rebuild(new PaimonShreddingUtils.PaimonShreddedRow(row), b.schema);
        if (!Arrays.equals(n.value(), o.value()) || !Arrays.equals(n.metadata(), o.metadata())) {
            throw new AssertionError("inconsistent rebuild for " + b.name);
        }
    }

    static Branch objectLeft(String name, Data d, int medianL) {
        return new Branch(name,
                new VariantSchema(0, 1, 2, 3, null, new VariantSchema.ObjectField[0], null),
                (row, value, meta) -> {
                    row.setField(0, new GenericRow(0));
                    row.setField(1, value);
                    row.setField(2, meta);
                },
                d, medianL);
    }

    static VariantSchema.ObjectField[] objectFields(int n) {
        VariantSchema.ObjectField[] f = new VariantSchema.ObjectField[n];
        for (int i = 0; i < n; i++) {
            VariantSchema leaf =
                    new VariantSchema(
                            0,
                            -1,
                            -1,
                            1,
                            new VariantSchema.IntegralType(VariantSchema.IntegralSize.LONG),
                            null,
                            null);
            f[i] = new VariantSchema.ObjectField("field" + i, leaf);
        }
        return f;
    }

    static GenericRow buildLongStruct(int n) {
        GenericRow top = new GenericRow(n);
        for (int i = 0; i < n; i++) {
            GenericRow field = new GenericRow(1);
            field.setField(0, (long) i);
            top.setField(i, field);
        }
        return top;
    }

    // NUM_SHAPES = number of distinct row shapes the bench may cycle through. All shapes are
    // generated up front by buildData, so this is the pool size of pre-generated shapes.
    static final int NUM_SHAPES = 64;

    static Data buildData(int medianL) {
        return new Data(medianL);
    }

    // One shape: L distinct random field names -> metadata dict. The pool of M value buffers is NOT
    // built here; value(j) generates each on demand and memoizes it.
    static Shape buildShape(int L, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        while (names.size() < L) {
            String n = randomName(rnd);
            if (used.add(n)) {
                names.add(n);
            }
        }
        byte[] meta = metadata(names.toArray(new String[0]));
        return new Shape(meta, names, L);
    }

    // Field-name length: mixture of two Gammas -- bulk (mostly 4-5) + tail (long, up to 30). A single
    // Gamma can't be both "mostly 4-5" and have a long tail, so it's a two-component mixture. Clamped [2,30].
    static final double BULK_P = 0.75;
    static final double BULK_K = 15.0, BULK_THETA = 0.30; // mean 4.5, std ~1.16 -> mostly 4-5, min 2
    static final double TAIL_K = 2.0, TAIL_THETA = 4.50;  // mean 9, mode 4.5, long tail toward 30

    static String randomName(java.util.Random rnd) {
        double shape = (rnd.nextDouble() < BULK_P) ? BULK_K : TAIL_K;
        double scale = (shape == BULK_K) ? BULK_THETA : TAIL_THETA;
        int len = (int) Math.round(gamma(shape, scale, rnd));
        len = Math.max(2, Math.min(30, len));
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + rnd.nextInt(26)));
        }
        return sb.toString();
    }

    // Sample from a Gamma(shape, scale) distribution (Marsaglia & Tsang). shape >= 1.
    static double gamma(double shape, double scale, java.util.Random rnd) {
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x;
            do {
                x = rnd.nextGaussian();
            } while (x <= -1.0 / c); // keep v > 0
            double v = 1.0 + c * x;
            v = v * v * v;
            double u = rnd.nextDouble();
            if (u < 1.0 - 0.0331 * x * x * x * x) {
                return d * v * scale;
            }
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
                return d * v * scale;
            }
        }
    }

    static int[] seqFor(int hitPct, int K) {
        if (hitPct >= 100) {
            return sequenceBlock(R, K);
        }
        if (hitPct <= 0) {
            return sequenceBlock(1, K);
        }
        return sequenceHitRate(hitPct / 100.0, K);
    }

    static int[] sequenceBlock(int B, int K) {
        int[] seq = new int[R];
        for (int i = 0; i < R; i++) {
            seq[i] = (i / B) % K;
        }
        return seq;
    }

    static int[] sequenceHitRate(double target, int K) {
        int[] seq = new int[R];
        java.util.Random rand = new java.util.Random(0x5EED);
        int cur = 0;
        seq[0] = 0;
        for (int i = 1; i < R; i++) {
            if (rand.nextDouble() < target) {
                seq[i] = cur;
            } else {
                seq[i] = (cur + 1 + rand.nextInt(K - 1)) % K;
                cur = seq[i];
            }
        }
        return seq;
    }

    static final class BenchResult {
        final double nsPerRow;     // ns/row of the single timed pass
        final String gcMarker;     // non-empty if the pass was GC-polluted
        final int gcSeverity;      // 0 = none, 1 = minor (young), 2 = MAJOR (old/full on G1)
        final long gcMs;           // total GC time (ms) observed during the pass

        BenchResult(double nsPerRow, String gcMarker, int gcSeverity, long gcMs) {
            this.nsPerRow = nsPerRow;
            this.gcMarker = gcMarker;
            this.gcSeverity = gcSeverity;
            this.gcMs = gcMs;
        }
    }

    // Times a single pass: one warmup-independent measurement of R rebuilds. GC during the pass is
    // perceived synchronously via the JMX counters diffed around the pass.
    static BenchResult bench(Supplier<Long> s) {
        long[] before = GC.snapshot();
        long t0 = System.nanoTime();
        long sink = s.get();
        long t1 = System.nanoTime();
        long[] after = GC.snapshot();
        if (sink == 0) {
            System.err.println("warn: sink zero");
        }
        long dYoungC = after[0] - before[0];
        long dYoungT = after[1] - before[1];
        long dOldC = after[2] - before[2];
        long dOldT = after[3] - before[3];
        long totalMs = dYoungT + dOldT;
        int sev = (dOldC > 0) ? 2 : (dYoungC > 0 ? 1 : 0);
        String marker = "";
        if (sev > 0) {
            marker = String.format("   *GC(%s, +%dms)", sev == 2 ? "MAJOR" : "minor", totalMs);
        }
        double nsPerRow = (t1 - t0) / (double) R;
        return new BenchResult(nsPerRow, marker, sev, totalMs);
    }

    static byte[] metadata(String[] keys) {
        int dictSize = keys.length;
        byte[][] utf = new byte[dictSize][];
        int total = 0;
        for (int i = 0; i < dictSize; i++) {
            utf[i] = keys[i].getBytes(StandardCharsets.UTF_8);
            total += utf[i].length;
        }
        int offsetSize = 1;
        while (offsetSize < 4 && (total >>> (8 * offsetSize)) > 0) {
            offsetSize++;
        }
        ByteBuffer b =
                ByteBuffer.allocate(1 + (dictSize + 2) * offsetSize + total)
                        .order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) (GenericVariantUtil.VERSION | ((offsetSize - 1) << 6)));
        writeUnsigned(b, 1, dictSize, offsetSize);
        int off = 0;
        for (int i = 0; i < dictSize; i++) {
            writeUnsigned(b, 1 + (i + 1) * offsetSize, off, offsetSize);
            off += utf[i].length;
        }
        writeUnsigned(b, 1 + (dictSize + 1) * offsetSize, off, offsetSize);
        int stringStart = 1 + (dictSize + 2) * offsetSize;
        for (int i = 0; i < dictSize; i++) {
            b.position(stringStart + readUnsigned(b, 1 + (i + 1) * offsetSize, offsetSize));
            b.put(utf[i]);
        }
        b.position(0);
        return b.array();
    }

    static void writeUnsigned(ByteBuffer b, int pos, int v, int s) {
        for (int i = 0; i < s; i++) {
            b.put(pos + i, (byte) (v >>> (8 * i)));
        }
    }

    static int readUnsigned(ByteBuffer b, int pos, int s) {
        int v = 0;
        for (int i = 0; i < s; i++) {
            v |= (b.get(pos + i) & 0xFF) << (8 * i);
        }
        return v;
    }

    // Perceives GC from inside the running program via the SYNCHRONOUS JMX GarbageCollectorMXBean
    // counters (getCollectionCount / getCollectionTime). We snapshot them around each timed pass and
    // diff, so we know whether that pass was polluted by a GC and whether it was a heavy
    // old-generation collection. No async notification listener (and its race) is involved.
    //
    // On G1 there is no dedicated "Full GC" bean: a full collection is reported under the old-gen
    // bean (the System.gc() probe printed name="G1 Old Generation", action="end of major GC"). So
    // old-gen activity is the heavy/full-equivalent we flag as MAJOR; young-gen activity is "minor".
    static final class GcMonitor {
        final List<GarbageCollectorMXBean> beans;
        final boolean[] young; // parallel to beans: true = young (minor) collector

        GcMonitor() {
            List<GarbageCollectorMXBean> list = new ArrayList<>();
            boolean[] y = new boolean[0];
            try {
                List<GarbageCollectorMXBean> all =
                        java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
                list.addAll(all);
                y = new boolean[all.size()];
                for (int i = 0; i < all.size(); i++) {
                    String name = all.get(i).getName();
                    y[i] = name.contains("Young") || name.contains("Scavenge")
                            || name.contains("Copy") || name.contains("ParNew");
                }
            } catch (Throwable t) {
                System.err.println("GcMonitor init failed: " + t);
            }
            this.beans = list;
            this.young = y;
        }

        // Cumulative [youngCount, youngTimeMs, oldCount, oldTimeMs] across all collectors.
        long[] snapshot() {
            long yc = 0, yt = 0, oc = 0, ot = 0;
            try {
                for (int i = 0; i < beans.size(); i++) {
                    GarbageCollectorMXBean bean = beans.get(i);
                    if (young[i]) {
                        yc += bean.getCollectionCount();
                        yt += bean.getCollectionTime();
                    } else {
                        oc += bean.getCollectionCount();
                        ot += bean.getCollectionTime();
                    }
                }
            } catch (Throwable ignore) {
                // never let a counter read blow up the benchmark
            }
            return new long[] {yc, yt, oc, ot};
        }
    }
}
