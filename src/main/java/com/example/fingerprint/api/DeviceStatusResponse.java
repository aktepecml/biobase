package com.example.fingerprint.api;

import java.util.List;

public record DeviceStatusResponse(
        String deviceId,
        boolean open,
        boolean ready,
        boolean acquiring,
        boolean previewAvailable,
        boolean captureAvailable,
        CaptureResponse.ObjectCountResponse objectCount,
        List<CaptureResponse.ObjectQualityResponse> objectQualities
) {
}
