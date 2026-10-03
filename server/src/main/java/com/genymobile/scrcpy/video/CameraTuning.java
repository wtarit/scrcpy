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
 * zoom=3.5        zoom ratio, 0 = 1.0 (switches lenses on multi-camera devices)
 * awb=auto|lock|manual|daylight|cloudy|shade|fluorescent|incandescent  white balance
 * kelvin=5500    color temperature for awb=manual, relative to 5500K
 * af=continuous|manual   autofocus mode
 * focus=0..100   manual focus, 0 = infinity, 100 = closest (used when af=manual)
 * stab=off|ois|eis|both  stabilization
 * nr=fast|off|hq noise reduction
 * hdr=0|1       HDR scene mode (overrides manual exposure on most devices)
 * tint=0         green(-)/magenta(+) shift for awb=manual, -50..50
 * ev=0.0          exposure compensation in stops (used when ae=1)
 * </pre>
 * The file is polled so the values can be changed while streaming.
 */
final class CameraTuning {

    static final String PATH = "/data/local/tmp/scrcpy-obs-camera.conf";
    static final String INFO_PATH = "/data/local/tmp/scrcpy-obs-camera.info";

    private boolean autoExposure = true;
    private int iso = 400;
    private long exposureNs = 8_333_333L;
    /** Zoom ratio from the file, or -1 if not set. */
    float zoom = -1;
    private String awb = null;
    private float kelvin = 5500;
    private String af = null;
    private float focus = 0;
    private String stab = null;
    private String nr = null;
    private int hdr = -1;
    private float tint = 0;
    private float ev = 0;

    private CameraTuning() {
    }

