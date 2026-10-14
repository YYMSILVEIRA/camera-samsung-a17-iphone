package com.ib2s.lumaa17;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.Settings;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.ExposureState;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ZoomState;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CameraActivity extends ComponentActivity implements CameraEngine.Listener, SensorEventListener {
    private static final int BG = 0xFF101113, PANEL = 0xFF202226, GOLD = 0xFFE8C97E, MUTED = 0xFFACAFB5;
    private CameraEngine engine;
    private CameraSettings settings;
    private SharedPreferences preferences;
    private PreviewView preview;
    private CameraOverlay overlay;
    private PreviewFrame previewFrame;
    private LinearLayout root, modes, tools, processingPanel;
    private ProgressBar processingBar;
    private TextView processingLabel;
    private int lastProcessingPercent=-1;
    private TextView session, status, telemetry, evLabel, zoomLabel, recordingLabel;
    private Button flashButton, timerButton, lensButton, switchButton, settingsButton, proButton, unlockButton, retryButton, pauseButton;
    private SeekBar evBar;
    private ShutterView shutter;
    private ImageView thumbnail;
    private CountDownTimer timer;
    private OrientationEventListener orientation;
    private SensorManager sensorManager;
    private Sensor gravitySensor;
    private Uri lastUri;
    private boolean lastVideo, torchOn, updatingUi, destroyed, scaleActive;
    private String reportToSave;
    private volatile boolean geminiInProgress, localInProgress;
    private Uri lastOriginalUri;
    private float roll;
    private float touchFocusX = Float.NaN, touchFocusY = Float.NaN;
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<String> cameraPermission, audioPermission, diagnosticDocument;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("luma_camera", MODE_PRIVATE);
        settings = new CameraSettings(preferences);
        registerResults();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        buildUi();
        applyImmersive();
        engine = new CameraEngine(this, this, preview, settings, this);
        setupGestures();
        orientation = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int degrees) {
                if (degrees == ORIENTATION_UNKNOWN || engine == null) return;
                int r = CapturePolicy.rotationDegrees(degrees);
                engine.setRotation(r == 90 ? Surface.ROTATION_90 : r == 180 ? Surface.ROTATION_180 : r == 270 ? Surface.ROTATION_270 : Surface.ROTATION_0);
            }
        };
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (gravitySensor == null) gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (timer != null) { cancelTimer(); return; }
                if (engine.isRecording()) { engine.stopRecording(); return; }
                if (engine.isBusy()) { toast("Aguarde a câmera concluir a captura."); return; }
                finish();
            }
        });
        if (hasCamera()) engine.initialize(); else cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private void registerResults() {
        cameraPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) { retryButton.setVisibility(View.GONE); engine.initialize(); }
            else {
                onError("Permita o acesso à câmera para fotografar e gravar.");
                retryButton.setText("Permitir câmera");
            }
        });
        audioPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return;
            if (granted) engine.startRecording();
            else new AlertDialog.Builder(this).setTitle("Gravar sem áudio?")
                    .setMessage("O microfone não foi autorizado. Você pode gravar este vídeo sem som ou permitir o microfone nas configurações do Android.")
                    .setPositiveButton("Sem áudio", (d,w) -> engine.startRecording())
                    .setNegativeButton("Cancelar", null).show();
        });
        diagnosticDocument = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
            if (uri == null || reportToSave == null) return;
            String text = reportToSave;
            files.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException();
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    runOnUiThread(() -> toast("Diagnóstico salvo."));
                } catch (Exception ex) { runOnUiThread(() -> toast("Não foi possível salvar o diagnóstico.")); }
            });
        });
    }

    private void buildUi() {
        root = column(); root.setBackgroundColor(BG); root.setPadding(dp(14), 0, dp(14), 0);
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            root.setPadding(dp(14) + bars.left, bars.top, dp(14) + bars.right, bars.bottom + dp(4));
            return insets;
        });
        LinearLayout header = row();
        TextView brand = text("LUMA", 20, Color.WHITE); brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD); brand.setLetterSpacing(.16f);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView model = text("A17", 12, GOLD); model.setLetterSpacing(.12f); header.addView(model, new LinearLayout.LayoutParams(dp(48), dp(50)));
        settingsButton = button("Ajustes", "Abrir ajustes da câmera", v -> showSettings());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(88), dp(48))); root.addView(header);

        LinearLayout top = row();
        flashButton = button("Flash off", "Mudar flash", v -> changeFlash());
        timerButton = button("Timer off", "Mudar temporizador", v -> changeTimer());
        proButton = button("Controles", "Exposição, cores e foco", v -> showControls());
        top.addView(flashButton, weighted(dp(48))); top.addView(timerButton, weighted(dp(48))); top.addView(proButton, weighted(dp(48)));
        root.addView(top);

        session = text("Preparando câmera…", 11, MUTED); session.setGravity(Gravity.CENTER); session.setMaxLines(1);
        previewFrame = new PreviewFrame();
        preview = new PreviewView(this); preview.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        preview.setScaleType(settings.fullscreenPreview ? PreviewView.ScaleType.FILL_CENTER : PreviewView.ScaleType.FIT_CENTER);
        preview.setContentDescription("Visor da câmera. Toque para foco e exposição; mantenha pressionado para travar; use dois dedos para zoom.");
        overlay = new CameraOverlay(this); applyOverlay();
        previewFrame.addView(preview); previewFrame.addView(overlay);
        FrameLayout hud = new FrameLayout(this);
        session.setBackgroundColor(0x88000000);
        hud.addView(session, new FrameLayout.LayoutParams(-1, dp(26), Gravity.TOP));
        LinearLayout lowerHud = column(); lowerHud.setPadding(dp(8),0,dp(8),0); lowerHud.setBackgroundColor(0x88000000);
        hud.addView(lowerHud,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        previewFrame.addView(hud);
        root.addView(previewFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        processingPanel = column();
        processingPanel.setPadding(dp(12),dp(5),dp(12),dp(5));
        processingPanel.setBackgroundColor(PANEL);
        processingLabel = text("Processamento 0%", 13, Color.WHITE);
        processingPanel.addView(processingLabel, new LinearLayout.LayoutParams(-1, dp(25)));
        processingBar = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        processingBar.setMax(100); processingBar.setProgress(0);
        processingBar.setProgressTintList(ColorStateList.valueOf(GOLD));
        processingPanel.addView(processingBar,new LinearLayout.LayoutParams(-1,dp(12)));
        processingPanel.setVisibility(View.GONE);
        root.addView(processingPanel,new LinearLayout.LayoutParams(-1,dp(51)));

        recordingLabel = text("", 14, 0xFFFF6B6B); recordingLabel.setGravity(Gravity.CENTER);
        recordingLabel.setTypeface(Typeface.MONOSPACE); recordingLabel.setVisibility(View.GONE);
        root.addView(recordingLabel, new LinearLayout.LayoutParams(-1, dp(26)));
        LinearLayout info = row();
        telemetry = text("Toque para foco e exposição", 11, MUTED); info.addView(telemetry, new LinearLayout.LayoutParams(0, dp(30), 1));
        unlockButton = button("Destravar", "Destravar foco e exposição", v -> {
            engine.unlock(); overlay.locked = false; overlay.invalidate(); refresh();
        });
        unlockButton.setVisibility(View.GONE); info.addView(unlockButton, new LinearLayout.LayoutParams(dp(98), dp(48))); lowerHud.addView(info);

        tools = row();
        lensButton = button("Principal", "Escolher lente disponível", v -> selectLens());
        zoomLabel = text("1,0×", 14, GOLD); zoomLabel.setGravity(Gravity.CENTER); zoomLabel.setBackground(pill(PANEL, 22));
        zoomLabel.setContentDescription("Zoom. Toque para alternar entre um e dois vezes.");
        zoomLabel.setOnClickListener(v -> {
            if (!available()) return;
            ZoomState z = engine.camera.getCameraInfo().getZoomState().getValue();
            if (z != null) engine.zoom(z.getZoomRatio() < 1.5f ? 2f : 1f);
        });
        pauseButton = button("Pausar", "Pausar ou retomar vídeo", v -> engine.pauseRecording()); pauseButton.setVisibility(View.GONE);
        tools.addView(lensButton, weighted(dp(48))); tools.addView(zoomLabel, new LinearLayout.LayoutParams(dp(68), dp(48)));
        tools.addView(pauseButton, weighted(dp(48)));
        TextView zoomHint = text("Zoom por pinça", 11, MUTED); zoomHint.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        tools.addView(zoomHint, weighted(dp(48))); lowerHud.addView(tools);

        LinearLayout exposure = row();
        evLabel = text("EV 0,0", 12, GOLD); evLabel.setContentDescription("Compensação de exposição");
        exposure.addView(evLabel, new LinearLayout.LayoutParams(dp(65), dp(38)));
        evBar = new SeekBar(this); evBar.setContentDescription("Clarear ou escurecer a imagem");
        evBar.setProgressTintList(ColorStateList.valueOf(GOLD)); evBar.setThumbTintList(ColorStateList.valueOf(GOLD));
        exposure.addView(evBar, new LinearLayout.LayoutParams(0, dp(38), 1)); root.addView(exposure);
        evBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user || updatingUi || !available()) return;
                ExposureState e = engine.camera.getCameraInfo().getExposureState();
                engine.setExposure((p + e.getExposureCompensationRange().getLower()) * e.getExposureCompensationStep().doubleValue());
                evLabel.setText(String.format(Locale.getDefault(), "EV %+.1f", settings.ev));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        HorizontalScrollView modeScroll = new HorizontalScrollView(this); modeScroll.setHorizontalScrollBarEnabled(false);
        modeScroll.setFillViewport(true); modes = row(); modes.setGravity(Gravity.CENTER);
        modeScroll.addView(modes, new FrameLayout.LayoutParams(-1, dp(48))); root.addView(modeScroll);

        LinearLayout captureRow = row(); captureRow.setGravity(Gravity.CENTER);
        thumbnail = new ImageView(this); thumbnail.setContentDescription("Abrir a última foto ou vídeo");
        thumbnail.setImageResource(R.drawable.ic_luma); thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackground(pill(PANEL, 14)); thumbnail.setClipToOutline(true); thumbnail.setOnClickListener(v -> openLast());
        thumbnail.setOnLongClickListener(v -> {
            if(lastOriginalUri==null) {toast("Tire uma foto antes de abrir as opções.");return true;}
            showReview(lastOriginalUri);return true;
        });
        FrameLayout thumbBox = new FrameLayout(this);
        FrameLayout.LayoutParams thumbParams = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER);
        thumbBox.addView(thumbnail, thumbParams); captureRow.addView(thumbBox, weighted(dp(92)));
        shutter = new ShutterView(); shutter.setOnClickListener(v -> capture());
        captureRow.addView(shutter, new LinearLayout.LayoutParams(dp(92), dp(92)));
        switchButton = button("↻", "Alternar câmera frontal e traseira", v -> flipCamera()); switchButton.setTextSize(32);
        captureRow.addView(switchButton, weighted(dp(92))); root.addView(captureRow);
        status = text("", 11, MUTED); status.setGravity(Gravity.CENTER); status.setMaxLines(2);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(32)));
        retryButton = button("Tentar novamente", "Tentar abrir a câmera novamente", v -> retryCamera());
        retryButton.setVisibility(View.GONE); root.addView(retryButton, new LinearLayout.LayoutParams(-1, dp(48)));
        renderModes();
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // GestureDetector routes actual taps to performClick below.
    private void setupGestures() {
        preview.setOnClickListener(v -> {
            if (!available()) return;
            float x = Float.isNaN(touchFocusX) ? preview.getWidth()/2f : touchFocusX;
            float y = Float.isNaN(touchFocusY) ? preview.getHeight()/2f : touchFocusY;
            touchFocusX = Float.NaN; touchFocusY = Float.NaN;
            engine.focus(x,y,false); overlay.focus(x,y,false); refresh();
        });
        ScaleGestureDetector scale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaleActive = true; return available(); }
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (available()) {
                    ZoomState state = engine.camera.getCameraInfo().getZoomState().getValue();
                    if (state != null) engine.zoom(state.getZoomRatio() * d.getScaleFactor());
                }
                return true;
            }
        });
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (!scaleActive && available()) {
                    touchFocusX = e.getX(); touchFocusY = e.getY(); preview.performClick();
                }
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (!scaleActive && available() && !engine.isExtension() && !engine.isConservative()) {
                    engine.focus(e.getX(), e.getY(), true); overlay.focus(e.getX(), e.getY(), true);
                    preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); refresh();
                }
            }
        });
        preview.setOnTouchListener((v,e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) scaleActive = false;
            scale.onTouchEvent(e); gestures.onTouchEvent(e); return true;
        });
    }

    private void capture() {
        if (engine.isRecording()) { engine.stopRecording(); return; }
        if (timer != null) { cancelTimer(); return; }
        if (!available()) return;
        if (settings.video) {
            if (settings.sound && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                audioPermission.launch(Manifest.permission.RECORD_AUDIO);
            else engine.startRecording();
            return;
        }
        if (settings.timerSeconds == 0) { engine.takePhoto(); return; }
        timer = new CountDownTimer(settings.timerSeconds * 1000L, 100) {
            @Override public void onTick(long millis) { overlay.countdown(String.valueOf((millis + 999) / 1000)); }
            @Override public void onFinish() {
                timer = null; overlay.countdown(""); refresh();
                if (getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) engine.takePhoto();
            }
        }.start(); refresh();
    }

    private void cancelTimer() {
        if (timer != null) { timer.cancel(); timer = null; }
        overlay.countdown(""); refresh();
    }
    private void switchMode(boolean video, int extension) {
        if (!available() || engine.isRecording() || timer != null) return;
        settings.video = video; settings.extension = extension; settings.portraitMode = false; settings.manualExposure = false;
        settings.focusDistance = -1; settings.lock = false; torchOn = false;
        overlay.locked = false; overlay.invalidate();
        if (extension != ExtensionMode.NONE) { settings.whiteBalance = CaptureRequest.CONTROL_AWB_MODE_AUTO; settings.ev = 0; }
        engine.bind(); renderModes();
    }
    private void renderModes() {
        modes.removeAllViews();
        modeButton("FOTO", false, ExtensionMode.NONE);
        Button portrait = button("RETRATO", "Modo retrato local offline com segmentação por IA", v -> switchPortrait());
        boolean chosenPortrait = settings.portraitMode && !settings.video;
        portrait.setTextColor(chosenPortrait ? GOLD : MUTED);
        portrait.setTypeface(Typeface.DEFAULT, chosenPortrait ? Typeface.BOLD : Typeface.NORMAL);
        portrait.setBackground(pill(chosenPortrait ? PANEL : BG, 20));
        portrait.setEnabled(engine == null || (!engine.isBusy() && !engine.isRecording() && timer == null));
        modes.addView(portrait, new LinearLayout.LayoutParams(dp(100), dp(46)));
        modeButton("VÍDEO", true, ExtensionMode.NONE);
        if (engine != null && engine.extensionAvailable(ExtensionMode.HDR)) modeButton("HDR", false, ExtensionMode.HDR);
        if (engine != null && engine.extensionAvailable(ExtensionMode.NIGHT)) modeButton("NOITE", false, ExtensionMode.NIGHT);
    }
    private void modeButton(String name, boolean video, int extension) {
        Button b = button(name, "Modo " + name, v -> switchMode(video, extension));
        boolean selected = settings.video == video && (video || (!settings.portraitMode && settings.extension == extension));
        b.setTextColor(selected ? GOLD : MUTED); b.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        b.setBackground(pill(selected ? PANEL : BG, 20));
        b.setEnabled(engine == null || (!engine.isBusy() && !engine.isRecording() && timer == null));
        modes.addView(b, new LinearLayout.LayoutParams(dp(80), dp(46)));
    }

    private void switchPortrait() {
        if (!canChange()) return;
        settings.video = false; settings.extension = ExtensionMode.NONE;
        settings.portraitMode = true; settings.manualExposure = false;
        torchOn = false;
        engine.bind(); renderModes();
    }

    private void flipCamera() {
        if (!canChange()) return;
        LensInfo next = null;
        for (LensInfo item : engine.lenses) if (item.front != engine.lens.front) {
            if (next == null || item.name.equals("Principal")) next = item;
        }
        if (next != null) { torchOn = false; engine.selectLens(next); }
        else toast("Não há outra câmera disponível.");
    }
    private void selectLens() {
        if (!canChange()) return;
        String[] names = new String[engine.lenses.size()];
        for (int i=0;i<names.length;i++) names[i] = engine.lenses.get(i).name;
        new AlertDialog.Builder(this).setTitle("Lentes disponíveis")
                .setItems(names, (d,which) -> { torchOn = false; engine.selectLens(engine.lenses.get(which)); }).show();
    }
    private void changeFlash() {
        if (!available() || timer != null || !engine.lens.info.hasFlashUnit()) return;
        if (settings.video) { torchOn = !torchOn; engine.torch(torchOn); }
        else if (!engine.isExtension() && !engine.isConservative() && !settings.manualExposure) {
            settings.flash = settings.flash == ImageCapture.FLASH_MODE_OFF ? ImageCapture.FLASH_MODE_AUTO
                    : settings.flash == ImageCapture.FLASH_MODE_AUTO ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF;
            engine.applyControls();
        }
        refresh();
    }
    private void changeTimer() {
        if (!canChange()) return;
        settings.timerSeconds = settings.timerSeconds == 0 ? 3 : settings.timerSeconds == 3 ? 10 : 0;
        settings.save(preferences); refresh();
    }

    @Override public void onReady() {
        if (destroyed) return;
        retryButton.setVisibility(View.GONE); status.setText("Pronto · " + engine.lens.name);
        engine.camera.getCameraInfo().getZoomState().removeObservers(this);
        engine.camera.getCameraInfo().getZoomState().observe(this, value -> {
            if (value != null) zoomLabel.setText(String.format(Locale.getDefault(), "%.1f×", value.getZoomRatio()));
        });
        renderModes(); refresh();
    }
    @Override public void onBusy(boolean busy) {
        if (destroyed) return;
        if (busy) status.setText(engine != null && engine.camera != null ? "Capturando… mantenha o celular firme" : "Preparando câmera…");
        refresh();
    }
    @Override public void onNotice(String text) { if (!destroyed) { status.setText(text); toast(text); } }
    @Override public void onError(String text) {
        if (destroyed) return;
        status.setText(text); retryButton.setVisibility(View.VISIBLE); refresh(); toast(text);
    }
    @Override public void onTelemetry(String text) { if (!destroyed) telemetry.setText(text); }
    @Override public void onSaved(Uri uri, boolean video) {
        if (destroyed) return;
        lastUri = uri; lastVideo = video;
        if (!video) lastOriginalUri = uri;
        status.setText(video ? "Vídeo salvo na galeria" : "Foto original salva na galeria");
        shutter.performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.KEYBOARD_TAP);
        files.execute(() -> {
            try {
                Bitmap bitmap = getContentResolver().loadThumbnail(uri, new Size(150,150), null);
                runOnUiThread(() -> { if (!destroyed && uri.equals(lastUri)) thumbnail.setImageBitmap(bitmap); });
            } catch (Exception ignored) { /* A successful capture must not depend on its thumbnail. */ }
        });
        if (!video) {
            boolean automatic = settings.portraitMode || settings.iphoneAutomatic || settings.localAutomatic;
            if(settings.portraitMode) processLocal(uri,settings.localPreset,true,false);
            else if(settings.iphoneAutomatic) processIphone(uri,settings.iphoneProfile);
            else if(settings.localAutomatic) processLocal(uri,settings.localPreset,false,false);
            if(settings.reviewAfterCapture && !automatic) showReview(uri);
            if(settings.geminiAutomatic) improvePhoto(uri,false);
        }
        refresh();
    }

    private void showReview(Uri original) {
        String[] actions = {"Ver foto original", "Estilo iPhone Local (cores / redução de ruído)",
                "Super-resolução neural EDSR ×4", "Ajuste de cor tradicional",
                "Retrato IA · fundo desfocado", "Salvar cópia PNG", "Salvar cópia HEIC",
                "Editar cópia no Gemini"};
        new AlertDialog.Builder(this).setTitle("Revisar fotografia")
            .setItems(actions, (dialog, which) -> {
                switch (which) {
                    case 0:
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(original,"image/jpeg")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                        } catch (ActivityNotFoundException ex) { toast("Abra a galeria para visualizar a foto."); }
                        break;
                    case 1: pickIphoneProfile(original); break;
                    case 2: startSuperResolution(original); break;
                    case 3: processLocal(original, Math.max(1, settings.localPreset), false, false); break;
                    case 4: processLocal(original, settings.localPreset, true, false); break;
                    case 5: processLocal(original, 0, false, true); break;
                    case 6: exportHeic(original); break;
                    case 7: improvePhoto(original,true); break;
                    default: break;
                }
            }).setNegativeButton("Voltar à câmera", null).show();
    }

    private void showProcessing(int percent, String stage) {
        runOnUiThread(() -> {
            if (destroyed || processingPanel == null) return;
            int value = Math.max(0, Math.min(100, percent));
            if (value == lastProcessingPercent && value != 0) return;
            lastProcessingPercent = value;
            processingPanel.setVisibility(View.VISIBLE);
            processingBar.setProgress(value);
            processingLabel.setText(value + "% · " + stage);
            status.setText(stage + " (" + value + "%)");
        });
    }

    private void finishProcessing() {
        processingPanel.setVisibility(View.GONE);
        lastProcessingPercent = -1;
    }

    private void showProcessed(Uri original, Uri processed, String message) {
        if (destroyed || isFinishing()) return;
        lastUri = processed; lastVideo = false;
        files.execute(() -> {
            try {
                Bitmap img = getContentResolver().loadThumbnail(processed,new Size(150,150),null);
                runOnUiThread(() -> {
                    if (!destroyed && processed.equals(lastUri)) thumbnail.setImageBitmap(img);
                });
            } catch(Exception ignored) {}
        });
        PhotoResultDialog.show(this,files,original,processed,message);
    }

    private void pickIphoneProfile(Uri original) {
        String[] presets={"Natural · textura original","Padrão · cores equilibradas","Noturno · ruído reduzido"};
        new AlertDialog.Builder(this).setTitle("Estilo iPhone · offline")
            .setItems(presets,(d,which)->processIphone(original,which))
            .setNegativeButton("Cancelar",null).show();
    }

    private void processIphone(Uri original,int profile) {
        if(localInProgress) {
            toast("Outra foto está sendo processada. O original foi salvo.");
            return;
        }
        localInProgress=true;
        showProcessing(0,"Preparando estilo iPhone offline");
        files.execute(()->{
            try {
                IphoneLookProcessor.Result result=IphoneLookProcessor.process(
                        getApplicationContext(),original,profile,this::showProcessing);
                runOnUiThread(()->{
                    localInProgress=false;
                    if(destroyed)return;
                    finishProcessing();
                    status.setText("Estilo iPhone "+IphoneLookProcessor.profileName(profile)
                            +" salvo · "+result.width+"×"+result.height);
                    showProcessed(original,result.uri,result.details);
                });
            } catch(Throwable error) {
                android.util.Log.e("LumaIphoneLook","Local photo processing failed",error);
                runOnUiThread(()->{
                    localInProgress=false;
                    if(destroyed)return;
                    finishProcessing();
                    String reason=error instanceof OutOfMemoryError
                            ? "Memória insuficiente para melhorar a foto. Aumente o espaço livre e tente novamente."
                            : error.getMessage()==null?"Falha no processamento local.":error.getMessage();
                    status.setText("Falha no Estilo iPhone · original preservado");
                    new AlertDialog.Builder(this).setTitle("Falha no processamento offline")
                        .setMessage(reason+"\n\nA fotografia original permanece na galeria.")
                        .setPositiveButton("Entendi",null).show();
                });
            }
        });
    }

    private void startSuperResolution(Uri original) {
        if (localInProgress) { toast("Aguarde o processamento anterior."); return; }
        new AlertDialog.Builder(this).setTitle("Super-resolução neural EDSR ×4")
            .setMessage("O modelo offline amplia imagens por IA. Uma foto grande pode ser reduzida antes da rede para respeitar a memória do A17. Novos pixels podem melhorar a aparência, mas não são detalhes originais garantidos. O JPEG original será preservado.\n\nO modo maior usa mais RAM e pode demorar.")
            .setNegativeButton("Cancelar",null)
            .setNeutralButton("Equilibrado", (d,w)->runSuperResolution(original,false))
            .setPositiveButton("Maior resolução", (d,w)->runSuperResolution(original,true)).show();
    }

    private void runSuperResolution(Uri original,boolean maximum) {
        if (localInProgress) { toast("Processamento em andamento."); return; }
        localInProgress = true;
        showProcessing(0,"Preparando modelo neural offline");
        files.execute(() -> {
            try {
                NeuralUpscaler.Result out = NeuralUpscaler.upscale(
                        getApplicationContext(),original,maximum,this::showProcessing);
                runOnUiThread(() -> {
                    localInProgress = false;
                    if (destroyed) return;
                    finishProcessing();
                    String report = String.format(Locale.getDefault(),
                            "%d×%d → %d×%d pixels · EDSR (%s) · original preservado",
                            out.originalWidth,out.originalHeight,out.outputWidth,out.outputHeight,
                            out.gpu ? "GPU" : "CPU");
                    status.setText("Ampliação concluída · "+out.outputWidth+"×"+out.outputHeight);
                    showProcessed(original,out.uri,report);
                });
            } catch (Throwable error) {
                android.util.Log.e("LumaSuperRes","EDSR processing failed",error);
                runOnUiThread(() -> {
                    localInProgress = false;
                    if (destroyed) return;
                    finishProcessing();
                    String why = error instanceof OutOfMemoryError
                            ? "Memória insuficiente. Tente o modo Equilibrado."
                            : error.getMessage()==null ? "Falha no processamento EDSR" : error.getMessage();
                    new AlertDialog.Builder(this).setTitle("Não foi possível ampliar")
                            .setMessage(why+"\n\nSua foto original está preservada.")
                            .setPositiveButton("Entendi",null).show();
                });
            }
        });
    }

    /** CPU/GPU work runs on the worker; every result is a NEW MediaStore image. */
    private void processLocal(Uri original, int preset, boolean portrait, boolean png) {
        if (localInProgress) {
            toast("Processamento local em andamento. Aguarde a conclusão.");
            return;
        }
        localInProgress = true;
        showProcessing(0,portrait ? "Preparando retrato IA local" : "Preparando filtros offline");
        final int blur = settings.portraitBlur;
        files.execute(() -> {
            try {
                LocalPhotoProcessor.Result result = LocalPhotoProcessor.process(
                        getApplicationContext(), original, preset, portrait, blur, png, this::showProcessing);
                runOnUiThread(() -> {
                    localInProgress=false;
                    if (destroyed) return;
                    finishProcessing();
                    status.setText(result.details);
                    showProcessed(original,result.uri,result.details);
                });
            } catch (Throwable error) {
                android.util.Log.e("LumaLocal", "Local processing failed", error);
                runOnUiThread(() -> {
                    localInProgress = false;
                    if (!destroyed) {
                        finishProcessing();
                        String reason = error instanceof OutOfMemoryError
                                ? "Memória insuficiente. Desative processamento automático em Ajustes."
                                : "Falha no processamento local. Verifique o modelo e espaço disponível.";
                        status.setText(reason + " Original preservado.");
                        toast(reason);
                    }
                });
            }
        });
    }

    /** Re-encode a local copy as HEIC; original remains JPEG. */
    private void exportHeic(Uri original) {
        if (localInProgress) { toast("Aguarde o processamento local atual."); return; }
        localInProgress = true;
        status.setText("Exportando HEIC offline…");
        files.execute(() -> {
            try {
                Uri result = HeicPhotoExporter.export(getApplicationContext(), original);
                runOnUiThread(() -> {
                    localInProgress = false;
                    if (destroyed) return;
                    status.setText("Cópia HEIC salva · JPEG original preservado.");
                    if (original.equals(lastOriginalUri)) {
                        lastUri = result; lastVideo = false;
                        files.execute(() -> {
                            try {
                                Bitmap img = getContentResolver().loadThumbnail(result,new Size(150,150),null);
                                runOnUiThread(() -> {
                                    if (!destroyed && result.equals(lastUri)) thumbnail.setImageBitmap(img);
                                });
                            } catch(Exception ignored) {}
                        });
                    }
                    toast("Cópia HEIC na galeria.");
                });
            } catch(Throwable error) {
                android.util.Log.w("LumaHeic","HEIC export unavailable",error);
                runOnUiThread(() -> {
                    localInProgress = false;
                    if(!destroyed) {
                        status.setText("Codificador HEIC indisponível. JPEG original preservado.");
                        toast("HEIC não pôde ser exportado neste aparelho.");
                    }
                });
            }
        });
    }

    private void applyImmersive() {
        if (settings.fullscreenPreview) {
            WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView())
                    .hide(WindowInsetsCompat.Type.systemBars());
            WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView())
                    .setSystemBarsBehavior(androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView())
                    .show(WindowInsetsCompat.Type.systemBars());
        }
        if (preview != null) preview.setScaleType(settings.fullscreenPreview
                ? PreviewView.ScaleType.FILL_CENTER : PreviewView.ScaleType.FIT_CENTER);
    }

    /** Camera capture remains independent from optional cloud photo generation. */
    private void improvePhoto(Uri original, boolean confirm) {
        if (original == null || geminiInProgress) {
            if (geminiInProgress) toast("Uma foto já está sendo processada pelo Gemini.");
            return;
        }
        if (confirm) {
            new AlertDialog.Builder(this).setTitle("Enviar foto ao Gemini?")
                .setMessage("A foto será enviada ao Google para criar uma cópia editada. Ela pode ter detalhes alterados, custos de API e resolução diferente. O original continuará salvo.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Enviar e editar", (dialog, which) -> improvePhoto(original, false)).show();
            return;
        }
        final String apiKey;
        try { apiKey = GeminiKeyStore.load(this); }
        catch (Exception ex) {
            status.setText("Não foi possível ler a chave Gemini. Abra Ajustes para cadastrar uma nova.");
            return;
        }
        if (apiKey.isEmpty()) {
            status.setText("Configure sua API key Gemini em Ajustes para usar IA.");
            return;
        }
        geminiInProgress = true;
        status.setText("Gemini: criando cópia editada, sem alterar a foto original…");
        int style = settings.geminiStyle;
        files.execute(() -> {
            try {
                Uri edited = GeminiPhotoEnhancer.improve(getApplicationContext(), original, apiKey, style);
                runOnUiThread(() -> {
                    geminiInProgress = false;
                    if (destroyed) return;
                    lastUri = edited; lastVideo = false;
                    status.setText("Cópia Gemini salva · original preservado");
                    toast("Foto editada salva na galeria (pasta IA).");
                    files.execute(() -> {
                        try {
                            Bitmap image = getContentResolver().loadThumbnail(edited, new Size(150, 150), null);
                            runOnUiThread(() -> {
                                if (!destroyed && edited.equals(lastUri)) thumbnail.setImageBitmap(image);
                            });
                        } catch (Exception ignored) {}
                    });
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    geminiInProgress = false;
                    if (!destroyed) {
                        status.setText("Gemini: " + ex.getMessage() + " Foto original preservada.");
                        toast("Não foi possível editar a foto. O original está salvo.");
                    }
                });
            }
        });
    }

    @Override public void onRecording(boolean recording, boolean paused, long millis, long bytes) {
        if (destroyed) return;
        recordingLabel.setVisibility(recording ? View.VISIBLE : View.GONE);
        long seconds = millis / 1000;
        recordingLabel.setText(String.format(Locale.getDefault(), "%s %02d:%02d  ·  %.1f MB", paused ? "PAUSA" : "● REC", seconds/60, seconds%60, bytes/1_000_000.0));
        pauseButton.setVisibility(recording ? View.VISIBLE : View.GONE); pauseButton.setText(paused ? "Retomar" : "Pausar");
        if (recording) status.setText(settings.sound && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                ? "Gravando com áudio · toque no quadrado para salvar" : "Gravando sem áudio · toque no quadrado para salvar");
        refresh();
    }

    private void refresh() {
        if (engine == null || destroyed) return;
        boolean active = available(), recording = engine.isRecording(), mutable = active && !recording && timer == null;
        updatingUi = true;
        settingsButton.setEnabled(mutable); lensButton.setEnabled(mutable); switchButton.setEnabled(mutable);
        timerButton.setEnabled(mutable && !settings.video); proButton.setEnabled(mutable && !engine.isExtension() && !engine.isConservative());
        timerButton.setText(settings.timerSeconds == 0 ? "Timer off" : settings.timerSeconds + " s");
        flashButton.setText(settings.video ? (torchOn ? "Luz on" : "Luz off") : settings.flash == ImageCapture.FLASH_MODE_AUTO ? "Flash auto" : settings.flash == ImageCapture.FLASH_MODE_ON ? "Flash on" : "Flash off");
        flashButton.setEnabled(active && timer == null && engine.lens != null && engine.lens.info.hasFlashUnit() && !engine.isExtension() && !engine.isConservative() && !settings.manualExposure);
        shutter.setEnabled(active || recording || timer != null); shutter.video = settings.video; shutter.recording = recording;
        shutter.busy = engine.isBusy(); shutter.invalidate();
        shutter.setContentDescription(timer != null ? "Cancelar temporizador" : recording ? "Parar gravação e salvar vídeo" : settings.video ? "Iniciar gravação de vídeo" : "Tirar foto");
        unlockButton.setVisibility(settings.lock ? View.VISIBLE : View.GONE);
        if (engine.lens != null) lensButton.setText(engine.lens.name);
        if (active) {
            session.setText(engine.sessionDescription());
            ExposureState e = engine.camera.getCameraInfo().getExposureState();
            evBar.setMax(e.getExposureCompensationRange().getUpper() - e.getExposureCompensationRange().getLower());
            evBar.setProgress(CapturePolicy.exposureIndex(settings.ev, e.getExposureCompensationStep().doubleValue(),
                    e.getExposureCompensationRange().getLower(), e.getExposureCompensationRange().getUpper()) - e.getExposureCompensationRange().getLower());
            evBar.setEnabled(e.isExposureCompensationSupported() && !settings.manualExposure && !engine.isExtension() && !engine.isConservative() && timer == null);
        } else evBar.setEnabled(false);
        evLabel.setText(settings.manualExposure ? "MANUAL" : String.format(Locale.getDefault(), "EV %+.1f", settings.ev));
        for (int i=0;i<modes.getChildCount();i++) modes.getChildAt(i).setEnabled(mutable);
        if (previewFrame.video != settings.video) { previewFrame.video = settings.video; previewFrame.requestLayout(); }
        overlay.locked = settings.lock; overlay.invalidate();
        updatingUi = false;
    }

    private boolean available() { return engine != null && engine.isReady(); }
    private boolean canChange() { return available() && !engine.isRecording() && timer == null; }
    private boolean hasCamera() { return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }
    private void retryCamera() {
        if (!hasCamera()) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) cameraPermission.launch(Manifest.permission.CAMERA);
            else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } else if (!engine.isBusy() && !engine.isRecording()) { retryButton.setVisibility(View.GONE); engine.initialize(); }
    }
    private void openLast() {
        if (lastUri == null) { toast("Suas capturas aparecerão aqui e na galeria."); return; }
        try {
            String mime = getContentResolver().getType(lastUri);
            if (mime == null || mime.isEmpty()) mime = lastVideo ? "video/mp4" : "image/jpeg";
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(lastUri,mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        }
        catch (ActivityNotFoundException ex) { toast("Abra a galeria para ver sua captura."); }
    }

    private void showSettings() {
        if (!canChange()) return;
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = column();
        box.setPadding(dp(22), dp(6), dp(22), dp(12));
        scroll.addView(box);
        section(box, "FOTOGRAFIA");
        CheckBox fullPreview = check(box, "Visor preenchendo a tela (como câmera nativa)", settings.fullscreenPreview);
        hint(box, "O visor pode recortar as bordas para preencher o espaço; a foto salva mantém o enquadramento do sensor.");
        CheckBox bright = check(box, "Exposição inicial +0,3 EV (clarear câmera)", settings.brighterExposure);
        hint(box, "Padrão mais claro para o Galaxy A17; ajuste o controle EV no visor quando necessário.");
        CheckBox detail = check(box, "Maior resolução real disponível", settings.detail);
        hint(box, "Usa somente as resoluções disponibilizadas pelo Android. Fotos maiores levam mais tempo para processar.");
        CheckBox jpeg = check(box, "JPEG com qualidade 100 (arquivos maiores)", settings.jpegMaximum);
        hint(box, "Aumenta a qualidade de compressão JPEG de 97 para 100; não recupera detalhes perdidos pelo sensor.");
        CheckBox hdr = check(box, "Ultra HDR JPEG, quando compatível", settings.ultraHdr);
        hint(box, "JPEG/R com ganho HDR em telas e câmeras suportadas; caso contrário, JPEG padrão.");
        CheckBox mirror = check(box, "Salvar selfie espelhada", settings.mirror);

        section(box, "ESTILO IPHONE LOCAL · FOTOS");
        hint(box, "Processamento offline que aproxima tons naturais, contraste moderado, cor ligeiramente quente e boa textura. Reduz ruído de cor e de brilho com preservação de bordas. Não é o algoritmo proprietário da Apple, nem recupera detalhes inexistentes.");
        String[] iphoneNames={"Natural (menos intervenção)", "Padrão (cores e nitidez equilibradas)",
                "Noturno (redução de ruído mais forte)"};
        Spinner iphoneLevel=new Spinner(this);
        iphoneLevel.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,iphoneNames));
        iphoneLevel.setSelection(settings.iphoneProfile);
        box.addView(iphoneLevel,new LinearLayout.LayoutParams(-1,dp(52)));
        CheckBox iphoneAuto=check(box,"Aplicar automaticamente após capturar (salva cópia)",settings.iphoneAutomatic);
        hint(box,"Ativado por padrão. O JPEG original é preservado. Resultado aparece dentro do app e na galeria; processar fotos de 12 MP pode demorar alguns segundos. Não é necessário internet.");
        if(lastOriginalUri!=null){
            Button iphoneLast=button("Aplicar estilo iPhone à última foto",
                    "Processar cópia local para comparar antes e depois",v -> processIphone(lastOriginalUri,iphoneLevel.getSelectedItemPosition()));
            box.addView(iphoneLast,new LinearLayout.LayoutParams(-1,dp(60)));
        }
        section(box, "IA LOCAL E REVISÃO (FUNCIONA SEM INTERNET)");
        hint(box, "Super-resolução EDSR ×4 offline aumenta pixels por rede neural (GPU/CPU). Retrato usa MediaPipe. Filtros de cor, ruído e nitidez não são super-resolução. A IA pode estimar detalhes que não estavam na foto.");
        String[] localNames = {"Desligado", "Natural (suave)", "Equilibrado", "Máximo"};
        Spinner localLevel = new Spinner(this);
        localLevel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, localNames));
        localLevel.setSelection(settings.localPreset);
        box.addView(localLevel,new LinearLayout.LayoutParams(-1,dp(52)));
        CheckBox localAuto = check(box, "Melhorar fotos automaticamente em uma cópia local", settings.localAutomatic);
        CheckBox review = check(box, "Mostrar opções Retrato, PNG e Gemini após a foto", settings.reviewAfterCapture);
        String[] blurNames = {"Retrato: desfoque moderado", "Retrato: desfoque forte"};
        Spinner blurLevel = new Spinner(this);
        blurLevel.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,blurNames));
        blurLevel.setSelection(settings.portraitBlur-1);
        box.addView(blurLevel,new LinearLayout.LayoutParams(-1,dp(52)));
        hint(box, "O original não é apagado. PNG preserva os pixels decodificados de JPEG, mas não recupera dados que já foram comprimidos. Retrato pode levar alguns segundos.");
        if (lastOriginalUri != null) {
            Button superResLast = button("Aumentar resolução da última foto com IA ×4", "Executar super-resolução neural e visualizar resultado", v ->
                    startSuperResolution(lastOriginalUri));
            box.addView(superResLast,new LinearLayout.LayoutParams(-1,dp(62)));
            Button localLast = button("Melhorar última foto offline", "Processar original sem enviar ao Google", v ->
                    processLocal(lastOriginalUri,Math.max(1,settings.localPreset),false,false));
            Button portraitLast = button("Retrato IA da última foto", "Desfocar fundo via MediaPipe local", v ->
                    processLocal(lastOriginalUri,settings.localPreset,true,false));
            box.addView(localLast,new LinearLayout.LayoutParams(-1,dp(55)));
            box.addView(portraitLast,new LinearLayout.LayoutParams(-1,dp(55)));
            Button heicLast = button("Exportar última foto HEIC", "Converter uma cópia da última foto para HEIC", v ->
                    exportHeic(lastOriginalUri));
            box.addView(heicLast,new LinearLayout.LayoutParams(-1,dp(55)));
        }

        section(box, "VÍDEO");
        CheckBox videoMax = check(box, "Maior resolução de vídeo disponível", settings.maximumVideoQuality);
        hint(box, "Consulta as resoluções expostas por cada lente; o Galaxy A17 anuncia Full HD 30 fps, não 4K.");
        CheckBox high = check(box, "Bitrate alto (Full HD: 24 Mb/s)", settings.highBitrate);
        CheckBox ultra = check(box, "Bitrate máximo (Full HD: alvo 32 Mb/s)", settings.ultraBitrate);
        hint(box, "Solicitações ao codificador; taxa e resultado final dependem do hardware. Desative o máximo se aquecer, travar ou gerar arquivos muito grandes.");
        CheckBox hd = check(box, "Priorizar 720p (sobrepõe a maior resolução)", settings.videoHd);
        CheckBox audio = check(box, "Gravar áudio", settings.sound);

        section(box, "GEMINI IA · OPÇÃO DE EDIÇÃO");
        hint(box, "Gemini NÃO aumenta a capacidade do sensor nem otimiza vídeo em tempo real. Edita uma CÓPIA da foto após capturar; pode inventar detalhes ou mudar resolução. Precisa internet, chave do Google AI Studio, e pode gerar cobranças.");
        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setHint("Chave Gemini (AQ. ou AIza...)");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTextSize(14);
        box.addView(key, new LinearLayout.LayoutParams(-1, dp(54)));
        try {
            if (!GeminiKeyStore.load(this).isEmpty())
                hint(box, "Uma chave já está armazenada criptografada neste aparelho. Deixe em branco para mantê-la.");
        } catch (Exception ignored) {
            hint(box, "A chave anterior não pôde ser lida. Cadastre uma nova.");
        }
        hint(box, "Aceita chaves AQ. e AIza. O botão Aplicar salva a chave sem bloquear por formato; o teste chama o Google e informa o erro específico. Nunca compartilhe a chave em prints ou mensagens.");
        Button testGemini = button("Testar conexão com Gemini", "Executar teste de autenticação sem enviar fotografias", v -> {
            String candidate = key.getText().toString().trim();
            if (candidate.isEmpty()) {
                try { candidate = GeminiKeyStore.load(this); }
                catch (Exception ex) {
                    toast("Não foi possível abrir a chave armazenada. Digite uma chave nova.");
                    return;
                }
            }
            if (candidate.isEmpty()) {
                toast("Digite ou salve uma chave do Google AI Studio primeiro.");
                return;
            }
            final String testKey = candidate;
            v.setEnabled(false);
            files.execute(() -> {
                String title = "Teste Gemini";
                String message;
                try {
                    message = GeminiPhotoEnhancer.testConnection(testKey);
                } catch (Exception ex) {
                    message = ex.getMessage() == null ? "Falha de conexão. Confira internet e serviço Google." : ex.getMessage();
                }
                final String finalMessage = message;
                runOnUiThread(() -> {
                    v.setEnabled(true);
                    if (!destroyed && !isFinishing()) new AlertDialog.Builder(this)
                        .setTitle(title).setMessage(finalMessage).setPositiveButton("Entendi", null).show();
                });
            });
        });
        box.addView(testGemini, new LinearLayout.LayoutParams(-1, dp(56)));
        hint(box, "O teste envia somente a frase 'Responda OK' ao Gemini 3.8 Flash. Pode consumir tokens. Não envia fotos e não garante acesso ao modelo de imagens.");
        CheckBox erase = check(box, "Apagar chave Gemini armazenada", false);
        CheckBox automatic = check(box, "Enviar cada nova foto automaticamente à IA", settings.geminiAutomatic);
        hint(box, "DESATIVADO por padrão. Ao ativar, você autoriza o envio de cada foto ao Google. O original continua local; a cópia gerada vai para DCIM/LumaA17/IA.");
        String[] styles = {"Natural / fiel ao original", "Leve nitidez e detalhes", "Baixa luz / sombras"};
        Spinner style = new Spinner(this);
        ArrayAdapter<String> options = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, styles);
        style.setAdapter(options);
        style.setSelection(settings.geminiStyle);
        box.addView(style, new LinearLayout.LayoutParams(-1, dp(52)));
        if (lastUri != null && !lastVideo) {
            Button runGemini = button("Melhorar última foto com Gemini", "Enviar a última foto ao Google para gerar uma cópia editada", v -> {
                improvePhoto(lastUri, true);
            });
            box.addView(runGemini, new LinearLayout.LayoutParams(-1, dp(55)));
            hint(box, "Para alterar a chave ou o estilo, toque primeiro em Aplicar e depois reabra Ajustes.");
        }

        section(box, "CAPTURA");
        CheckBox stabilization = check(box, "Estabilização disponível", settings.stabilization);
        hint(box, "Prioriza OIS quando disponível; usa EIS compatível em lentes sem OIS.");
        CheckBox grid = check(box, "Grade de composição", settings.grid);
        CheckBox horizon = check(box, "Nível do horizonte", settings.level);
        section(box, "SOBRE ESTA VERSÃO");
        hint(box, "Luma A17 " + BuildConfig.VERSION_NAME + " · Estilo iPhone Local com cores e redução de ruído (CPU); retrato MediaPipe GPU/CPU; EDSR neural para ampliação opcional. JPEG original preservado. Sem cópia do ISP Apple; sem HEVC de vídeo nem fusão de câmeras.");
        Button diagnostics = button("Salvar diagnóstico de câmera", "Salvar capacidades disponíveis em arquivo", v -> saveDiagnostic());
        box.addView(diagnostics);
        new AlertDialog.Builder(this).setTitle("Ajustes").setView(scroll)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar", (d, w) -> {
                boolean savedKey = true;
                try {
                    if (erase.isChecked()) GeminiKeyStore.save(this, "");
                    else if (!key.getText().toString().trim().isEmpty())
                        GeminiKeyStore.save(this, key.getText().toString().trim());
                } catch (Exception ex) {
                    savedKey = false;
                    toast("Erro ao guardar chave no Android Keystore; outros ajustes serão aplicados.");
                }
                boolean rebind = settings.detail != detail.isChecked() || settings.mirror != mirror.isChecked()
                    || settings.highBitrate != high.isChecked() || settings.videoHd != hd.isChecked()
                    || settings.stabilization != stabilization.isChecked()
                    || settings.jpegMaximum != jpeg.isChecked() || settings.ultraHdr != hdr.isChecked()
                    || settings.maximumVideoQuality != videoMax.isChecked() || settings.ultraBitrate != ultra.isChecked();
                settings.detail = detail.isChecked(); settings.mirror = mirror.isChecked();
                settings.highBitrate = high.isChecked(); settings.ultraBitrate = ultra.isChecked();
                settings.videoHd = hd.isChecked(); settings.maximumVideoQuality = videoMax.isChecked();
                settings.jpegMaximum = jpeg.isChecked(); settings.ultraHdr = hdr.isChecked();
                settings.sound = audio.isChecked(); settings.stabilization = stabilization.isChecked();
                settings.geminiAutomatic = automatic.isChecked() && !erase.isChecked() && savedKey;
                settings.geminiStyle = style.getSelectedItemPosition();
                settings.grid = grid.isChecked(); settings.level = horizon.isChecked();
                boolean exposureChanged = settings.brighterExposure != bright.isChecked();
                settings.fullscreenPreview = fullPreview.isChecked();
                settings.brighterExposure = bright.isChecked();
                settings.iphoneProfile = iphoneLevel.getSelectedItemPosition();
                settings.iphoneAutomatic = iphoneAuto.isChecked();
                settings.localPreset = localLevel.getSelectedItemPosition();
                settings.portraitBlur = blurLevel.getSelectedItemPosition() + 1;
                settings.localAutomatic = localAuto.isChecked() && settings.localPreset > 0;
                settings.reviewAfterCapture = review.isChecked();
                if (exposureChanged) settings.ev = settings.brighterExposure ? .33 : 0;
                settings.save(preferences);
                applyOverlay(); applyImmersive();
                if (exposureChanged && !rebind) engine.applyControls();
                if (rebind) engine.bind(); else refresh();
            }).show();
    }

    private void showControls() {
        if (!canChange() || engine.isExtension() || engine.isConservative()) return;
        ScrollView scroll = new ScrollView(this); LinearLayout box = column(); box.setPadding(dp(22), dp(6), dp(22), dp(16)); scroll.addView(box);
        section(box, "COR DA LUZ");
        String[] names = {"Automático", "Luz do dia", "Nublado", "Incandescente", "Fluorescente"};
        int[] values = {CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
                CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT};
        List<String> wbNames = new ArrayList<>(); List<Integer> wbValues = new ArrayList<>();
        for (int i=0;i<values.length;i++) if (engine.lens.wb(values[i])) { wbNames.add(names[i]); wbValues.add(values[i]); }
        Button wb = button("Balanço de branco", "Escolher balanço de branco", v -> new AlertDialog.Builder(this).setTitle("Balanço de branco")
                .setItems(wbNames.toArray(new String[0]), (dialog,which) -> {
                    settings.whiteBalance = wbValues.get(which); settings.lock = false; engine.applyControls(); refresh(); toast(wbNames.get(which));
                }).show());
        box.addView(wb); hint(box, "A cor é aplicada à captura e ao visor. Automático se adapta à iluminação.");

        section(box, "LUZ ARTIFICIAL");
        List<String> bandNames = new ArrayList<>(); List<Integer> bandValues = new ArrayList<>();
        int[] bands = {CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF};
        String[] bandLabels = {"60 Hz · Brasil", "50 Hz", "Automático", "Desativado"};
        for (int i=0;i<bands.length;i++) if (engine.lens.antibanding(bands[i])) { bandNames.add(bandLabels[i]); bandValues.add(bands[i]); }
        Button anti = button("Reduzir faixas de iluminação", "Escolher redução de cintilação", v -> new AlertDialog.Builder(this).setTitle("Reduzir cintilação")
                .setItems(bandNames.toArray(new String[0]), (dialog,which) -> {
                    settings.antibanding = bandValues.get(which); settings.save(preferences); engine.applyControls(); toast(bandNames.get(which));
                }).show()); box.addView(anti);
        hint(box, "Ajuda a reduzir faixas sob lâmpadas compatíveis. O modo manual exige ajustar o obturador à iluminação.");
        if (engine.lens.minFocus > 0) {
            section(box, "FOCO");
            CheckBox autoFocus = check(box, "Foco automático", settings.focusDistance < 0);
            SeekBar focus = new SeekBar(this); focus.setMax(100); focus.setContentDescription("Foco manual: longe para perto");
            focus.setProgress(settings.focusDistance < 0 ? 0 : Math.round(settings.focusDistance / engine.lens.minFocus * 100));
            focus.setEnabled(!autoFocus.isChecked()); box.addView(focus); hint(box, "Esquerda: infinito. Direita: foco próximo.");
            autoFocus.setOnCheckedChangeListener((b,checked) -> {
                focus.setEnabled(!checked); settings.focusDistance = checked ? -1 : engine.lens.minFocus * focus.getProgress()/100;
                settings.lock = false; engine.applyControls(); refresh();
            });
            focus.setOnSeekBarChangeListener(slider(p -> { settings.focusDistance = engine.lens.minFocus * p/100f; engine.applyControls(); }));
        } else { section(box, "FOCO FIXO"); hint(box, "Esta lente não oferece foco motorizado. Ajuste a distância entre o celular e o assunto."); }
        if (!settings.video && engine.lens.manualAvailable()) {
            section(box, "EXPOSIÇÃO MANUAL");
            CheckBox manual = check(box, "Controlar ISO e obturador", settings.manualExposure);
            int minIso = engine.lens.isoRange.getLower(), maxIso = engine.lens.isoRange.getUpper();
            long minTime = Math.max(engine.lens.shutterRange.getLower(), 125_000L);
            long maxTime = Math.max(minTime, Math.min(engine.lens.shutterRange.getUpper(), 250_000_000L));
            TextView isoLabel = text("ISO " + settings.iso, 13, GOLD); box.addView(isoLabel);
            SeekBar iso = new SeekBar(this); iso.setContentDescription("Sensibilidade ISO"); iso.setMax(maxIso-minIso);
            iso.setProgress(Math.max(0, settings.iso-minIso)); iso.setEnabled(manual.isChecked()); box.addView(iso);
            TextView timeLabel = text(CapturePolicy.shutterLabel(settings.shutterNs), 13, GOLD); box.addView(timeLabel);
            SeekBar time = new SeekBar(this); time.setContentDescription("Tempo do obturador"); time.setMax(100);
            int timeProgress = maxTime == minTime ? 0 : (int)Math.round(Math.log((double)Math.max(minTime,settings.shutterNs)/minTime)/Math.log((double)maxTime/minTime)*100);
            time.setProgress(Math.max(0, Math.min(100,timeProgress))); time.setEnabled(manual.isChecked()); box.addView(time);
            manual.setOnCheckedChangeListener((b,checked) -> {
                settings.manualExposure = checked; settings.lock = false;
                settings.iso = minIso + iso.getProgress(); settings.shutterNs = CapturePolicy.shutterFromProgress(time.getProgress(),minTime,maxTime);
                iso.setEnabled(checked); time.setEnabled(checked); engine.applyControls(); refresh();
            });
            iso.setOnSeekBarChangeListener(slider(p -> { settings.iso = minIso+p; isoLabel.setText("ISO " + settings.iso); engine.applyControls(); }));
            time.setOnSeekBarChangeListener(slider(p -> { settings.shutterNs = CapturePolicy.shutterFromProgress(p,minTime,maxTime); timeLabel.setText(CapturePolicy.shutterLabel(settings.shutterNs)); engine.applyControls(); }));
            hint(box, "ISO baixo preserva detalhe. Exposições longas pedem apoio e assuntos parados. Flash e compensação EV ficam desativados no manual.");
        }
        new AlertDialog.Builder(this).setTitle("Controles de captura").setView(scroll)
                .setNegativeButton("Restaurar automático", (d,w) -> { settings.resetLensControls(); engine.unlock(); refresh(); })
                .setPositiveButton("Concluir", (d,w) -> refresh()).show();
    }

    private void saveDiagnostic() {
        try { reportToSave = engine.diagnostic(); diagnosticDocument.launch("Luma_A17_diagnostico.json"); }
        catch (Exception ex) { toast("Não foi possível gerar o diagnóstico."); }
    }
    private void applyOverlay() {
        if (overlay == null) return;
        overlay.grid = settings.grid; overlay.showLevel = settings.level; overlay.invalidate();
    }
    @Override protected void onResume() {
        super.onResume();
        applyImmersive();
        if (orientation != null && orientation.canDetectOrientation()) orientation.enable();
        if (gravitySensor != null) sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_UI);
        if (engine != null && hasCamera() && engine.camera == null && !engine.isBusy()) engine.initialize();
    }
    @Override protected void onStop() {
        if (timer != null) cancelTimer();
        if (engine != null) { engine.stopRecording(); engine.torch(false); torchOn = false; }
        if (orientation != null) orientation.disable();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true; if (engine != null) engine.close(); files.shutdown(); super.onDestroy();
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if ((key == KeyEvent.KEYCODE_VOLUME_DOWN || key == KeyEvent.KEYCODE_VOLUME_UP) && event.getRepeatCount() == 0) { capture(); return true; }
        return super.onKeyDown(key,event);
    }
    @Override public boolean onKeyUp(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_VOLUME_DOWN || key == KeyEvent.KEYCODE_VOLUME_UP) return true;
        return super.onKeyUp(key,event);
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if (!settings.level || overlay == null) return;
        float x = event.values[0], y = event.values[1];
        if (Math.hypot(x,y) < 2.5) return;
        float angle = (float)Math.toDegrees(Math.atan2(x,y));
        // Show the nearest portrait/landscape level rather than rotating by a full turn.
        angle = ((angle + 225) % 90) - 45;
        roll = .75f*roll + .25f*angle; overlay.roll(roll);
    }
    @Override public void onAccuracyChanged(Sensor s, int accuracy) {}

    private SeekBar.OnSeekBarChangeListener slider(java.util.function.IntConsumer consumer) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b,int p,boolean user) { if (user) consumer.accept(p); }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        };
    }
    private LinearLayout column() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); return box; }
    private LinearLayout row() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL); return box; }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams weighted(int height) { return new LinearLayout.LayoutParams(0,height,1); }
    private TextView text(String value,float size,int color) {
        TextView label = new TextView(this); label.setText(value); label.setTextSize(size); label.setTextColor(color); label.setGravity(Gravity.CENTER_VERTICAL); return label;
    }
    private Button button(String value,String description,View.OnClickListener click) {
        Button button = new Button(this); button.setText(value); button.setTextSize(12); button.setAllCaps(false);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xFF64676E,Color.WHITE}));
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(6),0,dp(6),0); button.setBackground(pill(BG,18)); button.setContentDescription(description); button.setOnClickListener(click);
        return button;
    }
    private GradientDrawable pill(int color,float radius) { GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg; }
    private CheckBox check(LinearLayout box,String value,boolean checked) { CheckBox c = new CheckBox(this); c.setText(value); c.setTextSize(14); c.setChecked(checked); c.setMinHeight(dp(48)); box.addView(c); return c; }
    private void section(LinearLayout box,String value) { TextView label = text(value,11,GOLD); label.setTypeface(Typeface.DEFAULT,Typeface.BOLD); label.setPadding(0,dp(18),0,dp(8)); label.setLetterSpacing(.08f); box.addView(label); }
    private void hint(LinearLayout box,String value) { TextView label = text(value,12,MUTED); label.setPadding(0,0,0,dp(8)); label.setLineSpacing(dp(2),1); box.addView(label); }
    private void toast(String text) { if (!destroyed) Toast.makeText(this,text,Toast.LENGTH_SHORT).show(); }

    /** Fit the entire photo/video field of view; composition overlay follows that exact rectangle. */
    private final class PreviewFrame extends FrameLayout {
        boolean video;
        private int childWidth,childHeight;
        PreviewFrame() { super(CameraActivity.this); setBackground(pill(Color.BLACK,16)); setClipToOutline(true); }
        @Override protected void onMeasure(int ws,int hs) {
            int width = MeasureSpec.getSize(ws), height = MeasureSpec.getSize(hs);
            double ratio = video ? 9.0/16 : 3.0/4;
            childWidth = width; childHeight = (int)Math.round(width/ratio);
            if (childHeight>height) { childHeight=height; childWidth=(int)Math.round(height*ratio); }
            for (int i=0;i<getChildCount();i++) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(i<2?childWidth:width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(i<2?childHeight:height,MeasureSpec.EXACTLY));
            setMeasuredDimension(width,height);
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
            int x=(getWidth()-childWidth)/2, y=(getHeight()-childHeight)/2;
            for (int i=0;i<getChildCount();i++) {
                if (i<2) getChildAt(i).layout(x,y,x+childWidth,y+childHeight);
                else getChildAt(i).layout(0,0,getWidth(),getHeight());
            }
        }
    }
    private final class ShutterView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean video,recording,busy;
        ShutterView() { super(CameraActivity.this); setFocusable(true); setClickable(true); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); float x=getWidth()/2f,y=getHeight()/2f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(3)); p.setColor(isEnabled()?Color.WHITE:0xFF62646A);
            c.drawCircle(x,y,dp(35),p);
            p.setStyle(Paint.Style.FILL); p.setColor(video?0xFFF35656:Color.WHITE); p.setAlpha(busy?90:255);
            if (recording) c.drawRoundRect(new RectF(x-dp(15),y-dp(15),x+dp(15),y+dp(15)),dp(5),dp(5),p);
            else c.drawCircle(x,y,dp(29),p);
        }
    }
}
