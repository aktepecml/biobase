package com.example.fingerprint.biobase;

import com.example.fingerprint.api.CaptureResponse;
import com.example.fingerprint.api.DeviceStatusResponse;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final String PROP_ENCODING_FORMATS_SUPPORTED = "ENCODING_FORMATS_SUPPORTED";
    private static final String PROP_DEVICE_BEEPER_TYPE = "DEVICE_BEEPER_TYPE";
    private static final String PROP_BEEPER_NONE = "BEEPER_NONE";

    private final BioBaseClient client;
    private final FingerprintProperties properties;
    private final ExecutorService deviceOutputExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "biobase-device-output");
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
    private final AtomicBoolean previewSeenLogged = new AtomicBoolean(false);
    private final AtomicBoolean captureSuccessBeepSent = new AtomicBoolean(false);
    private final AtomicBoolean captureProgressBeepSent = new AtomicBoolean(false);
    private final AtomicLong lastPreviewCachedAtMillis = new AtomicLong(0);
    private final AtomicLong previewMetricWindowStartedAtMillis = new AtomicLong(0);
    private final AtomicLong previewMetricFrames = new AtomicLong(0);
    private final AtomicLong previewMetricCachedFrames = new AtomicLong(0);
    private final AtomicLong previewMetricBytes = new AtomicLong(0);
    private final AtomicLong previewMetricCopyNanos = new AtomicLong(0);
    private volatile String activeDeviceId;

    private final BioBaseNative.PreviewCallback previewCallback;
    private final BioBaseNative.AcquisitionStartedCallback startedCallback;
    private final BioBaseNative.AcquisitionCompletedCallback completedCallback;
    private final BioBaseNative.DataAvailableCallback dataAvailableCallback;
    private final BioBaseNative.ObjectQualityCallback objectQualityCallback;
    private final BioBaseNative.ObjectCountCallback objectCountCallback;

    public FingerprintCaptureService(BioBaseClient client, FingerprintProperties properties) {
        this.client = client;
        this.properties = properties;
        this.previewCallback = (deviceId, context, data) -> {
            if (data != null) {
                BioBaseNative.BioBData nativeData = client.readNativeData(data);
                BioBaseDataFormat format = BioBaseDataFormat.fromValue(nativeData.formatType);
                int bufferSize = Math.max(nativeData.bufferSize, 0);
                if (previewSeenLogged.compareAndSet(false, true)) {
                    log.info("First preview received: format={}, bytes={}", format, bufferSize);
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
                    log.info("Capture data received: format={}, bytes={}, detectedObjects={}",
                            capture.format(), capture.bytes().length, capture.detectedObjects());
                    lastCapture.set(capture);
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
    }

    public void closeDevice(String deviceId, boolean standby) {
        unregisterCallbacks(deviceId);
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
            client.beginAcquisition(
                    deviceId,
                    blankToDefault(position, properties.getDefaultPosition()),
                    effectiveImpression
            );
            long timeout = timeoutSeconds == null ? properties.getCaptureTimeoutSeconds() : timeoutSeconds;
            CapturedData captured = waitForCapture(future, timeout);
            waitUntilAcquisitionStopped(deviceId);
            enqueueCaptureSuccessBeep(deviceId);
            FingerSegmentation segmentation = lastPreviewSegmentation.get();
            CapturedData saved = saveAsImage(captured, "capture");
            if (segmentation.segments().isEmpty()) {
                segmentation = detectSegmentsFromFirViews(captured, saved);
            }
            if (segmentation.segments().isEmpty()) {
                segmentation = detectSegmentsFromCaptureImage(saved, captured.detectedObjects());
            }
            Path annotatedPath = saveAnnotatedCapture(saved, segmentation);
            lastCapture.set(saved);
            return toResponse(saved, segmentation, annotatedPath);
        } catch (TimeoutException e) {
            client.cancelAcquisition(deviceId);
            throw new BioBaseException("Capture timed out before final fingerprint data arrived.");
        } catch (BioBaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BioBaseException("Capture failed: " + e.getMessage());
        } finally {
            pendingCapture.compareAndSet(future, null);
            activeImpression.set(null);
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
                objectQualityResponses(lastObjectQualityStates.get())
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
            FingerSegment segment = boundsForRange(candidates.size() + 1, darkPixels, range[0], range[1], width, height);
            if (segment.width() >= Math.max(10, width / 120) && segment.height() >= Math.max(20, height / 20)) {
                candidates.add(segment);
            }
        }

        if (candidates.isEmpty()) {
            FingerSegment fullBounds = boundsForRange(1, darkPixels, 0, width - 1, width, height);
            if (fullBounds.width() > 1 && fullBounds.height() > 1) {
                candidates.add(fullBounds);
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
        logPreviewCapabilities(deviceId);
        setOptionalProperty(deviceId, PROP_PREVIEW_IMAGE_FORMAT, properties.getPreviewImageFormat());
        setOptionalProperty(deviceId, PROP_PREVIEW_LEVEL, properties.getPreviewLevel());
    }

    private void logBeeperCapability(String deviceId) {
        getOptionalProperty(deviceId, PROP_DEVICE_BEEPER_TYPE)
                .ifPresent(type -> log.info("BioBase device beeper type: {}", type));
    }

    private void logPreviewCapabilities(String deviceId) {
        if (!properties.isPreviewDiagnosticsEnabled()) {
            return;
        }
        logOptionalPreviewProperty(deviceId, PROP_AVAILABLE_PREVIEW_LEVELS);
        logOptionalPreviewProperty(deviceId, PROP_DEVICE_PREVIEW_FRAME_RATE);
        logOptionalPreviewProperty(deviceId, PROP_ENCODING_FORMATS_SUPPORTED);
        logOptionalPreviewProperty(deviceId, PROP_PREVIEW_IMAGE_FORMAT);
        logOptionalPreviewProperty(deviceId, PROP_PREVIEW_LEVEL);
    }

    private void logOptionalPreviewProperty(String deviceId, String propertyName) {
        try {
            log.info("BioBase preview property {}={}", propertyName, client.getProperty(deviceId, propertyName));
        } catch (BioBaseException e) {
            log.debug("BioBase preview property {} is not readable: {}", propertyName, e.getMessage());
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

    private void enqueueCaptureSuccessBeep(String deviceId) {
        if (!properties.isCaptureSuccessBeepEnabled()) {
            return;
        }
        if (!captureSuccessBeepSent.compareAndSet(false, true)) {
            return;
        }
        String pattern = blankToDefault(properties.getCaptureSuccessBeepPattern(), "3");
        String volume = blankToDefault(properties.getCaptureSuccessBeepVolume(), "100");
        enqueueBeep(deviceId, pattern, volume, "capture success", properties.getCaptureSuccessBeepDelayMillis());
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

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"true\"?>"
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

    private static boolean isRollImpression(String impression) {
        return impression != null && impression.toLowerCase(java.util.Locale.ROOT).contains("roll");
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

    private static CaptureResponse toResponse(CapturedData data) {
        return toResponse(data, FingerSegmentation.empty(), null, null, List.of());
    }

    private CaptureResponse toResponse(CapturedData data, FingerSegmentation segmentation, Path annotatedPath) {
        return toResponse(data, segmentation, annotatedPath, lastObjectCountState.get(), lastObjectQualityStates.get());
    }

    private static CaptureResponse toResponse(
            CapturedData data,
            FingerSegmentation segmentation,
            Path annotatedPath,
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
                    BioBaseObjectQualityState.fromValue(value).name()
            ));
        }
        return responses;
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
}
