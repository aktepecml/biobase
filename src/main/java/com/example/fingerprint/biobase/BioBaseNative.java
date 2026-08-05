package com.example.fingerprint.biobase;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.List;

public interface BioBaseNative extends StdCallLibrary {
    static BioBaseNative load(String libraryPath) {
        return Native.load(libraryPath, BioBaseNative.class);
    }

    void BioB_GetInterfaceVersion(IntByReference major, IntByReference minor);

    void BioB_Open(IntByReference retCode);

    void BioB_Close(IntByReference retCode);

    Pointer BioB_GetAPIProperties(IntByReference retCode);

    int BioB_GetDeviceCount(IntByReference retCode);

    Pointer BioB_GetDevicesInfo(IntByReference retCode);

    void BioB_OpenDevice(String deviceId, boolean reset, IntByReference retCode);

    void BioB_CloseDevice(String deviceId, boolean sendToStandby, IntByReference retCode);

    void BioB_RegisterDeviceCallback(String deviceId, Pointer context, int event, StdCallCallback callback, IntByReference retCode);

    Pointer BioB_GetProperties(String deviceId, IntByReference retCode);

    Pointer BioB_GetProperty(String deviceId, String propertyName, IntByReference retCode);

    void BioB_SetProperty(String deviceId, String propertyName, String value, IntByReference retCode);

    void BioB_SetOutputData(String deviceId, BioBSetOutputData data, IntByReference retCode);

    void BioB_CancelAcquisition(String deviceId, IntByReference retCode);

    void BioB_BeginAcquisitionProcess(String deviceId, String positionType, String impressionType, IntByReference retCode);

    void BioB_RequestAcquisitionOverride(String deviceId, IntByReference retCode);

    boolean BioB_IsDeviceAcquiring(String deviceId, IntByReference retCode);

    boolean BioB_IsDeviceOpened(String deviceId, IntByReference retCode);

    boolean BioB_IsDeviceReady(String deviceId, IntByReference retCode);

    void BioB_Free(Pointer pointer, IntByReference retCode);

    interface PreviewCallback extends StdCallCallback {
        void invoke(String deviceId, Pointer context, Pointer data);
    }

    interface AcquisitionStartedCallback extends StdCallCallback {
        void invoke(String deviceId, Pointer context, Pointer reserved);
    }

    interface AcquisitionCompletedCallback extends StdCallCallback {
        void invoke(String deviceId, Pointer context, Pointer reserved);
    }

    interface DataAvailableCallback extends StdCallCallback {
        void invoke(String deviceId, Pointer context, int dataStatus, Pointer data, int detectedObjects);
    }

    interface ObjectQualityCallback extends StdCallCallback {
        void invoke(String deviceId, Pointer context, Pointer qualityStates, int qualityStateCount);
    }

    interface ObjectCountCallback extends StdCallCallback {
        void invoke(String deviceId, Pointer context, int objectCountState);
    }

    class BioBData extends Structure {
        public Pointer buffer;
        public int bufferSize;
        public int formatType;
        public boolean finalImage;
        public Pointer extStruct;
        public Pointer structName;

        public BioBData(Pointer pointer) {
            super(pointer);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("buffer", "bufferSize", "formatType", "finalImage", "extStruct", "structName");
        }
    }

    class BioBSetOutputData extends Structure {
        public Pointer buffer;
        public int bufferSize;
        public int formatType;
        public Pointer extStruct;
        public Pointer structName;
        public int transactionId;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("buffer", "bufferSize", "formatType", "extStruct", "structName", "transactionId");
        }
    }

    class BioBROI extends Structure {
        public int x;
        public int y;
        public int width;
        public int height;

        public BioBROI() {
            super();
        }

        public BioBROI(Pointer pointer) {
            super(pointer);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("x", "y", "width", "height");
        }
    }

    class BioBScene extends Structure {
        public Pointer raster;
        public int width;
        public int height;
        public int sceneIndex;
        public int numDetected;
        public Pointer biometricObjects;

        public BioBScene(Pointer pointer) {
            super(pointer);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("raster", "width", "height", "sceneIndex", "numDetected", "biometricObjects");
        }
    }
}
