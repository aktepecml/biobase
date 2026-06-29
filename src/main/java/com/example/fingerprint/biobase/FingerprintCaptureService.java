package com.example.fingerprint.biobase;

import com.example.fingerprint.api.CaptureResponse;
import com.example.fingerprint.api.DeviceStatusResponse;
import com.example.fingerprint.config.FingerprintProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class FingerprintCaptureService {
    private static final String PROP_TRUE = "TRUE";
    private static final String PROP_FALSE = "FALSE";
    private static final String PROP_AUTOCAPTURE_SUPPORTED = "DEVICE_AUTOCAPTURE_SUPPORTED";
    private static final String PROP_AUTOCAPTURE_ON = "AUTOCAPTURE_ON";
    private static final String PROP_AUTOCAPTURE_NUM_RQD_OBJECTS = "AUTOCAPTURE_NUM_RQD_OBJECTS";

    private final BioBaseClient client;
    private final FingerprintProperties properties;
    private final AtomicReference<CapturedData> lastPreview = new AtomicReference<>();
    private final AtomicReference<CapturedData> lastCapture = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<CapturedData>> pendingCapture = new AtomicReference<>();
    private volatile String activeDeviceId;

    private final BioBaseNative.PreviewCallback previewCallback = (deviceId, context, data) -> {
        if (data != null) {
            CapturedData preview = client.readData(deviceId, 0, data, 0);
            if (preview.bytes().length > 0) {
                lastPreview.set(preview);
            }
        }
    };

    private final BioBaseNative.AcquisitionStartedCallback startedCallback = (deviceId, context, reserved) -> {
    };

    private final BioBaseNative.AcquisitionCompletedCallback completedCallback = (deviceId, context, reserved) -> {
    };

    private final BioBaseNative.DataAvailableCallback dataAvailableCallback = (deviceId, context, dataStatus, data, detectedObjects) -> {
        if (dataStatus >= 0 && data != null) {
            CapturedData capture = client.readData(deviceId, dataStatus, data, detectedObjects);
            if (capture.bytes().length > 0) {
                lastCapture.set(capture);
                CompletableFuture<CapturedData> future = pendingCapture.get();
                if (future != null) {
                    future.complete(capture);
                }
            }
        }
    };

    public FingerprintCaptureService(BioBaseClient client, FingerprintProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public void openSystem() {
        client.openSystem();
    }

    public void closeSystem() {
        if (activeDeviceId != null && client.isDeviceOpen(activeDeviceId)) {
            closeDevice(activeDeviceId, true);
        }
        client.closeSystem();
    }

    public List<DeviceInfo> devices() {
        return client.getDevices();
    }

    public int deviceCount() {
        return client.getDeviceCount();
    }

    public void openDevice(String deviceId, boolean reset) {
        client.registerCallback(deviceId, BioBaseEvent.BIOB_PREVIEW, previewCallback);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_ACQUISITION_STARTED, startedCallback);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_ACQUISITION_COMPLETED, completedCallback);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_DATA_AVAILABLE, dataAvailableCallback);
        client.openDevice(deviceId, reset);
        activeDeviceId = deviceId;
    }

    public void closeDevice(String deviceId, boolean standby) {
        unregisterCallbacks(deviceId);
        client.closeDevice(deviceId, standby);
        if (Objects.equals(activeDeviceId, deviceId)) {
            activeDeviceId = null;
        }
    }

    public CaptureResponse capture(String requestedDeviceId, String position, String impression, Long timeoutSeconds) {
        String deviceId = resolveDeviceId(requestedDeviceId);
        if (!client.isDeviceReady(deviceId)) {
            throw new BioBaseException("Device is not ready. Open the device first.");
        }

        CompletableFuture<CapturedData> future = new CompletableFuture<>();
        if (!pendingCapture.compareAndSet(null, future)) {
            throw new BioBaseException("Another capture is already running.");
        }

        try {
            configureAutoCapture(deviceId);
            client.beginAcquisition(
                    deviceId,
                    blankToDefault(position, properties.getDefaultPosition()),
                    blankToDefault(impression, properties.getDefaultImpression())
            );
            long timeout = timeoutSeconds == null ? properties.getCaptureTimeoutSeconds() : timeoutSeconds;
            CapturedData captured = future.get(timeout, TimeUnit.SECONDS);
            CapturedData saved = save(captured, "capture");
            lastCapture.set(saved);
            return toResponse(saved);
        } catch (TimeoutException e) {
            client.cancelAcquisition(deviceId);
            throw new BioBaseException("Capture timed out before final fingerprint data arrived.");
        } catch (BioBaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BioBaseException("Capture failed: " + e.getMessage());
        } finally {
            pendingCapture.compareAndSet(future, null);
        }
    }

    public void cancel(String requestedDeviceId) {
        client.cancelAcquisition(resolveDeviceId(requestedDeviceId));
    }

    public void overrideCapture(String requestedDeviceId) {
        client.requestAcquisitionOverride(resolveDeviceId(requestedDeviceId));
    }

    public DeviceStatusResponse status(String requestedDeviceId) {
        String deviceId = resolveDeviceId(requestedDeviceId);
        boolean open = client.isDeviceOpen(deviceId);
        boolean ready = client.isDeviceReady(deviceId);
        boolean acquiring = open && client.isDeviceAcquiring(deviceId);
        return new DeviceStatusResponse(
                deviceId,
                open,
                ready,
                acquiring,
                lastPreview.get() != null,
                lastCapture.get() != null
        );
    }

    public String propertiesXml(String requestedDeviceId) {
        return client.getProperties(resolveDeviceId(requestedDeviceId));
    }

    public Optional<CapturedData> lastPreview() {
        return Optional.ofNullable(lastPreview.get());
    }

    public Optional<CapturedData> lastCapture() {
        return Optional.ofNullable(lastCapture.get());
    }

    public CaptureResponse saveLastPreview() {
        CapturedData preview = lastPreview().orElseThrow(() -> new BioBaseException("No preview image has been received yet."));
        CapturedData saved = save(preview, "preview");
        lastPreview.set(saved);
        return toResponse(saved);
    }

    private void unregisterCallbacks(String deviceId) {
        client.registerCallback(deviceId, BioBaseEvent.BIOB_PREVIEW, null);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_ACQUISITION_STARTED, null);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_ACQUISITION_COMPLETED, null);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_DATA_AVAILABLE, null);
    }

    private void configureAutoCapture(String deviceId) {
        if (!properties.isAutoCaptureEnabled()) {
            client.setProperty(deviceId, PROP_AUTOCAPTURE_ON, PROP_FALSE);
            return;
        }

        String supported;
        try {
            supported = client.getProperty(deviceId, PROP_AUTOCAPTURE_SUPPORTED);
        } catch (BioBaseException e) {
            throw new BioBaseException("Could not check auto capture support: " + e.getMessage());
        }

        if (!PROP_TRUE.equalsIgnoreCase(supported.trim())) {
            client.setProperty(deviceId, PROP_AUTOCAPTURE_ON, PROP_FALSE);
            return;
        }

        client.setProperty(deviceId, PROP_AUTOCAPTURE_ON, PROP_TRUE);
        if (properties.getAutoCaptureRequiredObjects() > 0) {
            client.setProperty(deviceId, PROP_AUTOCAPTURE_NUM_RQD_OBJECTS, String.valueOf(properties.getAutoCaptureRequiredObjects()));
        }
    }

    private CapturedData save(CapturedData data, String prefix) {
        try {
            Files.createDirectories(properties.getOutputDir());
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-');
            Path path = properties.getOutputDir().resolve(prefix + "-" + timestamp + "." + data.format().extension()).toAbsolutePath();
            Files.write(path, data.bytes());
            return new CapturedData(
                    data.deviceId(),
                    data.format(),
                    data.finalImage(),
                    data.dataStatus(),
                    data.detectedObjects(),
                    data.bytes(),
                    path,
                    data.capturedAt()
            );
        } catch (IOException e) {
            throw new BioBaseException("Could not save data: " + e.getMessage());
        }
    }

    private String resolveDeviceId(String requestedDeviceId) {
        if (requestedDeviceId != null && !requestedDeviceId.isBlank()) {
            return requestedDeviceId;
        }
        if (activeDeviceId != null && !activeDeviceId.isBlank()) {
            return activeDeviceId;
        }
        throw new BioBaseException("No deviceId provided and no active device is open.");
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static CaptureResponse toResponse(CapturedData data) {
        return new CaptureResponse(
                data.deviceId(),
                data.format().name(),
                data.finalImage(),
                data.dataStatus(),
                data.detectedObjects(),
                data.savedPath() == null ? null : data.savedPath().toString(),
                data.bytes().length,
                data.capturedAt()
        );
    }
}
