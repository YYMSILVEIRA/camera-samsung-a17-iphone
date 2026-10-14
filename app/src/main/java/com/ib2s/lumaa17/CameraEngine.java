package com.ib2s.lumaa17;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.DynamicRange;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MirrorMode;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** One capture pipeline at a time. Never re-encode an already encoded photo or video. */
@OptIn(markerClass = ExperimentalCamera2Interop.class)
public final class CameraEngine {
    public interface Listener {
        void onReady();
        void onBusy(boolean busy);
        void onNotice(String text);
        void onError(String text);
        void onSaved(Uri uri, boolean video);
        void onRecording(boolean recording, boolean paused, long millis, long bytes);
        void onTelemetry(String text);
        void onExposureChanged();
    }
    private final Context context;
    private final LifecycleOwner owner;
    private final PreviewView previewView;
    private final CameraSettings settings;
    private final Listener listener;
    private final Executor main;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PreviewMeter meter;
    private ProcessCameraProvider provider;
    private ExtensionsManager extensions;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private boolean pendingVideo, stopping, paused, closed, busy, initializing;
    private int generation;
    private int boundExtension = ExtensionMode.NONE;
    private boolean conservative;
    private long lastTelemetry;
    private long durationMillis, recordingBytes;
    private int rotation = Surface.ROTATION_0;
    private String videoDescription = "";
    private Range<Integer> sessionFps;
    private boolean ultraHdrActive, lowLightRequested, lowLightRejected, torchRequested, exposurePending;
    private double autoExposure = 1.0 / 3, appliedEv;
    private int requestedExposureIndex = Integer.MIN_VALUE;
    private long meterPausedUntil;
    private ExposureAdvisor.Sample lastMeterSample;
    public Camera camera;
    public LensInfo lens;
    public final List<LensInfo> lenses = new ArrayList<>();

    public CameraEngine(Context context, LifecycleOwner owner, PreviewView previewView,
                        CameraSettings settings, Listener listener) {
        this.context = context; this.owner = owner; this.previewView = previewView;
        this.settings = settings; this.listener = listener;
        main = ContextCompat.getMainExecutor(context);
        meter = new PreviewMeter(previewView);
    }

    public boolean isBusy() { return busy; }
    public boolean isRecording() { return recording != null || pendingVideo || stopping; }
    public boolean isPaused() { return paused; }
    public boolean isExtension() { return boundExtension != ExtensionMode.NONE; }
    public boolean isConservative() { return conservative; }
    public boolean isReady() { return camera != null && !busy; }
    public void setRotation(int value) { rotation = value; }
    public double exposureEv() { return appliedEv; }
    public boolean isAutoExposure() { return settings.smartExposure && !settings.userExposureOverride
            && !settings.manualExposure && !isExtension() && !conservative; }

