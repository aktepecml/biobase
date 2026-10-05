package com.example.fingerprint.biobase;

import com.example.fingerprint.api.CaptureResponse;
import com.example.fingerprint.api.DeviceStatusResponse;
import com.example.fingerprint.api.DeviceLedResponse;
import com.example.fingerprint.cmtfinger.CmtFingerNative;
import com.example.fingerprint.cmtfinger.Cmt_finger_viewspec;
import com.example.fingerprint.config.FingerprintProperties;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static com.example.fingerprint.biobase.BioBaseDataFormat.BIOB_BMP;
import static com.example.fingerprint.biobase.BioBaseDataFormat.BIOB_FIR;

@Service
public class FingerprintCaptureService {
    private static final Logger log = LoggerFactory.getLogger(FingerprintCaptureService.class);
    private static final long ACQUISITION_STOP_TIMEOUT_MILLIS = 5_000;
    private static final long ACQUISITION_STOP_POLL_MILLIS = 100;
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
    private static final String PROP_AVAILABLE_PREVIEW_LEVELS = "AVAILABLE_PREVIEW_LEVELS";
    private static final String PROP_DEVICE_PREVIEW_FRAME_RATE = "DEVICE_FRAME_RATE";
    private static final String PROP_DEVICE_PREVIEW_IMAGES_SUPPORTED = "DEVICE_PREVIEW_IMAGES_SUPPORTED";
    private static final String PROP_ENCODING_FORMATS_SUPPORTED = "ENCODING_FORMATS_SUPPORTED";
    private static final String PROP_VISUALIZATION_MODE = "VISUALIZATION_MODE";
    private static final String PROP_VISUALIZATION_FULLIMAGE_ON = "VISUALIZATION_FULLIMAGE_ON";
    private static final String PROP_VISUALIZATION_BK_COLOR = "VISUALIZATION_BK_COLOR";
    private static final String PROP_VISMODE_PREVIEW_ONLY = "PreviewOnly";
    private static final String PROP_VISUALIZATION_FINGER_WINDOW = "FingerWnd";
    private static final String PROP_DEFAULT_BK_COLOR = "255 255 255";
    private static final String PROP_AUTOCONTRAST_WAIT_TIME = "AUTOCONTRAST_WAIT_TIME";
    private static final String PROP_DEVICE_BEEPER_TYPE = "DEVICE_BEEPER_TYPE";
    private static final String PROP_BEEPER_NONE = "BEEPER_NONE";
    private static final String PROP_DEVICE_LED_TYPE = "DEVICE_LED_TYPE";
    private static final String PROP_DEVICE_AVAILABLE_LEDS = "DEVICE_AVAILABLE_LEDS";
    private static final String PROP_LED_TYPE_NONE = "LED_TYPE_NONE";
    private static final String LED_NONE = "NONE";
    private static final String TFT_CAP_SCREEN = "CaptureProgressScreen";
    private static final String TFT_LOGO_SCREEN = "LogoScreen";
    private static final String TFT_INACTIVE = "INACTIVE";
    private static final String TFT_AUTOCAPTURE_OK = "AUTOCAPTURE_OK";
    private static final String TFT_MISSING = "MISSING";
    private static final String TFT_LEAVE_UNCHANGED = "LEAVE_UNCHANGED";
    private static final String TFT_ERASE = "ERASE";
    private static final List<String> TFT_SEGMENTS = List.of(
            "ColorLeftPalm",
            "ColorLeftThenar",
            "ColorLeftLowerThenar",
            "ColorLeftInterDigital",
            "ColorLeftThumb",
            "ColorLeftIndex",
            "ColorLeftMiddle",
            "ColorLeftRing",
            "ColorLeftSmall",
            "ColorRightPalm",
            "ColorRightThenar",
            "ColorRightLowerThenar",
            "ColorRightInterDigital",
            "ColorRightThumb",
            "ColorRightIndex",
            "ColorRightMiddle",
            "ColorRightRing",
            "ColorRightSmall"
    );

