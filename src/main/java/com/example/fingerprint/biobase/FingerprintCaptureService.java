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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FingerprintCaptureService {
    private static final Logger log = LoggerFactory.getLogger(FingerprintCaptureService.class);
    private static final String PROP_TRUE = "TRUE";
    private static final String PROP_FALSE = "FALSE";
    private static final String PROP_AUTOCAPTURE_SUPPORTED = "DEVICE_AUTOCAPTURE_SUPPORTED";
    private static final String PROP_AUTOCAPTURE_ON = "AUTOCAPTURE_ON";
    private static final String PROP_AUTOCAPTURE_NUM_RQD_OBJECTS = "AUTOCAPTURE_NUM_RQD_OBJECTS";
    private static final String PROP_AUTOCAPTURE_OVERRIDE_ON = "AUTOCAPTURE_OVERRIDE_ON";
    private static final String PROP_AUTOCAPTURE_OVERRIDE_TIME = "AUTOCAPTURE_OVERRIDE_TIME";
    private static final String PROP_AUTOCAPTURE_OVERRIDE_MODE = "AUTOCAPTURE_OVERRIDE_MODE";
    private static final String PROP_AUTOCONTRAST_ON = "AUTOCONTRAST_ON";
    private static final String PROP_IMAGE_RESOLUTION = "IMAGE_RESOLUTION";
    private static final String PROP_ACTIVE_AREA = "ACTIVE_AREA";
    private static final String PROP_SPOOF_DETECTION_ON = "SPOOF_DETECTION_ON";
    private static final String PROP_SPOOF_DETECTION_SUPPORTED = "DEVICE_SPOOF_DETECTION_SUPPORTED";
    private static final String PROP_PREVIEW_IMAGE_FORMAT = "PREVIEW_IMAGE_FORMAT";
    private static final String PROP_PREVIEW_LEVEL = "PREVIEW_LEVEL";

    private final BioBaseClient client;
    private final FingerprintProperties properties;
    private final AtomicReference<CapturedData> lastPreview = new AtomicReference<>();
    private final AtomicReference<CapturedData> lastCapture = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<CapturedData>> pendingCapture = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<CapturedData>> pendingPreview = new AtomicReference<>();
    private volatile String activeDeviceId;

    private final BioBaseNative.PreviewCallback previewCallback;
    private final BioBaseNative.AcquisitionStartedCallback startedCallback;
    private final BioBaseNative.AcquisitionCompletedCallback completedCallback;
    private final BioBaseNative.DataAvailableCallback dataAvailableCallback;

    public FingerprintCaptureService(BioBaseClient client, FingerprintProperties properties) {
        this.client = client;
        this.properties = properties;
        this.previewCallback = (deviceId, context, data) -> {
            if (data != null) {
                CapturedData preview = client.readData(deviceId, 0, data, 0);
                if (preview.bytes().length > 0) {
                    lastPreview.set(preview);
                    CompletableFuture<CapturedData> future = pendingPreview.get();
                    if (future != null) {
                        future.complete(preview);
                    }
                }
            }
        };
        this.startedCallback = (deviceId, context, reserved) -> {
        };
        this.completedCallback = (deviceId, context, reserved) -> {
        };
        this.dataAvailableCallback = (deviceId, context, dataStatus, data, detectedObjects) -> {
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
        CompletableFuture<CapturedData> previewFuture = new CompletableFuture<>();
        pendingPreview.set(previewFuture);

        try {
            configureCaptureProperties(deviceId, blankToDefault(impression, properties.getDefaultImpression()));
            client.beginAcquisition(
                    deviceId,
                    blankToDefault(position, properties.getDefaultPosition()),
                    blankToDefault(impression, properties.getDefaultImpression())
            );
            savePreviewWhenAvailable(previewFuture);
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
            pendingPreview.compareAndSet(previewFuture, null);
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

    private void configureCaptureProperties(String deviceId, String impression) {
        configureCoreCaptureProperties(deviceId, impression);
        configureAutoCapture(deviceId);
        configureSpoofDetection(deviceId);
        configurePreview(deviceId);
    }

    private void configureCoreCaptureProperties(String deviceId, String impression) {
        setOptionalProperty(deviceId, PROP_ACTIVE_AREA, properties.getActiveArea());
        setOptionalProperty(deviceId, PROP_IMAGE_RESOLUTION, properties.getImageResolution());

        boolean flatCapture = "FingerprintFlat".equalsIgnoreCase(impression);
        String autoContrast = properties.isAutoContrastEnabled() && flatCapture ? PROP_TRUE : PROP_FALSE;
        setOptionalProperty(deviceId, PROP_AUTOCONTRAST_ON, autoContrast);
    }

    private void configureAutoCapture(String deviceId) {
        if (!properties.isAutoCaptureEnabled()) {
            client.setProperty(deviceId, PROP_AUTOCAPTURE_ON, PROP_FALSE);
            setOptionalProperty(deviceId, PROP_AUTOCAPTURE_OVERRIDE_ON, PROP_FALSE);
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
            setOptionalProperty(deviceId, PROP_AUTOCAPTURE_OVERRIDE_ON, PROP_FALSE);
            return;
        }

        client.setProperty(deviceId, PROP_AUTOCAPTURE_ON, PROP_TRUE);
        if (properties.getAutoCaptureRequiredObjects() > 0) {
            client.setProperty(deviceId, PROP_AUTOCAPTURE_NUM_RQD_OBJECTS, String.valueOf(properties.getAutoCaptureRequiredObjects()));
        }

        if (properties.isAutoCaptureOverrideEnabled()) {
            setOptionalProperty(deviceId, PROP_AUTOCAPTURE_OVERRIDE_ON, PROP_TRUE);
            setOptionalProperty(deviceId, PROP_AUTOCAPTURE_OVERRIDE_TIME, properties.getAutoCaptureOverrideTime());
            setOptionalProperty(deviceId, PROP_AUTOCAPTURE_OVERRIDE_MODE, properties.getAutoCaptureOverrideMode());
        } else {
            setOptionalProperty(deviceId, PROP_AUTOCAPTURE_OVERRIDE_ON, PROP_FALSE);
        }
    }

    private void configureSpoofDetection(String deviceId) {
        if (!properties.isSpoofDetectionEnabled()) {
            setOptionalProperty(deviceId, PROP_SPOOF_DETECTION_ON, PROP_FALSE);
            return;
        }

        String supported = getOptionalProperty(deviceId, PROP_SPOOF_DETECTION_SUPPORTED).orElse(PROP_FALSE);
        if (PROP_TRUE.equalsIgnoreCase(supported.trim())) {
            setOptionalProperty(deviceId, PROP_SPOOF_DETECTION_ON, PROP_TRUE);
        } else {
            setOptionalProperty(deviceId, PROP_SPOOF_DETECTION_ON, PROP_FALSE);
        }
    }

    private void configurePreview(String deviceId) {
        setOptionalProperty(deviceId, PROP_PREVIEW_IMAGE_FORMAT, properties.getPreviewImageFormat());
        setOptionalProperty(deviceId, PROP_PREVIEW_LEVEL, properties.getPreviewLevel());
    }

    private Optional<String> getOptionalProperty(String deviceId, String propertyName) {
        try {
            return Optional.ofNullable(client.getProperty(deviceId, propertyName));
        } catch (BioBaseException e) {
            log.warn("Could not read optional BioBase property {}: {}", propertyName, e.getMessage());
            return Optional.empty();
        }
    }

    private void setOptionalProperty(String deviceId, String propertyName, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            client.setProperty(deviceId, propertyName, value);
        } catch (BioBaseException e) {
            log.warn("Could not set optional BioBase property {}={}: {}", propertyName, value, e.getMessage());
        }
    }

    private void savePreviewWhenAvailable(CompletableFuture<CapturedData> previewFuture) {
        try {
            CapturedData preview = previewFuture.get(properties.getPreviewTimeoutSeconds(), TimeUnit.SECONDS);
            CapturedData saved = save(preview, "preview");
            lastPreview.set(saved);
            log.info("Preview image saved to {}", saved.savedPath());
        } catch (TimeoutException e) {
            log.warn("No preview image arrived within {} seconds.", properties.getPreviewTimeoutSeconds());
        } catch (Exception e) {
            log.warn("Could not save preview image: {}", e.getMessage());
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
