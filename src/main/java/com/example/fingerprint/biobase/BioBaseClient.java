package com.example.fingerprint.biobase;

import com.sun.jna.Pointer;
import com.sun.jna.Memory;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.ptr.IntByReference;
import java.nio.charset.StandardCharsets;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Component
public class BioBaseClient {
    private final BioBaseNative nativeApi;
    private final Map<String, StdCallLibrary.StdCallCallback> registeredCallbacks = new HashMap<>();

    public BioBaseClient(@Value("${biobase.dll.path:LScanEssentialsBioBase4}") String libraryPath) {
        this.nativeApi = BioBaseNative.load(libraryPath);
    }

    public synchronized void openSystem() {
        IntByReference ret = new IntByReference();
        nativeApi.BioB_Open(ret);
        requireSuccess("BioB_Open", ret.getValue());
    }

    public synchronized void closeSystem() {
        IntByReference ret = new IntByReference();
        nativeApi.BioB_Close(ret);
        requireSuccess("BioB_Close", ret.getValue());
    }

    public int getDeviceCount() {
        IntByReference ret = new IntByReference();
        int count = nativeApi.BioB_GetDeviceCount(ret);
        requireSuccess("BioB_GetDeviceCount", ret.getValue());
        return count;
    }

    public List<DeviceInfo> getDevices() {
        IntByReference ret = new IntByReference();
        Pointer pointer = nativeApi.BioB_GetDevicesInfo(ret);
        requireSuccess("BioB_GetDevicesInfo", ret.getValue());
        String xml = callXml("BioB_GetDevicesInfo", pointer);
        return parseDevices(xml);
    }

    public String getApiProperties() {
        IntByReference ret = new IntByReference();
        Pointer pointer = nativeApi.BioB_GetAPIProperties(ret);
        requireSuccess("BioB_GetAPIProperties", ret.getValue());
        return pointerToStringAndFree(pointer);
    }

    public synchronized void openDevice(String deviceId, boolean reset) {
        IntByReference ret = new IntByReference();
        nativeApi.BioB_OpenDevice(deviceId, reset, ret);
        requireNonError("BioB_OpenDevice", ret.getValue());
    }

    public synchronized void closeDevice(String deviceId, boolean standby) {
        IntByReference ret = new IntByReference();
        nativeApi.BioB_CloseDevice(deviceId, standby, ret);
        requireSuccess("BioB_CloseDevice", ret.getValue());
    }

    public boolean isDeviceOpen(String deviceId) {
        IntByReference ret = new IntByReference();
        boolean open = nativeApi.BioB_IsDeviceOpened(deviceId, ret);
        return ret.getValue() == 0 && open;
    }

    public boolean isDeviceReady(String deviceId) {
        IntByReference ret = new IntByReference();
        boolean ready = nativeApi.BioB_IsDeviceReady(deviceId, ret);
        return ret.getValue() == 0 && ready;
    }

    public boolean isDeviceAcquiring(String deviceId) {
        IntByReference ret = new IntByReference();
        boolean acquiring = nativeApi.BioB_IsDeviceAcquiring(deviceId, ret);
        requireSuccess("BioB_IsDeviceAcquiring", ret.getValue());
        return acquiring;
    }

    public String getProperties(String deviceId) {
        IntByReference ret = new IntByReference();
        Pointer pointer = nativeApi.BioB_GetProperties(deviceId, ret);
        requireSuccess("BioB_GetProperties", ret.getValue());
        return pointerToStringAndFree(pointer);
    }

    public String getProperty(String deviceId, String propertyName) {
        IntByReference ret = new IntByReference();
        Pointer pointer = nativeApi.BioB_GetProperty(deviceId, propertyName, ret);
        requireSuccess("BioB_GetProperty", ret.getValue());
        return pointerToStringAndFree(pointer);
    }

    public void setProperty(String deviceId, String propertyName, String value) {
        IntByReference ret = new IntByReference();
        nativeApi.BioB_SetProperty(deviceId, propertyName, value, ret);
        requireSuccess("BioB_SetProperty", ret.getValue());
    }

