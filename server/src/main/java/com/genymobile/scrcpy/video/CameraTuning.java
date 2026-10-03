package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.util.Ln;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Manual exposure settings read from a small key=value file on the device, written by the scrcpy-obs plugin:
 * <pre>
 * ae=1|0          auto exposure on/off
 * iso=400         sensor sensitivity (used when ae=0)
 * exposure_us=8333  exposure time in microseconds (used when ae=0)
 * </pre>
 * The file is polled so the values can be changed while streaming.
 */
final class CameraTuning {

    static final String PATH = "/data/local/tmp/scrcpy-obs-camera.conf";

    private boolean autoExposure = true;
    private int iso = 400;
    private long exposureNs = 8_333_333L;

    private CameraTuning() {
    }

    /** Returns a stamp that changes when the file changes, or -1 if there is no file. */
    static long stamp() {
        File f = new File(PATH);
        return f.exists() ? f.lastModified() * 31 + f.length() : -1;
    }

    static CameraTuning read() {
        File f = new File(PATH);
        if (!f.exists()) {
            return null;
        }
        CameraTuning t = new CameraTuning();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                try {
                    switch (key) {
                        case "ae":
                            t.autoExposure = !value.equals("0");
                            break;
                        case "iso":
                            t.iso = Integer.parseInt(value);
                            break;
                        case "exposure_us":
                            t.exposureNs = Long.parseLong(value) * 1000L;
                            break;
                        default:
                            break;
                    }
                } catch (NumberFormatException e) {
                    Ln.w("Invalid camera tuning value: " + line);
                }
            }
        } catch (IOException e) {
            Ln.w("Could not read " + PATH);
            return null;
        }
        return t;
    }

    void apply(CaptureRequest.Builder builder, CameraCharacteristics characteristics, int fps) {
        if (autoExposure) {
            Ln.i("Camera exposure: auto");
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            return;
        }

        int isoValue = iso;
        long expValue = exposureNs;
        if (characteristics != null) {
            Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (isoRange != null) {
                isoValue = isoRange.clamp(isoValue);
            }
            Range<Long> expRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (expRange != null) {
                expValue = expRange.clamp(expValue);
            }
        }

        // The exposure can't be longer than the frame duration
        long frameDuration = 1_000_000_000L / (fps > 0 ? fps : 30);
        if (expValue > frameDuration) {
            expValue = frameDuration;
        }

        Ln.i("Camera exposure: manual iso=" + isoValue + " exposure=" + expValue / 1000 + "us");
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expValue);
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration);
    }
}
