package com.example.fingerprint.api;

public record CaptureRequest(
        String deviceId,
        String position,
        String impression,
        Long timeoutSeconds
) {
}
