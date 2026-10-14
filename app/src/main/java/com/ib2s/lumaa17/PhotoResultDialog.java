package com.ib2s.lumaa17;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** Full-screen in-app before/after preview, with zoom and gallery link. */
final class PhotoResultDialog {
    private PhotoResultDialog() {}

    static void show(Activity activity, ExecutorService executor, Uri original, Uri processed,
                     String details) {
        if(activity.isFinishing()||activity.isDestroyed())return;
        final int bg=0xFF101113;
        LinearLayout panel=new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity,10),dp(activity,10),dp(activity,10),dp(activity,10));
        panel.setBackgroundColor(bg);
        TextView title=new TextView(activity);
        title.setText("Imagem processada • original preservado");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setPadding(dp(activity,10),dp(activity,8),0,dp(activity,10));
        panel.addView(title);
        TextView label=new TextView(activity);
        label.setText(details+"\nCarregando pré-visualização…");
        label.setTextColor(0xFFE2E2E2);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        panel.addView(label,new LinearLayout.LayoutParams(-1,dp(activity,64)));
        FrameLayout viewport=new FrameLayout(activity);
        viewport.setBackgroundColor(Color.BLACK);
        viewport.setClipChildren(true);
        ImageView image=new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("Prévia ampliável da imagem selecionada. Use dois dedos para aproximar.");
        viewport.addView(image,new FrameLayout.LayoutParams(-1,-1));
        panel.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout controls=new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER);
        Button before=new Button(activity),after=new Button(activity);
        before.setText("Original");
        after.setText("Processada");
        controls.addView(before,new LinearLayout.LayoutParams(0,dp(activity,55),1));
        controls.addView(after,new LinearLayout.LayoutParams(0,dp(activity,55),1));
        panel.addView(controls);
        TextView hint=new TextView(activity);
        hint.setText("Toque nos botões para comparar. Pinça para zoom de até 5×.");
        hint.setTextSize(11);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(0xFFAEB3B9);
        panel.addView(hint,new LinearLayout.LayoutParams(-1,dp(activity,35)));

        AlertDialog dialog=new AlertDialog.Builder(activity).setView(panel)
                .setPositiveButton("Fechar",null)
                .setNeutralButton("Abrir na galeria",(d,w)-> {
                    try {
                        String type=activity.getContentResolver().getType(processed);
                        activity.startActivity(new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(processed,type==null?"image/jpeg":type)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    }catch(ActivityNotFoundException ex){}
                }).create();
        dialog.show();
        if(dialog.getWindow()!=null){
            dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
        final Bitmap[] previews=new Bitmap[2];
        final boolean[] processedSelected={true};
        final float[] zoom={1f};
        ScaleGestureDetector detector=new ScaleGestureDetector(activity,
                new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                    @Override public boolean onScale(ScaleGestureDetector d){
                        zoom[0]=Math.max(1f,Math.min(5f,zoom[0]*d.getScaleFactor()));
                        image.setScaleX(zoom[0]);image.setScaleY(zoom[0]);
                        return true;
                    }
                });
        image.setOnTouchListener((v,event)-> {
            detector.onTouchEvent(event);
            if(event.getAction()==android.view.MotionEvent.ACTION_UP) v.performClick();
            return true;
        });
        Runnable update=()->{
            int which=processedSelected[0]?1:0;
            Bitmap selected=previews[which];
            if(selected!=null){
                image.setImageBitmap(selected);
                zoom[0]=1;image.setScaleX(1);image.setScaleY(1);
                label.setText((processedSelected[0]?"PROCESSADA":"ORIGINAL")+"\n"+details);
            }
            before.setEnabled(processedSelected[0]);
            after.setEnabled(!processedSelected[0]);
        };
        before.setOnClickListener(v->{processedSelected[0]=false;update.run();});
        after.setOnClickListener(v->{processedSelected[0]=true;update.run();});
        dialog.setOnDismissListener(d->{
            // Do not recycle bitmaps while the ImageView still references them.
            image.setImageDrawable(null);
            for(Bitmap b:previews)if(b!=null&&!b.isRecycled())b.recycle();
        });
        executor.execute(()->{
            Bitmap orig=null,edited=null;
            try{
                orig=decode(activity,original);
                edited=decode(activity,processed);
            }catch(Exception ex){
                final String message=ex.getMessage();
                activity.runOnUiThread(()->{
                    if(dialog.isShowing())label.setText("Não foi possível abrir a prévia: "+message);
                });
            }
            final Bitmap a=orig,b=edited;
            activity.runOnUiThread(()->{
                if(!dialog.isShowing()){
                    if(a!=null)a.recycle();if(b!=null)b.recycle();
                    return;
                }
                previews[0]=a;previews[1]=b;
                update.run();
            });
        });
    }

    private static Bitmap decode(Activity a,Uri uri)throws IOException{
        ImageDecoder.Source source=ImageDecoder.createSource(a.getContentResolver(),uri);
        return ImageDecoder.decodeBitmap(source,(decoder,info,s)->{
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w=info.getSize().getWidth(),h=info.getSize().getHeight();
            float scale=Math.min(1f,1600f/Math.max(w,h));
            decoder.setTargetSize(Math.max(1,Math.round(w*scale)),
                    Math.max(1,Math.round(h*scale)));
        });
    }
    private static int dp(Activity a,int value){
        return (int)(a.getResources().getDisplayMetrics().density*value+.5f);
    }
}
