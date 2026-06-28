package com.example.fingerprint.api;

public record DeviceStatusResponse(
        String deviceId,
        boolean open,
        boolean ready,
        boolean acquiring,
        boolean previewAvailable,
        boolean captureAvailable
) {
}