    /** Publishes what the camera is really using (even in auto mode) and its limits, for the plugin to read. */
    static void writeInfo(CameraCharacteristics ch, Integer iso, Long exposureNs) {
        File tmp = new File(INFO_PATH + ".tmp");
        try (java.io.FileWriter w = new java.io.FileWriter(tmp)) {
            if (iso != null) {
                w.write("iso=" + iso + "\n");
            }
            if (exposureNs != null) {
                w.write("exposure_us=" + exposureNs / 1000 + "\n");
            }
            if (ch != null) {
                Range<Integer> isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                if (isoRange != null) {
                    w.write("iso_min=" + isoRange.getLower() + "\niso_max=" + isoRange.getUpper() + "\n");
                }
                Range<Long> expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                if (expRange != null) {
                    w.write("exp_min_us=" + expRange.getLower() / 1000 + "\nexp_max_us=" + expRange.getUpper() / 1000 + "\n");
                }
            }
        } catch (IOException e) {
            return;
        }
        tmp.renameTo(new File(INFO_PATH));
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
                        case "af":
                            t.af = value;
                            break;
                        case "focus":
                            t.focus = Float.parseFloat(value);
                            break;
                        case "stab":
                            t.stab = value;
                            break;
                        case "nr":
                            t.nr = value;
                            break;
                        case "hdr":
                            t.hdr = value.equals("1") ? 1 : 0;
                            break;
                        case "kelvin":
                            t.kelvin = Float.parseFloat(value);
                            break;
                        case "tint":
                            t.tint = Float.parseFloat(value);
                            break;
                        case "awb":
                            t.awb = value;
                            break;
                        case "ev":
                            t.ev = Float.parseFloat(value);
                            break;
                        case "zoom":
                            t.zoom = Float.parseFloat(value);
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

    private static boolean has(int[] values, int v) {
        if (values != null) {
            for (int x : values) {
                if (x == v) {
                    return true;
                }
            }
        }
        return false;
    }

    private void applyFocus(CaptureRequest.Builder builder, CameraCharacteristics ch) {
        if (af == null) {
            return;
        }
        if (af.equals("manual")) {
            Float minFocus = ch != null ? ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) : null;
            if (minFocus == null || minFocus <= 0) {
                Ln.w("Manual focus is not supported by this camera");
                return;
            }
            float distance = Math.max(0, Math.min(100, focus)) / 100f * minFocus;
            Ln.i("Camera focus: manual " + (int) focus + "% (" + distance + " diopters)");
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance);
        } else {
            int[] modes = ch != null ? ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) : null;
            int mode = has(modes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    : CaptureRequest.CONTROL_AF_MODE_AUTO;
            Ln.i("Camera focus: auto");
            builder.set(CaptureRequest.CONTROL_AF_MODE, mode);
        }
    }

    private void applyImage(CaptureRequest.Builder builder, CameraCharacteristics ch) {
        if (stab != null) {
            int[] oisModes = ch != null ? ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) : null;
            int[] eisModes = ch != null ? ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) : null;
            boolean ois = (stab.equals("ois") || stab.equals("both")) && has(oisModes, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            boolean eis = (stab.equals("eis") || stab.equals("both")) && has(eisModes, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            if (!stab.equals("off") && !ois && !eis) {
                Ln.w("Stabilization mode not supported by this camera: " + stab);
            }
            Ln.i("Camera stabilization: ois=" + ois + " eis=" + eis);
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    ois ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    eis ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        }

        if (nr != null) {
            int mode;
            switch (nr) {
                case "off":
                    mode = CaptureRequest.NOISE_REDUCTION_MODE_OFF;
                    break;
                case "hq":
                    mode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY;
                    break;
                default:
                    mode = CaptureRequest.NOISE_REDUCTION_MODE_FAST;
                    break;
            }
            int[] nrModes = ch != null ? ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) : null;
            if (has(nrModes, mode)) {
                Ln.i("Camera noise reduction: " + nr);
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, mode);
            } else {
                Ln.w("Noise reduction mode not supported by this camera: " + nr);
            }
        }

        if (hdr >= 0) {
            int[] scenes = ch != null ? ch.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) : null;
            if (hdr == 1 && has(scenes, CaptureRequest.CONTROL_SCENE_MODE_HDR)) {
                Ln.i("Camera HDR scene mode: on");
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR);
            } else {
                if (hdr == 1) {
                    Ln.w("HDR scene mode not supported by this camera");
                }
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            }
        }
    }

    boolean wantsLock() {
        return "lock".equals(awb) || isManualWb();
    }

    boolean isManualWb() {
        return "manual".equals(awb);
    }

    /** Approximate RGB (0..1) of a black body at the given temperature. */
    private static double[] planck(double kelvin) {
        double t = Math.max(1000, Math.min(15000, kelvin)) / 100.0;
        double r = t <= 66 ? 255 : 329.698727446 * Math.pow(t - 60, -0.1332047592);
        double g = t <= 66 ? 99.4708025861 * Math.log(t) - 161.1195681661 : 288.1221695283 * Math.pow(t - 60, -0.0755148492);
        double b = t >= 66 ? 255 : (t <= 19 ? 1 : 138.5177312231 * Math.log(t - 10) - 305.0447927307);
        return new double[] {Math.max(1, Math.min(255, r)) / 255, Math.max(1, Math.min(255, g)) / 255, Math.max(1, Math.min(255, b)) / 255};
    }

    private boolean applyManualWhiteBalance(CaptureRequest.Builder builder, CameraCharacteristics characteristics,
            android.hardware.camera2.params.RggbChannelVector baseGains, android.hardware.camera2.params.ColorSpaceTransform baseCcm) {
        if (baseGains == null || baseCcm == null) {
            return false;
        }
        if (characteristics != null) {
            int[] caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean ok = false;
            if (caps != null) {
                for (int c : caps) {
                    if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) {
                        ok = true;
                        break;
                    }
                }
            }
            if (!ok) {
                Ln.w("Manual white balance is not supported by this camera");
                return false;
            }
        }

        double[] k = planck(kelvin);
        double[] ref = planck(5500);
        float fr = (float) ((k[1] / k[0]) / (ref[1] / ref[0]));
        float fb = (float) ((k[1] / k[2]) / (ref[1] / ref[2]));
        float gt = 1 - tint * 0.004f;

        android.hardware.camera2.params.RggbChannelVector gains = new android.hardware.camera2.params.RggbChannelVector(
                baseGains.getRed() * fr, baseGains.getGreenEven() * gt, baseGains.getGreenOdd() * gt, baseGains.getBlue() * fb);
        Ln.i("Camera white balance: manual " + (int) kelvin + "K tint " + (int) tint);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains);
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, baseCcm);
        return true;
    }

    private void applyWhiteBalance(CaptureRequest.Builder builder, CameraCharacteristics characteristics, boolean allowLock,
            android.hardware.camera2.params.RggbChannelVector baseGains, android.hardware.camera2.params.ColorSpaceTransform baseCcm) {
        if (awb == null) {
            return;
        }
        if (isManualWb() && applyManualWhiteBalance(builder, characteristics, baseGains, baseCcm)) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false);
            return;
        }
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST);
        int mode;
        boolean lock = false;
        switch (awb) {
            case "lock":
                mode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
                lock = allowLock;
                break;
            case "daylight":
                mode = CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
                break;
            case "cloudy":
                mode = CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
                break;
            case "shade":
                mode = CaptureRequest.CONTROL_AWB_MODE_SHADE;
                break;
            case "fluorescent":
                mode = CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
                break;
            case "incandescent":
                mode = CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
                break;
            default:
                mode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
                break;
        }
        if (characteristics != null) {
            int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
            boolean supported = false;
            if (modes != null) {
                for (int m : modes) {
                    if (m == mode) {
                        supported = true;
                        break;
                    }
                }
            }
            if (!supported) {
                Ln.w("White balance mode not supported by this camera: " + awb);
                mode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
                lock = false;
            }
        }
        Ln.i("Camera white balance: " + awb + (lock ? " (locked)" : ""));
        builder.set(CaptureRequest.CONTROL_AWB_MODE, mode);
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, lock);
    }

    private void applyCompensation(CaptureRequest.Builder builder, CameraCharacteristics characteristics) {
        if (characteristics == null) {
            return;
        }
        Range<Integer> range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        android.util.Rational step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (range == null || step == null || step.floatValue() <= 0) {
            return;
        }
        int steps = range.clamp(Math.round(ev / step.floatValue()));
        Ln.i("Camera exposure compensation: " + steps + " steps");
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps);
    }

    void apply(CaptureRequest.Builder builder, CameraCharacteristics characteristics, int fps, boolean allowLock,
            android.hardware.camera2.params.RggbChannelVector baseGains, android.hardware.camera2.params.ColorSpaceTransform baseCcm) {
        applyImage(builder, characteristics);
        applyFocus(builder, characteristics);
        applyWhiteBalance(builder, characteristics, allowLock, baseGains, baseCcm);
        if (autoExposure) {
            Ln.i("Camera exposure: auto");
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            applyCompensation(builder, characteristics);
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

        // A long exposure lowers the frame rate
        long frameDuration = 1_000_000_000L / (fps > 0 ? fps : 30);
        if (expValue > frameDuration) {
            frameDuration = expValue;
            Long maxDuration = characteristics != null ? characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) : null;
            if (maxDuration != null && frameDuration > maxDuration) {
                frameDuration = maxDuration;
                expValue = maxDuration;
            }
        }

        Ln.i("Camera exposure: manual iso=" + isoValue + " exposure=" + expValue / 1000 + "us");
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expValue);
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration);
    }
}
