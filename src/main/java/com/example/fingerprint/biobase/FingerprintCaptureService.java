package com.example.fingerprint.biobase;

import com.example.fingerprint.api.CaptureResponse;
import com.example.fingerprint.api.DeviceStatusResponse;
import com.example.fingerprint.cmtfinger.CmtFingerNative;
import com.example.fingerprint.cmtfinger.Cmt_finger_viewspec;
import com.example.fingerprint.config.FingerprintProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static com.example.fingerprint.biobase.BioBaseDataFormat.BIOB_BMP;

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
    private final AtomicReference<CapturedData> livePreviewToWrite = new AtomicReference<>();
    private final AtomicBoolean livePreviewWriterRunning = new AtomicBoolean(false);
    private final ExecutorService previewWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "biobase-live-preview-writer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String activeDeviceId;
    private volatile boolean livePreviewActive;

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
                    if (livePreviewActive && properties.isLivePreviewFileEnabled()) {
                        livePreviewToWrite.set(preview);
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

        try {
            configureCaptureProperties(deviceId, blankToDefault(impression, properties.getDefaultImpression()));
            startLivePreviewWriter();
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
            stopLivePreviewWriter();
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

    private byte[] firToBmp(byte[] firData) {
        PointerByReference rcfir = new PointerByReference();
        int result = CmtFingerNative.INSTANCE.cmtfinger_create(rcfir);
        if (result != 0) {
            throw new RuntimeException("Record oluşturulamadı, hata: " + result);
        }
        Pointer cfir = rcfir.getValue();
        try {
            // FIR decode
            result = CmtFingerNative.INSTANCE.cmtfinger_decode(cfir, firData, firData.length);
            if (result != 0) {
                throw new RuntimeException("FIR decode hatası: " + result);
            }
            // BMP encode
            return encodeToBmp(cfir);
        } finally {
            CmtFingerNative.INSTANCE.cmtfinger_free(cfir);
        }
    }

    private byte[] encodeToBmp(Pointer cfir) {

        Cmt_finger_viewspec vs = new Cmt_finger_viewspec();
        vs.position = -1;  // veya bilinen pozisyon
        vs.impression = -2;
        vs.write();

        // Boyut al
        IntByReference bmpLengthRef = new IntByReference(0);
        int result = CmtFingerNative.INSTANCE.cmtfinger_encode_to_bmp(
                cfir, vs, null, bmpLengthRef
        );

        if (result != 0) {
            throw new RuntimeException("BMP boyut alınamadı: " + result);
        }

        // Veriyi al
        byte[] bmpBuffer = new byte[bmpLengthRef.getValue()];
        result = CmtFingerNative.INSTANCE.cmtfinger_encode_to_bmp(
                cfir, vs, bmpBuffer, bmpLengthRef
        );

        if (result != 0) {
            throw new RuntimeException("BMP encode hatası: " + result);
        }

        return bmpBuffer;
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
            byte[] bmpBytes = firToBmp(preview.bytes());

            String base64String = Base64.getEncoder().encodeToString(bmpBytes);

            CapturedData newCapturedData = new CapturedData(
                    preview.deviceId(),
                    BIOB_BMP,
                    preview.finalImage(),
                    preview.dataStatus(),
                    preview.detectedObjects(),
                    bmpBytes,                      // BMP byte array
                    preview.savedPath(),
                    preview.capturedAt()
            );
            CapturedData saved = save(newCapturedData, "preview");
            lastPreview.set(saved);
            log.info("Preview image saved to {}", saved.savedPath());
        } catch (TimeoutException e) {
            log.warn("No preview image arrived within {} seconds.", properties.getPreviewTimeoutSeconds());
        } catch (Exception e) {
            log.warn("Could not save preview image: {}", e.getMessage());
    private void startLivePreviewWriter() {
        if (!properties.isLivePreviewFileEnabled()) {
            return;
        }
        livePreviewActive = true;
        livePreviewToWrite.set(null);
        if (!livePreviewWriterRunning.compareAndSet(false, true)) {
            return;
        }
        previewWriter.execute(() -> {
            try {
                while (livePreviewActive || livePreviewToWrite.get() != null) {
                    CapturedData preview = livePreviewToWrite.getAndSet(null);
                    if (preview != null) {
                        saveLivePreview(preview);
                    }
                    Thread.sleep(Math.max(25, properties.getLivePreviewWriteIntervalMillis()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Live preview writer failed: {}", e.getMessage());
            } finally {
                livePreviewWriterRunning.set(false);
                if (livePreviewActive) {
                    startLivePreviewWriter();
                }
            }
        });
    }

    private void stopLivePreviewWriter() {
        livePreviewActive = false;
    }

    private void saveLivePreview(CapturedData data) {
        try {
            Files.createDirectories(properties.getOutputDir());
            Path path = properties.getOutputDir()
                    .resolve(properties.getLivePreviewFileName() + "." + data.format().extension())
                    .toAbsolutePath();
            Files.write(path, data.bytes());
        } catch (IOException e) {
            log.warn("Could not write live preview image: {}", e.getMessage());
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

    @PreDestroy
    void shutdownPreviewWriter() {
        previewWriter.shutdownNow();
    }
}