    public void initialize() {
        if (closed || initializing) return;
        if (provider != null) { bind(); return; }
        initializing = true;
        setBusy(true);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            if (closed) return;
            try {
                provider = future.get();
                CameraInfo primary = null;
                try { primary = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA); }
                catch (RuntimeException ignored) { /* Front-only devices remain usable. */ }
                lenses.clear();
                for (CameraInfo info : provider.getAvailableCameraInfos()) {
                    LensInfo item = new LensInfo(info);
                    if (primary != null && item.id.equals(new LensInfo(primary).id)) {
                        item.name = "Principal"; lens = item;
                    }
                    lenses.add(item);
                }
                if (lens == null && !lenses.isEmpty()) lens = lenses.get(0);
                if (lens == null) throw new IllegalStateException("Nenhuma câmera disponível");
                ListenableFuture<ExtensionsManager> manager = ExtensionsManager.getInstanceAsync(context, provider);
                manager.addListener(() -> {
                    if (closed) return;
                    try { extensions = manager.get(); }
                    catch (Exception ex) { Log.w("Luma", "OEM extensions unavailable", ex); }
                    initializing = false;
                    bind();
                }, main);
            } catch (Exception ex) {
                initializing = false; setBusy(false); fail("Não foi possível abrir a câmera. Feche outros apps de câmera e tente novamente.", ex);
            }
        }, main);
    }

    public boolean extensionAvailable(int mode) {
        if (mode == ExtensionMode.NONE) return true;
        if (extensions == null || lens == null) return false;
        try { return extensions.isExtensionAvailable(lens.selector(), mode); }
        catch (RuntimeException ex) { return false; }
    }

    public void selectLens(LensInfo next) {
        if (isRecording() || busy || next == null) return;
        boolean auto = settings.extension == ExtensionMode.AUTO;
        lens = next; settings.resetLensControls();
        if (auto && extensionAvailable(ExtensionMode.AUTO)) settings.extension = ExtensionMode.AUTO;
        bind();
    }

    public void bind() {
        if (closed || provider == null || lens == null || isRecording()) return;
        // Photo callbacks must finish before releasing their capture session.
        setBusy(true); settings.lock = false;
        meter.stop();
        if (camera != null) {
            camera.getCameraInfo().getCameraState().removeObservers(owner);
            camera.getCameraInfo().getZoomState().removeObservers(owner);
        }
        camera = null;
        imageCapture = null; videoCapture = null;
        autoExposure = 1.0 / 3; appliedEv = 0; requestedExposureIndex = Integer.MIN_VALUE;
        exposurePending = false; lowLightRequested = false; lowLightRejected = false; torchRequested = false;
        lastMeterSample = null;
        int token = ++generation;
        tryBind(token, false);
    }

    private void tryBind(int token, boolean safe) {
        if (closed || token != generation) return;
        provider.unbindAll();
        conservative = safe;
        ultraHdrActive = false; sessionFps = null;
        try {
            CameraSelector selector = lens.selector();
            if (!settings.video && settings.extension == ExtensionMode.AUTO && !extensionAvailable(ExtensionMode.AUTO))
                settings.extension = ExtensionMode.NONE;
            boundExtension = !settings.video && !safe && extensionAvailable(settings.extension)
                    ? settings.extension : ExtensionMode.NONE;
            if (boundExtension != ExtensionMode.NONE) {
                selector = extensions.getExtensionEnabledCameraSelector(selector, boundExtension);
            }
            Preview.Builder pb = new Preview.Builder().setResolutionSelector(new ResolutionSelector.Builder()
                    .setAspectRatioStrategy(settings.video ? AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                            : AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build());
            if (!safe && !isExtension()) {
                sessionFps = lens.frameRate(settings.video, settings.stableVideo);
                // A photo preview may slow down without reducing the JPEG's resolution.
                if (!settings.video && sessionFps != null)
                    new Camera2Interop.Extender<>(pb).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, sessionFps);
            }
            if (!isExtension() && !safe) {
                new Camera2Interop.Extender<>(pb).setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                            @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastTelemetry < 900) return;
                        lastTelemetry = now;
                        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                        Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        if (iso != null && exposure != null) main.execute(() -> {
                            if (!closed && token == generation && !isRecording())
                                listener.onTelemetry("ISO " + iso + "  ·  " + CapturePolicy.shutterLabel(exposure));
                        });
                    }
                });
            }
            Preview preview = pb.build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            if (settings.video) {
                List<Quality> supported = lens.qualities;
                if (supported.isEmpty()) throw new IllegalStateException("A câmera não oferece um perfil de vídeo compatível");
                // Prioritize the A17's documented real-time FHD path; never upscale to a fictitious 4K.
                List<Quality> desired = settings.videoHd || safe
                        ? Arrays.asList(Quality.HD, Quality.FHD, Quality.SD)
                        : Arrays.asList(Quality.FHD, Quality.HD, Quality.SD);
                Quality chosen = null;
                for (Quality q : desired) if (supported.contains(q)) { chosen = q; break; }
                if (chosen == null) chosen = supported.get(0);
                Size size = QualitySelector.getResolution(lens.info, chosen);
                int height = size == null ? 1080 : Math.min(size.getWidth(), size.getHeight());
                Recorder.Builder rb = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(chosen, FallbackStrategy.lowerQualityOrHigherThan(chosen)))
                        .setExecutor(io);
                int bitrate = CapturePolicy.bitrate(height, settings.highBitrate);
                if (!safe) rb.setTargetVideoEncodingBitRate(bitrate);
                VideoCapture.Builder<Recorder> vb = new VideoCapture.Builder<>(rb.build())
                        .setDynamicRange(DynamicRange.SDR)
                        .setMirrorMode(settings.mirror ? MirrorMode.MIRROR_MODE_ON_FRONT_ONLY : MirrorMode.MIRROR_MODE_OFF)
                        .setTargetRotation(rotation);
                if (!safe && sessionFps != null) vb.setTargetFrameRate(sessionFps);
                // Do not combine EIS with OIS: some HALs reject or over-crop that combination.
                boolean useEis = !safe && settings.stabilization && !lens.ois
                        && Recorder.getVideoCapabilities(lens.info).isStabilizationSupported();
                if (useEis) vb.setVideoStabilizationEnabled(true);
                videoCapture = vb.build();
                camera = provider.bindToLifecycle(owner, selector, preview, videoCapture);
                videoDescription = (size == null ? "Vídeo" : size.getWidth() + " × " + size.getHeight())
                        + (safe ? " · automático" : " · " + fpsLabel() + " · " + bitrate / 1_000_000 + " Mb/s");
            } else {
                ImageCapture.Builder ib = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(100).setTargetRotation(rotation);
                if (!safe && settings.ultraHdr) {
                    try {
                        CameraInfo captureInfo = isExtension() ? provider.getCameraInfo(selector) : lens.info;
                        if (ImageCapture.getImageCaptureCapabilities(captureInfo).getSupportedOutputFormats()
                                .contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)) {
                            ib.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR);
                            ultraHdrActive = true;
                        }
                    } catch (RuntimeException ex) { Log.w("Luma", "Ultra HDR capability unavailable", ex); }
                }
                if (!isExtension() && !safe) {
                    ResolutionSelector.Builder resolution = new ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY);
                    if (settings.detail) {
                        resolution.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY);
                    } else {
                        resolution.setResolutionStrategy(new ResolutionStrategy(lens.balancedPhotoSize(),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER));
                    }
                    ib.setResolutionSelector(resolution.build());
                }
                imageCapture = ib.build();
                imageCapture.setFlashMode(!isExtension() && lens.info.hasFlashUnit() && !settings.manualExposure
                        ? settings.flash : ImageCapture.FLASH_MODE_OFF);
                camera = provider.bindToLifecycle(owner, selector, preview, imageCapture);
            }
            setBusy(false);
            if (!safe && !isExtension()) applyControls();
            startExposureMeter(token);
            camera.getCameraInfo().getCameraState().observe(owner, state -> {
                if (token != generation || closed) return;
                CameraState.StateError error = state.getError();
                if (error != null) {
                    Log.w("Luma", "Camera state error " + error.getCode(), error.getCause());
                    listener.onError("Câmera interrompida. Se a imagem não voltar, toque em Tentar novamente.");
                }
            });
            listener.onReady();
            if (safe) listener.onNotice("Perfil compatível ativado; alguns ajustes avançados ficaram indisponíveis.");
            else if (settings.extension != ExtensionMode.NONE && !isExtension() && !settings.video)
                listener.onNotice("Este modo não está disponível nesta lente. Usando Foto.");
        } catch (RuntimeException ex) {
            Log.w("Luma", "Bind failed. Compatible=" + safe, ex);
            if (!safe) { handler.post(() -> tryBind(token, true)); }
            else {
                camera = null; imageCapture = null; videoCapture = null;
                setBusy(false); fail("Não foi possível iniciar esta câmera. Tente outra lente ou toque em Tentar novamente.", ex);
            }
        }
    }

    public void applyControls() {
        if (camera == null || isExtension() || conservative) return;
        CaptureRequestOptions.Builder options = new CaptureRequestOptions.Builder();
        if (lens.ois) options.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                settings.stabilization ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        // Keep the manufacturer's per-template noise reduction, sharpening and tone mapping.
        // A repeating FAST/HIGH_QUALITY override also affects still requests and can erase detail.
        if (lens.wb(settings.whiteBalance)) options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, settings.whiteBalance);
        if (lens.antibanding(settings.antibanding)) options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, settings.antibanding);
        if (lens.aeLock) options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, settings.lock && !settings.manualExposure);
        if (lens.awbLock) options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, settings.lock);
        if (!settings.video && settings.manualExposure && lens.manualAvailable()) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, lens.isoRange.clamp(settings.iso));
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, lens.shutterRange.clamp(settings.shutterNs));
        }
        if (settings.focusDistance >= 0 && lens.minFocus > 0) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, Math.min(lens.minFocus, settings.focusDistance));
        }
        Camera target = camera;
        ListenableFuture<Void> request = Camera2CameraControl.from(target.getCameraControl()).setCaptureRequestOptions(options.build());
        request.addListener(() -> {
            try { request.get(); }
            catch (Exception ex) {
                if (!closed && target == camera) listener.onNotice("Um ajuste não foi aceito pela câmera. Volte ao automático se necessário.");
            }
        }, main);
        if (imageCapture != null) imageCapture.setFlashMode(lens.info.hasFlashUnit() && !settings.manualExposure ? settings.flash : ImageCapture.FLASH_MODE_OFF);
        updateLowLight();
        if (!settings.manualExposure) applyExposure();
    }

    public void setExposure(double ev) {
        settings.userExposureOverride = true; settings.ev = ev;
        applyExposure();
    }

    public void resetExposure() {
        settings.userExposureOverride = false; settings.ev = 0; autoExposure = 1.0 / 3;
        requestedExposureIndex = Integer.MIN_VALUE;
        applyExposure();
    }

    private void applyExposure() {
        if (camera == null || settings.manualExposure || isExtension() || conservative || settings.lock) return;
        ExposureState state = camera.getCameraInfo().getExposureState();
        if (!state.isExposureCompensationSupported()) return;
        double ev = isAutoExposure() ? autoExposure : settings.ev;
        int index = CapturePolicy.exposureIndex(ev, state.getExposureCompensationStep().doubleValue(),
                state.getExposureCompensationRange().getLower(), state.getExposureCompensationRange().getUpper());
        if (index == requestedExposureIndex) return;
        requestedExposureIndex = index; exposurePending = true;
        Camera target = camera;
        var request = target.getCameraControl().setExposureCompensationIndex(index);
        request.addListener(() -> {
            if (closed || target != camera || requestedExposureIndex != index) return;
            exposurePending = false;
            try {
                request.get(); appliedEv = index * state.getExposureCompensationStep().doubleValue();
                listener.onExposureChanged();
            } catch (Exception ex) { requestedExposureIndex = Integer.MIN_VALUE; }
        }, main);
    }

    private void startExposureMeter(int token) {
        meter.start(() -> !closed && token == generation && camera != null && !busy && !exposurePending
                        && isAutoExposure() && !settings.lock && !stopping && SystemClock.elapsedRealtime() >= meterPausedUntil
                        && owner.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                        && previewView.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING,
                () -> isRecording() ? 2800L : 1200L, sample -> {
                    lastMeterSample = sample;
                    autoExposure = ExposureAdvisor.next(autoExposure, sample, settings.video);
                    applyExposure();
                });
    }

    private void updateLowLight() {
        if (camera == null || isExtension() || conservative || !camera.getCameraInfo().isLowLightBoostSupported()) return;
        // This API brightens video/preview streams; do not make a JPEG promise based on it.
        boolean desired = settings.lowLight && settings.video && !settings.manualExposure && !torchRequested && !lowLightRejected;
        if (desired == lowLightRequested) return;
        lowLightRequested = desired;
        Camera target = camera;
        var request = target.getCameraControl().enableLowLightBoostAsync(desired);
        request.addListener(() -> {
            try { request.get(); }
            catch (Exception ex) {
                if (!closed && target == camera && lowLightRequested == desired && desired) {
                    lowLightRequested = false; lowLightRejected = true;
                    Log.w("Luma", "Low-light boost rejected; keeping normal AE", ex);
                }
            }
        }, main);
    }

    public void zoom(float ratio) {
        if (camera == null || camera.getCameraInfo().getZoomState().getValue() == null) return;
        var state = camera.getCameraInfo().getZoomState().getValue();
        observeControl(camera.getCameraControl().setZoomRatio(Math.max(state.getMinZoomRatio(), Math.min(state.getMaxZoomRatio(), ratio))));
    }

    public void focus(float x, float y, boolean lock) {
        if (camera == null || busy || conservative || isExtension()) return;
        settings.lock = false; settings.focusDistance = -1;
        meterPausedUntil = SystemClock.elapsedRealtime() + 6000;
        applyControls();
        MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
        int flags = FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB;
        if (lens.minFocus > 0) flags |= FocusMeteringAction.FLAG_AF;
        FocusMeteringAction.Builder action = new FocusMeteringAction.Builder(point, flags);
        if (lock) action.disableAutoCancel(); else action.setAutoCancelDuration(5, TimeUnit.SECONDS);
        Camera target = camera;
        if (target.getCameraInfo().isFocusMeteringSupported(action.build())) {
            var future = target.getCameraControl().startFocusAndMetering(action.build());
            future.addListener(() -> {
                try {
                    future.get();
                    if (!closed && target == camera && lock) {
                        settings.lock = true; applyControls(); listener.onReady();
                    }
                } catch (Exception ignored) { /* A new tap or a switch cancels metering. */ }
            }, main);
        }
    }

    public void unlock() {
        settings.lock = false;
        if (camera != null) observeControl(camera.getCameraControl().cancelFocusAndMetering());
        applyControls();
    }

    public void torch(boolean on) {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchRequested = on;
        Camera target = camera;
        if (on && lowLightRequested) {
            lowLightRequested = false;
            var disable = target.getCameraControl().enableLowLightBoostAsync(false);
            disable.addListener(() -> {
                try { disable.get(); if (!closed && camera == target && torchRequested) observeControl(target.getCameraControl().enableTorch(true)); }
                catch (Exception ex) { if (!closed && camera == target) listener.onNotice("Não foi possível ligar a luz. Tente novamente."); }
            }, main);
        } else {
            var request = target.getCameraControl().enableTorch(on);
            request.addListener(() -> {
                try { request.get(); if (!closed && camera == target && !torchRequested) updateLowLight(); }
                catch (Exception ignored) { }
            }, main);
        }
    }

    private void observeControl(ListenableFuture<?> future) {
        future.addListener(() -> { try { future.get(); } catch (Exception ignored) {
            // Camera switches legitimately cancel controls. Camera-state observer reports real failures.
        } }, main);
    }

    public void takePhoto() {
        if (imageCapture == null || busy || closed || isRecording()) return;
        setBusy(true); imageCapture.setTargetRotation(rotation);
        ContentValues values = mediaValues(false);
        ImageCapture.Metadata metadata = new ImageCapture.Metadata();
        metadata.setReversedHorizontal(lens.front && settings.mirror);
        var output = new ImageCapture.OutputFileOptions.Builder(context.getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).setMetadata(metadata).build();
        try {
            imageCapture.takePicture(output, main, new ImageCapture.OnImageSavedCallback() {
                @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) {
                    if (closed) return;
                    setBusy(false);
                    if (result.getSavedUri() != null) listener.onSaved(result.getSavedUri(), false);
                    else listener.onError("A câmera não retornou o arquivo salvo. Confira a galeria.");
                }
                @Override public void onError(@NonNull ImageCaptureException exception) {
                    if (closed) return;
                    setBusy(false); fail("Não foi possível salvar a foto. Confira o espaço livre e tente novamente.", exception);
                }
            });
        } catch (RuntimeException ex) { setBusy(false); fail("Não foi possível capturar esta foto. Tente novamente.", ex); }
    }

    public void startRecording() {
        if (videoCapture == null || busy || isRecording() || closed) return;
        videoCapture.setTargetRotation(rotation);
        var output = new MediaStoreOutputOptions.Builder(context.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(mediaValues(true)).build();
        try {
            PendingRecording pending = videoCapture.getOutput().prepareRecording(context, output);
            if (settings.sound && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                pending = pending.withAudioEnabled();
            pendingVideo = true; paused = false; durationMillis = 0; recordingBytes = 0;
            listener.onRecording(true, false, 0, 0);
            recording = pending.start(main, this::videoEvent);
        } catch (RuntimeException ex) {
            pendingVideo = false; stopping = false; recording = null;
            listener.onRecording(false, false, 0, 0);
            fail("Não foi possível gravar. Tente o perfil de vídeo padrão ou confira o microfone.", ex);
        }
    }

    private void videoEvent(VideoRecordEvent event) {
        if (closed && !(event instanceof VideoRecordEvent.Finalize)) return;
        durationMillis = event.getRecordingStats().getRecordedDurationNanos() / 1_000_000;
        recordingBytes = event.getRecordingStats().getNumBytesRecorded();
        if (event instanceof VideoRecordEvent.Start) pendingVideo = false;
        if (event instanceof VideoRecordEvent.Pause) paused = true;
        if (event instanceof VideoRecordEvent.Resume) paused = false;
        if (event instanceof VideoRecordEvent.Finalize end) {
            recording = null; pendingVideo = false; stopping = false; paused = false;
            if (closed) { io.shutdown(); return; }
            listener.onRecording(false, false, durationMillis, recordingBytes);
            Uri uri = end.getOutputResults().getOutputUri();
            boolean usablePartial = end.getError() == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                    || end.getError() == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                    || end.getError() == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE;
            if ((!end.hasError() || usablePartial) && !Uri.EMPTY.equals(uri) && recordingBytes > 0) {
                listener.onSaved(uri, true);
                if (end.hasError()) listener.onNotice("Gravação interrompida; o trecho disponível foi salvo.");
            } else {
                // Failed empty recordings must not leave broken entries in the gallery.
                if (!Uri.EMPTY.equals(uri)) io.execute(() -> { try { context.getContentResolver().delete(uri, null, null); } catch (RuntimeException ignored) {} });
                fail("O vídeo não foi concluído. Tente novamente com qualidade padrão.", end.getCause());
            }
        } else if (!closed) listener.onRecording(true, paused, durationMillis, recordingBytes);
    }

    public void stopRecording() {
        if (recording != null && !stopping) { stopping = true; recording.stop(); }
    }
    public void pauseRecording() {
        if (recording == null || pendingVideo || stopping) return;
        if (paused) recording.resume(); else recording.pause();
    }

    private ContentValues mediaValues(boolean video) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "LUMA_" + stamp + (video ? ".mp4" : ".jpg"));
        values.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/LumaA17");
        return values;
    }

    public String sessionDescription() {
        if (settings.video) return videoDescription;
        String format = ultraHdrActive ? "Ultra HDR" : "JPEG 100%";
        String mode = boundExtension == ExtensionMode.AUTO ? " · Auto nativo" : "";
        if (imageCapture == null || imageCapture.getResolutionInfo() == null) return "Foto · " + format + mode;
        Size size = imageCapture.getResolutionInfo().getResolution();
        return String.format(Locale.US, "%.1f MP · %s%s", size.getWidth() * (double) size.getHeight() / 1_000_000, format, mode);
    }

    private String fpsLabel() {
        if (sessionFps == null) return "fps automático";
        return sessionFps.getLower().equals(sessionFps.getUpper()) ? "alvo " + sessionFps.getUpper() + " fps"
                : sessionFps.getLower() + "–" + sessionFps.getUpper() + " fps adaptativo";
    }

    public String diagnostic() throws Exception {
        JSONObject out = new JSONObject().put("app", "Luma A17 " + BuildConfig.VERSION_NAME)
                .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("cameraX", "1.5.3").put("currentSession", sessionDescription())
                .put("compatibleFallback", conservative).put("extensionMode", boundExtension)
                .put("targetFpsRange", String.valueOf(sessionFps)).put("ultraHdr", ultraHdrActive)
                .put("lowLightBoostRequested", lowLightRequested).put("lowLightBoostRejected", lowLightRejected)
                .put("smartExposure", isAutoExposure()).put("appliedExposureEv", appliedEv)
                .put("previewMedian", lastMeterSample == null ? JSONObject.NULL : lastMeterSample.median)
                .put("previewHighlights", lastMeterSample == null ? JSONObject.NULL : lastMeterSample.highlights)
                .put("previewClippedFraction", lastMeterSample == null ? JSONObject.NULL : lastMeterSample.clipped)
                .put("qualityCalibratedOnPhysicalA17", false);
        JSONArray cameras = new JSONArray();
        for (LensInfo item : lenses) {
            JSONObject info = item.report();
            try {
                info.put("hdrExtension", extensions != null && extensions.isExtensionAvailable(item.selector(), ExtensionMode.HDR));
                info.put("nightExtension", extensions != null && extensions.isExtensionAvailable(item.selector(), ExtensionMode.NIGHT));
                info.put("autoExtension", extensions != null && extensions.isExtensionAvailable(item.selector(), ExtensionMode.AUTO));
            } catch (RuntimeException ignored) { info.put("extensionQueryFailed", true); }
            cameras.put(info);
        }
        return out.put("cameras", cameras).toString(2);
    }

    private void setBusy(boolean value) { busy = value; if (!closed) listener.onBusy(value); }
    private void fail(String text, Throwable ex) { Log.e("Luma", text, ex); if (!closed) listener.onError(text); }

    public void close() {
        stopRecording(); closed = true; ++generation;
        handler.removeCallbacksAndMessages(null);
        meter.stop();
        if (provider != null) provider.unbindAll();
        if (!isRecording()) io.shutdown();
    }
}
