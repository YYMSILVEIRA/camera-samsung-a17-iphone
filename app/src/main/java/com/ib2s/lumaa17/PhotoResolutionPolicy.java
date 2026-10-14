package com.ib2s.lumaa17;

/** Pure selection policy: high-resolution-only JPEGs must participate in the normal profile. */
public final class PhotoResolutionPolicy {
    private PhotoResolutionPolicy() {}
    public static int[] balanced(int[][] regular, int[][] high) {
        int[] chosen = {4000, 3000};
        double best = Double.MAX_VALUE;
        for (int[][] group : new int[][][]{regular, high}) {
            if (group == null) continue;
            for (int[] size : group) {
                if (size == null || size.length < 2 || size[0] <= 0 || size[1] <= 0) continue;
                long pixels = (long) size[0] * size[1];
                double aspect = (double) Math.max(size[0], size[1]) / Math.min(size[0], size[1]);
                if (pixels > 16_000_000 || Math.abs(aspect - 4.0 / 3) > .04) continue;
                double score = Math.abs(Math.log(pixels / 12_000_000.0));
                if (score < best) { best = score; chosen = size.clone(); }
            }
        }
        return chosen;
    }
}
