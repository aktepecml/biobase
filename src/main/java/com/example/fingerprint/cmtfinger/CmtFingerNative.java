package com.example.fingerprint.cmtfinger;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

/**
 * Crossmatch .NET Wrapper - Java/JNA Version
 */
public interface CmtFingerNative extends Library {

    // DLL yükleme
    CmtFingerNative INSTANCE = Native.load(
            "cmtfinger",
            CmtFingerNative.class,
            W32APIOptions.UNICODE_OPTIONS
    );

    /**
     * Creates an opaque Finger Image object
     * @param rcfir the pointer to the opaque object to be created
     * @return an error code
     */
    int cmtfinger_create(PointerByReference rcfir);

    /**
     * Frees an opaque Finger Image object
     * @param cfir the opaque object to be freed
     */
    void cmtfinger_free(Pointer cfir);

    /**
     * Encodes an opaque Finger Image object into a standards based finger image record
     * @param cfir the opaque object to be encoded
     * @param encoding_format the encoding format to use
     * @param encoded_finger_record the client allocated buffer to be filled with the encoded FIR
     * @param enc_length the length of the FIR record generated
     * @return an error code
     */
    int cmtfinger_encode(
            Pointer cfir,
            int encoding_format,
            Pointer encoded_finger_record,
            IntByReference enc_length
    );

    /**
     * Decodes a standards based finger image record into an already allocated opaque Finger Image object
     * @param cfir the opaque object to be established by the FIR encoding
     * @param encoded_finger_record the encoded FIR to be decoded
     * @param enc_length the length of the FIR record
     * @return an error code
     */
    int cmtfinger_decode(
            Pointer cfir,
            byte[] encoded_finger_record,
            int enc_length
    );

    /**
     * Gets a property of an opaque Finger Image object
     * @param cfir the opaque object
     * @param property the property to get
     * @param pval the value of the property
     * @return an error code
     */
    int cmtfinger_get_property(
            Pointer cfir,
            int property,
            IntByReference pval
    );

    /**
     * Sets a property of an opaque Finger Image object
     * @param cfir the opaque object
     * @param property the property to set
     * @param pval the value of the property
     * @return an error code
     */
    int cmtfinger_set_property(
            Pointer cfir,
            int property,
            int pval
    );

    /**
     * Queries the opaque Finger Image object for particular fingers by position and/or impression
     * @param cfir the opaque object
     * @param query the viewspec with position and/or impression to look for
     * @param results the array of viewspecs allocated by the client
     * @param numresults contains number allocated on input, and number matched on output
     * @return an error code
     */
    int cmtfinger_query(
            Pointer cfir,
            Cmt_finger_viewspec query,
            Pointer results,
            IntByReference numresults
    );

    /**
     * Reads in a bitmap buffer to establish/add it to a particular finger image
     * @param cfir the opaque object
     * @param vs selects the position and impression to associate with the bitmap
     * @param bmp the buffer containing the windows bitmap byte stream
     * @param bmp_length the length of the bitmap byte stream
     * @return an error code
     */
    int cmtfinger_decode_from_bmp(
            Pointer cfir,
            Cmt_finger_viewspec vs,
            byte[] bmp,
            int bmp_length
    );

    /**
     * Generates outputs of a bitmap buffer from a particular finger image
     * @param cfir the opaque object
     * @param vs selects the position and impression to generate to the bitmap
     * @param bmp the buffer that will be filled in with the windows bitmap byte stream
     * @param bmp_length the returned length of the bitmap bytestream
     * @return an error code
     */
    int cmtfinger_encode_to_bmp(
            Pointer cfir,
            Cmt_finger_viewspec vs,
            byte[] bmp,
            IntByReference bmp_length
    );

    /**
     * Reads in a raster to establish/add it to a particular finger image
     * @param cfir the opaque object
     * @param vs selects the position and impression to associate with the raster
     * @param width the width of raster
     * @param height the height of raster
     * @param raster the raster
     * @return an error code
     */
    int cmtfinger_set_raster(
            Pointer cfir,
            Cmt_finger_viewspec vs,
            int width,
            int height,
            Pointer raster
    );

    /**
     * Generates a raster output from a particular finger image
     * @param cfir the opaque object
     * @param vs selects the position and impression to generate to the raster
     * @param width the width of returned raster
     * @param height the height of returned raster
     * @param raster the buffer to be filled in with the raster
     * @return an error code
     */
    int cmtfinger_get_raster(
            Pointer cfir,
            Cmt_finger_viewspec vs,
            IntByReference width,
            IntByReference height,
            byte[] raster
    );

    /**
     * Gets version of transcoder
     * @param vstring the buffer allocated by client and filled in by transcoder
     * @param buflen the length of buffer that is allowed to be used by the transcoder
     * @return an error code
     */
    int cmtfinger_get_version(
            byte[] vstring,
            int buflen
    );
}