    public synchronized void setOutputXml(String deviceId, String xml) {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        Memory buffer = new Memory(bytes.length);
        buffer.write(0, bytes, 0, bytes.length);

        BioBaseNative.BioBSetOutputData data = new BioBaseNative.BioBSetOutputData();
        data.buffer = buffer;
        data.bufferSize = bytes.length;
        data.formatType = 7; // BIOB_OUT_XML
        data.extStruct = Pointer.NULL;
        data.structName = Pointer.NULL;
        data.transactionId = 0;
        data.write();

        IntByReference ret = new IntByReference();
        nativeApi.BioB_SetOutputData(deviceId, data, ret);
        requireSuccess("BioB_SetOutputData", ret.getValue());
    }

    public synchronized int beginAcquisition(String deviceId, String position, String impression) {
        IntByReference ret = new IntByReference();
        nativeApi.BioB_BeginAcquisitionProcess(deviceId, position, impression, ret);
        requireNonError("BioB_BeginAcquisitionProcess", ret.getValue());
        return ret.getValue();
    }

    public synchronized void cancelAcquisition(String deviceId) {
        IntByReference ret = new IntByReference();
        nativeApi.BioB_CancelAcquisition(deviceId, ret);
        requireSuccess("BioB_CancelAcquisition", ret.getValue());
    }

    public synchronized void requestAcquisitionOverride(String deviceId) {
        IntByReference ret = new IntByReference();
        nativeApi.BioB_RequestAcquisitionOverride(deviceId, ret);
        requireSuccess("BioB_RequestAcquisitionOverride", ret.getValue());
    }

    public synchronized void registerCallback(String deviceId, BioBaseEvent event, StdCallLibrary.StdCallCallback callback) {
        IntByReference ret = new IntByReference();
        nativeApi.BioB_RegisterDeviceCallback(deviceId, Pointer.NULL, event.value(), callback, ret);
        requireSuccess("BioB_RegisterDeviceCallback " + event, ret.getValue());
        String key = callbackKey(deviceId, event);
        if (callback == null) {
            registeredCallbacks.remove(key);
        } else {
            registeredCallbacks.put(key, callback);
        }
    }

    public CapturedData readData(String deviceId, int dataStatus, Pointer data, int detectedObjects) {
        BioBaseNative.BioBData nativeData = new BioBaseNative.BioBData(data);
        byte[] bytes = nativeData.buffer == null || nativeData.bufferSize <= 0
                ? new byte[0]
                : nativeData.buffer.getByteArray(0, nativeData.bufferSize);
        return new CapturedData(
                deviceId,
                BioBaseDataFormat.fromValue(nativeData.formatType),
                nativeData.finalImage,
                dataStatus,
                detectedObjects,
                bytes,
                null,
                java.time.Instant.now()
        );
    }

    private String callXml(String operation, Pointer pointer) {
        if (pointer == null) {
            throw new BioBaseException(operation + " returned a null pointer");
        }
        return pointerToStringAndFree(pointer);
    }

    private String pointerToStringAndFree(Pointer pointer) {
        try {
            return pointer == null ? "" : pointer.getString(0);
        } finally {
            if (pointer != null) {
                IntByReference freeRet = new IntByReference();
                nativeApi.BioB_Free(pointer, freeRet);
                requireSuccess("BioB_Free", freeRet.getValue());
            }
        }
    }

    private static void requireSuccess(String operation, int retCode) {
        if (retCode != 0) {
            throw new BioBaseException(operation, retCode);
        }
    }

    private static void requireNonError(String operation, int retCode) {
        if (retCode < 0) {
            throw new BioBaseException(operation, retCode);
        }
    }

    private static List<DeviceInfo> parseDevices(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList nodes = document.getElementsByTagName("Device");
            List<DeviceInfo> devices = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                devices.add(new DeviceInfo(
                        text(element, "ModelName"),
                        text(element, "SerNum"),
                        text(element, "Interface"),
                        text(element, "DeviceId"),
                        text(element, "Modality"),
                        text(element, "Visualizers")
                ));
            }
            return devices;
        } catch (Exception e) {
            throw new BioBaseException("Could not parse BioB_GetDevicesInfo XML: " + e.getMessage());
        }
    }

    private static String text(Element element, String name) {
        NodeList nodes = element.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static String callbackKey(String deviceId, BioBaseEvent event) {
        return (deviceId == null ? "<system>" : deviceId) + ":" + event.name();
    }
}
