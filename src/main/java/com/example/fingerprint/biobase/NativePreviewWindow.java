package com.example.fingerprint.biobase;

import com.example.fingerprint.config.FingerprintProperties;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32Util;
import com.sun.jna.platform.win32.WinDef;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NativePreviewWindow {
    private static final Logger log = LoggerFactory.getLogger(NativePreviewWindow.class);
    private static final int HIDDEN_WINDOW_X = -32000;
    private static final int HIDDEN_WINDOW_Y = -32000;

    private final FingerprintProperties properties;
    private final AtomicReference<JFrame> frameRef = new AtomicReference<>();
    private final AtomicReference<Canvas> canvasRef = new AtomicReference<>();

    public NativePreviewWindow(FingerprintProperties properties) {
        this.properties = properties;
    }

    public OptionalLong open(String title) {
        if (!properties.isNativePreviewWindowEnabled()) {
            return OptionalLong.empty();
        }
        if (!isWindows()) {
            log.warn("Native preview window is only supported on Windows.");
            return OptionalLong.empty();
        }
        if (GraphicsEnvironment.isHeadless()) {
            log.warn("Native preview window is disabled because Java is running headless.");
            return OptionalLong.empty();
        }

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                return OptionalLong.of(openOnEventThread(title));
            }

            AtomicReference<OptionalLong> handleRef = new AtomicReference<>(OptionalLong.empty());
            AtomicReference<RuntimeException> errorRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    handleRef.set(OptionalLong.of(openOnEventThread(title)));
                } catch (RuntimeException e) {
                    errorRef.set(e);
                }
            });
            if (errorRef.get() != null) {
                throw errorRef.get();
            }
            return handleRef.get();
        } catch (Exception e) {
            log.warn("Could not open native preview window: {}", e.getMessage());
            return OptionalLong.empty();
        }
    }

    public void close() {
        JFrame frame = frameRef.getAndSet(null);
        canvasRef.set(null);
        if (frame == null) {
            return;
        }
        SwingUtilities.invokeLater(frame::dispose);
    }

    public boolean isOpen() {
        JFrame frame = frameRef.get();
        Canvas canvas = canvasRef.get();
        return frame != null && canvas != null && frame.isDisplayable() && canvas.isShowing();
    }

    public byte[] captureJpeg() {
        Canvas canvas = canvasRef.get();
        if (canvas == null || !canvas.isShowing()) {
            return new byte[0];
        }

        try {
            BufferedImage capture = captureCanvasImage(canvas);
            if (capture == null || capture.getWidth() <= 0 || capture.getHeight() <= 0) {
                return new byte[0];
            }

            BufferedImage rgb = new BufferedImage(capture.getWidth(), capture.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.drawImage(capture, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new BioBaseException("Native preview capture failed: " + e.getMessage());
        }
    }

    private BufferedImage captureCanvasImage(Canvas canvas) throws Exception {
        try {
            return captureCanvasImageWithGdi(canvas);
        } catch (Exception e) {
            log.debug("GDI native preview capture failed, falling back to Robot: {}", e.getMessage());
            return captureCanvasImageWithRobot(canvas);
        }
    }

    private static BufferedImage captureCanvasImageWithGdi(Canvas canvas) {
        long handle = componentHandle(canvas);
        return GDI32Util.getScreenshot(new WinDef.HWND(Pointer.createConstant(handle)));
    }

    private static BufferedImage captureCanvasImageWithRobot(Canvas canvas) throws Exception {
        Rectangle bounds = canvasScreenBounds(canvas);
        if (bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        return new Robot(canvas.getGraphicsConfiguration().getDevice()).createScreenCapture(bounds);
    }

    private long openOnEventThread(String title) {
        JFrame existingFrame = frameRef.get();
        Canvas existingCanvas = canvasRef.get();
        if (existingFrame != null && existingCanvas != null && existingFrame.isDisplayable()) {
            existingFrame.toFront();
            return componentHandle(existingCanvas);
        }

        JFrame frame = new JFrame(title == null || title.isBlank() ? "BioBase Native Preview" : title);
        Canvas canvas = new Canvas();
        canvas.setBackground(Color.BLACK);
        canvas.setPreferredSize(new Dimension(
                Math.max(160, properties.getNativePreviewWindowWidth()),
                Math.max(120, properties.getNativePreviewWindowHeight())
        ));

        boolean visibleWindow = properties.isNativePreviewWindowVisible();
        frame.setUndecorated(!visibleWindow);
        frame.setLayout(new BorderLayout());
        frame.add(canvas, BorderLayout.CENTER);
        frame.pack();
        if (visibleWindow) {
            frame.setLocationByPlatform(true);
        } else {
            frame.setLocation(HIDDEN_WINDOW_X, HIDDEN_WINDOW_Y);
        }
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setVisible(true);

        long handle = componentHandle(canvas);
        frameRef.set(frame);
        canvasRef.set(canvas);
        log.info("Native preview window opened: handle=0x{}, size={}x{}, visible={}",
                Long.toHexString(handle), canvas.getWidth(), canvas.getHeight(), visibleWindow);
        return handle;
    }

    private static long componentHandle(Canvas canvas) {
        canvas.addNotify();
        Pointer pointer = Native.getComponentPointer(canvas);
        long handle = Pointer.nativeValue(pointer);
        if (handle == 0) {
            throw new BioBaseException("Native preview window handle is 0.");
        }
        return handle;
    }

    private static Rectangle canvasScreenBounds(Canvas canvas) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return readCanvasScreenBounds(canvas);
        }

        AtomicReference<Rectangle> boundsRef = new AtomicReference<>();
        AtomicReference<RuntimeException> errorRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                boundsRef.set(readCanvasScreenBounds(canvas));
            } catch (RuntimeException e) {
                errorRef.set(e);
            }
        });
        if (errorRef.get() != null) {
            throw errorRef.get();
        }
        return boundsRef.get();
    }

    private static Rectangle readCanvasScreenBounds(Canvas canvas) {
        Point location = canvas.getLocationOnScreen();
        return new Rectangle(location.x, location.y, canvas.getWidth(), canvas.getHeight());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
