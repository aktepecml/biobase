package com.example.fingerprint.api;

import java.time.Instant;

public record CaptureResponse(
        String deviceId,
        String format,
        boolean finalImage,
        int dataStatus,
        int detectedObjects,
        String savedPath,
        int byteCount,
        Instant capturedAt
) {
}