    private final BioBaseClient client;
    private final FingerprintProperties properties;
    private final NativePreviewWindow nativePreviewWindow;
    private final ExecutorService deviceOutputExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "biobase-device-output");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService nativePreviewCaptureExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "native-preview-capture");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<CapturedData> lastPreview = new AtomicReference<>();
    private final AtomicReference<FingerSegmentation> lastPreviewSegmentation = new AtomicReference<>(FingerSegmentation.empty());
    private final AtomicReference<CapturedData> lastCapture = new AtomicReference<>();
    private final AtomicReference<Integer> lastObjectCountState = new AtomicReference<>();
    private final AtomicReference<List<Integer>> lastObjectQualityStates = new AtomicReference<>(List.of());
    private final AtomicReference<CompletableFuture<CapturedData>> pendingCapture = new AtomicReference<>();
    private final AtomicReference<String> activeImpression = new AtomicReference<>();
    private final AtomicReference<String> activePosition = new AtomicReference<>();
    private final AtomicReference<List<String>> lastQualityLedState = new AtomicReference<>(List.of());
    private final AtomicReference<String> lastTftStatus = new AtomicReference<>("");
    private final AtomicBoolean previewSeenLogged = new AtomicBoolean(false);
    private final AtomicBoolean captureSuccessBeepSent = new AtomicBoolean(false);
    private final AtomicBoolean captureProgressBeepSent = new AtomicBoolean(false);
    private final AtomicBoolean nativePreviewCaptureFailureLogged = new AtomicBoolean(false);
    private final AtomicBoolean nativePreviewFileLogged = new AtomicBoolean(false);
    private final AtomicLong lastPreviewCachedAtMillis = new AtomicLong(0);
    private final AtomicLong previewMetricWindowStartedAtMillis = new AtomicLong(0);
    private final AtomicLong previewMetricFrames = new AtomicLong(0);
    private final AtomicLong previewMetricCachedFrames = new AtomicLong(0);
    private final AtomicLong previewMetricBytes = new AtomicLong(0);
    private final AtomicLong previewMetricCopyNanos = new AtomicLong(0);
    private final AtomicReference<ScheduledFuture<?>> nativePreviewCaptureTask = new AtomicReference<>();
    private volatile String activeDeviceId;

    private final BioBaseNative.PreviewCallback previewCallback;
    private final BioBaseNative.AcquisitionStartedCallback startedCallback;
    private final BioBaseNative.AcquisitionCompletedCallback completedCallback;
    private final BioBaseNative.DataAvailableCallback dataAvailableCallback;
    private final BioBaseNative.ObjectQualityCallback objectQualityCallback;
    private final BioBaseNative.ObjectCountCallback objectCountCallback;

    public FingerprintCaptureService(BioBaseClient client, FingerprintProperties properties, NativePreviewWindow nativePreviewWindow) {
        this.client = client;
        this.properties = properties;
        this.nativePreviewWindow = nativePreviewWindow;
        this.previewCallback = (deviceId, context, data) -> {
            if (data != null) {
                BioBaseNative.BioBData nativeData = client.readNativeData(data);
                BioBaseDataFormat format = BioBaseDataFormat.fromValue(nativeData.formatType);
                int bufferSize = Math.max(nativeData.bufferSize, 0);
                if (previewSeenLogged.compareAndSet(false, true)) {
                    log.info("First preview received: format={}, bytes={}, finalImage={}, structName={}, extStruct={}, buffer={}",
                            format,
                            bufferSize,
                            nativeData.finalImage,
                            pointerString(nativeData.structName),
                            pointerAddress(nativeData.extStruct),
                            pointerAddress(nativeData.buffer));
                }
                if (shouldUseNativePreviewCapture()) {
                    recordPreviewMetrics(format, bufferSize, false, 0);
                    return;
                }
                if (shouldCachePreviewPayload()) {
                    long copyStartedAtNanos = System.nanoTime();
                    CapturedData preview = client.readData(deviceId, 0, nativeData, 0);
                    long copyNanos = System.nanoTime() - copyStartedAtNanos;
                    if (preview.bytes().length > 0) {
                        if (!properties.isPreviewSegmentationEnabled() || preview.format() == BIOB_FIR) {
                            lastPreviewSegmentation.set(FingerSegmentation.empty());
                        } else {
                            lastPreviewSegmentation.set(readPreviewSegmentation(nativeData));
                        }
                        lastPreview.set(preview);
                    }
                    recordPreviewMetrics(format, bufferSize, true, copyNanos);
                } else {
                    recordPreviewMetrics(format, bufferSize, false, 0);
                }
            }
        };
        this.startedCallback = (deviceId, context, reserved) -> enqueueCaptureProgressBeep(deviceId);
        this.completedCallback = (deviceId, context, reserved) -> {
        };
        this.dataAvailableCallback = (deviceId, context, dataStatus, data, detectedObjects) -> {
            if (dataStatus >= 0 && data != null) {
                CapturedData capture = client.readData(deviceId, dataStatus, data, detectedObjects);
                if (capture.bytes().length > 0) {
                    log.info("Capture data received: format={}, bytes={}, detectedObjects={}", capture.format(), capture.bytes().length, capture.detectedObjects());
                    lastCapture.set(capture);
                    enqueueFinalTftStatus(deviceId, dataStatus);
                    CompletableFuture<CapturedData> future = pendingCapture.get();
                    if (future != null) {
                        future.complete(capture);
                    }
                }
            }
        };
        this.objectQualityCallback = (deviceId, context, qualityStates, qualityStateCount) -> {
            if (qualityStates == null || qualityStateCount <= 0) {
                lastObjectQualityStates.set(List.of());
                return;
            }

            ArrayList<Integer> states = new ArrayList<>(qualityStateCount);
            for (int index = 0; index < qualityStateCount; index++) {
                states.add(qualityStates.getInt((long) index * Integer.BYTES));
            }
            List<Integer> previous = lastObjectQualityStates.getAndSet(List.copyOf(states));
            if (!previous.equals(states)) {
                log.debug("Object quality changed: {}", toQualityLog(states));
                enqueueLiveQualityLeds(deviceId, states);
                enqueueLiveTftStatus(deviceId, states);
            }
        };
        this.objectCountCallback = (deviceId, context, objectCountState) -> {
            Integer previous = lastObjectCountState.getAndSet(objectCountState);
            if (!Objects.equals(previous, objectCountState)) {
                log.debug("Object count changed: {}", toCountLog(objectCountState));
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
        client.registerCallback(deviceId, BioBaseEvent.BIOB_OBJECT_QUALITY, objectQualityCallback);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_OBJECT_COUNT, objectCountCallback);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_ACQUISITION_STARTED, startedCallback);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_ACQUISITION_COMPLETED, completedCallback);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_DATA_AVAILABLE, dataAvailableCallback);
        client.openDevice(deviceId, reset);
        activeDeviceId = deviceId;
        logDeviceInfo(deviceId);
        logLedCapability(deviceId);
        openNativePreviewWindow(deviceId);
    }

    public void closeDevice(String deviceId, boolean standby) {
        unregisterCallbacks(deviceId);
        stopNativePreviewCapture();
        nativePreviewWindow.close();
        client.closeDevice(deviceId, standby);
        if (Objects.equals(activeDeviceId, deviceId)) {
            activeDeviceId = null;
        }
    }

    public synchronized CaptureResponse capture(String requestedDeviceId, String position, String impression, Long timeoutSeconds) {
        String deviceId = resolveDeviceId(requestedDeviceId);
        if (!client.isDeviceReady(deviceId)) {
            throw new BioBaseException("Device is not ready. Open the device first.");
        }

        CompletableFuture<CapturedData> future = new CompletableFuture<>();
        if (!pendingCapture.compareAndSet(null, future)) {
            throw new BioBaseException("Another capture is already running.");
        }

        try {
            clearLiveObjectState();
            resetPreviewState();
            String effectiveImpression = blankToDefault(impression, properties.getDefaultImpression());
            activeImpression.set(effectiveImpression);
            configureCaptureProperties(deviceId, effectiveImpression);
            String effectivePosition = blankToDefault(position, properties.getDefaultPosition());
            activePosition.set(effectivePosition);
            enqueueCaptureStartLed(deviceId, effectivePosition);
            enqueueTftCaptureProgress(deviceId, effectivePosition, effectiveImpression);
            client.beginAcquisition(
                    deviceId,
                    effectivePosition,
                    effectiveImpression
            );
            long timeout = timeoutSeconds == null ? properties.getCaptureTimeoutSeconds() : timeoutSeconds;
            CapturedData captured = waitForCapture(future, timeout);
            waitUntilAcquisitionStopped(deviceId);
            enqueueCaptureSuccessLed(deviceId);
            sendCaptureSuccessBeep(deviceId);
            FingerSegmentation segmentation = lastPreviewSegmentation.get();
            CapturedData saved = saveAsImage(captured, "capture");
            if (segmentation.segments().isEmpty()) {
                segmentation = detectSegmentsFromFirViews(captured, saved);
            }
            if (segmentation.segments().isEmpty()) {
                segmentation = detectSegmentsFromCaptureImage(saved, captured.detectedObjects());
            }
            Path annotatedPath = saveAnnotatedCapture(saved, segmentation);
            Path croppedPath = saveCroppedCapture(saved, effectivePosition);
            Path trimmedPath = saveTrimmedRollCapture(saved, segmentation);
            lastCapture.set(saved);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(lastCapture.get().bytes()));
            BufferedImage centerImg = centerCrop(img);
            File outputFile = new File("D:\\workspace\\EGM-AFIS\\biobase\\biobase\\captures\\cropped.png");
            ImageIO.write(centerImg, "png", outputFile);
            return toResponse(saved, segmentation, annotatedPath, croppedPath);
        } catch (TimeoutException e) {
            client.cancelAcquisition(deviceId);
            enqueueCaptureFailureLed(deviceId);
            throw new BioBaseException("Capture timed out before final fingerprint data arrived.");
        } catch (BioBaseException e) {
            enqueueCaptureFailureLed(deviceId);
            throw e;
        } catch (Exception e) {
            enqueueCaptureFailureLed(deviceId);
            throw new BioBaseException("Capture failed: " + e.getMessage());
        } finally {
            pendingCapture.compareAndSet(future, null);
            activeImpression.set(null);
            activePosition.set(null);
            lastQualityLedState.set(List.of());
        }
    }

    private CapturedData waitForCapture(CompletableFuture<CapturedData> future, long timeoutSeconds) throws Exception {
        if (timeoutSeconds <= 0) {
            return future.get();
        }
        return future.get(timeoutSeconds, TimeUnit.SECONDS);
    }

    private void waitUntilAcquisitionStopped(String deviceId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ACQUISITION_STOP_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!client.isDeviceAcquiring(deviceId)) {
                return;
            }
            Thread.sleep(ACQUISITION_STOP_POLL_MILLIS);
        }
        log.warn("Device is still acquiring after {} ms; continuing cleanup.", ACQUISITION_STOP_TIMEOUT_MILLIS);
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
                lastCapture.get() != null,
                objectCountResponse(lastObjectCountState.get()),
                objectQualityResponses(lastObjectQualityStates.get()),
                guidanceMessages(lastObjectQualityStates.get(), lastObjectCountState.get())
        );
    }

    public String propertiesXml(String requestedDeviceId) {
        return client.getProperties(resolveDeviceId(requestedDeviceId));
    }

    public DeviceLedResponse ledInfo(String requestedDeviceId) {
        String deviceId = resolveDeviceId(requestedDeviceId);
        return new DeviceLedResponse(
                deviceId,
                getOptionalProperty(deviceId, PROP_DEVICE_LED_TYPE).orElse("unknown"),
                getOptionalProperty(deviceId, PROP_DEVICE_AVAILABLE_LEDS).orElse("")
        );
    }

    public void setStatusLed(String requestedDeviceId, String led) {
        sendStatusLed(resolveDeviceId(requestedDeviceId), led, "manual");
    }

    public void clearStatusLeds(String requestedDeviceId) {
        sendStatusLed(resolveDeviceId(requestedDeviceId), LED_NONE, "manual clear");
    }

    public void showLScan1000Logo(String requestedDeviceId, int progressPercent) {
        String deviceId = resolveDeviceId(requestedDeviceId);
        requireLScan1000(deviceId);
        String percent = Integer.toString(Math.max(0, Math.min(100, progressPercent)));
        String xml = outputXml("<Tft><" + TFT_LOGO_SCREEN + ">"
                + element("Option", "SHOW_FW_VERSION")
                + element("ProgressBarPercent", percent)
                + "</" + TFT_LOGO_SCREEN + "></Tft>");
        client.setOutputXml(deviceId, xml);
        log.info("LScan1000 display logo sent: deviceId={}, progress={}", deviceId, percent);
    }

    public void clearLScan1000Display(String requestedDeviceId) {
        showLScan1000Logo(requestedDeviceId, 0);
    }

    public void showLScan1000CaptureProgress(String requestedDeviceId, String position, String impression) {
        String deviceId = resolveDeviceId(requestedDeviceId);
        requireLScan1000(deviceId);
        String effectivePosition = blankToDefault(position, properties.getDefaultPosition());
        String effectiveImpression = blankToDefault(impression, properties.getDefaultImpression());
        sendTftCaptureProgress(deviceId, effectivePosition, effectiveImpression, TFT_ERASE, true);
    }

    public void setLScan1000DisplayStatus(String requestedDeviceId, String status) {
        String deviceId = resolveDeviceId(requestedDeviceId);
        requireLScan1000(deviceId);
        sendTftCaptureProgress(deviceId, activePosition.get(), activeImpression.get(), blankToDefault(status, TFT_ERASE), false);
    }

    public void setVisualizationWindow(String requestedDeviceId, String windowHandle) {
        String deviceId = resolveDeviceId(requestedDeviceId);
        long handle = parseWindowHandle(windowHandle);
        client.setVisualizationWindow(deviceId, handle, PROP_VISUALIZATION_FINGER_WINDOW, 0);
        setOptionalProperty(deviceId, PROP_VISUALIZATION_MODE, PROP_VISMODE_PREVIEW_ONLY);
        setOptionalProperty(deviceId, PROP_VISUALIZATION_FULLIMAGE_ON, PROP_FALSE);
        setOptionalProperty(deviceId, PROP_VISUALIZATION_BK_COLOR, PROP_DEFAULT_BK_COLOR);
        log.info("BioBase visualization window set: deviceId={}, handle=0x{}, visualizer={}",
                deviceId, Long.toHexString(handle), PROP_VISUALIZATION_FINGER_WINDOW);
    }

    private void openNativePreviewWindow(String deviceId) {
        String title = devices().stream()
                .filter(device -> Objects.equals(device.deviceId(), deviceId))
                .findFirst()
                .map(device -> "BioBase Preview - " + device.modelName())
                .orElse("BioBase Preview - " + deviceId);
        nativePreviewWindow.open(title)
                .ifPresent(handle -> {
                    setVisualizationWindow(deviceId, "0x" + Long.toHexString(handle));
                    startNativePreviewCapture(deviceId);
                });
    }

    private boolean shouldUseNativePreviewCapture() {
        return properties.isNativePreviewCaptureEnabled() && nativePreviewWindow.isOpen();
    }

    private void startNativePreviewCapture(String deviceId) {
        stopNativePreviewCapture();
        if (!properties.isNativePreviewCaptureEnabled()) {
            return;
        }

        int fps = Math.max(1, Math.min(30, properties.getNativePreviewCaptureFps()));
        long periodMillis = Math.max(1, 1000L / fps);
        ScheduledFuture<?> task = nativePreviewCaptureExecutor.scheduleAtFixedRate(
                () -> captureNativePreviewFrame(deviceId),
                0,
                periodMillis,
                TimeUnit.MILLISECONDS
        );
        nativePreviewCaptureTask.set(task);
        nativePreviewCaptureFailureLogged.set(false);
        log.info("Native preview capture started: deviceId={}, fps={}", deviceId, fps);
    }

    private void stopNativePreviewCapture() {
        ScheduledFuture<?> task = nativePreviewCaptureTask.getAndSet(null);
        if (task != null) {
            task.cancel(true);
            log.info("Native preview capture stopped.");
        }
    }

    private void captureNativePreviewFrame(String deviceId) {
        try {
            byte[] bytes = nativePreviewWindow.captureJpeg();
            if (bytes.length == 0) {
                return;
            }
            lastPreview.set(new CapturedData(
                    deviceId,
                    BioBaseDataFormat.BIOB_JPG,
                    false,
                    0,
                    0,
                    bytes,
                    null,
                    Instant.now()
            ));
            writeNativePreviewFile(bytes);
        } catch (Exception e) {
            if (nativePreviewCaptureFailureLogged.compareAndSet(false, true)) {
                log.warn("Native preview capture is not available: {}", e.getMessage());
            }
        }
    }

    private void writeNativePreviewFile(byte[] bytes) {
        try {
            Path outputDir = properties.getOutputDir();
            Files.createDirectories(outputDir);
            Path outputPath = outputDir.resolve("native-preview-live.jpg");
            Files.write(outputPath, bytes);
            if (nativePreviewFileLogged.compareAndSet(false, true)) {
                log.info("Native preview live image is being updated at {}", outputPath.toAbsolutePath());
            }
        } catch (IOException e) {
            if (nativePreviewCaptureFailureLogged.compareAndSet(false, true)) {
                log.warn("Could not write native preview live image: {}", e.getMessage());
            }
        }
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
        return firToBmp(firData, true);
    }

    private byte[] firToBmp(byte[] firData, boolean preferLargestView) {
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
            List<Cmt_finger_viewspec> views = queryViews(cfir);
            if (!preferLargestView || views.size() == 1) {
                return encodeViewToBmp(cfir, views.get(0));
            }

            return decodeFirViewImages(cfir).stream()
                    .max((left, right) -> Integer.compare(imageArea(left.image()), imageArea(right.image())))
                    .orElseThrow(() -> new RuntimeException("FIR içinde görüntü bulunamadı"))
                    .bmpBytes();
        } finally {
            CmtFingerNative.INSTANCE.cmtfinger_free(cfir);
        }
    }

    private List<FirViewImage> decodeFirViewImages(byte[] firData) {
        PointerByReference rcfir = new PointerByReference();
        int result = CmtFingerNative.INSTANCE.cmtfinger_create(rcfir);
        if (result != 0) {
            throw new RuntimeException("Record oluşturulamadı, hata: " + result);
        }
        Pointer cfir = rcfir.getValue();
        try {
            result = CmtFingerNative.INSTANCE.cmtfinger_decode(cfir, firData, firData.length);
            if (result != 0) {
                throw new RuntimeException("FIR decode hatası: " + result);
            }
            return decodeFirViewImages(cfir);
        } finally {
            CmtFingerNative.INSTANCE.cmtfinger_free(cfir);
        }
    }

    private List<FirViewImage> decodeFirViewImages(Pointer cfir) {
        List<Cmt_finger_viewspec> views = queryViews(cfir);
        ArrayList<FirViewImage> images = new ArrayList<>(views.size());
        for (int index = 0; index < views.size(); index++) {
            Cmt_finger_viewspec view = views.get(index);
            byte[] bmpBytes = encodeViewToBmp(cfir, view);
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bmpBytes));
                if (image != null) {
                    images.add(new FirViewImage(index, view.position, view.impression, view.quality, bmpBytes, image));
                }
            } catch (IOException e) {
                log.warn("Could not read FIR view {} as BMP: {}", index, e.getMessage());
            }
        }
        return List.copyOf(images);
    }

    private List<Cmt_finger_viewspec> queryViews(Pointer cfir) {
        Cmt_finger_viewspec query = new Cmt_finger_viewspec();
        query.position = -1;
        query.impression = -1;
        query.write();

        IntByReference numResults = new IntByReference(0);
        int result = CmtFingerNative.INSTANCE.cmtfinger_query(cfir, query, null, numResults);
        if (result != 0) {
            throw new RuntimeException("FIR view query hatası: " + result);
        }
        if (numResults.getValue() <= 0) {
            throw new RuntimeException("FIR içinde görüntü bulunamadı");
        }

        int count = numResults.getValue();
        int viewSpecSize = query.size();
        Memory results = new Memory((long) count * viewSpecSize);
        result = CmtFingerNative.INSTANCE.cmtfinger_query(cfir, query, results, numResults);
        if (result != 0) {
            throw new RuntimeException("FIR view result hatası: " + result);
        }

        java.util.ArrayList<Cmt_finger_viewspec> views = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            long offset = (long) index * viewSpecSize;
            Cmt_finger_viewspec view = new Cmt_finger_viewspec();
            view.position = results.getInt(offset);
            view.impression = results.getInt(offset + Integer.BYTES);
            view.quality = results.getInt(offset + (2L * Integer.BYTES));
            view.write();
            views.add(view);
        }
        return views;
    }

    private byte[] encodeViewToBmp(Pointer cfir, Cmt_finger_viewspec vs) {
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

    private FingerSegmentation readPreviewSegmentation(BioBaseNative.BioBData nativeData) {
        try {
            if (nativeData.extStruct == null || Pointer.nativeValue(nativeData.extStruct) == 0) {
                log.debug("Preview segmentation extStruct is not available for format={}", BioBaseDataFormat.fromValue(nativeData.formatType));
                return FingerSegmentation.empty();
            }

            BioBaseNative.BioBScene scene = new BioBaseNative.BioBScene(nativeData.extStruct);
            if (scene.numDetected <= 0 || scene.biometricObjects == null || Pointer.nativeValue(scene.biometricObjects) == 0) {
                return new FingerSegmentation(scene.width, scene.height, List.of());
            }

            int roiSize = new BioBaseNative.BioBROI().size();
            java.util.ArrayList<FingerSegment> segments = new java.util.ArrayList<>();
            for (int index = 0; index < scene.numDetected; index++) {
                BioBaseNative.BioBROI roi = new BioBaseNative.BioBROI(scene.biometricObjects.share((long) index * roiSize));
                segments.add(new FingerSegment(
                        index + 1,
                        roi.x,
                        roi.y,
                        roi.width,
                        roi.height
                ));
            }

            FingerSegmentation segmentation = new FingerSegmentation(scene.width, scene.height, List.copyOf(segments));
            log.info("Preview segmentation received: image={}x{}, segments={}",
                    segmentation.imageWidth(), segmentation.imageHeight(), segmentation.segments().size());
            return segmentation;
        } catch (Exception e) {
            log.warn("Could not read preview segmentation coordinates: {}", e.getMessage());
            return FingerSegmentation.empty();
        }
    }

    private FingerSegmentation detectSegmentsFromFirViews(CapturedData originalData, CapturedData savedMainImage) {
        if (originalData.format() != BIOB_FIR || savedMainImage.savedPath() == null) {
            return FingerSegmentation.empty();
        }

        try {
            BufferedImage mainImage = ImageIO.read(savedMainImage.savedPath().toFile());
            if (mainImage == null) {
                return FingerSegmentation.empty();
            }

            List<FirViewImage> views = decodeFirViewImages(originalData.bytes());
            if (views.size() <= 1) {
                log.debug("FIR view matching skipped: only {} view(s) available", views.size());
                return FingerSegmentation.empty();
            }

            FirViewImage mainView = views.stream()
                    .max((left, right) -> Integer.compare(imageArea(left.image()), imageArea(right.image())))
                    .orElse(null);
            if (mainView == null) {
                return FingerSegmentation.empty();
            }

            ArrayList<FingerSegment> segments = new ArrayList<>();
            int segmentIndex = 1;
            for (FirViewImage view : views) {
                if (view.index() == mainView.index()) {
                    continue;
                }
                if (imageArea(view.image()) >= imageArea(mainImage) * 0.90) {
                    continue;
                }

                Optional<FingerSegment> matched = matchSegmentImage(mainImage, view.image(), segmentIndex);
                if (matched.isPresent()) {
                    segments.add(matched.get());
                    segmentIndex++;
                } else {
                    log.warn("Could not locate FIR segment view {} on main capture image", view.index());
                }
            }

            if (segments.isEmpty()) {
                return FingerSegmentation.empty();
            }

            segments.sort((left, right) -> Integer.compare(left.x(), right.x()));
            ArrayList<FingerSegment> indexed = new ArrayList<>(segments.size());
            for (int index = 0; index < segments.size(); index++) {
                FingerSegment segment = segments.get(index);
                indexed.add(new FingerSegment(index + 1, segment.x(), segment.y(), segment.width(), segment.height()));
            }
            log.info("FIR view matching detected {} segment(s) on capture image {}x{}",
                    indexed.size(), mainImage.getWidth(), mainImage.getHeight());
            return new FingerSegmentation(mainImage.getWidth(), mainImage.getHeight(), List.copyOf(indexed));
        } catch (Exception e) {
            log.warn("FIR view matching skipped: {}", e.getMessage());
            return FingerSegmentation.empty();
        }
    }

    private Optional<FingerSegment> matchSegmentImage(BufferedImage mainImage, BufferedImage segmentImage, int index) {
        Rectangle contentBounds = darkContentBounds(segmentImage);
        if (contentBounds == null || contentBounds.width <= 0 || contentBounds.height <= 0) {
            return Optional.empty();
        }
        if (contentBounds.width > mainImage.getWidth() || contentBounds.height > mainImage.getHeight()) {
            return Optional.empty();
        }

        int templateWidth = contentBounds.width;
        int templateHeight = contentBounds.height;
        List<int[]> samplePoints = templateSamplePoints(templateWidth, templateHeight);
        int step = Math.max(1, Math.min(templateWidth, templateHeight) / 30);

        MatchScore best = findBestTemplateMatch(mainImage, segmentImage, contentBounds, samplePoints, step, 0, 0,
                mainImage.getWidth() - templateWidth, mainImage.getHeight() - templateHeight);
        int refineRadius = Math.max(2, step + 1);
        best = findBestTemplateMatch(mainImage, segmentImage, contentBounds, samplePoints, 1,
                Math.max(0, best.x() - refineRadius),
                Math.max(0, best.y() - refineRadius),
                Math.min(mainImage.getWidth() - templateWidth, best.x() + refineRadius),
                Math.min(mainImage.getHeight() - templateHeight, best.y() + refineRadius));

        if (best.averageDifference() > 75) {
            log.warn("FIR segment match rejected: average luminance difference {}", best.averageDifference());
            return Optional.empty();
        }

        return Optional.of(new FingerSegment(index, best.x(), best.y(), templateWidth, templateHeight));
    }

    private MatchScore findBestTemplateMatch(
            BufferedImage mainImage,
            BufferedImage segmentImage,
            Rectangle contentBounds,
            List<int[]> samplePoints,
            int step,
            int startX,
            int startY,
            int endX,
            int endY
    ) {
        MatchScore best = new MatchScore(startX, startY, Integer.MAX_VALUE);
        for (int y = startY; y <= endY; y += step) {
            for (int x = startX; x <= endX; x += step) {
                int score = templateDifference(mainImage, segmentImage, contentBounds, samplePoints, x, y);
                if (score < best.totalDifference()) {
                    best = new MatchScore(x, y, score);
                }
            }
        }
        return best;
    }

    private int templateDifference(
            BufferedImage mainImage,
            BufferedImage segmentImage,
            Rectangle contentBounds,
            List<int[]> samplePoints,
            int mainX,
            int mainY
    ) {
        int total = 0;
        for (int[] point : samplePoints) {
            int x = point[0];
            int y = point[1];
            int templateLuminance = luminance(segmentImage.getRGB(contentBounds.x + x, contentBounds.y + y));
            int mainLuminance = luminance(mainImage.getRGB(mainX + x, mainY + y));
            total += Math.abs(templateLuminance - mainLuminance);
        }
        return total / Math.max(1, samplePoints.size());
    }

    private static List<int[]> templateSamplePoints(int width, int height) {
        int gridX = Math.min(32, Math.max(8, width / 8));
        int gridY = Math.min(32, Math.max(8, height / 8));
        ArrayList<int[]> points = new ArrayList<>(gridX * gridY);
        for (int gy = 0; gy < gridY; gy++) {
            int y = gridY == 1 ? 0 : gy * (height - 1) / (gridY - 1);
            for (int gx = 0; gx < gridX; gx++) {
                int x = gridX == 1 ? 0 : gx * (width - 1) / (gridX - 1);
                points.add(new int[]{x, y});
            }
        }
        return List.copyOf(points);
    }

    private Rectangle darkContentBounds(BufferedImage image) {
        int threshold = otsuThreshold(image);
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (luminance(image.getRGB(x, y)) <= threshold) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return null;
        }

        int padding = Math.max(2, Math.min(image.getWidth(), image.getHeight()) / 100);
        minX = Math.max(0, minX - padding);
        minY = Math.max(0, minY - padding);
        maxX = Math.min(image.getWidth() - 1, maxX + padding);
        maxY = Math.min(image.getHeight() - 1, maxY + padding);
        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private FingerSegmentation detectSegmentsFromCaptureImage(CapturedData data, int expectedCount) {
        if (data.savedPath() == null) {
            return FingerSegmentation.empty();
        }

        try {
            BufferedImage image = ImageIO.read(data.savedPath().toFile());
            if (image == null) {
                log.warn("Image segmentation skipped: unsupported image format at {}", data.savedPath());
                return FingerSegmentation.empty();
            }

            List<FingerSegment> segments = detectFingerprintBands(image, expectedCount);
            if (segments.isEmpty()) {
                log.warn("Image segmentation found no finger regions in {}", data.savedPath());
                return FingerSegmentation.empty();
            }

            log.info("Image segmentation detected {} segment(s) on capture image {}x{}",
                    segments.size(), image.getWidth(), image.getHeight());
            return new FingerSegmentation(image.getWidth(), image.getHeight(), segments);
        } catch (Exception e) {
            log.warn("Image segmentation skipped: {}", e.getMessage());
            return FingerSegmentation.empty();
        }
    }

    private BufferedImage centerCrop(BufferedImage image) {
        int targetW = 1600;
        int targetH = 1500;

        int width = image.getWidth();
        int height = image.getHeight();

        // Görüntü zaten hedef boyutta veya küçükse dokunma
        if (width <= targetW && height <= targetH) {
            return image;
        }

        // 1) Koyu piksel haritası ve sütun/satır dolulukları
        int threshold = otsuThreshold(image);
        int[] columnCounts = new int[width];
        int[] rowCounts = new int[height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (luminance(image.getRGB(x, y)) <= threshold) {
                    columnCounts[x]++;
                    rowCounts[y]++;
                }
            }
        }

        // 2) Yumuşat
        int[] smoothedCols = smooth(columnCounts, Math.max(2, width / 250));
        int[] smoothedRows = smooth(rowCounts, Math.max(2, height / 250));

        // 3) Aktiflik eşikleri
        int colThreshold = Math.max(8, height / 120);
        int rowThreshold = Math.max(8, width / 120);

        // 4) İçeriğin yatay sınırları
        int minX = -1;
        int maxX = -1;
        for (int x = 0; x < width; x++) {
            if (smoothedCols[x] >= colThreshold) {
                if (minX == -1) {
                    minX = x;
                }
                maxX = x;
            }
        }

        // 5) İçeriğin dikey sınırları
        int minY = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            if (smoothedRows[y] >= rowThreshold) {
                if (minY == -1) {
                    minY = y;
                }
                maxY = y;
            }
        }

        // İçerik bulunamazsa: görüntüyü ortadan kırp
        if (minX == -1 || minY == -1) {
            minX = 0;
            maxX = width - 1;
            minY = 0;
            maxY = height - 1;
        }

        // 6) Kırpma penceresi: içerik ortada kalacak şekilde
        int cropW = Math.min(targetW, width);
        int cropH = Math.min(targetH, height);

        int centerX = (minX + maxX) / 2;
        int cropX = centerX - cropW / 2;
        cropX = Math.max(0, Math.min(cropX, width - cropW));

        int centerY = (minY + maxY) / 2;
        int cropY = centerY - cropH / 2;
        cropY = Math.max(0, Math.min(cropY, height - cropH));

        // 7) Kırpılmış görüntüyü oluştur
        BufferedImage cropped = new BufferedImage(cropW, cropH, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < cropH; y++) {
            for (int x = 0; x < cropW; x++) {
                cropped.setRGB(x, y, image.getRGB(cropX + x, cropY + y));
            }
        }
        return cropped;
    }

    private List<FingerSegment> detectFingerprintBands(BufferedImage image, int expectedCount) {
        int width = image.getWidth();
        int height = image.getHeight();
        int threshold = otsuThreshold(image);
        int[] columnCounts = new int[width];
        boolean[][] darkPixels = new boolean[width][height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int luminance = luminance(image.getRGB(x, y));
                boolean dark = luminance <= threshold;
                darkPixels[x][y] = dark;
                if (dark) {
                    columnCounts[x]++;
                }
            }
        }

        int[] smoothed = smooth(columnCounts, Math.max(2, width / 250));
        int activeThreshold = Math.max(8, height / 120);
        boolean[] activeColumns = new boolean[width];
        for (int x = 0; x < width; x++) {
            activeColumns[x] = smoothed[x] >= activeThreshold;
        }

        List<int[]> ranges = columnRanges(activeColumns, Math.max(3, width / 120), Math.max(12, width / 80));
        java.util.ArrayList<FingerSegment> candidates = new java.util.ArrayList<>();
        for (int[] range : ranges) {
            FingerSegment segment = boundsForRange(1, darkPixels, range[0], range[1], width, height);
            if (segment.width() >= Math.max(10, width / 120) && segment.height() >= Math.max(20, height / 20)) {
                FingerSegment topBogum = cropToFirstKnuckle(segment, darkPixels);
                candidates.add(topBogum);
            }
        }

        if (candidates.isEmpty()) {
            FingerSegment fullBounds = boundsForRange(1, darkPixels, 0, width - 1, width, height);
            if (fullBounds.width() > 1 && fullBounds.height() > 1) {
                candidates.add(cropToFirstKnuckle(fullBounds, darkPixels));
            }
        }

        if (expectedCount > 0 && candidates.size() < expectedCount) {
            while (candidates.size() < expectedCount) {
                FingerSegment widestSegment = null;
                int widestIndex = -1;

                for (int i = 0; i < candidates.size(); i++) {
                    FingerSegment s = candidates.get(i);
                    if (widestSegment == null || s.width() > widestSegment.width()) {
                        widestSegment = s;
                        widestIndex = i;
                    }
                }

                if (widestSegment == null || widestSegment.width() < Math.max(20, width / 60)) {
                    break;
                }

                int bestSplitX = -1;
                int minDarkCount = Integer.MAX_VALUE;
                int startX = widestSegment.x() + (widestSegment.width() / 5);
                int endX = widestSegment.x() + (widestSegment.width() * 4 / 5);

                for (int x = startX; x <= endX; x++) {
                    if (smoothed[x] < minDarkCount) {
                        minDarkCount = smoothed[x];
                        bestSplitX = x;
                    }
                }

                if (bestSplitX != -1) {
                    candidates.remove(widestIndex);
                    FingerSegment leftSeg = boundsForRange(1, darkPixels, widestSegment.x(), bestSplitX - 1, width, height);
                    FingerSegment rightSeg = boundsForRange(1, darkPixels, bestSplitX + 1, widestSegment.x() + widestSegment.width() - 1, width, height);

                    candidates.add(cropToFirstKnuckle(leftSeg, darkPixels));
                    candidates.add(cropToFirstKnuckle(rightSeg, darkPixels));
                } else {
                    break;
                }
            }
        }

        int limit = expectedCount > 0 ? expectedCount : candidates.size();
        if (candidates.size() > limit) {
            candidates.sort((left, right) -> Integer.compare(area(right), area(left)));
            candidates = new java.util.ArrayList<>(candidates.subList(0, limit));
        }

        candidates.sort((left, right) -> Integer.compare(left.x(), right.x()));
        java.util.ArrayList<FingerSegment> indexed = new java.util.ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            FingerSegment segment = candidates.get(index);
            indexed.add(new FingerSegment(index + 1, segment.x(), segment.y(), segment.width(), segment.height()));
        }
        return List.copyOf(indexed);
    }

    private FingerSegment cropToFirstKnuckle(FingerSegment segment, boolean[][] darkPixels) {
        int startY = segment.y();
        int endY = segment.y() + segment.height();
        int segmentWidth = segment.width();
        int segmentHeight = segment.height();

        // 0) KISA SEGMENT: sadece uç basımı — hiç kırpma
        if (segmentHeight < segmentWidth * 1.6) {
            return segment;
        }

        // 1) Her satırdaki koyu piksel sayısı
        int[] rowCounts = new int[endY - startY];
        for (int y = startY; y < endY; y++) {
            int darkInRow = 0;
            for (int x = segment.x(); x < segment.x() + segmentWidth; x++) {
                if (darkPixels[x][y]) {
                    darkInRow++;
                }
            }
            rowCounts[y - startY] = darkInRow;
        }

        // 2) 5 satırlık hareketli ortalama
        int[] smoothed = new int[rowCounts.length];
        for (int i = 0; i < rowCounts.length; i++) {
            int sum = 0, count = 0;
            for (int d = -2; d <= 2; d++) {
                int idx = i + d;
                if (idx >= 0 && idx < rowCounts.length) {
                    sum += rowCounts[idx];
                    count++;
                }
            }
            smoothed[i] = sum / count;
        }

        // 3) Arama bölgesi: %40 - %70
        int searchStart = startY + (int) (segmentHeight * 0.40);
        int searchEnd = startY + (int) (segmentHeight * 0.70);

        // 4) Tepe değeri
        int peak = 0;
        for (int i = 0; i <= (searchStart - startY); i++) {
            if (smoothed[i] > peak) {
                peak = smoothed[i];
            }
        }
        if (peak <= 0) {
            peak = segmentWidth;
        }

        // 5) Oransal eşik
        int valleyThreshold = (int) (peak * 0.55);

        int bestCutY = -1;

        // 6) Sürdürülebilir vadi arama
        int sustain = Math.max(3, (int) (segmentHeight * 0.05));
        for (int y = searchStart; y < searchEnd; y++) {
            int idx = y - startY;
            if (smoothed[idx] <= valleyThreshold) {
                boolean sustained = true;
                for (int k = 1; k <= sustain && idx + k < smoothed.length; k++) {
                    if (smoothed[idx + k] > valleyThreshold) {
                        sustained = false;
                        break;
                    }
                }
                if (sustained) {
                    bestCutY = y;
                    break;
                }
            }
        }

        boolean valleyFound = (bestCutY != -1);

        // 7) Vadi bulunamazsa: en büyük dikey düşüş
        if (!valleyFound) {
            int maxDrop = 0;
            for (int y = searchStart; y < searchEnd; y++) {
                int idx = y - startY;
                if (idx + 1 < smoothed.length) {
                    int drop = smoothed[idx] - smoothed[idx + 1];
                    if (drop > maxDrop) {
                        maxDrop = drop;
                        bestCutY = y;
                    }
                }
            }
        }

        // 8) Segment uzunluğuna göre güvenlik sınırları:
        //    - ÇOK UZUN segment (boğumlu basım): vadi tespitine güven,
        //      kesim %45'in üstüne çıkamaz ama aşağı serbesttir.
        //    - ORTA UZUN segment (belirsiz): sıkı sınır, en fazla %35 kırp.
        boolean veryLong = segmentHeight > segmentWidth * 2.2;
        int minCutY, maxCutY;

        if (veryLong) {
            minCutY = startY + (int) (segmentHeight * 0.45);
            maxCutY = startY + (int) (segmentHeight * 0.70);
        } else {
            minCutY = startY + (int) (segmentHeight * 0.65);
            maxCutY = startY + (int) (segmentHeight * 0.70);
        }

        if (bestCutY == -1) {
            bestCutY = startY + (int) (segmentHeight * 0.55);
        }
        if (bestCutY < minCutY) {
            bestCutY = minCutY;
        }
        if (bestCutY > maxCutY) {
            bestCutY = maxCutY;
        }

        // 9) ORTA UZUN segmentlerde vadi bulunamadıysa kesim yapma (koru)
        if (!veryLong && !valleyFound) {
            return segment;
        }

        int newHeight = bestCutY - startY;
        return new FingerSegment(segment.index(), segment.x(), startY, segmentWidth, newHeight);
    }

    private static int otsuThreshold(BufferedImage image) {
        int[] histogram = new int[256];
        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                histogram[luminance(image.getRGB(x, y))]++;
            }
        }

        int total = width * height;
        long sum = 0;
        for (int level = 0; level < histogram.length; level++) {
            sum += (long) level * histogram[level];
        }

        long backgroundSum = 0;
        int backgroundWeight = 0;
        double maxVariance = -1;
        int threshold = 127;
        for (int level = 0; level < histogram.length; level++) {
            backgroundWeight += histogram[level];
            if (backgroundWeight == 0) {
                continue;
            }

            int foregroundWeight = total - backgroundWeight;
            if (foregroundWeight == 0) {
                break;
            }

            backgroundSum += (long) level * histogram[level];
            double backgroundMean = (double) backgroundSum / backgroundWeight;
            double foregroundMean = (double) (sum - backgroundSum) / foregroundWeight;
            double variance = (double) backgroundWeight * foregroundWeight
                    * (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean);
            if (variance > maxVariance) {
                maxVariance = variance;
                threshold = level;
            }
        }
        return Math.min(210, Math.max(40, threshold + 15));
    }

    private static int luminance(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    private static int[] smooth(int[] values, int radius) {
        int[] result = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            int start = Math.max(0, index - radius);
            int end = Math.min(values.length - 1, index + radius);
            int sum = 0;
            for (int cursor = start; cursor <= end; cursor++) {
                sum += values[cursor];
            }
            result[index] = sum / (end - start + 1);
        }
        return result;
    }

    private static List<int[]> columnRanges(boolean[] activeColumns, int gapTolerance, int minimumWidth) {
        java.util.ArrayList<int[]> ranges = new java.util.ArrayList<>();
        int start = -1;
        int lastActive = -1;
        for (int index = 0; index < activeColumns.length; index++) {
            if (activeColumns[index]) {
                if (start < 0) {
                    start = index;
                }
                lastActive = index;
            } else if (start >= 0 && index - lastActive > gapTolerance) {
                if (lastActive - start + 1 >= minimumWidth) {
                    ranges.add(new int[]{start, lastActive});
                }
                start = -1;
                lastActive = -1;
            }
        }

        if (start >= 0 && lastActive - start + 1 >= minimumWidth) {
            ranges.add(new int[]{start, lastActive});
        }
        return ranges;
    }

    private static FingerSegment boundsForRange(int index, boolean[][] darkPixels, int startX, int endX, int width, int height) {
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int x = Math.max(0, startX); x <= Math.min(width - 1, endX); x++) {
            for (int y = 0; y < height; y++) {
                if (darkPixels[x][y]) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return new FingerSegment(index, 0, 0, 0, 0);
        }

        return new FingerSegment(index, minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static int area(FingerSegment segment) {
        return segment.width() * segment.height();
    }

    private static int imageArea(BufferedImage image) {
        return image.getWidth() * image.getHeight();
    }

    private boolean shouldCachePreviewPayload() {
        if (!properties.isPreviewPayloadCacheEnabled()) {
            return false;
        }
        long intervalMillis = properties.getPreviewPayloadCacheIntervalMillis();
        if (intervalMillis <= 0) {
            return true;
        }

        long now = System.currentTimeMillis();
        long previous = lastPreviewCachedAtMillis.get();
        return now - previous >= intervalMillis && lastPreviewCachedAtMillis.compareAndSet(previous, now);
    }

    private void resetPreviewState() {
        previewSeenLogged.set(false);
        captureSuccessBeepSent.set(false);
        captureProgressBeepSent.set(false);
        lastPreviewCachedAtMillis.set(0);
        previewMetricWindowStartedAtMillis.set(System.currentTimeMillis());
        previewMetricFrames.set(0);
        previewMetricCachedFrames.set(0);
        previewMetricBytes.set(0);
        previewMetricCopyNanos.set(0);
    }

    private void recordPreviewMetrics(BioBaseDataFormat format, int bytes, boolean cached, long copyNanos) {
        if (!properties.isPreviewDiagnosticsEnabled()) {
            return;
        }
        previewMetricFrames.incrementAndGet();
        if (cached) {
            previewMetricCachedFrames.incrementAndGet();
            previewMetricBytes.addAndGet(bytes);
            previewMetricCopyNanos.addAndGet(copyNanos);
        }

        long intervalMillis = properties.getPreviewDiagnosticsIntervalMillis();
        if (intervalMillis <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        long windowStart = previewMetricWindowStartedAtMillis.get();
        if (now - windowStart < intervalMillis || !previewMetricWindowStartedAtMillis.compareAndSet(windowStart, now)) {
            return;
        }

        long frames = previewMetricFrames.getAndSet(0);
        long cachedFrames = previewMetricCachedFrames.getAndSet(0);
        long totalBytes = previewMetricBytes.getAndSet(0);
        long totalCopyNanos = previewMetricCopyNanos.getAndSet(0);
        long elapsedMillis = Math.max(now - windowStart, 1);
        double fps = frames * 1000.0 / elapsedMillis;
        double cachedFps = cachedFrames * 1000.0 / elapsedMillis;
        double avgCopyMillis = cachedFrames == 0 ? 0.0 : (totalCopyNanos / 1_000_000.0) / cachedFrames;
        long avgBytes = cachedFrames == 0 ? 0 : totalBytes / cachedFrames;
        log.info("Preview metrics: format={}, frames={}, fps={}, cachedFrames={}, cachedFps={}, avgBytes={}, avgCopyMs={}",
                format, frames, round(fps, 1), cachedFrames, round(cachedFps, 1), avgBytes, round(avgCopyMillis, 3));
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    private void unregisterCallbacks(String deviceId) {
        client.registerCallback(deviceId, BioBaseEvent.BIOB_PREVIEW, null);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_OBJECT_QUALITY, null);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_OBJECT_COUNT, null);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_ACQUISITION_STARTED, null);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_ACQUISITION_COMPLETED, null);
        client.registerCallback(deviceId, BioBaseEvent.BIOB_DATA_AVAILABLE, null);
    }

    private void configureCaptureProperties(String deviceId, String impression) {
        configureCoreCaptureProperties(deviceId, impression);
        configureAutoCapture(deviceId);
        configureSpoofDetection(deviceId);
        configurePreview(deviceId);
        logBeeperCapability(deviceId);
    }

    private void configureCoreCaptureProperties(String deviceId, String impression) {
        setOptionalProperty(deviceId, PROP_ACTIVE_AREA, properties.getActiveArea());
        setOptionalProperty(deviceId, PROP_IMAGE_RESOLUTION, properties.getImageResolution());

        boolean flatCapture = "FingerprintFlat".equalsIgnoreCase(impression);
        boolean autoContrastEnabled = isPatrolFlatSpeedMode(deviceId, impression)
                ? properties.isPatrolFlatAutoContrastEnabled()
                : properties.isAutoContrastEnabled();
        String autoContrast = autoContrastEnabled && flatCapture ? PROP_TRUE : PROP_FALSE;
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
        logPreviewCapabilities(deviceId, "before");
        setOptionalProperty(deviceId, PROP_PREVIEW_IMAGE_FORMAT, properties.getPreviewImageFormat());
        setOptionalProperty(deviceId, PROP_PREVIEW_LEVEL, effectivePreviewLevel(deviceId));
        logPreviewCapabilities(deviceId, "after");
    }

    private void logBeeperCapability(String deviceId) {
        getOptionalProperty(deviceId, PROP_DEVICE_BEEPER_TYPE)
                .ifPresent(type -> log.info("BioBase device beeper type: {}", type));
    }

    private void logDeviceInfo(String deviceId) {
        if (!properties.isPreviewDiagnosticsEnabled()) {
            return;
        }
        devices().stream()
                .filter(device -> Objects.equals(device.deviceId(), deviceId))
                .findFirst()
                .ifPresent(device -> log.info(
                        "BioBase device info: model={}, serial={}, interface={}, modality={}, visualizers={}",
                        device.modelName(),
                        device.serialNumber(),
                        device.interfaceName(),
                        device.modality(),
                        device.visualizers()));
    }

    private String effectivePreviewLevel(String deviceId) {
        if (isPatrolFlatSpeedMode(deviceId, activeImpression.get())) {
            return blankToDefault(properties.getPatrolFlatPreviewLevel(), properties.getPreviewLevel());
        }
        return properties.getPreviewLevel();
    }

    private boolean isPatrolFlatSpeedMode(String deviceId, String impression) {
        return properties.isPatrolFlatSpeedModeEnabled()
                && "FingerprintFlat".equalsIgnoreCase(impression)
                && isPatrolDevice(deviceId);
    }

    private boolean isPatrolDevice(String deviceId) {
        return devices().stream()
                .filter(device -> Objects.equals(device.deviceId(), deviceId))
                .map(DeviceInfo::modelName)
                .filter(Objects::nonNull)
                .anyMatch(model -> model.toUpperCase(java.util.Locale.ROOT).contains("PATROL"));
    }

    private void logLedCapability(String deviceId) {
        if (!properties.isPreviewDiagnosticsEnabled()) {
            return;
        }
        getOptionalProperty(deviceId, PROP_DEVICE_LED_TYPE)
                .ifPresent(type -> log.info("BioBase device LED type: {}", type));
        getOptionalProperty(deviceId, PROP_DEVICE_AVAILABLE_LEDS)
                .ifPresent(leds -> log.info("BioBase device available LEDs: {}", leds));
    }

    private void logPreviewCapabilities(String deviceId, String phase) {
        if (!properties.isPreviewDiagnosticsEnabled()) {
            return;
        }
        logOptionalPreviewProperty(deviceId, phase, PROP_AVAILABLE_PREVIEW_LEVELS);
        logOptionalPreviewProperty(deviceId, phase, PROP_DEVICE_PREVIEW_FRAME_RATE);
        logOptionalPreviewProperty(deviceId, phase, PROP_DEVICE_PREVIEW_IMAGES_SUPPORTED);
        logOptionalPreviewProperty(deviceId, phase, PROP_ENCODING_FORMATS_SUPPORTED);
        logOptionalPreviewProperty(deviceId, phase, PROP_PREVIEW_IMAGE_FORMAT);
        logOptionalPreviewProperty(deviceId, phase, PROP_PREVIEW_LEVEL);
        logOptionalPreviewProperty(deviceId, phase, PROP_ACTIVE_AREA);
        logOptionalPreviewProperty(deviceId, phase, PROP_AUTOCONTRAST_ON);
        logOptionalPreviewProperty(deviceId, phase, PROP_AUTOCONTRAST_WAIT_TIME);
        logOptionalPreviewProperty(deviceId, phase, PROP_AUTOCAPTURE_ON);
        logOptionalPreviewProperty(deviceId, phase, PROP_AUTOCAPTURE_NUM_RQD_OBJECTS);
        logOptionalPreviewProperty(deviceId, phase, PROP_AUTOCAPTURE_OVERRIDE_ON);
        logOptionalPreviewProperty(deviceId, phase, PROP_VISUALIZATION_MODE);
        logOptionalPreviewProperty(deviceId, phase, PROP_VISUALIZATION_FULLIMAGE_ON);
    }

    private void logOptionalPreviewProperty(String deviceId, String phase, String propertyName) {
        try {
            log.info("BioBase preview property [{}] {}={}", phase, propertyName, client.getProperty(deviceId, propertyName));
        } catch (BioBaseException e) {
            log.debug("BioBase preview property [{}] {} is not readable: {}", phase, propertyName, e.getMessage());
        }
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

    private CapturedData saveAsImage(CapturedData data, String prefix) {
        return save(toImageData(data), prefix);
    }

    private CapturedData toImageData(CapturedData data) {
        if (data.format() != BIOB_FIR) {
            return data;
        }

        try {
            byte[] bmpBytes = firToBmp(data.bytes());
            return new CapturedData(
                    data.deviceId(),
                    BIOB_BMP,
                    data.finalImage(),
                    data.dataStatus(),
                    data.detectedObjects(),
                    bmpBytes,
                    data.savedPath(),
                    data.capturedAt()
            );
        } catch (Exception e) {
            log.warn("Could not convert FIR to BMP, saving raw FIR data instead: {}", e.getMessage());
            return data;
        }
    }

    private CapturedData save(CapturedData data, String prefix) {
        try {
            Files.createDirectories(properties.getOutputDir());
            Path path = properties.getOutputDir().resolve(prefix + "-" + timestamp() + "." + data.format().extension()).toAbsolutePath();
            Files.write(path, data.bytes());
            log.info("Saved {} data to {}", prefix, path);
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

    private Path saveAnnotatedCapture(CapturedData data, FingerSegmentation segmentation) {
        if (data.savedPath() == null || segmentation.segments().isEmpty()) {
            return null;
        }

        try {
            BufferedImage source = ImageIO.read(data.savedPath().toFile());
            if (source == null) {
                log.warn("Could not create annotated capture image: unsupported image format at {}", data.savedPath());
                return null;
            }

            BufferedImage annotated = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = annotated.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, null);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(Color.RED);
                graphics.setStroke(new BasicStroke(Math.max(2f, Math.min(source.getWidth(), source.getHeight()) / 250f)));

                double scaleX = segmentation.imageWidth() > 0 ? (double) source.getWidth() / segmentation.imageWidth() : 1.0;
                double scaleY = segmentation.imageHeight() > 0 ? (double) source.getHeight() / segmentation.imageHeight() : 1.0;

                for (FingerSegment segment : segmentation.segments()) {
                    int x = clamp((int) Math.round(segment.x() * scaleX), 0, source.getWidth() - 1);
                    int y = clamp((int) Math.round(segment.y() * scaleY), 0, source.getHeight() - 1);
                    int width = clamp((int) Math.round(segment.width() * scaleX), 1, source.getWidth() - x);
                    int height = clamp((int) Math.round(segment.height() * scaleY), 1, source.getHeight() - y);
                    graphics.drawRect(x, y, width, height);
                }
            } finally {
                graphics.dispose();
            }

            Path path = annotatedPath(data.savedPath());
            ImageIO.write(annotated, "png", path.toFile());
            log.info("Saved annotated capture image to {}", path);
            return path;
        } catch (Exception e) {
            log.warn("Could not create annotated capture image: {}", e.getMessage());
            return null;
        }
    }

    private Path saveCroppedCapture(CapturedData data, String position) {
        if (!properties.isCaptureContentCropEnabled() || !isPalmPosition(position) || data.savedPath() == null) {
            return null;
        }

        try {
            BufferedImage source = ImageIO.read(data.savedPath().toFile());
            if (source == null) {
                log.warn("Could not crop capture image: unsupported image format at {}", data.savedPath());
                return null;
            }

            Rectangle bounds = contentBoundsByProjection(source);
            if (bounds == null) {
                log.warn("Could not crop capture image: no biometric content detected in {}", data.savedPath());
                return null;
            }
            if (bounds.width >= source.getWidth() * 0.98 && bounds.height >= source.getHeight() * 0.98) {
                log.info("Capture crop skipped because detected content already covers full image: {}x{}",
                        bounds.width, bounds.height);
                return null;
            }

            BufferedImage cropped = source.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height);
            Path path = croppedPath(data.savedPath());
            ImageIO.write(cropped, "png", path.toFile());
            log.info("Saved cropped capture image to {} with bbox x={}, y={}, width={}, height={}",
                    path, bounds.x, bounds.y, bounds.width, bounds.height);
            return path;
        } catch (Exception e) {
            log.warn("Could not crop capture image: {}", e.getMessage());
            return null;
        }
    }

    private Rectangle contentBoundsByProjection(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int threshold = otsuThreshold(image);
        int[] columnCounts = new int[width];
        int[] rowCounts = new int[height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (luminance(image.getRGB(x, y)) <= threshold) {
                    columnCounts[x]++;
                    rowCounts[y]++;
                }
            }
        }

        int minColumnDarkPixels = Math.max(4, height / 250);
        int minRowDarkPixels = Math.max(4, width / 250);
        int minX = firstActiveIndex(columnCounts, minColumnDarkPixels);
        int maxX = lastActiveIndex(columnCounts, minColumnDarkPixels);
        int minY = firstActiveIndex(rowCounts, minRowDarkPixels);
        int maxY = lastActiveIndex(rowCounts, minRowDarkPixels);
        if (minX < 0 || maxX < minX || minY < 0 || maxY < minY) {
            return null;
        }

        int padding = Math.max(0, properties.getCaptureContentCropPaddingPixels());
        minX = Math.max(0, minX - padding);
        minY = Math.max(0, minY - padding);
        maxX = Math.min(width - 1, maxX + padding);
        maxY = Math.min(height - 1, maxY + padding);
        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static int firstActiveIndex(int[] counts, int threshold) {
        for (int index = 0; index < counts.length; index++) {
            if (counts[index] >= threshold) {
                return index;
            }
        }
        return -1;
    }

    private static int lastActiveIndex(int[] counts, int threshold) {
        for (int index = counts.length - 1; index >= 0; index--) {
            if (counts[index] >= threshold) {
                return index;
            }
        }
        return -1;
    }

    private void enqueueCaptureProgressBeep(String deviceId) {
        if (!properties.isCaptureProgressBeepEnabled() || !isRollImpression(activeImpression.get())) {
            return;
        }
        if (!captureProgressBeepSent.compareAndSet(false, true)) {
            return;
        }
        String pattern = blankToDefault(properties.getCaptureProgressBeepPattern(), "2");
        String volume = blankToDefault(properties.getCaptureProgressBeepVolume(), "100");
        enqueueBeep(deviceId, pattern, volume, "capture progress");
    }

    private void sendCaptureSuccessBeep(String deviceId) {
        if (!properties.isCaptureSuccessBeepEnabled()) {
            return;
        }
        if (!captureSuccessBeepSent.compareAndSet(false, true)) {
            return;
        }
        String pattern = blankToDefault(properties.getCaptureSuccessBeepPattern(), "3");
        String volume = blankToDefault(properties.getCaptureSuccessBeepVolume(), "100");
        sendBeepWithRetry(deviceId, pattern, volume, "capture success",
                properties.getCaptureSuccessBeepDelayMillis(),
                properties.getCaptureSuccessBeepRetries());
    }

    private void enqueueBeep(String deviceId, String pattern, String volume, String reason) {
        enqueueBeep(deviceId, pattern, volume, reason, 0);
    }

    private void enqueueBeep(String deviceId, String pattern, String volume, String reason, long delayMillis) {
        deviceOutputExecutor.execute(() -> {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            sendBeep(deviceId, pattern, volume, reason);
        });
    }

    private void sendBeep(String deviceId, String pattern, String volume, String reason) {
        Optional<String> beeperType = getOptionalProperty(deviceId, PROP_DEVICE_BEEPER_TYPE);
        if (beeperType.map(type -> PROP_BEEPER_NONE.equalsIgnoreCase(type.trim())).orElse(false)) {
            log.warn("Skipping {} beep because device reports {}", reason, PROP_BEEPER_NONE);
            return;
        }

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<BioBase Version=\"4.0\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xsi:noNamespaceSchemaLocation=\"BioBase.xsd\">"
                + "<OutputData>"
                + "<Beeper Pattern=\"" + escapeXmlAttribute(pattern) + "\" Volume=\"" + escapeXmlAttribute(volume) + "\"/>"
                + "</OutputData>"
                + "</BioBase>";

        try {
            client.setOutputXml(deviceId, xml);
            log.info("{} beep sent: pattern={}, volume={}, beeperType={}",
                    reason, pattern, volume, beeperType.orElse("unknown"));
        } catch (BioBaseException e) {
            log.warn("Could not send {} beep: {}", reason, e.getMessage());
        }
    }

    private void sendBeepWithRetry(String deviceId, String pattern, String volume, String reason, long delayMillis, int retries) {
        int attempts = Math.max(1, retries + 1);
        long currentDelayMillis = Math.max(0, delayMillis);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (currentDelayMillis > 0) {
                sleepBeforeBeep(currentDelayMillis, reason, attempt);
            }
            log.info("Sending {} beep attempt {}/{}", reason, attempt, attempts);
            sendBeep(deviceId, pattern, volume, reason);
            currentDelayMillis = Math.max(currentDelayMillis * 2, 250);
        }
    }

    private void enqueueCaptureStartLed(String deviceId, String position) {
        if (!properties.isLedEnabled() || isLScan1000Device(deviceId)) {
            return;
        }
        if (properties.isCaptureClearLedsOnStart()) {
            enqueueStatusLed(deviceId, LED_NONE, "capture start clear", 0, 0);
        }
        enqueueStatusLeds(deviceId, captureStartLeds(position), "capture start", 0, 0);
    }

    private void enqueueCaptureSuccessLed(String deviceId) {
        if (properties.isLedEnabled() && !isLScan1000Device(deviceId)) {
            enqueueStatusLeds(deviceId, parseLedSpec(properties.getCaptureSuccessLed()), "capture success", 0,
                    properties.getCaptureResultLedDurationMillis());
        }
    }

    private void enqueueCaptureFailureLed(String deviceId) {
        if (properties.isLedEnabled() && !isLScan1000Device(deviceId)) {
            enqueueStatusLeds(deviceId, parseLedSpec(properties.getCaptureFailureLed()), "capture failure", 0,
                    properties.getCaptureResultLedDurationMillis());
        }
    }

    private void enqueueLiveQualityLeds(String deviceId, List<Integer> qualityStates) {
        if (!properties.isLedEnabled() || !properties.isLiveQualityLedEnabled() || isLScan1000Device(deviceId)) {
            return;
        }
        if (pendingCapture.get() == null) {
            return;
        }

        List<String> leds = liveQualityLeds(qualityStates, activePosition.get());
        if (leds.isEmpty()) {
            return;
        }
        List<String> previous = lastQualityLedState.getAndSet(leds);
        if (previous.equals(leds)) {
            return;
        }
        enqueueStatusLeds(deviceId, leds, "live quality", 0, 0);
    }

    private void enqueueTftCaptureProgress(String deviceId, String position, String impression) {
        if (!isLScan1000Device(deviceId)) {
            return;
        }
        deviceOutputExecutor.execute(() -> {
            try {
                sendTftCaptureProgress(deviceId, position, impression, TFT_ERASE, true);
            } catch (BioBaseException e) {
                log.warn("Could not initialize LScan1000 display: {}", e.getMessage());
            }
        });
    }

    private void enqueueLiveTftStatus(String deviceId, List<Integer> qualityStates) {
        if (!isLScan1000Device(deviceId) || pendingCapture.get() == null) {
            return;
        }
        String status = tftStatusFromQualityStates(qualityStates);
        String previous = lastTftStatus.getAndSet(status);
        if (Objects.equals(previous, status)) {
            return;
        }
        deviceOutputExecutor.execute(() -> {
            try {
                sendTftCaptureProgress(deviceId, activePosition.get(), activeImpression.get(), status, false);
            } catch (BioBaseException e) {
                log.warn("Could not update LScan1000 display status: {}", e.getMessage());
            }
        });
    }

    private void enqueueFinalTftStatus(String deviceId, int dataStatus) {
        if (!isLScan1000Device(deviceId)) {
            return;
        }
        String status = tftFinalStatus(dataStatus, activeImpression.get());
        lastTftStatus.set(status);
        deviceOutputExecutor.execute(() -> {
            try {
                sendTftCaptureProgress(deviceId, activePosition.get(), activeImpression.get(), status, false);
            } catch (BioBaseException e) {
                log.warn("Could not update LScan1000 final display status: {}", e.getMessage());
            }
        });
    }

    private void enqueueStatusLed(String deviceId, String led, String reason, long delayMillis, long durationMillis) {
        enqueueStatusLeds(deviceId, parseLedSpec(led), reason, delayMillis, durationMillis);
    }

    private void enqueueStatusLeds(String deviceId, List<String> leds, String reason, long delayMillis, long durationMillis) {
        if (leds.isEmpty()) {
            return;
        }
        deviceOutputExecutor.execute(() -> {
            if (delayMillis > 0) {
                sleepBeforeDeviceOutput(delayMillis, reason);
            }
            sendStatusLeds(deviceId, leds, reason);
            if (durationMillis > 0) {
                sleepBeforeDeviceOutput(durationMillis, reason + " clear");
                sendStatusLed(deviceId, LED_NONE, reason + " clear");
            }
        });
    }

    private void sendStatusLed(String deviceId, String led, String reason) {
        sendStatusLeds(deviceId, parseLedSpec(led), reason);
    }

    private void sendStatusLeds(String deviceId, List<String> leds, String reason) {
        List<String> normalizedLeds = leds.isEmpty() ? List.of(LED_NONE) : leds;
        Optional<String> ledType = getOptionalProperty(deviceId, PROP_DEVICE_LED_TYPE);
        if (ledType.map(type -> PROP_LED_TYPE_NONE.equalsIgnoreCase(type.trim())).orElse(false)) {
            log.warn("Skipping {} LED because device reports {}", reason, PROP_LED_TYPE_NONE);
            return;
        }

        StringBuilder ledXml = new StringBuilder();
        if (!normalizedLeds.contains(LED_NONE)) {
            ledXml.append("<Led>").append(escapeXmlText(LED_NONE)).append("</Led>");
        }
        for (String led : normalizedLeds) {
            ledXml.append("<Led>").append(escapeXmlText(led)).append("</Led>");
        }

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<BioBase Version=\"4.0\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xsi:noNamespaceSchemaLocation=\"BioBase.xsd\">"
                + "<OutputData>"
                + "<StatusLeds>"
                + ledXml
                + "</StatusLeds>"
                + "</OutputData>"
                + "</BioBase>";

        try {
            client.setOutputXml(deviceId, xml);
            log.info("{} LED sent: leds={}, ledType={}", reason, normalizedLeds, ledType.orElse("unknown"));
        } catch (BioBaseException e) {
            log.warn("Could not send {} LEDs {}: {}", reason, normalizedLeds, e.getMessage());
        }
    }

    private void sendTftCaptureProgress(String deviceId, String position, String impression, String bottomStatus, boolean initializeSegments) {
        Map<String, String> values = initializeSegments
                ? tftCaptureSegments(position)
                : tftLeaveSegmentsUnchanged();
        values.put("LeftButton", initializeSegments ? TFT_ERASE : TFT_LEAVE_UNCHANGED);
        values.put("RightButton", initializeSegments ? TFT_ERASE : TFT_LEAVE_UNCHANGED);
        values.put("StatTop", initializeSegments ? tftTopStatus(impression, position) : TFT_LEAVE_UNCHANGED);
        values.put("StatBottom", blankToDefault(bottomStatus, TFT_ERASE));

        StringBuilder screen = new StringBuilder();
        screen.append("<Tft><").append(TFT_CAP_SCREEN).append(">");
        values.forEach((key, value) -> screen.append(element(key, value)));
        screen.append("</").append(TFT_CAP_SCREEN).append("></Tft>");

        client.setOutputXml(deviceId, outputXml(screen.toString()));
        log.info("LScan1000 display capture progress sent: deviceId={}, position={}, impression={}, status={}, initialize={}",
                deviceId, position, impression, bottomStatus, initializeSegments);
    }

    private Map<String, String> tftCaptureSegments(String position) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String segment : TFT_SEGMENTS) {
            values.put(segment, TFT_INACTIVE);
        }

        String normalized = normalizeModeText(position);
        if (normalized.contains("boththumb")) {
            values.put("ColorRightThumb", TFT_AUTOCAPTURE_OK);
            values.put("ColorLeftThumb", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("rightfour")) {
            values.put("ColorRightIndex", TFT_AUTOCAPTURE_OK);
            values.put("ColorRightMiddle", TFT_AUTOCAPTURE_OK);
            values.put("ColorRightRing", TFT_AUTOCAPTURE_OK);
            values.put("ColorRightSmall", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("leftfour")) {
            values.put("ColorLeftIndex", TFT_AUTOCAPTURE_OK);
            values.put("ColorLeftMiddle", TFT_AUTOCAPTURE_OK);
            values.put("ColorLeftRing", TFT_AUTOCAPTURE_OK);
            values.put("ColorLeftSmall", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("rightlowerpalm")) {
            values.put("ColorRightPalm", TFT_AUTOCAPTURE_OK);
            values.put("ColorRightLowerThenar", TFT_MISSING);
        } else if (normalized.contains("leftlowerpalm")) {
            values.put("ColorLeftPalm", TFT_AUTOCAPTURE_OK);
            values.put("ColorLeftLowerThenar", TFT_MISSING);
        } else if (normalized.contains("rightupperpalm")) {
            values.put("ColorRightIndex", TFT_AUTOCAPTURE_OK);
            values.put("ColorRightMiddle", TFT_AUTOCAPTURE_OK);
            values.put("ColorRightRing", TFT_AUTOCAPTURE_OK);
            values.put("ColorRightSmall", TFT_AUTOCAPTURE_OK);
            values.put("ColorRightInterDigital", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("leftupperpalm")) {
            values.put("ColorLeftIndex", TFT_AUTOCAPTURE_OK);
            values.put("ColorLeftMiddle", TFT_AUTOCAPTURE_OK);
            values.put("ColorLeftRing", TFT_AUTOCAPTURE_OK);
            values.put("ColorLeftSmall", TFT_AUTOCAPTURE_OK);
            values.put("ColorLeftInterDigital", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("rightthumb")) {
            values.put("ColorRightThumb", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("leftthumb")) {
            values.put("ColorLeftThumb", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("rightindex")) {
            values.put("ColorRightIndex", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("rightmiddle")) {
            values.put("ColorRightMiddle", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("rightring")) {
            values.put("ColorRightRing", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("rightlittle") || normalized.contains("rightsmall")) {
            values.put("ColorRightSmall", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("leftindex")) {
            values.put("ColorLeftIndex", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("leftmiddle")) {
            values.put("ColorLeftMiddle", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("leftring")) {
            values.put("ColorLeftRing", TFT_AUTOCAPTURE_OK);
        } else if (normalized.contains("leftlittle") || normalized.contains("leftsmall")) {
            values.put("ColorLeftSmall", TFT_AUTOCAPTURE_OK);
        }
        return values;
    }

    private Map<String, String> tftLeaveSegmentsUnchanged() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String segment : TFT_SEGMENTS) {
            values.put(segment, TFT_LEAVE_UNCHANGED);
        }
        return values;
    }

    private static String tftTopStatus(String impression, String position) {
        if (!isRollImpression(impression)) {
            return "CAPTURE_FLAT";
        }
        String normalized = normalizeModeText(position);
        if (normalized.contains("left")) {
            return "ROLL_HORIZONTAL_LEFT";
        }
        if (normalized.contains("right")) {
            return "ROLL_HORIZONTAL_RIGHT";
        }
        return "ROLL_HORIZONTAL";
    }

    private static String tftStatusFromQualityStates(List<Integer> qualityStates) {
        if (qualityStates == null || qualityStates.isEmpty()) {
            return TFT_ERASE;
        }
        boolean tooHigh = false;
        boolean tooLow = false;
        boolean tooLeft = false;
        boolean tooRight = false;
        boolean notOk = false;
        for (Integer value : qualityStates) {
            BioBaseObjectQualityState state = BioBaseObjectQualityState.fromValue(value);
            switch (state) {
                case BIOB_OBJECT_GOOD -> {
                }
                case BIOB_OBJECT_POSITION_TOO_HIGH, BIOB_OBJECT_FLEX_POSITION_TOO_HIGH -> tooHigh = true;
                case BIOB_OBJECT_POSITION_TOO_LOW, BIOB_OBJECT_FLEX_POSITION_TOO_LOW -> tooLow = true;
                case BIOB_OBJECT_POSITION_TOO_LEFT, BIOB_OBJECT_FLEX_POSITION_TOO_LEFT -> tooLeft = true;
                case BIOB_OBJECT_POSITION_TOO_RIGHT, BIOB_OBJECT_FLEX_POSITION_TOO_RIGHT -> tooRight = true;
                case BIOB_OBJECT_NOT_PRESENT, UNKNOWN -> {
                }
                default -> notOk = true;
            }
        }
        if (notOk) {
            return "COMMON_ERROR";
        }
        if ((tooHigh && tooLeft && tooRight) || (tooLow && tooLeft && tooRight)) {
            return "POSITION_DOWN_LEFT_RIGHT_UP";
        }
        if (tooHigh && tooLeft) {
            return "POSITION_DOWN_RIGHT";
        }
        if (tooHigh && tooRight) {
            return "POSITION_DOWN_LEFT";
        }
        if (tooLow && tooLeft) {
            return "POSITION_UP_RIGHT";
        }
        if (tooLow && tooRight) {
            return "POSITION_UP_LEFT";
        }
        if (tooRight && tooLeft) {
            return "POSITION_LEFT_RIGHT";
        }
        if (tooRight) {
            return "POSITION_LEFT";
        }
        if (tooLeft) {
            return "POSITION_RIGHT";
        }
        if (tooHigh) {
            return "POSITION_DOWN";
        }
        if (tooLow) {
            return "POSITION_UP";
        }
        return TFT_ERASE;
    }

    private static String tftFinalStatus(int dataStatus, String impression) {
        if (dataStatus == 0) {
            return "OK";
        }
        if (isRollImpression(impression)) {
            return "ROLL_ERROR";
        }
        return switch (dataStatus) {
            case -1, -2, -3 -> "CAPTURE_ERROR";
            default -> "COMMON_ERROR";
        };
    }

    private void requireLScan1000(String deviceId) {
        if (!isLScan1000Device(deviceId)) {
            throw new BioBaseException("Device is not an LScan1000-family device: " + deviceId);
        }
    }

    private boolean isLScan1000Device(String deviceId) {
        Optional<String> ledType = getOptionalProperty(deviceId, PROP_DEVICE_LED_TYPE);
        if (ledType.map(FingerprintCaptureService::normalizeModeText)
                .map(type -> type.contains("ledtypelscandisplayemulation"))
                .orElse(false)) {
            return true;
        }
        return devices().stream()
                .filter(device -> Objects.equals(device.deviceId(), deviceId))
                .map(DeviceInfo::modelName)
                .map(FingerprintCaptureService::normalizeModeText)
                .anyMatch(model -> model.contains("lscan1000") || model.contains("lscan1000p") || model.contains("lscan1000px") || model.contains("lscan1000t"));
    }

    private List<String> captureStartLeds(String position) {
        String configured = properties.getCaptureStartLed();
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        if (!"AUTO".equalsIgnoreCase(configured.trim())) {
            return parseLedSpec(configured);
        }
        return modeIconLeds(position);
    }

    private static List<String> parseLedSpec(String ledSpec) {
        if (ledSpec == null || ledSpec.isBlank()) {
            return List.of();
        }
        String[] parts = ledSpec.split("[,;\\s]+");
        ArrayList<String> leds = new ArrayList<>();
        for (String part : parts) {
            String led = part.trim();
            if (!led.isEmpty()) {
                leds.add(led);
            }
        }
        return List.copyOf(leds);
    }

    private static List<String> liveQualityLeds(List<Integer> qualityStates, String position) {
        if (qualityStates == null || qualityStates.isEmpty()) {
            return modeIconLeds(position);
        }

        ArrayList<String> leds = new ArrayList<>();
        int count = Math.min(qualityStates.size(), 4);
        for (int index = 0; index < count; index++) {
            leds.addAll(statusQualityLeds(index + 1, BioBaseObjectQualityState.fromValue(qualityStates.get(index))));
        }
        leds.addAll(modeIconLeds(position));
        return List.copyOf(leds);
    }

    private static List<String> statusQualityLeds(int statusIndex, BioBaseObjectQualityState qualityState) {
        if (qualityState == BioBaseObjectQualityState.BIOB_OBJECT_NOT_PRESENT || qualityState == BioBaseObjectQualityState.UNKNOWN) {
            return List.of();
        }

        String prefix = "S" + statusIndex + "_";
        return switch (qualityState) {
            case BIOB_OBJECT_GOOD -> List.of(prefix + "GREEN_B1", prefix + "GREEN_B2");
            case BIOB_OBJECT_TRACKING_NOT_OK -> List.of(
                    prefix + "RED_B1",
                    prefix + "RED_B2",
                    prefix + "GREEN_B1",
                    prefix + "GREEN_B2"
            );
            case BIOB_OBJECT_TOO_DARK,
                 BIOB_OBJECT_TOO_LIGHT,
                 BIOB_OBJECT_BAD_SHAPE,
                 BIOB_OBJECT_POSITION_NOT_OK,
                 BIOB_OBJECT_POSITION_TOO_HIGH,
                 BIOB_OBJECT_POSITION_TOO_LEFT,
                 BIOB_OBJECT_POSITION_TOO_RIGHT,
                 BIOB_OBJECT_POSITION_TOO_LOW,
                 BIOB_OBJECT_FLEX_POSITION_TOO_HIGH,
                 BIOB_OBJECT_FLEX_POSITION_TOO_LEFT,
                 BIOB_OBJECT_FLEX_POSITION_TOO_RIGHT,
                 BIOB_OBJECT_FLEX_POSITION_TOO_LOW,
                 BIOB_OBJECT_CORE_NOT_PRESENT,
                 BIOB_OBJECT_TOO_CLOSE,
                 BIOB_OBJECT_TOO_FAR,
                 BIOB_OBJECT_NOT_FOCUSED,
                 BIOB_OBJECT_NOT_STILL,
                 BIOB_OBJECT_NOT_ALIGNED,
                 BIOB_OBJECT_OCCLUSION,
                 BIOB_OBJECT_CONFUSION,
                 BIOB_OBJECT_ROTATED_CLOCKWISE,
                 BIOB_OBJECT_ROTATED_COUNTERCLOCKWISE -> List.of(prefix + "RED_B1", prefix + "RED_B2");
            default -> List.of();
        };
    }

    private static List<String> modeIconLeds(String position) {
        if (position == null || position.isBlank()) {
            return List.of();
        }
        String normalized = position.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("boththumb")) {
            return List.of("I2_GREEN_B1", "I2_GREEN_B2", "I4_GREEN_B1", "I4_GREEN_B2");
        }
        if (normalized.contains("rightthumb")) {
            return List.of("I4_GREEN_B1", "I4_GREEN_B2");
        }
        if (normalized.contains("leftthumb")) {
            return List.of("I2_GREEN_B1", "I2_GREEN_B2");
        }
        if (normalized.contains("right") && !normalized.contains("palm")) {
            return List.of("I3_GREEN_B1", "I3_GREEN_B2");
        }
        if (normalized.contains("left") && !normalized.contains("palm")) {
            return List.of("I1_GREEN_B1", "I1_GREEN_B2");
        }
        return List.of();
    }

    private static void sleepBeforeDeviceOutput(long delayMillis, String reason) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BioBaseException("Interrupted before " + reason);
        }
    }

    private static void sleepBeforeBeep(long delayMillis, String reason, int attempt) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BioBaseException("Interrupted before " + reason + " beep attempt " + attempt);
        }
    }

    private static boolean isRollImpression(String impression) {
        return impression != null && impression.toLowerCase(java.util.Locale.ROOT).contains("roll");
    }

    private static boolean isPalmPosition(String position) {
        return position != null && position.toLowerCase(java.util.Locale.ROOT).contains("palm");
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

    private static String escapeXmlAttribute(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String outputXml(String outputBody) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<BioBase Version=\"4.0\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xsi:noNamespaceSchemaLocation=\"BioBase.xsd\">"
                + "<OutputData>"
                + outputBody
                + "</OutputData>"
                + "</BioBase>";
    }

    private static String element(String name, String text) {
        return "<" + name + ">" + escapeXmlText(text) + "</" + name + ">";
    }

    private static String escapeXmlText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String normalizeModeText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String pointerString(Pointer pointer) {
        return pointer == null || Pointer.nativeValue(pointer) == 0 ? "" : pointer.getString(0);
    }

    private static String pointerAddress(Pointer pointer) {
        return pointer == null || Pointer.nativeValue(pointer) == 0
                ? "0x0"
                : "0x" + Long.toHexString(Pointer.nativeValue(pointer));
    }

    private static long parseWindowHandle(String value) {
        if (value == null || value.isBlank()) {
            throw new BioBaseException("Visualization window handle is required.");
        }
        String trimmed = value.trim();
        try {
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return Long.parseUnsignedLong(trimmed.substring(2), 16);
            }
            return Long.parseUnsignedLong(trimmed);
        } catch (NumberFormatException e) {
            throw new BioBaseException("Invalid visualization window handle: " + value);
        }
    }

    private static CaptureResponse toResponse(CapturedData data) {
        return toResponse(data, FingerSegmentation.empty(), null, null, null, List.of());
    }

    private CaptureResponse toResponse(CapturedData data, FingerSegmentation segmentation, Path annotatedPath) {
        return toResponse(data, segmentation, annotatedPath, null);
    }

    private CaptureResponse toResponse(CapturedData data, FingerSegmentation segmentation, Path annotatedPath, Path croppedPath) {
        return toResponse(data, segmentation, annotatedPath, croppedPath, lastObjectCountState.get(), lastObjectQualityStates.get());
    }

    private static CaptureResponse toResponse(
            CapturedData data,
            FingerSegmentation segmentation,
            Path annotatedPath,
            Path croppedPath,
            Integer objectCountState,
            List<Integer> objectQualityStates
    ) {
        return new CaptureResponse(
                data.deviceId(),
                data.format().name(),
                data.finalImage(),
                data.dataStatus(),
                data.detectedObjects(),
                data.savedPath() == null ? null : data.savedPath().toString(),
                annotatedPath == null ? null : annotatedPath.toString(),
                croppedPath == null ? null : croppedPath.toString(),
                segmentation.imageWidth(),
                segmentation.imageHeight(),
                segmentation.segments().stream()
                        .map(segment -> new CaptureResponse.SegmentResponse(
                                segment.index(),
                                segment.x(),
                                segment.y(),
                                segment.width(),
                                segment.height()
                        ))
                        .toList(),
                objectCountResponse(objectCountState),
                objectQualityResponses(objectQualityStates),
                guidanceMessages(objectQualityStates, objectCountState),
                data.bytes().length,
                data.capturedAt()
        );
    }

    private void clearLiveObjectState() {
        lastObjectCountState.set(null);
        lastObjectQualityStates.set(List.of());
    }

    private static CaptureResponse.ObjectCountResponse objectCountResponse(Integer value) {
        if (value == null) {
            return null;
        }
        return new CaptureResponse.ObjectCountResponse(value, BioBaseObjectCountState.fromValue(value).name());
    }

    private static List<CaptureResponse.ObjectQualityResponse> objectQualityResponses(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<CaptureResponse.ObjectQualityResponse> responses = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            int value = values.get(index);
            responses.add(new CaptureResponse.ObjectQualityResponse(
                    index,
                    value,
                    BioBaseObjectQualityState.fromValue(value).name(),
                    guidanceMessage(BioBaseObjectQualityState.fromValue(value))
            ));
        }
        return responses;
    }

    private static List<String> guidanceMessages(List<Integer> qualityValues, Integer countValue) {
        ArrayList<String> messages = new ArrayList<>();
        if (countValue != null) {
            BioBaseObjectCountState countState = BioBaseObjectCountState.fromValue(countValue);
            String countMessage = countGuidanceMessage(countState);
            if (countMessage != null) {
                messages.add(countMessage);
            }
        }
        if (qualityValues != null) {
            for (Integer value : qualityValues) {
                if (value == null) {
                    continue;
                }
                String message = guidanceMessage(BioBaseObjectQualityState.fromValue(value));
                if (message != null && !messages.contains(message)) {
                    messages.add(message);
                }
            }
        }
        return List.copyOf(messages);
    }

    private static String countGuidanceMessage(BioBaseObjectCountState state) {
        return switch (state) {
            case BIOB_TOO_MANY_OBJECTS -> "Tarayici alaninda beklenenden fazla iz var.";
            case BIOB_TOO_FEW_OBJECTS -> "Tarayici alaninda beklenenden az iz var.";
            default -> null;
        };
    }

    private static String guidanceMessage(BioBaseObjectQualityState state) {
        return switch (state) {
            case BIOB_OBJECT_TOO_LIGHT -> "Iz kontrasti dusuk.";
            case BIOB_OBJECT_TOO_DARK -> "Iz cok koyu.";
            case BIOB_OBJECT_BAD_SHAPE -> "Iz sekli uygun degil.";
            case BIOB_OBJECT_POSITION_NOT_OK -> "Iz tarayici takip alaninda degil.";
            case BIOB_OBJECT_CORE_NOT_PRESENT -> "Iz merkezi algilanamadi.";
            case BIOB_OBJECT_TRACKING_NOT_OK -> "Iz takibi uygun degil.";
            case BIOB_OBJECT_POSITION_TOO_HIGH, BIOB_OBJECT_FLEX_POSITION_TOO_HIGH ->
                    "Iz ust taraftan tarayici alaninin disinda, asagi kaydirin.";
            case BIOB_OBJECT_POSITION_TOO_LEFT, BIOB_OBJECT_FLEX_POSITION_TOO_LEFT ->
                    "Iz sol taraftan tarayici alaninin disinda, saga kaydirin.";
            case BIOB_OBJECT_POSITION_TOO_RIGHT, BIOB_OBJECT_FLEX_POSITION_TOO_RIGHT ->
                    "Iz sag taraftan tarayici alaninin disinda, sola kaydirin.";
            case BIOB_OBJECT_POSITION_TOO_LOW, BIOB_OBJECT_FLEX_POSITION_TOO_LOW ->
                    "Iz alt taraftan tarayici alaninin disinda, yukari kaydirin.";
            case BIOB_OBJECT_TOO_CLOSE -> "Iz tarayiciya cok yakin.";
            case BIOB_OBJECT_TOO_FAR -> "Iz tarayicidan cok uzak.";
            case BIOB_OBJECT_NOT_FOCUSED -> "Iz odakta degil.";
            case BIOB_OBJECT_NOT_STILL -> "Iz sabit degil.";
            case BIOB_OBJECT_NOT_ALIGNED -> "Iz hizali degil.";
            case BIOB_OBJECT_OCCLUSION -> "Izde kapanma/engel algilandi.";
            case BIOB_OBJECT_CONFUSION -> "Iz algilama kararsiz.";
            case BIOB_OBJECT_ROTATED_CLOCKWISE -> "Iz saat yonunde fazla donuk.";
            case BIOB_OBJECT_ROTATED_COUNTERCLOCKWISE -> "Iz saat yonunun tersine fazla donuk.";
            default -> null;
        };
    }

    private static String toCountLog(int value) {
        return BioBaseObjectCountState.fromValue(value).name() + "(" + value + ")";
    }

    private static List<String> toQualityLog(List<Integer> values) {
        return values.stream()
                .map(value -> BioBaseObjectQualityState.fromValue(value).name() + "(" + value + ")")
                .toList();
    }

    private static Path annotatedPath(Path capturePath) {
        String fileName = capturePath.getFileName().toString();
        int extensionStart = fileName.lastIndexOf('.');
        String baseName = extensionStart < 0 ? fileName : fileName.substring(0, extensionStart);
        return capturePath.resolveSibling(baseName + "-annotated.png").toAbsolutePath();
    }

    private static Path croppedPath(Path capturePath) {
        String fileName = capturePath.getFileName().toString();
        int extensionStart = fileName.lastIndexOf('.');
        String baseName = extensionStart < 0 ? fileName : fileName.substring(0, extensionStart);
        return capturePath.resolveSibling(baseName + "-cropped.png").toAbsolutePath();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String timestamp() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-');
    }

    private record FirViewImage(
            int index,
            int position,
            int impression,
            int quality,
            byte[] bmpBytes,
            BufferedImage image
    ) {
    }

    private record MatchScore(
            int x,
            int y,
            int totalDifference
    ) {
        int averageDifference() {
            return totalDifference;
        }
    }

    private Path saveTrimmedRollCapture(CapturedData data, FingerSegmentation segmentation) {
        if (data.savedPath() == null) {
            return null;
        }

        try {
            BufferedImage source = ImageIO.read(data.savedPath().toFile());
            if (source == null) {
                log.warn("Could not trim roll capture: unsupported image format at {}", data.savedPath());
                return null;
            }

            Rectangle bounds = rollContentBounds(source, segmentation);
            if (bounds == null) {
                log.warn("Could not trim roll capture: no fingerprint content detected in {}", data.savedPath());
                return null;
            }

            if (bounds.width >= source.getWidth() * 0.98 && bounds.height >= source.getHeight() * 0.98) {
                log.info("Roll trim skipped: content already covers full image ({}x{})",
                        bounds.width, bounds.height);
                return null;
            }

            BufferedImage trimmed = source.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height);
            Path path = trimmedPath(data.savedPath());
            ImageIO.write(trimmed, "png", path.toFile());
            log.info("Saved trimmed roll capture to {} with bbox x={}, y={}, width={}, height={}",
                    path, bounds.x, bounds.y, bounds.width, bounds.height);
            return path;
        } catch (Exception e) {
            log.warn("Could not trim roll capture: {}", e.getMessage());
            return null;
        }
    }

    private boolean[][] buildFingerMask(BufferedImage image, int threshold) {
        int width = image.getWidth();
        int height = image.getHeight();
        boolean bgDark = backgroundIsDark(image, threshold);
        boolean[][] mask = new boolean[width][height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean dark = luminance(image.getRGB(x, y)) <= threshold;
                mask[x][y] = bgDark ? !dark : dark;
            }
        }
        return mask;
    }

    private static boolean backgroundIsDark(BufferedImage image, int threshold) {
        int width = image.getWidth();
        int height = image.getHeight();
        long dark = 0, total = 0;
        for (int x = 0; x < width; x++) {
            total += 2;
            if (luminance(image.getRGB(x, 0)) <= threshold) dark++;
            if (luminance(image.getRGB(x, height - 1)) <= threshold) dark++;
        }
        for (int y = 0; y < height; y++) {
            total += 2;
            if (luminance(image.getRGB(0, y)) <= threshold) dark++;
            if (luminance(image.getRGB(width - 1, y)) <= threshold) dark++;
        }
        return dark * 2 > total;
    }

    private Rectangle rollContentBounds(BufferedImage image, FingerSegmentation segmentation) {
        int width = image.getWidth();
        int height = image.getHeight();
        int threshold = otsuThreshold(image);
        boolean[][] mask = buildFingerMask(image, threshold);

        int[] columnCounts = new int[width];
        int[] rowCounts = new int[height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[x][y]) {
                    columnCounts[x]++;
                    rowCounts[y]++;
                }
            }
        }
        int minColumnActive = Math.max(6, height / 150);
        int minRowActive = Math.max(6, width / 150);
        int minX = firstActiveIndex(columnCounts, minColumnActive);
        int maxX = lastActiveIndex(columnCounts, minColumnActive);
        int minY = firstActiveIndex(rowCounts, minRowActive);
        int maxY = lastActiveIndex(rowCounts, minRowActive);
        if (minX < 0 || maxX < minX || minY < 0 || maxY < minY) {
            return null;
        }
        // Alt kenara yakın satırlarda içerik varsa kırpma sınırını görüntü sonuna çek
        int bottomScanStart = Math.max(0, height - Math.max(10, height / 50));
        for (int y = bottomScanStart; y < height; y++) {
            if (rowCounts[y] >= minRowActive / 2) { // daha toleranslı eşik
                maxY = height - 1;
                break;
            }
        }
        return applyRollTrimPadding(new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1), width, height);
    }

    private Rectangle applyRollTrimPadding(Rectangle bounds, int imageWidth, int imageHeight) {
        int padding = 40;

        int minX = Math.max(0, bounds.x - padding);
        int minY = Math.max(0, bounds.y - padding);
        int maxX = Math.min(imageWidth - 1, bounds.x + bounds.width - 1 + padding);

        int maxY = Math.min(imageHeight - 1, bounds.y + bounds.height - 1 + padding);

        int bottomEdgeTolerance = Math.max(10, imageHeight / 20);

        if (bounds.y + bounds.height >= imageHeight - bottomEdgeTolerance) {
            maxY = imageHeight - 1;
        }
        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }


    private static Path trimmedPath(Path capturePath) {
        String fileName = capturePath.getFileName().toString();
        int extensionStart = fileName.lastIndexOf('.');
        String baseName = extensionStart < 0 ? fileName : fileName.substring(0, extensionStart);
        return capturePath.resolveSibling(baseName + "-trimmed.png").toAbsolutePath();
    }
}
