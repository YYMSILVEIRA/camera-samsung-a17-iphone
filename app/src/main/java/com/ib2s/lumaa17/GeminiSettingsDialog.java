package com.ib2s.lumaa17;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class GeminiSettingsDialog {
    private GeminiSettingsDialog(){}
    public static void show(Activity activity){
        GeminiKeyStore keys=new GeminiKeyStore(activity);
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(20*activity.getResources().getDisplayMetrics().density);box.setPadding(pad,pad,pad,pad);
        TextView help=new TextView(activity);help.setText("Opcional: envie uma foto ao Google após capturar. Pode haver cobrança pela API e alteração de detalhes. O original fica salvo.\n\nUm código gen-lang-client-… é um ID de projeto, não uma chave. A chave fica protegida no armazenamento deste aparelho.");box.addView(help);
        EditText key=new EditText(activity);key.setSingleLine(true);key.setHint(keys.configured()?"Chave salva · digite para substituir":"Cole a chave de API");
        key.setContentDescription("Chave de API do Gemini");key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);box.addView(key);
        TextView label=new TextView(activity);label.setText("Modelo de edição de imagem");box.addView(label);
        EditText model=new EditText(activity);model.setSingleLine(true);model.setText(keys.model());model.setContentDescription("Modelo Gemini");box.addView(model);
        CheckBox fourK=new CheckBox(activity);fourK.setText("Solicitar saída 4K (maior custo)");fourK.setChecked(keys.size().equals("4K"));box.addView(fourK);
        Button studio=new Button(activity);studio.setText("Abrir Google AI Studio");studio.setOnClickListener(v -> {
            try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://aistudio.google.com/apikey")));}
            catch(android.content.ActivityNotFoundException e){Toast.makeText(activity,"Acesse aistudio.google.com/apikey no navegador.",Toast.LENGTH_LONG).show();}
        });box.addView(studio);
        ScrollView scroll=new ScrollView(activity);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Gemini · chave pessoal").setView(scroll)
                .setNegativeButton("Cancelar",null).setNeutralButton("Remover chave",(d,w)->keys.clear()).setPositiveButton("Salvar",null).create();
        dialog.setOnShowListener(d -> {
            if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value=key.getText().toString().trim(),modelName=model.getText().toString().trim();
                if(!modelName.matches("[a-zA-Z0-9._-]{1,100}")){model.setError("Informe apenas o nome do modelo.");return;}
                try{
                    if(!value.isEmpty())keys.save(value);
                    keys.options(modelName,fourK.isChecked()?"4K":"2K");key.setText("");dialog.dismiss();
                }catch(IllegalArgumentException e){key.setError(e.getMessage());}
                catch(Exception e){key.setError("Não foi possível proteger a chave. Tente novamente.");}
            });
        });dialog.show();
    }
}
