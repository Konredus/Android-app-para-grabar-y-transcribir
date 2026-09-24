package cl.vozlocal.app;

import android.content.*;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Importar: 1) elegir archivo, 2) ver progreso de preparación, 3) nombrar y (opcional) recortar, 4) guardar.
 * Se muestra un solo paso a la vez; la acción principal queda fija abajo al alcance del pulgar.
 */
public class ImportActivity extends Screen {
    private EditText title,start,end;private TextView stage,detail,percent,fileName,fileMeta,problem;private Ui.Btn save,cancel;private ProgressBar progress;
    private LinearLayout picker,progressPanel,form;private RangeView range;private boolean syncing;
    private final Handler handler=new Handler(Looper.getMainLooper());private android.media.MediaPlayer preview;private String boundId="";private boolean visible;
    private final Runnable poll=new Runnable(){public void run(){if(!visible)return;render();handler.postDelayed(this,250);}};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);shell("Grabar",-1);largeTitle(page,"Importar audio","Grabadoras, notas de voz y audios de WhatsApp.");

        picker=ui.column();picker.setGravity(Gravity.CENTER_HORIZONTAL);picker.setPadding(ui.dp(S5),ui.dp(S8),ui.dp(S5),ui.dp(S8));picker.setBackground(ui.ripple(outline(this,p.card,p.outline,R_CARD,true),R_CARD));
        picker.addView(ui.tile(R.drawable.ic_upload,p.primary,p.primaryContainer,56,28));TextView pt=ui.text("Elegir un archivo de audio",Type.TITLE_MEDIUM,p.onSurface);pt.setGravity(Gravity.CENTER);pt.setPadding(0,ui.dp(S3),0,ui.dp(S1));picker.addView(pt,Ui.wrap());
        picker.addView(ui.text("Hasta 1 GB · se conserva el original",Type.BODY_MEDIUM,p.onSurfaceVariant),Ui.wrap());
        LinearLayout formats=ui.row();formats.setPadding(0,ui.dp(S4),0,0);for(String f:new String[]{"M4A","MP3","WAV","OGG","OPUS"}){TextView c=ui.chip(f,p.primary,p.primaryContainer);LinearLayout.LayoutParams lp=Ui.wrap();lp.setMargins(ui.dp(3),0,ui.dp(3),0);formats.addView(c,lp);}picker.addView(formats,Ui.wrap());
        picker.setClickable(true);picker.setContentDescription("Elegir un archivo de audio");picker.setAccessibilityDelegate(Ui.buttonRole());picker.setOnClickListener(v->pick());Ui.pressable(picker);
        page.addView(picker,Ui.fill());
        TextView share=ui.footnote("¿Es un audio de WhatsApp? Mantenlo presionado → Compartir → Voz local.");share.setPadding(ui.dp(S1),ui.dp(S3),0,0);page.addView(share);

        progressPanel=ui.card();progressPanel.setPadding(ui.dp(S5),ui.dp(S5),ui.dp(S5),ui.dp(S4));
        stage=ui.text("Leyendo archivo",Type.TITLE_MEDIUM,p.onSurface);stage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);progressPanel.addView(stage);
        percent=ui.text("",Type.HEADLINE_MEDIUM,p.primary);percent.setFontFeatureSettings("tnum");percent.setPadding(0,ui.dp(S2),0,ui.dp(S2));progressPanel.addView(percent);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(ColorStateList.valueOf(p.primary));progress.setIndeterminateTintList(ColorStateList.valueOf(p.primary));progress.setProgressBackgroundTintList(ColorStateList.valueOf(p.surfaceContainerHighest));progressPanel.addView(progress,new LinearLayout.LayoutParams(-1,ui.dp(8)));
        detail=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);detail.setFontFeatureSettings("tnum");detail.setPadding(0,ui.dp(S2),0,ui.dp(S3));progressPanel.addView(detail);
        progressPanel.addView(ui.text("Puedes salir de esta pantalla o bloquear el teléfono: la preparación continúa.",Type.BODY_MEDIUM,p.onSurfaceVariant));
        cancel=ui.button("Cancelar importación",0,Ui.Style.PLAIN,v->confirmCancel());progressPanel.addView(cancel,ui.top(S2));
        page.addView(progressPanel,ui.top(S2));

        form=ui.column();page.addView(form,Ui.fill());
        problem=ui.text("",Type.BODY_MEDIUM,p.error);problem.setBackground(shape(this,p.errorContainer,R_CONTROL));problem.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S3),ui.dp(S3));form.addView(problem,ui.top(0));
        LinearLayout file=ui.card();file.setOrientation(LinearLayout.HORIZONTAL);file.setGravity(Gravity.CENTER_VERTICAL);file.addView(ui.tile(R.drawable.ic_wave,p.primary,p.primaryContainer,44,22));file.addView(ui.space(S3));
        LinearLayout ft=ui.column();fileName=ui.oneLine(ui.text("",Type.TITLE_MEDIUM,p.onSurface));fileMeta=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);ft.addView(fileName);ft.addView(fileMeta);file.addView(ft,new LinearLayout.LayoutParams(0,-2,1));
        Ui.Btn other=ui.button("Cambiar",0,Ui.Style.PLAIN,v->{release();ImportService.dismiss(this);boundId="";pick();});other.setPadding(ui.dp(S3),0,ui.dp(S1),0);file.addView(other);
        form.addView(file,ui.top(S3));
        form.addView(ui.section("Título"));title=ui.field("Nombre de la grabación","Título");title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});form.addView(title,Ui.fill());
        form.addView(ui.section("Tramo (opcional)"));
        LinearLayout trim=ui.card();range=new RangeView(this,p);trim.addView(range,new LinearLayout.LayoutParams(-1,ui.dp(48)));
        LinearLayout fields=ui.row();LinearLayout a=ui.column(),b=ui.column();start=ui.labeled(a,"Desde (seg.)","0");end=ui.labeled(b,"Hasta (seg.)","Final");start.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);end.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);start.setText("0");
        fields.addView(a,new LinearLayout.LayoutParams(0,-2,1));fields.addView(ui.space(S3));fields.addView(b,new LinearLayout.LayoutParams(0,-2,1));trim.addView(fields,Ui.fill());
        trim.addView(ui.button("Escuchar el tramo",R.drawable.ic_play,Ui.Style.TONAL,v->listen()),ui.top(S4));
        form.addView(trim,Ui.fill());
        form.addView(ui.footnote("El archivo completo conserva la calidad original. Si recortas o el formato no es AAC, se crea una copia M4A nueva."));
        range.setListener((from,to)->{syncing=true;start.setText(number(from));end.setText(number(to));syncing=false;});
        TextWatcher sync=new TextWatcher(){public void beforeTextChanged(CharSequence s,int x,int y,int z){}public void onTextChanged(CharSequence s,int x,int y,int z){}public void afterTextChanged(Editable e){if(syncing)return;ImportSession s=ImportService.session(ImportActivity.this);if(s==null||!s.ready)return;try{range.set(s.duration,seconds(start),seconds(end));}catch(Exception ignored){}}};
        start.addTextChangedListener(sync);end.addTextChangedListener(sync);

        save=ui.button("Guardar audio",R.drawable.ic_check,Ui.Style.PRIMARY,v->save());bottom.addView(save,Ui.fill());
        if(new Settings(this).automatic()&&new Settings(this).hasKey()){TextView n=ui.text("Se transcribirá automáticamente al guardar.",Type.BODY_MEDIUM,p.onSurfaceVariant);n.setGravity(Gravity.CENTER);n.setPadding(0,ui.dp(S2),0,0);bottom.addView(n,Ui.fill());}

        if(saved!=null){boundId=saved.getString("boundId","");title.setText(saved.getString("title",""));start.setText(saved.getString("from","0"));end.setText(saved.getString("to",""));}
        ImportSession existing=ImportService.session(this);if(existing!=null&&(existing.cancelled||existing.done)){ImportService.dismiss(this);existing=null;}
        if(existing==null){Uri uri=getIntent().getParcelableExtra(Intent.EXTRA_STREAM);String id=getIntent().getStringExtra("sourceId");if(uri!=null||id!=null)ImportService.load(this,uri,id);else if(saved==null)pick();}
        render();
    }
    private void save(){
        try{ImportSession s=ImportService.session(this);if(s==null||s.busy||!s.ready||s.done)return;long from=seconds(start),to=seconds(end);if(from<0||to-from<RangeView.MIN_GAP||to>s.duration+100)throw new IllegalArgumentException();
            String label=title.getText().toString().trim();if(label.isEmpty())label="Audio importado";release();ImportService.save(this,label,from,Math.min(to,s.duration));render();}
        catch(Exception e){message("Revisa el tramo","El final debe superar el inicio por al menos medio segundo y no pasar la duración del archivo.");}
    }
    private void render(){
        ImportSession s=ImportService.session(this);boolean busy=s!=null&&s.busy,ready=s!=null&&s.ready&&!busy;
        picker.setVisibility(s==null?View.VISIBLE:View.GONE);((View)page.getChildAt(page.indexOfChild(picker)+1)).setVisibility(s==null?View.VISIBLE:View.GONE);
        progressPanel.setVisibility(busy?View.VISIBLE:View.GONE);form.setVisibility(ready||(s!=null&&!busy&&!s.error.isEmpty())?View.VISIBLE:View.GONE);
        bottom.setVisibility(ready?View.VISIBLE:View.GONE);
        if(s==null)return;
        if(s.cancelled&&!s.busy){ImportService.dismiss(this);if(visible)finish();return;}
        if(busy){stage.setText(s.cancelled?"Cancelando…":s.stage);detail.setText(ImportService.progressText(s));progress.setIndeterminate(s.total<=0);progress.setProgress(ImportService.percent(s));percent.setText(s.total<=0?"":ImportService.percent(s)+" %");cancel.setEnabled(!s.cancelled);}
        problem.setVisibility(s.error.isEmpty()?View.GONE:View.VISIBLE);problem.setText(s.error);
        fileName.setText(s.name);fileMeta.setText(s.ready?"Duración "+Recording.time(s.duration):s.stage);
        save.setEnabled(ready&&!s.done&&!s.cancelled);save.setText(s.error.isEmpty()?"Guardar audio":"Volver a intentar");
        if(s.ready&&!boundId.equals(s.id)){boundId=s.id;title.setText(s.name);syncing=true;start.setText(number(s.from));end.setText(number(s.to>0?s.to:s.duration));syncing=false;range.set(s.duration,s.from,s.to>0?s.to:s.duration);}
        if(s.done&&!s.busy&&visible){ImportService.dismiss(this);startActivity(new Intent(this,MainActivity.class).putExtra("library",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}
    }
    private static String number(long ms){return String.format(java.util.Locale.ROOT,"%.1f",ms/1000d);}
    private void listen(){ImportSession s=ImportService.session(this);if(s==null||!s.ready||s.busy)return;try{long from=seconds(start),to=seconds(end);if(from<0||to<=from||to>s.duration+100)throw new IllegalArgumentException();release();preview=new android.media.MediaPlayer();preview.setDataSource(s.source.getPath());preview.prepare();preview.seekTo((int)from);preview.start();final android.media.MediaPlayer player=preview;handler.postDelayed(()->{if(preview==player)release();},to-from);preview.setOnCompletionListener(mp->release());toast("Reproduciendo el tramo…");}catch(Exception e){message("Escuchar","Revisa el tramo e inténtalo otra vez.");}}
    private void pick(){try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),61);}catch(ActivityNotFoundException e){message("Importar","Este teléfono no tiene un selector de archivos disponible.");}}
    private static long seconds(EditText field){double value=Double.parseDouble(field.getText().toString().replace(',','.'));if(!Double.isFinite(value))throw new IllegalArgumentException();return (long)(value*1000);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==61){if(result==RESULT_OK&&data!=null&&data.getData()!=null){ImportService.load(this,data.getData(),null);render();}else if(ImportService.session(this)==null)render();}}
    private void confirmCancel(){confirm("¿Cancelar la importación?","Se elimina solo la copia temporal. Tu archivo original se conserva.","Cancelar importación",true,()->{ImportService.cancel(this);render();});}
    @Override public void onBackPressed(){release();super.onBackPressed();}
    @Override protected void onResume(){super.onResume();visible=true;handler.post(poll);}
    @Override protected void onPause(){visible=false;handler.removeCallbacks(poll);release();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("boundId",boundId);out.putString("title",title.getText().toString());out.putString("from",start.getText().toString());out.putString("to",end.getText().toString());super.onSaveInstanceState(out);}
    private void release(){if(preview!=null){preview.release();preview=null;}}
}
