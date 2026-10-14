package com.ib2s.lumaa17;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.DynamicRange;
import androidx.camera.video.Quality;
import androidx.camera.video.Recorder;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@OptIn(markerClass = ExperimentalCamera2Interop.class)
public final class LensInfo {
    public final CameraInfo info;
    public final String id;
    public final boolean front, ois, eis, manual, aeLock, awbLock;
    public final float minFocus;
    public final Range<Integer> isoRange;
    public final Range<Long> shutterRange;
    public final Camera2CameraInfo camera2;
    public final List<Quality> qualities;
    public String name;

    public LensInfo(CameraInfo source) {
        info = source; camera2 = Camera2CameraInfo.from(source); id = camera2.getCameraId();
        Integer facing = get(CameraCharacteristics.LENS_FACING);
        front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        ois = has(get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        eis = has(get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        manual = has(get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
        aeLock = Boolean.TRUE.equals(get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
        awbLock = Boolean.TRUE.equals(get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE));
        Float distance = get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        minFocus = distance == null ? 0 : distance;
        isoRange = get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        shutterRange = get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        List<Quality> supported;
        try { supported = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR); }
        catch (RuntimeException ex) { supported = new ArrayList<>(); }
        qualities = supported;
        name = front ? "Frontal" : "Traseira " + id;
    }

    public <T> T get(CameraCharacteristics.Key<T> key) { return camera2.getCameraCharacteristic(key); }
    public boolean wb(int mode) { return has(get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES), mode); }
    public boolean noise(int mode) { return has(get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), mode); }
    public boolean edge(int mode) { return has(get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), mode); }
    public boolean antibanding(int mode) { return has(get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES), mode); }
    public boolean manualAvailable() { return manual && isoRange != null && shutterRange != null; }
    public Range<Integer> frameRate(boolean video, boolean fixed) {
        List<int[]> ranges = new ArrayList<>();
        for (Range<Integer> range : info.getSupportedFrameRateRanges())
            ranges.add(new int[]{range.getLower(), range.getUpper()});
        int[] selected = CapturePolicy.frameRate(ranges.toArray(new int[0][]), video, fixed);
        return selected == null ? null : new Range<>(selected[0], selected[1]);
    }
    public Size balancedPhotoSize() {
        StreamConfigurationMap map = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new Size(4000, 3000);
        int[] result = PhotoResolutionPolicy.balanced(dimensions(map.getOutputSizes(ImageFormat.JPEG)),
                dimensions(map.getHighResolutionOutputSizes(ImageFormat.JPEG)));
        return new Size(result[0], result[1]);
    }
    private static int[][] dimensions(Size[] sizes) {
        if (sizes == null) return new int[0][];
        int[][] result = new int[sizes.length][2];
        for (int i = 0; i < sizes.length; i++) result[i] = new int[]{sizes[i].getWidth(), sizes[i].getHeight()};
        return result;
    }
    public CameraSelector selector() {
        return new CameraSelector.Builder().addCameraFilter(cameras -> {
            List<CameraInfo> matching = new ArrayList<>();
            for (CameraInfo candidate : cameras) {
                if (Camera2CameraInfo.from(candidate).getCameraId().equals(id)) matching.add(candidate);
            }
            return matching;
        }).build();
    }

    public static boolean has(int[] values, int expected) {
        if (values != null) for (int value : values) if (value == expected) return true;
        return false;
    }

    public JSONObject report() throws JSONException {
        JSONObject out = new JSONObject().put("id", id).put("name", name)
                .put("front", front).put("ois", ois).put("eis", eis)
                .put("manualSensor", manual).put("minimumFocusDistance", minFocus)
                .put("hardwareLevel", get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                .put("isoRange", String.valueOf(isoRange)).put("exposureTimeRangeNs", String.valueOf(shutterRange))
                .put("aeLock", aeLock).put("awbLock", awbLock)
                .put("lowLightBoostSupported", info.isLowLightBoostSupported())
                .put("rawSensor", has(get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW))
                .put("logicalMultiCamera", has(get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA))
                .put("balancedPhotoTarget", balancedPhotoSize().toString());
        JSONArray sizes = new JSONArray();
        StreamConfigurationMap map = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] jpeg = map.getOutputSizes(ImageFormat.JPEG);
            if (jpeg != null) for (Size size : jpeg) sizes.put(size.toString());
            JSONArray high = new JSONArray();
            Size[] highSizes = map.getHighResolutionOutputSizes(ImageFormat.JPEG);
            if (highSizes != null) for (Size size : highSizes) high.put(size.toString());
            out.put("highResolutionJpegSizes", high);
        }
        out.put("jpegSizes", sizes);
        JSONArray fps = new JSONArray();
        Range<Integer>[] ranges = get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) for (Range<Integer> range : ranges) fps.put(range.toString());
        out.put("aeFpsRanges", fps);
        JSONArray video = new JSONArray();
        for (Quality quality : qualities) video.put(quality.toString());
        return out.put("videoQualitiesSdr", video);
    }
}
