package com.example.fingerprint.biobase;

public record DeviceInfo(
        String modelName,
        String serialNumber,
        String interfaceName,
        String deviceId,
        String modality,
        String visualizers
) {
}
