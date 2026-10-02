package com.example.fingerprint.biobase;

import com.example.fingerprint.config.FingerprintProperties;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NativePreviewWindow {
    private static final Logger log = LoggerFactory.getLogger(NativePreviewWindow.class);

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

        frame.setLayout(new BorderLayout());
        frame.add(canvas, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setVisible(true);

        long handle = componentHandle(canvas);
        frameRef.set(frame);
        canvasRef.set(canvas);
        log.info("Native preview window opened: handle=0x{}, size={}x{}",
                Long.toHexString(handle), canvas.getWidth(), canvas.getHeight());
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

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
