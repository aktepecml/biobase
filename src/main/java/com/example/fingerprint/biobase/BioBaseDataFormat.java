package com.example.fingerprint.biobase;

public enum BioBaseDataFormat {
    BIOB_IIR(0, "iir", "application/octet-stream"),
    BIOB_FIR(1, "fir", "application/octet-stream"),
    BIOB_FACE_IR(2, "face_ir", "application/octet-stream"),
    BIOB_BMP(3, "bmp", "image/bmp"),
    BIOB_JPG(4, "jpg", "image/jpeg"),
    BIOB_FORMAT_NULL(999, "bin", "application/octet-stream"),
    UNKNOWN(Integer.MIN_VALUE, "bin", "application/octet-stream");

    private final int value;
    private final String extension;
    private final String mediaType;

    BioBaseDataFormat(int value, String extension, String mediaType) {
        this.value = value;
        this.extension = extension;
        this.mediaType = mediaType;
    }

    public String extension() {
        return extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public static BioBaseDataFormat fromValue(int value) {
        for (BioBaseDataFormat format : values()) {
            if (format.value == value) {
                return format;
            }
        }
        return UNKNOWN;
    }
}
