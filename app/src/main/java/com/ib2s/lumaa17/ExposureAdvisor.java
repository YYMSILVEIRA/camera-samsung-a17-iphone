package com.ib2s.lumaa17;

/** Small, bounded AE bias. This changes sensor exposure; it never edits an encoded image. */
public final class ExposureAdvisor {
    private ExposureAdvisor() {}

    public static final class Sample {
        public final int median, highlights;
        public final double clipped;
        public Sample(int median, int highlights, double clipped) {
            this.median = median; this.highlights = highlights; this.clipped = clipped;
        }
    }

    public static Sample measure(int[] pixels, int width, int height) {
        if (width < 1 || height < 1 || pixels.length < width * height)
            throw new IllegalArgumentException("Invalid preview sample");
        int[] center = new int[256], all = new int[256];
        int centerCount = 0, clipped = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int rgb = pixels[y * width + x];
            int luma = (54 * ((rgb >>> 16) & 255) + 183 * ((rgb >>> 8) & 255) + 19 * (rgb & 255)) >>> 8;
            all[luma]++;
            if (luma >= 248) clipped++;
            if (x >= width / 5 && x < width - width / 5 && y >= height / 5 && y < height - height / 5) {
                center[luma]++; centerCount++;
            }
        }
        return new Sample(percentile(center, centerCount, .5), percentile(all, width * height, .95),
                clipped / (double)(width * height));
    }

    private static int percentile(int[] histogram, int count, double fraction) {
        int target = Math.max(1, (int)Math.ceil(count * fraction)), seen = 0;
        for (int i = 0; i < histogram.length; i++) { seen += histogram[i]; if (seen >= target) return i; }
        return 0;
    }

    public static double next(double current, Sample scene, boolean video) {
        double limit = video ? 2.0 / 3 : 1.0;
        current = Math.max(0, Math.min(limit, current));
        // A blank/not-yet-drawn surface supplies no exposure evidence.
        if (scene.highlights < 3 || !Double.isFinite(scene.clipped)) return current;
        double step = video ? 1.0 / 6 : 1.0 / 3;
        if (scene.clipped > .012 || scene.highlights > 242 || scene.median > 140)
            return Math.max(0, current - step);
        if (scene.median < 96 && scene.highlights < 225)
            return Math.min(limit, current + step);
        // A wide dead band avoids oscillating around a single brightness target.
        return current;
    }
}
