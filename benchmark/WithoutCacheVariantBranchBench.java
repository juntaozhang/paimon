/*
 * w/o-cache variant metadata cache bench.
 *
 * Compiled against the w/o-cache commit (paimon, no cache). Drives the REAL ShreddingUtils.rebuild
 * of that commit, which decodes the metadata dictionary fresh on every row (no VariantMetadata
 * class exists at this commit). This is the faithful "before" baseline that the with-cache path is
 * compared to.
 */
package org.apache.paimon.data.variant;

public class WithoutCacheVariantBranchBench {
    public static void main(String[] args) throws Exception {
        String out = System.getProperty("bench.file", "benchmark/bench_wocache.txt");
        BranchKit.runAll(
                () -> (row, schema) -> ShreddingUtils.rebuild(row, schema),
                out);
    }
}
