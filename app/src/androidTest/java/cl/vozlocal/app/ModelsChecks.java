package cl.vozlocal.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pruebas de la parte «catalog» de la 0.8.0 (ver docs/diseno/SPEC-0.8.md). Sin red ni claves reales: el catálogo y la
 * comprobación de la clave responden desde un HttpApi falso, y las fechas de retiro se arman a partir de «ahora», así
 * el resultado no depende del día en que corre. Al terminar deja las preferencias y el catálogo guardado como estaban.
 */
final class ModelsChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    /** Mismo check con otro nombre: dentro de un HttpApi falso, «check» sería HttpApi.check(). */
    static void expect(boolean ok,String text){check(ok,text);}
    private static final long DAY=86_400_000L;
    private static final String MAI="microsoft/mai-transcribe-2",NOVA="deepgram/nova-3",GEMINI="google/gemini-3.5-transcribe",GROK="x-ai/grok-stt-1.0",GPT="openai/gpt-transcribe",NEW="acme/new-asr-1";
    /** Preferencias que tocan estas pruebas: se devuelven a su valor (o a su ausencia) al terminar. */
    private static final String[] PREFS={"provider","orSpeakersModel","orTextModel","orAutoSpeakers","orAutoText","orAutoBeforeSpeakers","orAutoBeforeText","noteProvider","noteModel","speakersMode","customBase","customModel","customSpeakers","orCatalogTried",
        "keyEncrypted","keyIv","openrouter_keyEncrypted","openrouter_keyIv","custom_keyEncrypted","custom_keyIv","anthropic_keyEncrypted","anthropic_keyIv"};

    static void run(Context c,Recording r)throws Exception{
        Settings s=new Settings(c);Map<String,Object> before=new HashMap<>(s.prefs.getAll());
        File cache=Models.file(c);byte[] saved=cache.isFile()?java.nio.file.Files.readAllBytes(cache.toPath()):null;
        try{
            recipes();
            long now=System.currentTimeMillis();
            prices();
            catalog(now);
            automatic(now);
            cache(c,s,now);
            resume(c,s);
            key();
            balance();
            billed();
            settings(c,s);
            onlyOpenRouter(s);
        }finally{
            SharedPreferences.Editor e=s.prefs.edit();
            for(String k:PREFS){Object v=before.get(k);
                if(v==null)e.remove(k);else if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);
                else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Float)e.putFloat(k,(Float)v);}
            e.commit();
            if(saved==null)new android.util.AtomicFile(cache).delete();else java.nio.file.Files.write(cache.toPath(),saved);
            Models.forget();
        }
    }

    // ---------- Datos de prueba ----------
    /** «2026-10-10»: la fecha (UTC) de un momento, como la entrega OpenRouter en expiration_date. */
    private static String day(long at){java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));return f.format(new java.util.Date(at));}
    private static JSONObject model(String id,String name,Object created,Object prompt)throws Exception{
        JSONObject m=new JSONObject().put("id",id).put("created",created).put("architecture",new JSONObject().put("output_modalities",new JSONArray().put("transcription")));
        if(name!=null)m.put("name",name);
        if(prompt!=null)m.put("pricing",new JSONObject().put("prompt",prompt).put("completion","0"));
        return m;
    }
    /**
     * Catálogo de ejemplo con lo que puede llegar: modelos de la tabla con precio por hora, por segundo y por tokens; uno
     * desconocido; uno que se retira en 10 días; uno ya retirado; un alias «~», una variante «:free», un id inválido, una
     * entrada sin id, otra que no es un objeto, campos null, números como texto y una entrada casi vacía.
     */
    private static JSONObject sample(long now)throws Exception{
        JSONArray data=new JSONArray();
        data.put(model(NEW,"Acme: New ASR 1",1790000000L,"0.00005"));
        data.put(model(MAI,"Microsoft: MAI Transcribe 2",1785000000L,"0.10").put("expiration_date",JSONObject.NULL));
        data.put(model(NOVA,"Deepgram: Nova-3",1780000000L,"0.0000722"));
        data.put(model(GEMINI,"Google: Gemini 3.5 Transcribe",1779000000L,"0.0000005"));
        data.put(model(GROK,"xAI: Grok STT 1.0",1770000000L,"0.0000278").put("expiration_date",day(now+10*DAY)));
        data.put(model("old/retired-asr","Old: Retired ASR",1700000000L,"0.0001").put("expiration_date",day(now-2*DAY)));
        data.put(model("~openai/transcribe-latest","Alias",1791000000L,"0.0001"));
        data.put(model("deepgram/nova-3:free","Variante gratis",1791000000L,"0"));
        data.put(model("bad id/with space","Id inválido",1791000000L,"0.0001"));
        data.put(new JSONObject().put("name","Sin id"));
        data.put("texto suelto");
        data.put(new JSONObject().put("id","bare/minimal"));
        data.put(model(GPT,null,"1760000000",0.000075).put("name",JSONObject.NULL));
        data.put(model(NOVA,"Deepgram: Nova-3 repetido",1600000000L,"0.5"));
        return new JSONObject().put("data",data);
    }
    private static List<String> ids(List<Models.Model> list){List<String> out=new ArrayList<>();for(Models.Model m:list)out.add(m.id);return out;}
    private static boolean near(double a,double b){return Math.abs(a-b)<1e-9;}
    /** HttpApi falso del catálogo: exige la dirección pública, sin clave ni cuerpo, y responde lo que se le dio. */
    private static HttpApi catalogHttp(int code,String body,int[] calls){
        return new HttpApi(){@Override Response request(String method,String url,String token,String type,Body content,Map<String,String> extra)throws Exception{
            calls[0]++;
            expect(method.equals("GET")&&url.equals("https://openrouter.ai/api/v1/models?output_modalities=transcription&sort=newest"),"El catálogo se pidió a otra dirección: "+url);
            expect(token==null||token.isEmpty(),"El catálogo es público: no debe viajar la clave");
            expect(content==null&&extra==null,"El catálogo no lleva cuerpo ni encabezados propios");
            return new Response(code,body,null);
        }};
    }

    // ---------- Recetas y precios ----------
    static void recipes(){
        Models.Recipe mai=Models.recipe(MAI),nova=Models.recipe(NOVA),unknown=Models.recipe(NEW),none=Models.recipe(null);
        check(mai.diarize==Models.Diarize.AZURE&&mai.diarizes&&mai.verified&&mai.unit==Models.Unit.HOUR,"Receta de MAI-Transcribe 2 cambiada");
        check(nova.diarize==Models.Diarize.DEEPGRAM&&nova.diarizes&&nova.unit==Models.Unit.SECOND,"Receta de Nova-3 cambiada");
        check(!Models.recipe(GPT).diarizes,"gpt-transcribe no separa voces");
        check(unknown.id.equals(NEW)&&!unknown.diarizes&&unknown.verbose&&unknown.unit==Models.Unit.UNKNOWN&&!unknown.verified,"Un modelo desconocido debe recibir la receta por defecto");
        check(none!=null&&none.id.isEmpty()&&!none.diarizes,"recipe(null) no debe fallar");
        check(Models.known(MAI)&&!Models.known(NEW)&&!Models.known(null),"known() no distingue la tabla local");
        check(Models.rank(MAI)<Models.rank(NOVA)&&Models.rank(NEW)==Integer.MAX_VALUE,"El orden de la tabla cambió");
        check(Models.DEFAULT_SPEAKERS.equals(MAI)&&Models.recipe(Models.DEFAULT_SPEAKERS).diarizes,"El recomendado con voces debe saber separar voces");
        check(Models.NOTE_MODELS.length==Models.NOTE_NAMES.length&&Models.NOTE_DEFAULT.equals(Models.NOTE_MODELS[0])&&Models.NOTE_DEFAULT.startsWith("~"),"Alias de la nota inconsistentes");
        List<Models.Model> builtin=Models.builtin();
        check(builtin.size()>=2&&builtin.get(0).id.equals(MAI)&&builtin.get(0).known&&builtin.get(0).approx&&near(builtin.get(0).perHour,0.10),"La lista local (sin red) debe partir por el recomendado, con precio de referencia");
    }
    static void prices(){
        check(near(Models.hourly(0.0001,Models.Unit.SECOND),0.36),"Precio por segundo: se multiplica por 3600");
        check(near(Models.hourly(0.10,Models.Unit.HOUR),0.10),"Precio por hora: va tal cual");
        check(Models.hourly(0.0000005,Models.Unit.TOKEN)==-1&&Models.hourly(0.0001,Models.Unit.UNKNOWN)==-1,"Por tokens o sin unidad no hay precio por hora");
        check(Models.hourly(0.10,Models.Unit.SECOND)==-1,"US$360 la hora no es un precio creíble: la unidad no calza");
        check(Models.hourly(0.0000278,Models.Unit.HOUR)==-1,"Un precio por hora casi nulo no es creíble: la unidad no calza");
        check(Models.hourly(Double.NaN,Models.Unit.SECOND)==-1&&Models.hourly(0,Models.Unit.SECOND)==-1&&Models.hourly(-1,Models.Unit.HOUR)==-1,"Sin precio, o en cero, no se inventa uno");
        check(near(Models.reference(GEMINI),0.30)&&Models.reference(NEW)==-1&&Models.reference(null)==-1,"Precio de referencia mal resuelto");
        check(SettingsActivity.hourPrice(0.10).equals("US$0,10")&&SettingsActivity.hourPrice(0.25992).equals("US$0,26")&&SettingsActivity.hourPrice(0.036).equals("US$0,036")&&SettingsActivity.hourPrice(1.5).equals("US$1,50"),"Formato del precio por hora");
        check(SettingsActivity.money(4.2).equals("US$4,20")&&SettingsActivity.money(12).equals("US$12,00"),"Formato del saldo");
    }

    // ---------- Catálogo ----------
    static void catalog(long now)throws Exception{
        List<Models.Model> list=Models.parse(sample(now),now);
        // Quedan 7: fuera el retirado, el alias, la variante, el id inválido, la entrada sin id, el texto suelto y el repetido.
        check(ids(list).equals(java.util.Arrays.asList(NEW,MAI,NOVA,GEMINI,GROK,GPT,"bare/minimal")),"Modelos u orden inesperados: "+ids(list));
        Models.Model fresh=Models.find(list,NEW),mai=Models.find(list,MAI),nova=Models.find(list,NOVA),gemini=Models.find(list,GEMINI),grok=Models.find(list,GROK),gpt=Models.find(list,GPT),bare=Models.find(list,"bare/minimal");
        check(!fresh.known&&fresh.perHour==-1&&!fresh.approx&&!fresh.recipe.diarizes&&fresh.expires==0&&fresh.created==1790000000L,"Modelo desconocido: sin precio, sin voces y «sin probar»");
        check(Models.label(fresh).equals("New ASR 1")&&Models.vendor(fresh).equals("Acme"),"Nombre y autor del catálogo mal separados");
        check(mai.known&&near(mai.perHour,0.10)&&!mai.approx&&mai.recipe.diarizes&&mai.expires==0,"MAI-2: precio por hora tal cual y sin fecha de retiro (null)");
        check(Models.label(mai).equals("MAI Transcribe 2")&&Models.vendor(mai).equals("Microsoft"),"Nombre de MAI-2");
        check(near(nova.perHour,0.0000722*3600)&&!nova.approx&&nova.name.equals("Deepgram: Nova-3"),"Nova-3: precio por segundo ×3600, y gana la primera entrada de un id repetido");
        check(near(gemini.perHour,0.30)&&gemini.approx,"Gemini cobra por tokens: precio de referencia, marcado como aproximado");
        check(grok.expires>now/1000&&Models.retiresSoon(grok,now)&&!Models.retiresSoon(mai,now)&&near(grok.perHour,0.0000278*3600),"Modelo que se retira en 10 días: se lista con su fecha");
        check(gpt.created==1760000000L&&near(gpt.perHour,0.27)&&gpt.name.equals(GPT)&&Models.label(gpt).equals("GPT Transcribe")&&Models.vendor(gpt).equals("OpenAI"),"Fecha como texto, precio como número y nombre null deben tolerarse");
        check(bare.name.equals("bare/minimal")&&bare.created==0&&bare.perHour==-1&&bare.expires==0&&!bare.known&&Models.label(bare).equals("minimal")&&Models.vendor(bare).equals("Bare"),"Entrada casi vacía: se tolera");
        // Un modelo que se retira en 90 días se lista con fecha, pero todavía no es «pronto».
        List<Models.Model> later=Models.parse(new JSONObject().put("data",new JSONArray().put(model(MAI,"Microsoft: MAI Transcribe 2",1L,"0.10").put("expiration_date",day(now+90*DAY)+"T00:00:00Z"))),now);
        check(later.size()==1&&later.get(0).expires>0&&!Models.retiresSoon(later.get(0),now),"Fecha de retiro con hora, o a más de 30 días, mal leída");
        // La fecha de retiro también puede venir como número (segundos o milisegundos).
        List<Models.Model> numeric=Models.parse(new JSONObject().put("data",new JSONArray().put(model(MAI,"A",1L,"0.10").put("expiration_date",(now+10*DAY)/1000)).put(model(NOVA,"B",1L,"0.0000722").put("expires_at",now+10*DAY))),now);
        check(numeric.size()==2&&Models.retiresSoon(numeric.get(0),now)&&Models.retiresSoon(numeric.get(1),now)&&Math.abs(numeric.get(0).expires-numeric.get(1).expires)<=1,"Fecha de retiro numérica mal leída");
        // Respuestas raras: nunca fallan, dan lista vacía.
        check(Models.parse(new JSONObject(),now).isEmpty()&&Models.parse(new JSONObject().put("data","x"),now).isEmpty()&&Models.parse(null,now).isEmpty(),"Un catálogo sin datos debe dar lista vacía");
        // Si el servidor ignora el filtro y manda todo, solo quedan los de la tabla y los que declaran transcripción.
        JSONArray all=new JSONArray();
        for(int i=0;i<100;i++)all.put(new JSONObject().put("id","chat/model-"+i).put("name","Chat "+i).put("created",1790000000L+i).put("architecture",new JSONObject().put("output_modalities",new JSONArray().put("text"))));
        all.put(new JSONObject().put("id",MAI).put("name","Microsoft: MAI Transcribe 2").put("created",5));
        all.put(model("acme/asr","Acme: ASR",4,"0.0001"));
        check(ids(Models.parse(new JSONObject().put("data",all),now)).equals(java.util.Arrays.asList(MAI,"acme/asr")),"Catálogo sin filtrar: deben quedar solo los modelos de transcripción");
    }

    // ---------- «Automático» ----------
    private static List<Models.Model> without(List<Models.Model> list,String... drop){List<Models.Model> out=new ArrayList<>();for(Models.Model m:list)if(!java.util.Arrays.asList(drop).contains(m.id))out.add(m);return out;}
    static void automatic(long now)throws Exception{
        List<Models.Model> list=Models.parse(sample(now),now);
        check(Models.auto(list,true,now).equals(MAI)&&Models.auto(list,false,now).equals(MAI),"Automático debe preferir MAI-Transcribe 2");
        check(Models.auto(without(list,MAI),true,now).equals(NOVA)&&Models.auto(without(list,MAI),false,now).equals(NOVA),"Sin MAI-2, el respaldo es Nova-3");
        // Sin los documentados: para voces queda Gemini (Grok se retira en 10 días); para texto, gpt-transcribe.
        check(Models.auto(without(list,MAI,NOVA),true,now).equals(GEMINI),"Automático eligió un modelo que se retira pronto o que no separa voces");
        check(Models.auto(without(list,MAI,NOVA),false,now).equals(GPT),"Automático (solo texto) debe seguir el orden de preferencia");
        // Un modelo desconocido nunca es «Automático», aunque sea el más nuevo; y sin candidatos no se inventa uno.
        check(Models.auto(without(list,MAI,NOVA,GEMINI,GROK,GPT),true,now).isEmpty()&&Models.auto(without(list,MAI,NOVA,GEMINI,GROK,GPT),false,now).isEmpty()&&Models.auto(new ArrayList<>(),true,now).isEmpty(),"Sin modelos probados, Automático no debe elegir nada");
        // El preferido que se retira en menos de 30 días se salta.
        List<Models.Model> leaving=Models.parse(new JSONObject().put("data",new JSONArray().put(model(MAI,"Microsoft: MAI Transcribe 2",2L,"0.10").put("expiration_date",day(now+10*DAY))).put(model(NOVA,"Deepgram: Nova-3",1L,"0.0000722"))),now);
        check(Models.auto(leaving,true,now).equals(NOVA)&&Models.auto(leaving,false,now).equals(NOVA),"Automático no debe quedarse con un modelo que se retira en 10 días");
        // Un modelo de solo texto no sirve para «con voces».
        List<Models.Model> textOnly=Models.parse(new JSONObject().put("data",new JSONArray().put(model(GPT,"OpenAI: GPT Transcribe",1L,0.000075))),now);
        check(Models.auto(textOnly,true,now).isEmpty()&&Models.auto(textOnly,false,now).equals(GPT),"Automático con voces eligió un modelo que no las separa");
    }

    // ---------- Caché y refresh ----------
    static void cache(Context c,Settings s,long now)throws Exception{
        s.prefs.edit().remove("orSpeakersModel").remove("orTextModel").remove("orAutoSpeakers").remove("orAutoText").commit();
        new android.util.AtomicFile(Models.file(c)).delete();Models.forget();
        check(Models.cached(c).isEmpty()&&Models.fetchedAt(c)==0&&Models.stale(c),"Sin archivo no hay catálogo guardado");
        check(near(Models.perMinute(c,NOVA),0.26/60)&&Models.perMinute(c,NEW)==-1&&Models.perMinute(c,null)==-1,"Sin catálogo, el precio sale de la referencia (o no hay)");
        check(Models.chosen(s,true).equals(Models.DEFAULT_SPEAKERS)&&Models.chosen(s,false).equals(Models.DEFAULT_TEXT),"Sin catálogo, Automático usa los recomendados fijos");
        check(Models.name(c,NEW).equals("new-asr-1")&&Models.name(c,MAI).equals("MAI Transcribe 2")&&Models.name(c,"").isEmpty(),"Nombres sin catálogo");

        int[] calls={0};long t0=System.currentTimeMillis();
        List<Models.Model> list=Models.refresh(c,catalogHttp(200,sample(now).toString(),calls));long t1=System.currentTimeMillis();
        check(calls[0]==1&&list.size()==7,"refresh() debe hacer una sola llamada y devolver los 7 modelos");
        check(ids(Models.cached(c)).equals(ids(list))&&Models.fetchedAt(c)>=t0&&Models.fetchedAt(c)<=t1&&!Models.stale(c),"El catálogo no quedó guardado con su fecha");
        check(Models.file(c).isFile()&&!new String(java.nio.file.Files.readAllBytes(Models.file(c).toPath()),"UTF-8").contains("output_modalities"),"Del catálogo solo se guarda lo que se usa");
        check(near(Models.perMinute(c,NOVA),0.0000722*60)&&near(Models.perMinute(c,MAI),0.10/60)&&near(Models.perMinute(c,GEMINI),0.30/60)&&Models.perMinute(c,NEW)==-1&&Models.perMinute(c,"otro/modelo")==-1,"perMinute() no usa el catálogo guardado");
        check(MAI.equals(s.prefs.getString("orAutoSpeakers",""))&&MAI.equals(s.prefs.getString("orAutoText",""))&&Models.chosen(s,true).equals(MAI)&&Models.automatic(s,false).equals(MAI),"refresh() no dejó escrito lo que usa Automático");
        check(Models.name(c,NEW).equals("New ASR 1")&&Models.name(NOVA).equals("Nova-3"),"Nombres desde el catálogo guardado");
        // Releer desde el archivo (memoria olvidada) da lo mismo, con los mismos precios.
        Models.forget();List<Models.Model> again=Models.cached(c);
        check(ids(again).equals(ids(list))&&near(Models.find(again,NOVA).perHour,0.0000722*3600)&&Models.find(again,GEMINI).approx&&Models.find(again,GROK).expires==Models.find(list,GROK).expires&&Models.fetchedAt(c)>=t0,"El catálogo leído del archivo difiere del recibido");

        // Una falla (servidor caído, lista vacía, respuesta que no es JSON) no borra ni reemplaza la lista buena.
        long at=Models.fetchedAt(c);String[] bad={"500|{\"error\":{\"code\":500,\"message\":\"x\"}}","200|{\"data\":[]}","200|<html>mantenimiento</html>","200|"};
        for(String b:bad){
            int cut=b.indexOf('|');boolean threw=false;
            try{Models.refresh(c,catalogHttp(Integer.parseInt(b.substring(0,cut)),b.substring(cut+1),calls));}catch(HttpApi.UserAction e){throw new AssertionError("Una falla del catálogo no es algo que el usuario deba resolver: "+b);}catch(Exception e){threw=true;}
            check(threw&&Models.cached(c).size()==7&&Models.fetchedAt(c)==at&&MAI.equals(s.prefs.getString("orAutoSpeakers","")),"Una falla del catálogo cambió la lista guardada: "+b);
        }
        // Sin red: la excepción pasa tal cual y la lista queda.
        boolean offline=false;try{Models.refresh(c,new HttpApi(){@Override Response request(String m,String u,String t,String ty,Body b,Map<String,String> x)throws Exception{throw new IOException("sin red");}});}catch(IOException e){offline=true;}
        check(offline&&Models.cached(c).size()==7,"Sin red, la lista guardada debe quedar igual");

        // La lista cambia sola: si OpenRouter deja de ofrecer MAI-2, Automático pasa a Nova-3; lo elegido a mano no se toca.
        s.prefs.edit().putString("orTextModel",GROK).commit();
        JSONObject fewer=sample(now);JSONArray data=fewer.getJSONArray("data");for(int i=0;i<data.length();i++){JSONObject m=data.optJSONObject(i);if(m!=null&&MAI.equals(m.optString("id"))){data.remove(i);break;}}
        List<Models.Model> next=Models.refresh(c,catalogHttp(200,fewer.toString(),calls));
        check(next.size()==6&&Models.find(Models.cached(c),MAI)==null,"La lista guardada no se reemplazó por la nueva");
        check(Models.chosen(s,true).equals(NOVA)&&Models.automatic(s,false).equals(NOVA)&&Models.chosen(s,false).equals(GROK),"Automático no siguió al catálogo, o pisó la elección del usuario");
        check(MAI.equals(s.prefs.getString("orAutoBeforeSpeakers",""))&&MAI.equals(s.prefs.getString("orAutoBeforeText","")),"refresh() no recordó de qué modelo se movió Automático (lo usan los trabajos a medias)");
        check(near(Models.perMinute(c,MAI),0.10/60),"Un modelo de la tabla que salió del catálogo conserva su precio de referencia");
        // Catálogo solo con modelos desconocidos: Automático vuelve a los recomendados fijos.
        Models.refresh(c,catalogHttp(200,new JSONObject().put("data",new JSONArray().put(model(NEW,"Acme: New ASR 1",1L,"0.00005"))).toString(),calls));
        check(!s.prefs.contains("orAutoSpeakers")&&!s.prefs.contains("orAutoText")&&Models.chosen(s,true).equals(Models.DEFAULT_SPEAKERS)&&Models.cached(c).size()==1,"Sin modelos probados en el catálogo, Automático debe volver a los recomendados");
        // Un archivo dañado cuenta como «nunca se leyó», sin fallar.
        java.nio.file.Files.write(Models.file(c).toPath(),"{roto".getBytes("UTF-8"));Models.forget();
        check(Models.cached(c).isEmpty()&&Models.fetchedAt(c)==0&&Models.stale(c)&&Models.perMinute(c,NEW)==-1,"Un catálogo guardado dañado debe tratarse como vacío");
        // Un catálogo de hace más de un día está vencido; uno de hace una hora, no.
        FilesStore.write(Models.file(c),new JSONObject().put("v",1).put("fetchedAt",now-25*3600_000L).put("models",new JSONArray().put(new JSONObject().put("id",MAI).put("name","Microsoft: MAI Transcribe 2").put("created",1).put("prompt",0.10).put("expires",0))));Models.forget();
        check(Models.cached(c).size()==1&&Models.stale(c),"Un catálogo de hace 25 horas debe pedir actualización");
        FilesStore.write(Models.file(c),new JSONObject().put("v",1).put("fetchedAt",now-3600_000L).put("models",new JSONArray().put(new JSONObject().put("id",MAI).put("name","Microsoft: MAI Transcribe 2").put("created",1).put("prompt",0.10).put("expires",(now-DAY)/1000))));Models.forget();
        check(Models.cached(c).isEmpty(),"Un modelo que se retiró después de guardar la lista ya no se ofrece");
        s.prefs.edit().remove("orTextModel").remove("orAutoSpeakers").remove("orAutoText").commit();
    }

    // ---------- Clave ----------
    private static HttpApi keyHttp(int code,String body,String path,String expectedKey,int[] calls){
        return new HttpApi(){@Override Response request(String method,String url,String token,String type,Body content,Map<String,String> extra)throws Exception{
            calls[0]++;
            expect(method.equals("GET")&&url.equals("https://openrouter.ai/api/v1"+path),"La clave se comprobó en otra dirección: "+url);
            expect(expectedKey.equals(token),"La clave debe viajar como Bearer, tal cual");
            expect(content==null,"Comprobar la clave no lleva cuerpo");
            return new Response(code,body,null);
        }};
    }
    static void key()throws Exception{
        String key="sk-or-v1-clave-de-prueba-no-real";int[] calls={0};
        Models.KeyInfo limited=Models.checkKey(keyHttp(200,"{\"data\":{\"label\":\"sk-or-v1-abc...xyz\",\"usage\":5.8,\"limit\":10,\"limit_remaining\":4.2,\"is_free_tier\":false,\"rate_limit\":{\"requests\":10}}}","/key",key,calls),key);
        check(calls[0]==1&&near(limited.remaining,4.2)&&near(limited.usage,5.8)&&!limited.freeTier&&limited.label.equals("sk-or-v1-abc...xyz"),"Clave válida con límite: saldo mal leído");
        Models.KeyInfo open=Models.checkKey(keyHttp(200,"{\"data\":{\"label\":\"mi clave\",\"usage\":0.5,\"limit\":null,\"limit_remaining\":null,\"is_free_tier\":true}}","/key",key,calls),"  "+key+" ");
        check(Double.isNaN(open.remaining)&&near(open.usage,0.5)&&open.freeTier,"Clave sin límite: no hay «quedan» que mostrar");
        Models.KeyInfo computed=Models.checkKey(keyHttp(200,"{\"data\":{\"usage\":4,\"limit\":10}}","/key",key,calls),key);
        check(near(computed.remaining,6)&&computed.label.isEmpty()&&!computed.freeTier,"Sin limit_remaining, lo que queda es el límite menos lo usado");
        // Otro formato (o un cuerpo vacío) con 200: la clave vale y los montos quedan sin dato.
        Models.KeyInfo odd=Models.checkKey(keyHttp(200,"","/key",key,calls),key),flat=Models.checkKey(keyHttp(200,"{\"usage\":\"1.5\",\"limit\":\"2\"}","/key",key,calls),key);
        check(Double.isNaN(odd.remaining)&&Double.isNaN(odd.usage)&&odd.label.isEmpty()&&near(flat.remaining,0.5),"Una respuesta 200 con otro formato no debe invalidar la clave");
        for(int code:new int[]{401,403}){
            String message=null;try{Models.checkKey(keyHttp(code,"{\"error\":{\"code\":"+code+",\"message\":\"No auth credentials found\"}}","/key",key,calls),key);}catch(HttpApi.UserAction e){message=e.getMessage();}
            check(message!=null&&message.toLowerCase(Locale.ROOT).contains("clave")&&!message.contains(key),"Una clave inválida ("+code+") debe pedir revisarla, sin mostrarla");
        }
        for(int code:new int[]{408,429,500,503}){
            boolean retry=false;try{Models.checkKey(keyHttp(code,"","/key",key,calls),key);}catch(HttpApi.UserAction e){throw new AssertionError("Un "+code+" es pasajero: no debe culpar a la clave");}catch(IOException e){retry=true;}
            check(retry,"Un "+code+" de OpenRouter debe ser un error pasajero");
        }
        // Un código que no se esperaba no dice nada de la clave: sale como IOException (la bienvenida lo muestra como «no se
        // pudo comprobar ahora», no como «clave mala»), con un mensaje que nombra a OpenRouter y nunca repite la clave.
        boolean other=false;try{Models.checkKey(keyHttp(400,"{}","/key",key,calls),key);}catch(HttpApi.UserAction e){throw new AssertionError("Un error no previsto no debe culpar a la clave");}catch(IOException e){other=String.valueOf(e.getMessage()).startsWith(HttpApi.OPENROUTER)&&!e.getMessage().contains(key);}
        check(other,"Un error no previsto debe avisarse sin mostrar la clave");
        int made=calls[0];
        for(String empty:new String[]{null,"","   "}){boolean missing=false;try{Models.checkKey(keyHttp(200,"{}","/key",key,calls),empty);}catch(HttpApi.UserAction e){missing=true;}check(missing,"Sin clave no hay nada que comprobar");}
        check(calls[0]==made,"Sin clave no se debe llamar a OpenRouter");

        // Saldo de la cuenta: un dato de cortesía que nunca lanza.
        check(near(Models.credits(keyHttp(200,"{\"data\":{\"total_credits\":10,\"total_usage\":5.8}}","/credits",key,calls),key),4.2),"Saldo de la cuenta mal calculado");
        check(Double.isNaN(Models.credits(keyHttp(403,"{\"error\":{\"code\":403,\"message\":\"Only management keys\"}}","/credits",key,calls),key)),"Si OpenRouter no entrega el saldo, no hay saldo");
        check(Double.isNaN(Models.credits(keyHttp(200,"{\"data\":{\"total_credits\":0,\"total_usage\":0}}","/credits",key,calls),key))&&Double.isNaN(Models.credits(keyHttp(200,"no es json","/credits",key,calls),key)),"Un saldo sin créditos cargados o ilegible no se muestra");
        check(Double.isNaN(Models.credits(new HttpApi(){@Override Response request(String m,String u,String t,String ty,Body b,Map<String,String> x)throws Exception{throw new IOException("sin red");}},key)),"credits() nunca debe lanzar");
    }

    // ---------- Settings con OpenRouter ----------
    static void settings(Context c,Settings s)throws Exception{
        String router="sk-or-v1-prueba-openrouter",openai="sk-prueba-openai",server="clave-prueba-servidor",claude="sk-ant-prueba";
        // Sin catálogo guardado, los nombres salen de la tabla local: así los textos esperados no dependen de la prueba anterior.
        new android.util.AtomicFile(Models.file(c)).delete();Models.forget();
        s.prefs.edit().putString("provider","openrouter").putString("speakersMode","ask").remove("orSpeakersModel").remove("orTextModel").remove("orAutoSpeakers").remove("orAutoText").remove("noteProvider").remove("customBase")
            .remove("keyEncrypted").remove("keyIv").remove("openrouter_keyEncrypted").remove("openrouter_keyIv").remove("custom_keyEncrypted").remove("custom_keyIv").remove("anthropic_keyEncrypted").remove("anthropic_keyIv").commit();
        check(s.openRouter()&&!s.custom()&&s.prefix().equals("openrouter_")&&!s.needsServer()&&s.providerName().equals("OpenRouter"),"OpenRouter no es un servidor propio: no pide dirección ni usa su prefijo");
        check(Settings.prefix("openai").isEmpty()&&Settings.prefix("openrouter").equals("openrouter_")&&Settings.prefix("custom").equals("custom_"),"Prefijos de clave cambiados");
        check(!s.hasKey()&&!s.hasOpenRouterKey()&&s.apiKey().isEmpty()&&s.openRouterKey().isEmpty(),"Sin clave guardada no debe aparecer ninguna");

        // La clave del proveedor activo es la de OpenRouter, cifrada, y no toca las demás.
        s.saveKey(router);
        check(s.hasKey()&&s.hasOpenRouterKey()&&s.apiKey().equals(router)&&s.openRouterKey().equals(router),"La clave de OpenRouter no se guardó con su prefijo");
        check(!s.prefs.getString("openrouter_keyEncrypted","").contains("prueba")&&!s.prefs.contains("keyEncrypted")&&!s.prefs.contains("custom_keyEncrypted")&&!s.hasOpenAiKey(),"La clave quedó en claro o pisó la de otro proveedor");
        s.saveKeyFor("openai",openai);s.saveKeyFor("custom",server);s.saveKeyFor("anthropic",claude);
        check(s.openAiKey().equals(openai)&&s.openRouterKey().equals(router)&&s.anthropicKey().equals(claude)&&s.apiKey().equals(router)&&s.provider().equals("openrouter"),"Guardar la clave de otro proveedor cambió la de OpenRouter o el proveedor activo");

        // config(): OpenRouter con «Automático» usa el recomendado y solo separa voces si la receta sabe.
        ProviderConfig voices=s.config(true),text=s.config(false),byDefault=s.config();
        check(voices.provider.equals("openrouter")&&voices.base.equals(Models.BASE)&&voices.model.equals(Models.DEFAULT_SPEAKERS)&&voices.key.equals(router)&&voices.speakers,"config(true) con OpenRouter");
        check(text.provider.equals("openrouter")&&text.model.equals(Models.DEFAULT_TEXT)&&!text.speakers&&text.key.equals(router),"config(false) con OpenRouter");
        check(s.canSeparate()&&s.defaultSpeakers()&&byDefault.speakers,"Con el recomendado, OpenRouter separa voces");
        s.prefs.edit().putString("speakersMode","never").commit();check(s.canSeparate()&&!s.defaultSpeakers()&&!s.config().speakers,"«Nunca separar voces» debe respetarse con OpenRouter");s.prefs.edit().putString("speakersMode","ask").commit();
        check(SettingsActivity.modelSummary(s).equals("OpenRouter · MAI Transcribe 2 + separación de voces")&&SettingsActivity.modelName(s).equals("MAI Transcribe 2"),"Resumen del modelo con OpenRouter: "+SettingsActivity.modelSummary(s));
        // «Automático» sigue lo que dejó refresh(); la elección del usuario manda sobre Automático.
        s.prefs.edit().putString("orAutoSpeakers",NOVA).putString("orAutoText",GPT).commit();
        check(s.config(true).model.equals(NOVA)&&s.config(true).speakers&&s.config(false).model.equals(GPT),"config() no sigue al recomendado vigente");
        check(SettingsActivity.modelSummary(s).equals("OpenRouter · GPT Transcribe · voces con Nova-3"),"Resumen con dos modelos distintos: "+SettingsActivity.modelSummary(s));
        String auto=s.config(true).fingerprint();
        s.prefs.edit().putString("orSpeakersModel",GEMINI).putString("orTextModel",NEW).commit();
        check(s.config(true).model.equals(GEMINI)&&s.config(true).speakers&&s.config(false).model.equals(NEW)&&!s.config(false).speakers&&!s.config(true).fingerprint().equals(auto),"La elección del usuario no manda, o cambiar de modelo no cambia la huella");
        // Un modelo de solo texto elegido «con voces» no promete voces.
        s.prefs.edit().putString("orSpeakersModel",GPT).commit();
        check(!s.canSeparate()&&!s.defaultSpeakers()&&!s.config(true).speakers&&s.config(true).model.equals(GPT)&&SettingsActivity.modelSummary(s).equals("OpenRouter · new-asr-1"),"Un modelo que no separa voces no debe ofrecerlas: "+SettingsActivity.modelSummary(s));
        s.prefs.edit().putString("orSpeakersModel",Models.AUTO).putString("orTextModel","").commit();
        check(s.config(true).model.equals(NOVA)&&s.config(false).model.equals(GPT),"«auto» y vacío significan Automático");

        // La nota: quien transcribe con OpenRouter y nunca eligió IA usa OpenRouter; una elección explícita manda.
        check(s.noteProvider().equals("openrouter"),"Con OpenRouter y sin elección, la nota usa OpenRouter");
        s.prefs.edit().putString("noteProvider","anthropic").commit();check(s.noteProvider().equals("anthropic"),"La IA de la nota elegida a mano manda");
        s.prefs.edit().remove("noteProvider").putString("provider","openai").commit();check(s.noteProvider().equals("openai"),"Con OpenAI y sin elección, la nota sigue con OpenAI");

        // Al cambiar de proveedor, cada uno encuentra su clave; nada se pisa.
        check(!s.openRouter()&&!s.custom()&&s.prefix().isEmpty()&&s.apiKey().equals(openai)&&s.providerName().equals("OpenAI")&&s.config(false).provider.equals("openai")&&s.config(false).base.equals("https://api.openai.com/v1")&&s.config(true).model.equals("gpt-4o-transcribe-diarize"),"OpenAI directo cambió");
        check(SettingsActivity.modelSummary(s).startsWith("OpenAI · "),"Resumen del modelo con OpenAI");
        s.prefs.edit().putString("provider","custom").commit();
        check(s.custom()&&s.prefix().equals("custom_")&&s.apiKey().equals(server)&&s.needsServer()&&s.providerName().equals("Tu servidor")&&SettingsActivity.modelSummary(s).startsWith("Tu servidor · "),"El servidor propio cambió");
        boolean noServer=false;try{s.config(false);}catch(HttpApi.UserAction e){noServer=Settings.noServer().equals(e.getMessage());}check(noServer,"Sin dirección, el servidor propio no debe enviar nada");
        s.prefs.edit().putString("customBase","https://example.com/v1").putString("customModel","mi-modelo").commit();
        check(!s.needsServer()&&s.config(false).provider.equals("custom")&&s.config(false).base.equals("https://example.com/v1")&&s.config(false).model.equals("mi-modelo")&&s.config(false).key.equals(server),"config() del servidor propio cambió");
        check(s.hasOpenRouterKey()&&s.openRouterKey().equals(router),"La clave de OpenRouter sigue disponible para la nota aunque se transcriba con otro proveedor");

        // Borrar una clave borra solo esa; una clave con espacios se rechaza sin perder la anterior.
        s.saveOpenRouterKey("");
        check(!s.hasOpenRouterKey()&&s.openRouterKey().isEmpty()&&!s.prefs.contains("openrouter_keyIv")&&s.openAiKey().equals(openai)&&s.apiKey().equals(server)&&s.anthropicKey().equals(claude),"Borrar la clave de OpenRouter tocó otra");
        s.saveKeyFor("openrouter",router);boolean rejected=false;try{s.saveKeyFor("openrouter","clave con espacios");}catch(IllegalArgumentException e){rejected=true;}
        check(rejected&&s.openRouterKey().equals(router),"Una clave con espacios debe rechazarse y conservar la anterior");
        s.prefs.edit().putString("provider","openrouter").commit();s.saveKey("");
        check(!s.hasKey()&&!s.hasOpenRouterKey()&&s.openAiKey().equals(openai),"Eliminar la clave del proveedor activo (OpenRouter) no debe tocar la de OpenAI");
    }

    // ---------- 0.8.0, segunda ronda: solo OpenRouter ----------
    /**
     * Migración del esquema 5 (VozApp.openRouterOnly) para quien venía de OpenAI: pasa a OpenRouter, la nota también, las
     * claves viejas quedan cifradas y sin uso, y la app sabe explicarle por qué se le pide otra clave. Claves de mentira.
     */
    static void onlyOpenRouter(Settings s)throws Exception{
        String openai="sk-prueba-openai-migracion",router="sk-or-v1-prueba-migracion";
        s.prefs.edit().remove("provider").remove("noteProvider").remove("noteModel").remove("keyEncrypted").remove("keyIv").remove("openrouter_keyEncrypted").remove("openrouter_keyIv").remove("custom_keyEncrypted").remove("custom_keyIv").commit();
        check(s.provider().equals("openrouter")&&s.openRouter()&&s.oldService()==null,"Sin nada guardado, el proveedor por defecto es OpenRouter y no hay clave vieja que explicar");
        // Quien venía de la 0.7 con OpenAI: proveedor, su clave, la nota con Claude y un modelo de nota de OpenAI.
        s.prefs.edit().putString("provider","openai").putString("noteProvider","anthropic").putString("noteModel","gpt-5.5-mini").commit();s.saveKeyFor("openai",openai);
        check(s.hasKey()&&s.oldService().equals("OpenAI"),"Datos de partida de la migración mal armados");
        VozApp.openRouterOnly(s,s.prefs.edit()).commit();
        check(s.openRouter()&&!s.prefs.contains("noteProvider")&&!s.prefs.contains("noteModel")&&Notes.provider(s).equals("openrouter"),"La migración no dejó OpenRouter para transcribir y para la nota");
        check(s.hasOpenAiKey()&&s.openAiKey().equals(openai)&&!s.hasKey()&&!s.hasOpenRouterKey()&&"OpenAI".equals(s.oldService()),"La migración borró la clave vieja, o no se ve que falta la de OpenRouter");
        // Un modelo de nota de OpenRouter (de quien probó la primera ronda) se conserva; la clave de OpenRouter, también.
        s.prefs.edit().putString("provider","openai").putString("noteModel","~openai/gpt-luna-latest").commit();s.saveKeyFor("openrouter",router);
        VozApp.openRouterOnly(s,s.prefs.edit()).commit();
        check(s.openRouter()&&"~openai/gpt-luna-latest".equals(s.noteModel())&&s.hasKey()&&s.apiKey().equals(router)&&s.openAiKey().equals(openai),"La migración tocó un modelo de nota de OpenRouter o una clave");
        // Quien usaba su servidor: la explicación nombra el servidor.
        s.saveKeyFor("openai","");s.saveKeyFor("custom","clave-prueba-servidor");
        check("tu servidor".equals(s.oldService()),"La clave vieja de un servidor propio no se reconoce");
        s.saveKeyFor("custom","");s.saveKeyFor("openrouter","");
    }

    /** Saldo al comprobar la clave: una sola regla para Ajustes y la bienvenida (Models.balance y SettingsActivity.balanceText). */
    static void balance(){
        Models.KeyInfo capped=new Models.KeyInfo("",1,4.2,false),open=new Models.KeyInfo("",1,Double.NaN,false),fresh=new Models.KeyInfo("",0,5,true);
        Models.Balance b=Models.balance(capped,Double.NaN);
        check(near(b.left,4.2)&&!b.noCredits,"Sin saldo de la cuenta, queda lo de la clave");
        check(near(Models.balance(new Models.KeyInfo("",0,5,false),0.4).left,0.4)&&near(Models.balance(open,3).left,3),"El saldo es el menor entre la clave y la cuenta");
        b=Models.balance(fresh,Double.NaN);
        // El tope de la clave (US$5) no se guarda como saldo: queda «no sabido», igual que en la bienvenida (OnboardingActivity.valid).
        check(b.noCredits&&Double.isNaN(b.left),"Una cuenta sin créditos (is_free_tier) con clave con tope no tiene plata disponible");
        b=Models.balance(fresh,2);
        check(!b.noCredits&&near(b.left,2),"Si se supo el saldo de la cuenta, manda el saldo");
        check(Double.isNaN(Models.balance(null,Double.NaN).left)&&!Models.balance(null,Double.NaN).noCredits&&Double.isNaN(Models.balance(open,Double.NaN).left),"Sin datos, no se inventa un saldo");
        check("quedan US$4,20".equals(SettingsActivity.balanceText(4.2,false))&&SettingsActivity.balanceText(0.001,false).startsWith("sin saldo")&&SettingsActivity.balanceText(Double.NaN,false)==null,"Texto del saldo");
        check(SettingsActivity.balanceText(5,true).contains("sin créditos")&&SettingsActivity.balanceText(Double.NaN,true).contains("sin créditos"),"Sin créditos manda sobre el tope de la clave");
    }

    /** Audio que cobra OpenRouter con voces conocidas (anclas delante de cada bloque), para los estimados. */
    static void billed(){
        long anchor=Math.min(Voices.MAX_MS,OrAudio.ANCHOR_MAX_MS)+OrAudio.GAP_MS,block=Transcriber.orBlockMax(Models.recipe(MAI),true),min=60_000L,half=30*min;
        check(RecordingActions.billedMs("openai",MAI,min,4,false)==min&&RecordingActions.billedMs("openrouter",MAI,min,0,false)==min&&RecordingActions.billedMs("openrouter",MAI,0,2,false)==0,"Sin OpenRouter o sin muestras, se cobra la duración tal cual");
        check(RecordingActions.billedMs("openrouter",MAI,min,2,false)==min+2*anchor,"Un bloque con dos muestras");
        long blocks=(half+block-1)/block;
        check(blocks>1&&RecordingActions.billedMs("openrouter",MAI,half,1,false)==half+blocks*anchor&&RecordingActions.billedMs("openrouter",MAI,half,1,true)==half+anchor,"Las muestras se cobran en cada bloque (y una vez «sin cortar»)");
        // Una sola regla (tercera ronda): las pantallas no tienen su propia cuenta, usan la del motor (Pricing.orBilledMs).
        check(RecordingActions.billedMs("openrouter",MAI,half,2,false)==Pricing.orBilledMs(half,2,Models.recipe(MAI))&&RecordingActions.billedMs("openrouter",MAI,half,9,false)==Pricing.orBilledMs(half,9,Models.recipe(MAI)),"billedMs debe ser la misma cuenta de Pricing.orBilledMs");
    }

    /** Un trabajo a medias no cambia de modelo porque «Automático» se movió entre dos intentos (Models.resume). */
    static void resume(Context c,Settings s)throws Exception{
        FilesStore.write(Models.file(c),new JSONObject().put("v",1).put("fetchedAt",System.currentTimeMillis()).put("models",new JSONArray()
            .put(new JSONObject().put("id",MAI).put("name","Microsoft: MAI Transcribe 2").put("created",2).put("prompt",0.10).put("expires",0))
            .put(new JSONObject().put("id",NOVA).put("name","Deepgram: Nova-3").put("created",1).put("prompt",0.0000722).put("expires",0))));Models.forget();
        s.prefs.edit().remove("orSpeakersModel").remove("orTextModel").putString("orAutoSpeakers",NOVA).putString("orAutoBeforeSpeakers",MAI).commit();
        JSONObject job=new JSONObject().put("provider","openrouter").put("model",MAI).put("blocksDone",1).put("speakers",true);
        check(Models.resume(c,s,true,job).equals(MAI),"Un trabajo a medias debe terminar con el modelo con que empezó");
        check(Models.resume(c,s,true,new JSONObject(job.toString()).put("blocksDone",0)).equals(NOVA)&&Models.resume(c,s,true,null).equals(NOVA),"Sin partes listas, manda Automático de hoy");
        check(Models.resume(c,s,true,new JSONObject(job.toString()).put("model",GEMINI)).equals(NOVA),"Solo se conserva el modelo del que se movió Automático");
        check(Models.resume(c,s,false,job).equals(Models.chosen(s,false))&&Models.resume(c,s,true,new JSONObject(job.toString()).put("provider","openai")).equals(NOVA),"Otro modo de voces u otro proveedor: manda la elección de hoy");
        s.prefs.edit().putString("orSpeakersModel",GROK).commit();
        check(Models.resume(c,s,true,job).equals(GROK),"Un modelo elegido a mano manda sobre el trabajo a medias");
        s.prefs.edit().remove("orSpeakersModel").commit();
        FilesStore.write(Models.file(c),new JSONObject().put("v",1).put("fetchedAt",System.currentTimeMillis()).put("models",new JSONArray().put(new JSONObject().put("id",NOVA).put("name","Deepgram: Nova-3").put("created",1).put("prompt",0.0000722).put("expires",0))));Models.forget();
        check(Models.resume(c,s,true,job).equals(NOVA),"Si el modelo anterior salió de la lista, se sigue con Automático");
        s.prefs.edit().remove("orAutoSpeakers").remove("orAutoBeforeSpeakers").commit();
    }
}
