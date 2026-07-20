package com.example.fingerprint.biobase;

import java.util.List;

public record FingerSegmentation(
        int imageWidth,
        int imageHeight,
        List<FingerSegment> segments
) {
    public static FingerSegmentation empty() {
        return new FingerSegmentation(0, 0, List.of());
    }
}
