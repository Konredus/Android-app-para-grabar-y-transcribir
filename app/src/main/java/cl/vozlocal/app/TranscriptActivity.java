package cl.vozlocal.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import org.json.*;
import java.util.*;

public class TranscriptActivity extends Screen {
    private String id;private boolean demo;private Transcript transcript;private Recording recording;private LinearLayout content;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);demo=getIntent().getBooleanExtra("demo",false);id=getIntent().getStringExtra("id");
        try{
            if(demo){recording=new Recording("00000000-0000-0000-0000-000000000000","Conversación de ejemplo",System.currentTimeMillis(),27000);java.io.File file=new java.io.File(getFilesDir(),"demo-transcript.json");transcript=file.exists()?new Transcript(FilesStore.read(file)):example();}
            else{recording=FilesStore.recording(this,id);if(recording==null)throw new Exception();transcript=Transcript.load(this,id);}
            setup(demo?"Prueba de voces":"Transcripción",recording.title);
            if(demo)help(page,"EJEMPLO · Texto de demostración, no generado por la API. Puedes editar los nombres sin enviar datos.");
            else help(page,"Cada etiqueta identifica una voz detectada. Revisa las asignaciones: el ruido o las voces superpuestas pueden producir errores.");
            LinearLayout actions=card("Quién está hablando");Button names=button("Nombrar hablantes",true);names.setOnClickListener(v->editSpeakers());actions.addView(names);
            Button share=button("Compartir transcripción",false);share.setOnClickListener(v->{try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,transcript.text(recording)),"Compartir transcripción"));}catch(Exception e){message("Transcripción","No se pudo compartir el texto.");}});actions.addView(share);
            if(!demo){Button sync=button("Sincronizar con Drive",false);sync.setOnClickListener(v->{try{if(!new Settings(this).driveConnected()){startActivity(new Intent(this,SettingsActivity.class));return;}Pipeline.request(this,id);message("En cola","Se actualizará la transcripción cuando se cumplan tus condiciones de conexión.");}catch(Exception e){message("No se pudo sincronizar",e.getMessage());}});actions.addView(sync);}
            if(transcript.data.optInt("parts",1)>1)help(page,"Este audio se procesó en bloques. Una persona puede aparecer con varias etiquetas: puedes asignarles el mismo nombre si reconoces la misma voz.");
            content=column();page.addView(content);render();
        }catch(Exception e){setup("Transcripción","No se pudo abrir esta grabación.");help(page,"Vuelve a la biblioteca y comprueba su estado.");}
    }
    static Transcript example()throws Exception{return new Transcript(new JSONObject("{\"demo\":true,\"names\":{},\"segments\":[{\"speaker\":\"A\",\"start\":0,\"end\":7,\"text\":\"Me gustaría que guardemos las ideas de esta conversación.\"},{\"speaker\":\"B\",\"start\":7,\"end\":14,\"text\":\"Sí, y después podemos revisar juntos lo que dijimos.\"},{\"speaker\":\"C\",\"start\":14,\"end\":20,\"text\":\"Yo puedo ayudar a ordenar los próximos pasos.\"},{\"speaker\":\"A\",\"start\":20,\"end\":27,\"text\":\"Perfecto. Así no se nos pierde ninguna idea.\"}]}"));}
    private void render()throws Exception{
        content.removeAllViews();Map<String,String> names=transcript.speakers();JSONArray segments=transcript.segments();
        if(segments.length()==0){help(content,"No se detectó habla en este audio.");return;}
        for(int i=0;i<segments.length();i++){JSONObject segment=segments.getJSONObject(i);String speaker=names.get(segment.getString("speaker"));label(content,speaker+"  ·  "+Recording.time((long)(segment.getDouble("start")*1000)));android.widget.TextView text=text(segment.getString("text"),17,INK);text.setTextIsSelectable(true);text.setPadding(0,0,0,dp(18));content.addView(text);}
    }
    private void editSpeakers(){try{
        ScrollView scroll=new ScrollView(this);LinearLayout box=column();box.setPadding(dp(24),dp(8),dp(24),dp(8));scroll.addView(box);
        Map<String,EditText> fields=new LinkedHashMap<>();int index=0;
        for(Map.Entry<String,String> person:transcript.speakers().entrySet()){EditText field=input(box,"Persona "+(++index),"Nombre opcional");field.setText(person.getValue());field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});fields.put(person.getKey(),field);}
        help(box,"Se cambiará el nombre en todas sus intervenciones. Puedes usar el mismo nombre en varias etiquetas.");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Nombrar hablantes").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Guardar nombres",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{Map<String,String> names=new LinkedHashMap<>();for(Map.Entry<String,EditText> field:fields.entrySet())names.put(field.getKey(),field.getValue().getText().toString().trim());
            if(demo){JSONObject mapping=new JSONObject();for(Map.Entry<String,String> name:names.entrySet())mapping.put(name.getKey(),name.getValue());transcript.data.put("names",mapping);FilesStore.write(new java.io.File(getFilesDir(),"demo-transcript.json"),transcript.data);}
            else{Transcript.rename(this,id,names);transcript=Transcript.load(this,id);}render();dialog.dismiss();Toast.makeText(this,"Nombres guardados",Toast.LENGTH_SHORT).show();
        }catch(Exception e){message("No se guardaron los nombres","Vuelve a intentarlo.");}}));dialog.show();
    }catch(Exception e){message("Transcripción","No se pudieron cargar los hablantes.");}}
}
