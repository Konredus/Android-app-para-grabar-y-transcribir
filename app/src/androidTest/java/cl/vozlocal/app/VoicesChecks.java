package cl.vozlocal.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.File;
import java.util.*;

/**
 * Voces conocidas (0.6.0): migración desde «Mi voz», biblioteca (agregar, ordenar, renombrar, usar o no, reemplazar,
 * eliminar), muestras que van en cada envío (orden, tope, nombres enviados y voz destino), huella y unión de bloques.
 * Sin red: las muestras son archivos de prueba. La carpeta de voces real se aparta antes y se devuelve al final.
 */
final class VoicesChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static JSONObject seg(String who,double a,double b,String text)throws JSONException{return new JSONObject().put("speaker",who).put("start",a).put("end",b).put("text",text);}
    /** Una «muestra» de prueba: basta con que pese más que el mínimo (nadie la decodifica). */
    private static File clip(Context c,int bytes)throws Exception{
        File f=new File(c.getCacheDir(),"voices-check-"+System.nanoTime()+".m4a");byte[] data=new byte[bytes];new Random(bytes).nextBytes(data);
        java.nio.file.Files.write(f.toPath(),data);return f;
    }
    private static void deleteTree(File f){if(f==null||!f.exists())return;File[] kids=f.listFiles();if(kids!=null)for(File k:kids)deleteTree(k);f.delete();}

    static void run(Context c,Recording r)throws Exception{
        File dir=new File(c.getFilesDir(),"voices"),backup=new File(c.getFilesDir(),"voices-backup-check");
        SharedPreferences prefs=new Settings(c).prefs;boolean hadName=prefs.contains("myVoiceName");String oldName=prefs.getString("myVoiceName","");
        deleteTree(backup);boolean had=dir.exists();
        if(had&&!dir.renameTo(backup))throw new AssertionError("Could not set the real voices aside");
        List<File> temps=new ArrayList<>();
        try{
            migration(c,prefs,temps);
            library(c,temps);
            joining();
        }finally{
            for(File t:temps)t.delete();
            deleteTree(dir);
            if(had&&!backup.renameTo(dir))throw new AssertionError("Could not restore the real voices");
            SharedPreferences.Editor e=prefs.edit();if(hadName)e.putString("myVoiceName",oldName);else e.remove("myVoiceName");e.commit();
        }
    }

    // ---------- Migración desde «Mi voz» (0.5) ----------
    private static void migration(Context c,SharedPreferences prefs,List<File> temps)throws Exception{
        check(Voices.list(c).isEmpty()&&!Voices.has(c)&&Voices.reference(c)==null&&Voices.references(c).isEmpty()&&Voices.fingerprint(c).equals("none"),"Empty voice library not empty");
        // Sin nombre en Ajustes: «Yo».
        prefs.edit().remove("myVoiceName").commit();
        File me=clip(c,2000);temps.add(me);java.nio.file.Files.copy(me.toPath(),Voices.file(c).toPath());
        File index=new File(Voices.dir(c),"voices.json");check(!index.exists(),"Index exists before the migration");
        List<Voices.Voice> all=Voices.list(c);
        check(all.size()==1&&all.get(0).id.equals(Voices.ME_ID)&&all.get(0).me&&all.get(0).use&&all.get(0).name.equals("Yo"),"Old «Mi voz» not migrated with the default name");
        check(index.isFile(),"Migration did not write the index");
        // Con nombre en Ajustes: se usa ese (índice nuevo desde cero).
        new android.util.AtomicFile(index).delete();prefs.edit().putString("myVoiceName","Konrad Prueba").commit();
        Voices.Voice mine=Voices.get(c,Voices.ME_ID);
        check(mine!=null&&mine.name.equals("Konrad Prueba")&&Voices.has(c)&&Voices.name(c).equals("Konrad Prueba"),"Migration ignored the name in Settings");
        JSONArray saved=new JSONArray(new String(java.nio.file.Files.readAllBytes(index.toPath()),"UTF-8"));
        check(saved.length()==1&&saved.getJSONObject(0).getString("id").equals("me")&&saved.getJSONObject(0).getBoolean("me")&&saved.getJSONObject(0).getBoolean("use")&&saved.getJSONObject(0).has("createdAt"),"Index format wrong: "+saved);
        // Con solo tu voz, la huella es la de la 0.5 (una transcripción en curso al actualizar no pierde sus partes).
        File f=Voices.file(c);check(Voices.fingerprint(c).equals(f.length()+"-"+f.lastModified()),"Single-voice fingerprint changed format");
        String[] ref=Voices.reference(c);
        check(ref!=null&&ref[0].equals(Voices.MINE)&&ref[1].startsWith("data:audio/mp4;base64,")&&ref[2].equals(Voices.ME)&&ref[3].contains("(tú)"),"Legacy reference wrong");
        // setName (API anterior) también renombra la voz guardada.
        Voices.setName(c,"Konrad");check(Voices.name(c).equals("Konrad")&&prefs.getString("myVoiceName","").equals("Konrad"),"setName did not rename my voice");
        // delete (API anterior) borra la muestra y la entrada.
        Voices.delete(c);check(!Voices.has(c)&&Voices.reference(c)==null&&!Voices.file(c).exists()&&Voices.list(c).isEmpty(),"Legacy delete left my voice behind");
        check(Voices.name(c).equals("Konrad"),"Name lost after deleting the voice");
    }

    // ---------- Biblioteca ----------
    private static void library(Context c,List<File> temps)throws Exception{
        String none=Voices.fingerprint(c);
        File a=clip(c,2000);temps.add(a);
        Voices.Voice fran=Voices.add(c,"Fran",a,false);
        check(fran.id.matches("[a-z0-9]{6,12}")&&!fran.me&&fran.use&&fran.name.equals("Fran")&&fran.createdAt>0,"New voice id or fields wrong: "+fran.id);
        check(!a.exists()&&Voices.file(c,fran.id).length()==2000,"Clip not moved into the library");
        String f1=Voices.fingerprint(c);check(!f1.equals(none),"Fingerprint did not change when adding a voice");
        File b=clip(c,2100);temps.add(b);Voices.Voice ana=Voices.add(c,"ana",b,false);
        File m=clip(c,2200);temps.add(m);Voices.Voice me=Voices.add(c,"Konrad",m,true);
        check(me.id.equals(Voices.ME_ID)&&me.me&&Voices.has(c),"My voice not added");
        // Orden: la tuya primero y después por nombre (sin distinguir mayúsculas).
        List<Voices.Voice> all=Voices.list(c);
        check(all.size()==3&&all.get(0).me&&all.get(1).id.equals(ana.id)&&all.get(2).id.equals(fran.id),"Voice order wrong");
        check(Voices.get(c,fran.id).name.equals("Fran")&&Voices.get(c,"zzzzzz")==null&&Voices.get(c,"../x")==null,"get wrong");
        boolean rejected=false;try{Voices.file(c,"../secreto");}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Voice id used as a path");
        rejected=false;try{Voices.add(c,"Vacía",new File(c.getCacheDir(),"no-existe.m4a"),false);}catch(java.io.IOException e){rejected=true;}check(rejected,"Voice added without audio");
        // Tu voz de nuevo: reemplaza la muestra, no crea otra.
        File m2=clip(c,2300);temps.add(m2);Voices.add(c,"Konrad",m2,true);check(Voices.list(c).size()==3&&Voices.file(c).length()==2300,"Recording my voice again created a duplicate");

        // Renombrar: cambia el orden y el nombre de tu voz en Ajustes.
        check(Voices.rename(c,fran.id,"Francisca")&&Voices.get(c,fran.id).name.equals("Francisca"),"Rename failed");
        check(!Voices.rename(c,fran.id,"   ")&&Voices.get(c,fran.id).name.equals("Francisca"),"Blank rename accepted");
        Voices.rename(c,ana.id,"Zoe");all=Voices.list(c);check(all.get(1).id.equals(fran.id)&&all.get(2).id.equals(ana.id),"Order not updated after renaming");
        Voices.rename(c,Voices.ME_ID,"Konrad P");check(Voices.name(c).equals("Konrad P")&&new Settings(c).prefs.getString("myVoiceName","").equals("Konrad P"),"Renaming my voice did not update Settings");
        check(Voices.findByName(c,"francisca").id.equals(fran.id)&&Voices.findByName(c,"Nadie")==null,"findByName wrong");
        check("Francisca".equals(Voices.nameFor(c,"voice:"+fran.id))&&"Konrad P".equals(Voices.nameFor(c,Voices.ME))&&Voices.nameFor(c,"block0:A")==null&&Voices.nameFor(c,"voice:zzzzzz")==null,"nameFor wrong");

        // Muestras de cada envío: la tuya primero, luego por nombre; nombres enviados únicos y voz destino por id.
        List<String[]> refs=Voices.references(c);
        check(refs.size()==3,"References count wrong: "+refs.size());
        check(refs.get(0)[0].equals(Voices.MINE)&&refs.get(0)[2].equals(Voices.ME),"My voice not first");
        check(refs.get(1)[0].equals("voz_"+fran.id)&&refs.get(1)[2].equals("voice:"+fran.id)&&refs.get(1)[3].equals("Francisca")&&refs.get(1)[1].startsWith("data:audio/mp4;base64,"),"Saved voice reference wrong");
        check(refs.get(2)[0].equals("voz_"+ana.id)&&refs.get(2)[2].equals("voice:"+ana.id),"Second saved voice reference wrong");
        check(Voices.people(Voices.selected(c)).equals("a ti, a Francisca y a Zoe"),"People phrase wrong: "+Voices.people(Voices.selected(c)));
        // 0.9.0: la misma frase en inglés y portugués (la usan «Volver a transcribir» y «¿Separar voces?»).
        try{Lang.override(Lang.EN);String en=Voices.people(Voices.selected(c));Lang.override(Lang.PT);String pt=Voices.people(Voices.selected(c));
            check(en.equals("you, Francisca and Zoe")&&pt.equals("você, Francisca e Zoe"),"People phrase in other languages wrong: "+en+" / "+pt);}
        finally{Lang.override(Lang.ES);}

        // Usar o no: la voz apagada no se envía y la huella cambia.
        String before=Voices.fingerprint(c);
        check(Voices.setUse(c,ana.id,false)&&!Voices.get(c,ana.id).use,"setUse failed");
        String off=Voices.fingerprint(c);check(!off.equals(before),"Fingerprint did not change when a voice stopped being used");
        refs=Voices.references(c);check(refs.size()==2&&!refs.get(1)[2].equals("voice:"+ana.id)&&Voices.used(c).size()==2&&Voices.list(c).size()==3,"Unused voice still sent");
        Voices.setUse(c,ana.id,true);check(Voices.fingerprint(c).equals(before),"Fingerprint not stable");

        // Tope: a lo más MAX_KNOWN por envío, siempre con la tuya primero.
        List<String> extra=new ArrayList<>();
        for(String n:new String[]{"Beto","Carla","Dani"}){File x=clip(c,2400);temps.add(x);extra.add(Voices.add(c,n,x,false).id);}
        refs=Voices.references(c);
        check(refs.size()==Transcriber.MAX_KNOWN&&refs.get(0)[2].equals(Voices.ME)&&Voices.selected(c).size()==Transcriber.MAX_KNOWN&&Voices.used(c).size()==6,"References not capped at "+Transcriber.MAX_KNOWN);
        Set<String> names=new HashSet<>();for(String[] ref:refs)check(names.add(ref[0]),"Duplicate sent name "+ref[0]);
        check(refs.get(1)[3].equals("Beto")&&refs.get(3)[3].equals("Dani"),"Capped references not in name order");

        // Reemplazar la muestra: se mueve el archivo nuevo y la huella cambia.
        String prev=Voices.fingerprint(c);File y=clip(c,3000);temps.add(y);
        Voices.replaceAudio(c,fran.id,y);check(!y.exists()&&Voices.file(c,fran.id).length()==3000&&!Voices.fingerprint(c).equals(prev),"replaceAudio failed");
        rejected=false;try{File z=clip(c,2000);temps.add(z);Voices.replaceAudio(c,"zzzzzz",z);}catch(java.io.IOException e){rejected=true;}check(rejected,"Audio replaced for a voice that does not exist");

        // Eliminar: muestra y entrada; la huella cambia.
        prev=Voices.fingerprint(c);
        check(Voices.remove(c,fran.id)&&Voices.get(c,fran.id)==null&&!Voices.file(c,fran.id).exists()&&!Voices.fingerprint(c).equals(prev),"Remove failed");
        check(!Voices.remove(c,fran.id),"Removing twice reported success");
        for(String id:extra)Voices.remove(c,id);Voices.remove(c,ana.id);Voices.remove(c,Voices.ME_ID);
        check(Voices.list(c).isEmpty()&&Voices.fingerprint(c).equals("none"),"Library not empty after removing everything");

        // Segunda pasada: las voces conocidas ocupan lugares y no se les saca otra muestra (ni por id ni por nombre).
        JSONArray fixed=new JSONArray()
            .put(new JSONObject().put("id","voice:abcdef12").put("name","Fran").put("start",0).put("end",5))
            .put(new JSONObject().put("id","block0:A").put("name","fran").put("start",10).put("end",15))
            .put(new JSONObject().put("id","block0:B").put("name","").put("start",20).put("end",25))
            .put(new JSONObject().put("id","block0:C").put("name","Pedro").put("start",30).put("end",35))
            .put(new JSONObject().put("id","block0:D").put("name","").put("start",40).put("end",45));
        Map<String,String> known=new LinkedHashMap<>();known.put(Voices.ME,"Konrad");known.put("voice:abcdef12","Fran");
        List<String[]> plan=Transcriber.fixedPlan(fixed,known);
        check(plan.size()==Transcriber.MAX_KNOWN-2&&plan.get(0)[1].equals("block0:B")&&plan.get(1)[1].equals("block0:C"),"Second pass sampled a saved voice or used too many slots");
        check(Transcriber.fixedPlan(fixed,false).size()==Transcriber.MAX_KNOWN,"Plan without saved voices wrong");
        // Cada voz habla 16 s (las muestras automáticas piden al menos 10 s de habla).
        JSONArray talk=new JSONArray();String[] who={"block0:A","block0:B","block0:C",Voices.ME};
        for(int round=0;round<2;round++)for(int k=0;k<who.length;k++){double at=round*90+k*20;talk.put(seg(who[k],at,at+8,"esta voz habla tranquila un rato bastante largo"));}
        Transcript t=new Transcript(new JSONObject().put("diarized",true).put("reviewed",true)
            .put("names",new JSONObject().put("block0:A","Fran").put("block0:C","Pedro")).put("segments",talk));
        JSONArray picked=Retranscribe.pickKnown(t,known);
        Set<String> ids=new HashSet<>();for(int i=0;i<picked.length();i++)ids.add(picked.getJSONObject(i).getString("id"));
        check(picked.length()==2&&ids.contains("block0:B")&&ids.contains("block0:C"),"pickKnown sampled a saved voice: "+ids);
        known.put("voice:x1","Ana");known.put("voice:x2","Beto");
        check(Retranscribe.pickKnown(t,known).length()==0&&Transcriber.fixedPlan(fixed,known).isEmpty(),"Samples added although 4 saved voices fill every slot");
        Retranscribe.Facts facts=new Retranscribe.Facts();facts.transcribed=true;facts.hasKey=true;facts.openai=true;facts.canSeparate=true;facts.diarized=true;facts.confirmed=true;facts.samples=0;facts.saved=Transcriber.MAX_KNOWN;
        check(String.valueOf(Retranscribe.reason(facts,Retranscribe.Mode.CORRECTIONS)).contains("voces conocidas"),"Full slots not explained");
    }

    // ---------- Unión de bloques con voces conocidas ----------
    private static void joining()throws Exception{
        String id="k3j9x2ab";
        JSONObject known=new JSONObject().put("voz_"+id,"voice:"+id).put(Voices.MINE,Voices.ME);
        JSONObject one=new JSONObject().put("_known",known).put("segments",new JSONArray()
            .put(seg("voz_"+id,0,4,"Hola, soy la Fran")).put(seg(Voices.MINE,4,8,"Y yo soy Konrad")).put(seg("A",8,12,"Yo soy nueva")));
        Transcript single=Transcript.fromParts(Collections.singletonList(one),Collections.singletonList(0d));JSONArray s=single.segments();
        check(s.getJSONObject(0).getString("speaker").equals("voice:"+id)&&s.getJSONObject(1).getString("speaker").equals(Voices.ME)&&s.getJSONObject(2).getString("speaker").equals("A"),"Saved voices not mapped in a single part");
        JSONObject two=new JSONObject().put("_known",known).put("segments",new JSONArray().put(seg("voz_"+id,0,4,"Sigo yo, la Fran")).put(seg("A",4,8,"Otra persona")));
        Transcript joined=Transcript.fromParts(Arrays.asList(new JSONObject(one.toString()),two),Arrays.asList(0d,600d));JSONArray j=joined.segments();
        check(j.getJSONObject(0).getString("speaker").equals("voice:"+id)&&j.getJSONObject(3).getString("speaker").equals("voice:"+id)
            &&j.getJSONObject(2).getString("speaker").equals("block0:A")&&j.getJSONObject(4).getString("speaker").equals("block1:A"),"Saved voices not the same person across parts");
        check(joined.speakers().size()==4,"Saved voice split across parts");
    }
}
