package com.example.fingerprint.biobase;

import java.nio.file.Path;
import java.time.Instant;

public record CapturedData(
        String deviceId,
        BioBaseDataFormat format,
        boolean finalImage,
        int dataStatus,
        int detectedObjects,
        byte[] bytes,
        Path savedPath,
        Instant capturedAt
) {
}
