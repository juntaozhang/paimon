/*
 * with-cache variant metadata cache bench.
 *
 * Compiled against the with-cache commit (paimon-nxt, with VariantMetadata). Drives the REAL
 * ShreddingUtils.rebuild of this commit. The cache is exercised by reusing ONE VariantMetadata
 * instance across the R rows of each rep, exactly as a real reader would (a single shared slot
 * per batch). One fresh empty() per Rebuild so the snapshot persists across rows but is reset
 * between reps.
 */
package org.apache.paimon.data.variant;

public class WithCacheVariantBranchBench {
    public static void main(String[] args) throws Exception {
        String out = System.getProperty("bench.file", "benchmark/bench_withcache.txt");
        BranchKit.runAll(
                () -> {
                    VariantMetadata m = VariantMetadata.empty();
                    return (row, schema) -> ShreddingUtils.rebuild(row, schema, m);
                },
                out);
    }
}
