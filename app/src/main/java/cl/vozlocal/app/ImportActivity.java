package cl.vozlocal.app;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;

public class ImportActivity extends Screen {
    private EditText title,start,end;private TextView stage,detail,percent,error;private Button save,cancel;private ProgressBar progress;private LinearLayout progressPanel,form;
    private final Handler handler=new Handler(Looper.getMainLooper());private android.media.MediaPlayer preview;private String boundId="";private boolean visible;
    private final Runnable poll=new Runnable(){public void run(){if(!visible)return;render();handler.postDelayed(this,250);}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);setup("Importar audio","Elige un audio completo o guarda solo el tramo que necesitas.");
        progressPanel=card("Preparando tu audio");stage=text("Leyendo archivo",24,INK);stage.setTypeface(null,Typeface.BOLD);stage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);progressPanel.addView(stage);
        detail=text("",16,MUTED);detail.setPadding(0,dp(12),0,dp(12));progressPanel.addView(detail);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(ColorStateList.valueOf(GREEN));progress.setIndeterminateTintList(ColorStateList.valueOf(GREEN));progressPanel.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        percent=text("",18,GREEN);percent.setTypeface(null,Typeface.BOLD);percent.setPadding(0,dp(10),0,dp(4));progressPanel.addView(percent);
        help(progressPanel,"Puedes salir de esta pantalla o bloquear el teléfono. La preparación continuará y el original se conserva.");cancel=button("Cancelar importación",false);cancel.setOnClickListener(v->confirmCancel());progressPanel.addView(cancel);
        form=card("Audio y tramo");error=text("",16,INK);error.setPadding(0,dp(6),0,dp(12));form.addView(error);
        title=input(form,"Título","Nombre de la grabación");title.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        start=input(form,"Desde (segundos)","0");start.setText("0");end=input(form,"Hasta (segundos)","Duración total");start.setInputType(8194);end.setInputType(8194);
        help(form,"El tramo completo conserva la calidad del AAC original. Para otros formatos o recortes se crea una nueva copia M4A.");
        Button listen=button("Escuchar el tramo",false);listen.setOnClickListener(v->listen());form.addView(listen);
        save=button("Guardar audio",true);save.setOnClickListener(v->{try{ImportSession s=ImportService.session(this);if(s==null||s.busy||!s.ready||s.done)return;long from=seconds(start),to=seconds(end);if(from<0||to-from<500||to>s.duration+100)throw new IllegalArgumentException();String label=title.getText().toString().trim();if(label.isEmpty())label="Audio importado";release();ImportService.save(this,label,from,Math.min(to,s.duration));render();}catch(Exception e){message("Revisa el tramo","El final debe superar el inicio por al menos medio segundo y no pasar la duración del archivo.");}});form.addView(save);
        Button choose=button("Elegir otro archivo",false);choose.setOnClickListener(v->{release();ImportService.dismiss(this);boundId="";pick();});form.addView(choose);
        if(saved!=null){boundId=saved.getString("boundId","");title.setText(saved.getString("title",""));start.setText(saved.getString("from","0"));end.setText(saved.getString("to",""));}
        ImportSession existing=ImportService.session(this);if(existing!=null&&(existing.cancelled||existing.done)){ImportService.dismiss(this);existing=null;}
        if(existing==null){Uri uri=getIntent().getParcelableExtra(Intent.EXTRA_STREAM);String id=getIntent().getStringExtra("sourceId");if(uri!=null||id!=null)ImportService.load(this,uri,id);else if(saved==null)pick();}
        render();
    }
    private void render(){ImportSession s=ImportService.session(this);boolean busy=s!=null&&s.busy;progressPanel.setVisibility(busy?View.VISIBLE:View.GONE);form.setVisibility(busy?View.GONE:View.VISIBLE);
        if(s==null){save.setEnabled(false);error.setText("Elige un archivo de tu grabadora o comparte un audio de WhatsApp con Voz local.");return;}
        if(s.cancelled&&!s.busy){ImportService.dismiss(this);if(visible)finish();return;}
        if(busy){stage.setText(s.cancelled?"Cancelando importación":s.stage);detail.setText(ImportService.progressText(s));progress.setIndeterminate(s.total<=0);progress.setProgress(ImportService.percent(s));percent.setText(s.total<=0?"":ImportService.percent(s)+" %");cancel.setEnabled(!s.cancelled);cancel.setText(s.cancelled?"Cancelando…":"Cancelar importación");}
        error.setText(s.error.isEmpty()?(s.ready?"Duración: "+Recording.time(s.duration):s.stage):s.error);save.setEnabled(s.ready&&!s.busy&&!s.done&&!s.cancelled);save.setText(s.error.isEmpty()?"Guardar audio":"Volver a intentar");
        if(s.ready&&!boundId.equals(s.id)){boundId=s.id;title.setText(s.name);start.setText(number(s.from));end.setText(number(s.to>0?s.to:s.duration));}
        if(s.done&&!s.busy&&visible){ImportService.dismiss(this);startActivity(new Intent(this,MainActivity.class).putExtra("library",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}
    }
    private static String number(long ms){return String.format(java.util.Locale.ROOT,"%.3f",ms/1000d);}
    private void listen(){ImportSession s=ImportService.session(this);if(s==null||!s.ready||s.busy)return;try{long from=seconds(start),to=seconds(end);if(from<0||to<=from||to>s.duration+100)throw new IllegalArgumentException();release();preview=new android.media.MediaPlayer();preview.setDataSource(s.source.getPath());preview.prepare();preview.seekTo((int)from);preview.start();final android.media.MediaPlayer player=preview;handler.postDelayed(()->{if(preview==player)release();},to-from);preview.setOnCompletionListener(p->release());}catch(Exception e){message("Escuchar audio","Revisa el tramo e inténtalo otra vez.");}}
    private void pick(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),61);}
    private static long seconds(EditText field){double value=Double.parseDouble(field.getText().toString().replace(',','.'));if(!Double.isFinite(value))throw new IllegalArgumentException();return (long)(value*1000);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==61){if(result==RESULT_OK&&data!=null&&data.getData()!=null){ImportService.load(this,data.getData(),null);render();}else if(ImportService.session(this)==null)finish();}}
    private void confirmCancel(){new AlertDialog.Builder(this).setTitle("¿Cancelar la importación?").setMessage("Se eliminará solo la copia temporal. Tu archivo original se conserva.").setNegativeButton("Continuar",null).setPositiveButton("Cancelar importación",(d,w)->{ImportService.cancel(this);render();}).show();}
    @Override public void onBackPressed(){release();super.onBackPressed();}
    @Override protected void onResume(){super.onResume();visible=true;handler.post(poll);}
    @Override protected void onPause(){visible=false;handler.removeCallbacks(poll);release();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("boundId",boundId);out.putString("title",title.getText().toString());out.putString("from",start.getText().toString());out.putString("to",end.getText().toString());super.onSaveInstanceState(out);}
    private void release(){if(preview!=null){preview.release();preview=null;}}
}
