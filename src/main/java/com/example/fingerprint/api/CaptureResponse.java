package com.example.fingerprint.api;

import java.time.Instant;
import java.util.List;

public record CaptureResponse(
        String deviceId,
        String format,
        boolean finalImage,
        int dataStatus,
        int detectedObjects,
        String savedPath,
        String annotatedPath,
        int segmentImageWidth,
        int segmentImageHeight,
        List<SegmentResponse> segments,
        ObjectCountResponse objectCount,
        List<ObjectQualityResponse> objectQualities,
        int byteCount,
        Instant capturedAt
) {
    public record SegmentResponse(
            int index,
            int x,
            int y,
            int width,
            int height
    ) {
    }

    public record ObjectCountResponse(
            int value,
            String name
    ) {
    }

    public record ObjectQualityResponse(
            int index,
            int value,
            String name
    ) {
    }
}
