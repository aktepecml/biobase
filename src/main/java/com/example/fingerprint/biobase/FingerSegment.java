package com.example.fingerprint.biobase;

public record FingerSegment(
        int index,
        int x,
        int y,
        int width,
        int height
) {
}
