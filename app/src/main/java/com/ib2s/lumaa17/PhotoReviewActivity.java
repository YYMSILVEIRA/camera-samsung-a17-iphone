package com.ib2s.lumaa17;

import android.app.Application;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Review always keeps the archival original selectable. Editing never replaces it. */
public final class PhotoReviewActivity extends ComponentActivity {
    private ReviewModel model;
    private ImageView photo;
    private TextView message;
    private ProgressBar progress;
    private LinearLayout actions;
    private Button done,original,latest;
    private Bitmap displayed;
    private Uri requestedPreview;
    private int strength;
    private boolean destroyed;
    private final ExecutorService previews=Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView()).hide(WindowInsetsCompat.Type.systemBars());
        Uri source=getIntent().getData();if(source==null){finish();return;}
        model=new ViewModelProvider(this).get(ReviewModel.class);
        strength=getIntent().getIntExtra("strength",5);
        model.initialize(source,state==null?null:state.getString("current"));
        buildUi();
        model.state.observe(this,this::render);
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){finishReview();}
        });
        if(state==null&&getIntent().getBooleanExtra("portrait",false)&&!model.startedPortrait){model.startedPortrait=true;model.portrait(strength);}
    }
    private void buildUi(){
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);setContentView(root);
        photo=new ImageView(this);photo.setScaleType(ImageView.ScaleType.FIT_CENTER);photo.setContentDescription("Revisão da foto inteira");
        root.addView(photo,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setBackgroundColor(0xbb000000);
        message=new TextView(this);message.setTextColor(Color.WHITE);message.setTextSize(14);message.setPadding(dp(16),dp(12),dp(16),dp(12));
        top.addView(message);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);top.addView(progress,new LinearLayout.LayoutParams(-1,dp(4)));
        root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setPadding(dp(10),dp(4),dp(10),dp(8));bottom.setBackgroundColor(0xcc000000);
        LinearLayout compare=new LinearLayout(this);
        original=button("Original",v->model.selectOriginal());latest=button("Cópia",v->model.selectLatest());
        compare.addView(original,new LinearLayout.LayoutParams(0,dp(48),1));compare.addView(latest,new LinearLayout.LayoutParams(0,dp(48),1));bottom.addView(compare);
        actions=new LinearLayout(this);
        actions.addView(button("Retrato",v->portraitDialog()),new LinearLayout.LayoutParams(0,dp(52),1));
        actions.addView(button("PNG",v->new AlertDialog.Builder(this).setTitle("Cópia PNG sem perdas")
                .setMessage("Mantém os pixels e a resolução da foto original em uma cópia SDR, com arquivo maior. Converter JPEG para PNG não recupera detalhe já perdido nem preserva o ganho Ultra HDR. O JPEG original continua salvo.")
                .setNegativeButton("Cancelar",null).setPositiveButton("Salvar PNG",(d,w)->model.png()).show()),new LinearLayout.LayoutParams(0,dp(52),1));
        actions.addView(button("Gemini",v->geminiDialog()),new LinearLayout.LayoutParams(0,dp(52),1));bottom.addView(actions);
        LinearLayout navigation=new LinearLayout(this);
        navigation.addView(button("Galeria",v->{
            try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(model.current,"image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
            catch(android.content.ActivityNotFoundException e){Toast.makeText(this,"A cópia está em DCIM/LumaA17.",Toast.LENGTH_SHORT).show();}
        }),new LinearLayout.LayoutParams(0,dp(48),1));
        done=button("Voltar à câmera",v->finishReview());navigation.addView(done,new LinearLayout.LayoutParams(0,dp(48),1));bottom.addView(navigation);
        root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            var bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            top.setPadding(bars.left,bars.top,bars.right,0);bottom.setPadding(dp(10)+bars.left,dp(4),dp(10)+bars.right,dp(8)+bars.bottom);return insets;
        });
    }
    private void portraitDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        TextView info=new TextView(this);info.setText("Retrato de pessoas com IA no aparelho. Ajuste a intensidade do fundo. Cabelo, vidro e movimentos podem exigir outro enquadramento.");box.addView(info);
        SeekBar blur=new SeekBar(this);blur.setMax(9);blur.setProgress(strength-1);blur.setContentDescription("Intensidade do desfoque de fundo");box.addView(blur);
        new AlertDialog.Builder(this).setTitle("Retrato · fundo desfocado").setView(box).setNegativeButton("Cancelar",null)
                .setPositiveButton("Criar retrato",(d,w)->{strength=blur.getProgress()+1;model.portrait(strength);}).show();
    }
    private void geminiDialog(){
        if(!new GeminiKeyStore(this).configured()){GeminiSettingsDialog.show(this);return;}
        new AlertDialog.Builder(this).setTitle("Melhorar foto com Gemini?")
                .setMessage("Esta ação envia somente a foto original ao Google, sem os metadados EXIF. Pode haver cobrança e mudanças em rostos, texturas ou objetos. A imagem gerada será salva separadamente; o original permanece intacto. Não é processamento em tempo real.")
                .setNeutralButton("Chave / modelo",(d,w)->GeminiSettingsDialog.show(this))
                .setNegativeButton("Cancelar",null).setPositiveButton("Enviar esta foto",(d,w)->model.gemini()).show();
    }
    private void render(ReviewState state){
        if(state==null)return;
        message.setText(state.message);progress.setVisibility(state.busy?View.VISIBLE:View.GONE);done.setEnabled(!state.busy);
        original.setEnabled(!state.busy);latest.setEnabled(!state.busy&&model.latest!=null);
        for(int i=0;i<actions.getChildCount();i++)actions.getChildAt(i).setEnabled(!state.busy);
        if(!state.uri.equals(requestedPreview)){
            requestedPreview=state.uri;Uri uri=state.uri;
            previews.execute(()->{
                Bitmap bitmap=null;try{bitmap=PhotoFiles.decode(this,uri,1600,false);}catch(Exception|OutOfMemoryError ignored){}
                Bitmap result=bitmap;
                runOnUiThread(()->{
                    if(destroyed||!uri.equals(requestedPreview)){if(result!=null)result.recycle();return;}
                    if(result!=null){Bitmap old=displayed;displayed=result;photo.setImageBitmap(result);if(old!=null)old.recycle();}
                });
            });
        }
    }
    private void finishReview(){
        if(model.busy){Toast.makeText(this,"Aguarde o processamento. O original já está salvo.",Toast.LENGTH_SHORT).show();return;}
        setResult(RESULT_OK,new Intent().setData(model.latest==null?model.original:model.latest));finish();
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("current",model.current.toString());}
    @Override protected void onDestroy(){destroyed=true;previews.shutdown();if(photo!=null)photo.setImageDrawable(null);if(displayed!=null)displayed.recycle();super.onDestroy();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private Button button(String label,View.OnClickListener click){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(4),0,dp(4),0);b.setOnClickListener(click);return b;}

    static final class ReviewState {
        final Uri uri;final String message;final boolean busy;
        ReviewState(Uri uri,String message,boolean busy){this.uri=uri;this.message=message;this.busy=busy;}
    }
    public static final class ReviewModel extends AndroidViewModel {
        final MutableLiveData<ReviewState> state=new MutableLiveData<>();
        private final ExecutorService worker=Executors.newSingleThreadExecutor();
        Uri original,current,latest;boolean busy,startedPortrait;
        String latestLabel="Cópia salva na galeria";
        public ReviewModel(Application application){super(application);}
        void initialize(Uri uri,String restored){
            if(original!=null)return;original=uri;current=restored==null?uri:Uri.parse(restored);
            if(!current.equals(original))latest=current;
            state.setValue(new ReviewState(current,current.equals(original)?"Original preservado · escolha uma melhoria":"Cópia salva na galeria",false));
        }
        void selectOriginal(){if(!busy){current=original;state.setValue(new ReviewState(current,"Original preservado",false));}}
        void selectLatest(){if(!busy&&latest!=null){current=latest;state.setValue(new ReviewState(current,latestLabel,false));}}
        interface Job {Uri run()throws Exception;}
        void portrait(int strength){run("Criando retrato no aparelho…","Retrato PNG salvo · original preservado",()->PortraitProcessor.process(getApplication(),original,strength));}
        void png(){run("Salvando PNG sem perdas…","PNG SDR salvo · mesma resolução do original",()->{
            Bitmap bitmap=PhotoFiles.decode(getApplication(),original,0,false);
            try{return PhotoFiles.png(getApplication(),bitmap,"SEM_PERDAS");}finally{bitmap.recycle();}
        });}
        void gemini(){run("Gemini processando… isso pode levar alguns minutos","Cópia Gemini salva · compare com o original",()->GeminiClient.enhance(getApplication(),original));}
        private void run(String working,String success,Job job){
            if(busy)return;busy=true;state.setValue(new ReviewState(current,working,true));
            worker.execute(()->{
                Uri result=null;String outcome;
                try{result=job.run();outcome=success;}
                catch(OutOfMemoryError e){outcome="Memória insuficiente para editar. O original está salvo; feche outros apps e tente novamente.";}
                catch(java.net.SocketTimeoutException e){outcome="O Gemini demorou para responder. O original está salvo. Confira o uso da API antes de tentar de novo.";}
                catch(Exception e){outcome=e instanceof java.io.IOException||e instanceof IllegalStateException?e.getMessage():"Não foi possível processar a foto. O original está salvo.";}
                Uri saved=result;String text=outcome;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                    busy=false;if(saved!=null){current=saved;latest=saved;latestLabel=success;}
                    state.setValue(new ReviewState(current,text==null?"Falha no processamento. Original preservado.":text,false));
                });
            });
        }
        @Override protected void onCleared(){worker.shutdown();}
    }
}
