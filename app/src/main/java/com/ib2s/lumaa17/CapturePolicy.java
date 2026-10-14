package com.ib2s.lumaa17;

/** Pure, testable limits shared by UI and capture requests. No device assumptions. */
public final class CapturePolicy {
    private CapturePolicy() {}

    public static int exposureIndex(double ev, double step, int low, int high) {
        if (!Double.isFinite(ev) || !Double.isFinite(step) || step <= 0) return 0;
        long value = Math.round(ev / step);
        return (int) Math.max(low, Math.min(high, value));
    }

    public static long shutterFromProgress(int progress, long low, long high) {
        if (low <= 0 || high < low) throw new IllegalArgumentException("Invalid exposure range");
        double ratio = Math.max(0, Math.min(100, progress)) / 100.0;
        return Math.max(low, Math.min(high, Math.round(low * Math.pow((double) high / low, ratio))));
    }

    public static int bitrate(int height, boolean high) {
        return height >= 2160 ? (high ? 60_000_000 : 40_000_000)
                : height >= 1080 ? (high ? 24_000_000 : 16_000_000)
                : height >= 720 ? (high ? 12_000_000 : 8_000_000) : 4_000_000;
    }

    public static int rotationDegrees(int orientation) {
        if (orientation < 0) return 0;
        if (orientation >= 315 || orientation < 45) return 0;
        if (orientation < 135) return 270;
        if (orientation < 225) return 180;
        return 90;
    }

    public static String shutterLabel(long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        if (seconds <= 0) return "Auto";
        if (seconds < 0.5) return "1/" + Math.round(1.0 / seconds) + " s";
        return String.format(java.util.Locale.US, "%.1f s", seconds);
    }
}
