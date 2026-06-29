package com.example.fingerprint.cmtfinger;

import com.sun.jna.Structure;
import java.util.Arrays;
import java.util.List;

/**
 * This is the structure used to select a particular fingerprint from a collection
 */
public class Cmt_finger_viewspec extends Structure {
    public int position;      // Cmtfinger_position
    public int impression;    // Cmtfinger_impression
    public int quality;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("position", "impression", "quality");
    }
}
