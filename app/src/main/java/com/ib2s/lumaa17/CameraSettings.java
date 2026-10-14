package com.ib2s.lumaa17;

import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;
import androidx.camera.core.ImageCapture;
import androidx.camera.extensions.ExtensionMode;

public final class CameraSettings {
    public boolean video, detail, highBitrate, mirror, grid = true, level = true, sound = true;
    public boolean stabilization = true, manualExposure, lock;
    public boolean maximumVideoQuality, ultraBitrate, jpegMaximum, ultraHdr, geminiAutomatic;
    public int geminiStyle;
    public int extension = ExtensionMode.NONE;
    public int timerSeconds, flash = ImageCapture.FLASH_MODE_OFF;
    public int whiteBalance = CaptureRequest.CONTROL_AWB_MODE_AUTO;
    public int antibanding = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ;
    public int iso = 100;
    public long shutterNs = 10_000_000L;
    public float focusDistance = -1;
    public double ev;
    public boolean videoHd;

    public CameraSettings(SharedPreferences p) {
        detail = p.getBoolean("detail", false);
        highBitrate = p.getBoolean("bitrate", false);
        mirror = p.getBoolean("mirror", false);
        grid = p.getBoolean("grid", true);
        level = p.getBoolean("level", true);
        sound = p.getBoolean("sound", true);
        stabilization = p.getBoolean("stabilization", true);
        antibanding = p.getInt("antibanding", CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ);
        timerSeconds = p.getInt("timer", 0);
        videoHd = p.getBoolean("hd", false);
        maximumVideoQuality = p.getBoolean("maximumVideoQuality", true);
        ultraBitrate = p.getBoolean("ultraBitrate", false);
        jpegMaximum = p.getBoolean("jpegMaximum", false);
        ultraHdr = p.getBoolean("ultraHdr", false);
        geminiAutomatic = p.getBoolean("geminiAutomatic", false);
        geminiStyle = Math.max(0, Math.min(2, p.getInt("geminiStyle", 0)));
    }

    public void save(SharedPreferences p) {
        p.edit().putBoolean("detail", detail).putBoolean("bitrate", highBitrate)
                .putBoolean("mirror", mirror).putBoolean("grid", grid)
                .putBoolean("level", level).putBoolean("sound", sound)
                .putBoolean("stabilization", stabilization).putInt("antibanding", antibanding)
                .putInt("timer", timerSeconds).putBoolean("hd", videoHd)
                .putBoolean("maximumVideoQuality", maximumVideoQuality).putBoolean("ultraBitrate", ultraBitrate)
                .putBoolean("jpegMaximum", jpegMaximum).putBoolean("ultraHdr", ultraHdr)
                .putBoolean("geminiAutomatic", geminiAutomatic).putInt("geminiStyle", geminiStyle).apply();
    }

    public void resetLensControls() {
        manualExposure = false; focusDistance = -1; lock = false; ev = 0;
        whiteBalance = CaptureRequest.CONTROL_AWB_MODE_AUTO;
        extension = ExtensionMode.NONE; flash = ImageCapture.FLASH_MODE_OFF;
    }
}
