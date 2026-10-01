package cl.vozlocal.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Pruebas de la parte «metrics» de la 0.8.0 (ver docs/diseno/SPEC-0.8b.md). Sin red y sin tocar los datos reales: todo
 * se calcula sobre una carpeta temporal con grabaciones sintéticas (los .m4a son archivos vacíos: las métricas leen la
 * duración guardada en el .json) y un «ahora» fijo, el jueves 24-09-2026 a las 15:00 (hora local del teléfono).
 *
 * Las grabaciones (lunes de cada semana: 21-09 es la actual, 14-09 la anterior…):
 * A  jue 24-09 10:00 · 10 min · voces con nombre, OpenRouter con costo real US$0,05, nota real US$0,002, en 0-Inbox, 2 ★
 * B  mié 23-09 18:00 · 30 min · sin voces (3.600 palabras), OpenAI → estimado 30 × 0,006 = US$0,18; sin nota ni 0-Inbox
 * C  mar 22-09 09:00 · 5 min · sin transcribir
 * D  lun 14-09 12:00 · 20 min · OpenRouter real US$0,10 + versión anterior OpenAI del 10-09 (estimado US$0,12); nota con
 *    costo real 0 (no cuenta como costo, sí sus tokens)
 * E–H 18 al 21-08 · en proceso, con error y sin transcribir (la mejor racha: 4 días)
 * I  lun 01-06 · 60 min · tu servidor (sin tarifa: costo desconocido)
 * J  ejemplo (demo): no cuenta. «basura.m4a»: no es una grabación, no cuenta.
 */
final class MetricsChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static boolean near(double a,double b){return Math.abs(a-b)<1e-9;}
    private static final ZoneId ZONE=ZoneId.systemDefault();
    private static long at(int month,int day,int hour,int minute){return LocalDateTime.of(2026,month,day,hour,minute).atZone(ZONE).toInstant().toEpochMilli();}

    static void run(Context c,Recording r)throws Exception{
        words();formats();
        File dir=new File(c.getCacheDir(),"metrics-check-"+UUID.randomUUID());
        if(!dir.mkdirs())throw new AssertionError("No se pudo crear la carpeta temporal de métricas");
        try{
            empty(c,dir);
            synthetic(c,dir);
            File again=new File(dir,"otra-pasada");check(again.mkdirs(),"No se pudo crear la subcarpeta de prueba");retranscribing(c,again);
            File unknown=new File(dir,"sin-tarifa");check(unknown.mkdirs(),"No se pudo crear la subcarpeta de prueba");unknownByService(c,unknown);
        }finally{deleteTree(dir);}
        // El contrato de la tarjeta de Ajustes, con los datos reales (solo lectura): nunca lanza, y queda vacío solo si no
        // hay grabaciones (antes exigía que nunca quedara vacío: la tarjeta pasó a mostrar su propia invitación en ese caso).
        String line=Metrics.summaryLine(c);boolean none=Metrics.compute(c,false).total==0;
        check(line!=null&&line.isEmpty()==none,"summaryLine con datos reales: vacío solo sin grabaciones: «"+line+"»");
        check(none||line.contains(" · "),"summaryLine lleva la cifra grande antes de « · »: «"+line+"»");
        // La segunda llamada, sin cambios de por medio, sale del recuerdo y dice lo mismo.
        check(line.equals(Metrics.summaryLine(c)),"summaryLine cambió sin que cambiara nada");
    }

    /** Contar palabras: tramos con alguna letra o número. */
    private static void words(){
        check(Metrics.countWords("Hola, ¿cómo estás? — bien…")==4,"countWords cuenta 4 palabras y no el guion suelto");
        check(Metrics.countWords("  ")==0&&Metrics.countWords(null)==0,"countWords de vacío es 0");
        check(Metrics.countWords("12 de octubre, 2026")==4,"countWords cuenta números");
    }
    private static void formats(){
        check(Metrics.hours(0).equals("0 h"),"hours(0)");
        check(Metrics.hours(20_000).equals("< 1 min"),"hours(20 s): "+Metrics.hours(20_000));
        check(Metrics.hours(45*60_000L).equals("45 min"),"hours(45 min): "+Metrics.hours(45*60_000L));
        check(Metrics.hours(90*60_000L).equals("1,5 h"),"hours(90 min): "+Metrics.hours(90*60_000L));
        check(Metrics.hours(60*60_000L).equals("1 h"),"hours(60 min): "+Metrics.hours(60*60_000L));
        check(Metrics.hours((long)(12.4*3_600_000)).equals("12 h"),"hours(12,4 h): "+Metrics.hours((long)(12.4*3_600_000)));
        check(Metrics.number(128450).equals("128.450"),"number con punto de miles: "+Metrics.number(128450));
        check(Metrics.count(1,"grabación","grabaciones").equals("1 grabación")&&Metrics.count(2,"grabación","grabaciones").equals("2 grabaciones"),"count singular y plural");
        check(MetricsActivity.niceMinutes(72)==90&&MetricsActivity.niceMinutes(5)==5&&MetricsActivity.niceMinutes(4000)==4200,"niceMinutes redondea al tope siguiente");
        check(near(MetricsActivity.niceUsd(0.23),0.5)&&near(MetricsActivity.niceUsd(0.052),0.1)&&near(MetricsActivity.niceUsd(1.0),1)&&near(MetricsActivity.niceUsd(3),5),"niceUsd usa 1, 2 o 5 por potencia de 10");
    }

    /** Sin grabaciones: todo en cero y una línea amable para Ajustes. */
    private static void empty(Context c,File dir){
        Metrics.Data d=Metrics.compute(c,dir,at(9,24,15,0),"Konrad",false,true);
        check(d.total==0&&d.all.recordings==0&&d.streak==0&&d.best==0,"Carpeta vacía: sin grabaciones ni racha");
        check(Metrics.summary(d).line().equals("0 grabaciones · aquí verás tu voz en números"),"Resumen vacío: "+Metrics.summary(d).line());
        // La línea de la tarjeta de Ajustes queda vacía: la tarjeta muestra su propia invitación (contrato con Ajustes).
        check(Metrics.summaryLine(d).isEmpty()&&Metrics.summaryLine((Metrics.Data)null).isEmpty(),"summaryLine sin grabaciones debe ser vacío");
    }

    private static void synthetic(Context c,File dir)throws Exception{
        long now=at(9,24,15,0);String or="openrouter",orModel="mistralai/voxtral-mini-transcribe-x";
        // A: voces con nombre. Tu voz («voice:me») y quien se llama como tú no cuentan como «personas con las que conversas»;
        // «Fran» y «fran» son la misma persona (mismo nombre = misma persona).
        String a=rec(dir,at(9,24,10,0),600_000);
        transcript(dir,a,or,orModel,true,new Object[][]{{"voice:me",0,240,40},{"A",240,480,60},{"B",480,540,20},{"C",540,570,10},{"D",570,600,20}},
            new JSONObject().put("A","Fran").put("B","fran").put("D","Konrad"));
        state(dir,a,new JSONObject().put("provider",or).put("model",orModel).put("audioMs",600_000).put("costUsd",0.05).put("doneAt",at(9,24,10,20)).put("queuedAt",at(9,24,10,1))
            .put("inboxAt",at(9,24,11,0)).put("marks",new JSONArray().put(new JSONObject().put("t",1000)).put(new JSONObject().put("t",2000))));
        FilesStore.write(new File(dir,a+".note.json"),new JSONObject().put("provider",or).put("model","~anthropic/claude-sonnet-latest").put("createdAt",at(9,24,10,21))
            .put("usage",new JSONObject().put("input_tokens",1000).put("output_tokens",200)).put("costUsd",0.002).put("costReal",true));
        // B: sin voces, 3.600 palabras, OpenAI (estimado con la tabla de Pricing).
        String b=rec(dir,at(9,23,18,0),1_800_000);
        transcript(dir,b,"openai","gpt-4o-transcribe",false,new Object[][]{{"text",0,1800,3600}},null);
        state(dir,b,new JSONObject().put("provider","openai").put("model","gpt-4o-transcribe").put("audioMs",1_800_000).put("doneAt",at(9,23,18,40)));
        // C: solo audio.
        String cc=rec(dir,at(9,22,9,0),300_000);
        // D: versión vigente con OpenRouter y la anterior (OpenAI) todavía guardada: las dos se pagaron.
        String d=rec(dir,at(9,14,12,0),1_200_000);
        transcript(dir,d,or,orModel,true,new Object[][]{{"A",0,600,200}},new JSONObject().put("A","Fran"));
        FilesStore.write(new File(dir,d+".transcript.prev.json"),new JSONObject().put("segments",new JSONArray()));
        state(dir,d,new JSONObject().put("provider",or).put("model",orModel).put("audioMs",1_200_000).put("costUsd",0.10).put("doneAt",at(9,14,12,30))
            .put("retranscribe",new JSONObject().put("mode","SPEAKERS").put("at",at(9,14,12,5)).put("before",new JSONObject().put("provider","openai").put("model","gpt-4o-transcribe").put("audioMs",1_200_000).put("doneAt",at(9,10,8,0)))));
        FilesStore.write(new File(dir,d+".note.json"),new JSONObject().put("provider",or).put("model","~anthropic/claude-sonnet-latest").put("createdAt",at(9,14,12,31))
            .put("usage",new JSONObject().put("input_tokens",500).put("output_tokens",100)).put("costUsd",0).put("costReal",true));
        // E a H: cuatro días seguidos de agosto (en proceso, con error y dos sin transcribir).
        String e=rec(dir,at(8,20,8,0),900_000);state(dir,e,new JSONObject().put("requested",true).put("queuedAt",at(8,20,8,1)));
        String f=rec(dir,at(8,19,8,0),360_000);state(dir,f,new JSONObject().put("failed",true));
        String g=rec(dir,at(8,18,8,0),60_000);
        String h=rec(dir,at(8,21,8,0),120_000);
        // I: tu servidor, sin tarifa conocida.
        String i=rec(dir,at(6,1,8,0),3_600_000);
        transcript(dir,i,"custom","mi-modelo",false,new Object[][]{{"text",0,3600,100}},null);
        state(dir,i,new JSONObject().put("provider","custom").put("model","mi-modelo").put("audioMs",3_600_000).put("doneAt",at(6,1,9,0)));
        // J: el ejemplo no cuenta. Y un .m4a que no es una grabación tampoco.
        String j=rec(dir,at(9,24,9,0),99_000);
        FilesStore.write(new File(dir,j+".transcript.json"),new JSONObject().put("demo",true).put("segments",new JSONArray().put(new JSONObject().put("speaker","text").put("start",0).put("end",99).put("text","texto de ejemplo"))));
        state(dir,j,new JSONObject().put("demo",true));
        check(new File(dir,"basura.m4a").createNewFile(),"No se pudo crear el archivo de ruido");

        Metrics.Data m=Metrics.compute(c,dir,now,"Konrad",true,true);
        check(m.total==9,"9 grabaciones (sin el ejemplo ni el archivo de ruido): "+m.total);

        // ---- Últimos 7 días: A, B, C ----
        Metrics.Totals w=m.of(Metrics.Period.WEEK);
        check(w.recordings==3&&w.audioMs==2_700_000,"7 días: 3 grabaciones y 45 min: "+w.recordings+" · "+w.audioMs);
        check(w.transcribed==2&&w.withNote==1&&w.inInbox==1,"7 días: embudo 3 → 2 → 1 → 1: "+w.transcribed+"/"+w.withNote+"/"+w.inInbox);
        check(w.words==3750,"7 días: 150 + 3.600 palabras: "+w.words);
        check(w.wpm()==94,"7 días: 3.750 palabras en 40 min = 94 por minuto: "+w.wpm());
        check(w.savedMs()==Math.round(3750*60_000d/Metrics.TYPING_WPM),"7 días: tiempo ahorrado a 40 palabras por minuto");
        check(w.pending.size()==1&&w.pending.get(0).equals(cc)&&w.noNote.size()==1&&w.noNote.get(0).equals(b)&&w.notSaved.size()==1&&w.notSaved.get(0).equals(b),"7 días: a medio camino (C sin transcribir, B sin nota ni 0-Inbox)");
        check(w.days.size()==3&&w.marks==2,"7 días: 3 días con grabaciones y 2 momentos ★");
        check(near(w.realUsd,0.05)&&near(w.estUsd,0.18)&&near(w.noteReal,0.002)&&near(w.noteEst,0),"7 días: cobrado 0,05 + nota 0,002; estimado 0,18: "+w.realUsd+" · "+w.estUsd+" · "+w.noteReal);
        check(near(w.usd(),0.232)&&near(w.real(),0.052)&&near(w.estimated(),0.18),"7 días: total US$0,232");
        check(w.billedMs==2_400_000&&near(w.usdPerHour(),0.23/(40/60d)),"7 días: US$ por hora de audio = 0,23 / (40 min): "+w.usdPerHour());
        List<Metrics.Person> people=w.people();
        check(people.size()==1&&people.get(0).name.equals("Fran")&&people.get(0).ms==300_000&&people.get(0).recordings==1,"7 días: solo Fran (5 min, «fran» se une; tú no cuentas): "+people.size());

        // ---- Este mes (septiembre): A, B, C, D, y la versión anterior de D del 10-09 ----
        Metrics.Totals mo=m.of(Metrics.Period.MONTH);
        check(mo.recordings==4&&mo.audioMs==3_900_000&&mo.transcribed==3&&mo.words==3950,"Mes: 4 grabaciones, 65 min, 3 transcritas, 3.950 palabras: "+mo.recordings+" · "+mo.audioMs+" · "+mo.words);
        check(near(mo.realUsd,0.15)&&near(mo.estUsd,0.30)&&mo.passes==4,"Mes: cobrado 0,15 (A+D) y estimado 0,30 (B + versión anterior de D): "+mo.realUsd+" · "+mo.estUsd+" · "+mo.passes);
        check(mo.notes==2&&mo.noteIn==1500&&mo.noteOut==300&&near(mo.noteReal,0.002)&&near(mo.noteEst,0),"Mes: 2 notas; el costo real 0 no cuenta, sus tokens sí");
        List<Metrics.ModelUse> models=mo.models();
        check(models.size()==2,"Mes: dos modelos: "+models.size());
        check(models.get(0).provider.equals("openai")&&models.get(0).name.equals("GPT-4o Transcribe")&&models.get(0).count==2&&near(models.get(0).estUsd,0.30)&&near(models.get(0).realUsd,0),"Mes: primero OpenAI (≈ US$0,30, estimado)");
        check(models.get(1).provider.equals(or)&&models.get(1).count==2&&near(models.get(1).realUsd,0.15)&&near(models.get(1).estUsd,0)&&models.get(1).audioMs==1_800_000,"Mes: después OpenRouter (US$0,15 cobrado)");
        check(mo.people().get(0).name.equals("Fran")&&mo.people().get(0).ms==900_000&&mo.people().get(0).recordings==2,"Mes: Fran en A y D (15 min, 2 audios)");

        // ---- Todo ----
        Metrics.Totals all=m.of(Metrics.Period.ALL);
        check(all.recordings==9&&all.audioMs==8_940_000&&all.transcribed==4&&all.words==4050,"Todo: 9 grabaciones, 149 min, 4 transcritas, 4.050 palabras: "+all.audioMs+" · "+all.words);
        check(all.unknown==1&&near(all.realUsd,0.15)&&near(all.estUsd,0.30),"Todo: tu servidor no tiene tarifa (no se inventa costo)");
        check(MetricsActivity.unknownNote(all).equals("1 transcripción con tu servidor: sin tarifa conocida.\n"),"Todo: el pie nombra a tu servidor: «"+MetricsActivity.unknownNote(all)+"»");
        check(all.queued.size()==1&&all.queued.get(0).equals(e)&&all.failed.size()==1&&all.failed.get(0).equals(f),"Todo: E en proceso y F con error");
        check(all.pending.size()==3&&all.pending.get(0).equals(cc)&&all.pending.get(1).equals(h)&&all.pending.get(2).equals(g),"Todo: sin transcribir de la más nueva a la más vieja (C, H, G)");
        check(all.heatMs[3][10]==600_000&&all.heatCount[3][10]==1&&all.heatMs[2][18]==1_800_000&&all.heatMs[3][8]==900_000,"Mapa: A el jueves a las 10, B el miércoles a las 18, E el jueves a las 8");

        // ---- Semanas (lunes 21-09 es la última) ----
        check(Metrics.bucket(m.weekStarts,m.weekStarts[Metrics.WEEKS-1]+7*86_400_000L,now)==Metrics.WEEKS-1&&Metrics.bucket(m.weekStarts,m.weekStarts[Metrics.WEEKS-1]+7*86_400_000L,at(6,1,8,0))==-1,"El ahora cae en la última semana; junio, fuera de las 8");
        check(m.weekStarts[7]==at(9,21,0,0)&&m.weekStarts[6]==at(9,14,0,0)&&m.weekStarts[0]==at(8,3,0,0),"Semanas desde el lunes 03-08 hasta el lunes 21-09");
        check(m.weekMs[7]==2_700_000&&m.weekCount[7]==3,"Esta semana: 45 min en 3 grabaciones: "+m.weekMs[7]);
        check(m.weekMs[6]==1_200_000&&m.weekMs[2]==1_440_000&&m.weekCount[2]==4&&m.weekMs[5]==0,"Semanas anteriores: D (20 min) y agosto (24 min en 4)");
        check(near(m.weekReal[7],0.052)&&near(m.weekEst[7],0.18)&&near(m.weekReal[6],0.10)&&near(m.weekEst[5],0.12),"Gasto por semana: cobrado y estimado donde se cobró");

        // ---- Racha ----
        check(m.today&&m.streak==3&&m.best==4,"Racha: 3 días hasta hoy (22, 23 y 24-09); la mejor, 4 (18 a 21-08): "+m.streak+" · "+m.best);
        Metrics.Data tomorrow=Metrics.compute(c,dir,at(9,25,9,0),"Konrad",true,true);
        check(!tomorrow.today&&tomorrow.streak==3,"Al día siguiente, sin grabar todavía, la racha sigue viva: "+tomorrow.streak);
        Metrics.Data later=Metrics.compute(c,dir,at(9,26,9,0),"Konrad",true,true);
        check(later.streak==0&&later.best==4,"Dos días sin grabar: la racha se corta");

        // ---- Resumen de Ajustes (pasada liviana: sin leer transcripciones) ----
        Metrics.Data light=Metrics.compute(c,dir,now,"Konrad",true,false);
        check(light.total==9&&light.all.words==0&&light.all.people().isEmpty(),"La pasada liviana no lee transcripciones");
        Metrics.Summary s=Metrics.summary(light);
        check(s.figure.equals("2,5 h")&&s.label.equals("grabadas")&&s.detail.equals("≈ US$0,452 este mes"),"Resumen: «2,5 h grabadas · ≈ US$0,452 este mes»: "+s.line());
        check(Metrics.summaryLine(light).equals("2,5 h grabadas · ≈ US$0,452 este mes"),"La línea de Ajustes con grabaciones: "+Metrics.summaryLine(light));

        // ---- Caché por archivo: si la transcripción cambia, se vuelve a leer ----
        transcript(dir,b,"openai","gpt-4o-transcribe",false,new Object[][]{{"text",0,1800,3700}},null);
        Metrics.Data again=Metrics.compute(c,dir,now,"Konrad",true,true);
        check(again.of(Metrics.Period.WEEK).words==3850,"La caché nota el cambio de la transcripción: "+again.of(Metrics.Period.WEEK).words);

        // ---- Personas: si tú eres «Fran», Fran no cuenta ----
        Metrics.Data asFran=Metrics.compute(c,dir,now,"fran",true,true);
        check(asFran.of(Metrics.Period.WEEK).people().isEmpty()||!asFran.of(Metrics.Period.WEEK).people().get(0).name.equalsIgnoreCase("fran"),"Tu propio nombre nunca aparece entre las personas");
    }

    /**
     * «Volver a transcribir» en la cola: la versión anterior quedó en .transcript.prev.json y el estado todavía trae sus
     * datos (los mismos de «before»). Se cobró una vez y se cuenta una vez. Cuando la nueva empieza, el motor borra
     * "costUsd" y lo que cobre la nueva se suma aparte.
     */
    private static void retranscribing(Context c,File dir)throws Exception{
        String or="openrouter",model="mistralai/voxtral-mini-transcribe-x";long done=at(9,5,10,0),asked=at(9,23,10,0);
        String k=rec(dir,at(9,5,9,0),600_000);
        FilesStore.write(new File(dir,k+".transcript.prev.json"),new JSONObject().put("segments",new JSONArray()));
        JSONObject before=new JSONObject().put("provider",or).put("model",model).put("audioMs",600_000).put("costUsd",0.07).put("doneAt",done);
        // Pedida el 23-09 (Pipeline.request pone "queuedAt"); el estado sigue con el "doneAt" de la pasada del 05-09.
        state(dir,k,new JSONObject(before.toString()).put("requested",true).put("queuedAt",asked).put("retranscribe",new JSONObject().put("mode","SPEAKERS").put("at",asked).put("before",before)));
        Metrics.Totals all=Metrics.compute(c,dir,at(9,24,15,0),"Konrad",true,true).all;
        check(near(all.realUsd,0.07)&&all.passes==1,"En la cola, la pasada anterior se cuenta una sola vez: "+all.realUsd+" · "+all.passes);
        check(all.queued.size()==1&&all.transcribed==0,"Mientras se rehace, la grabación está «en proceso»");
        // La nueva empezó: el motor quitó "costUsd" (el resto del estado sigue igual).
        JSONObject started=new JSONObject(before.toString()).put("requested",true).put("queuedAt",asked).put("retranscribe",new JSONObject().put("before",before));started.remove("costUsd");
        state(dir,k,started);
        all=Metrics.compute(c,dir,at(9,24,15,0),"Konrad",true,true).all;
        check(near(all.realUsd,0.07)&&all.passes==1,"Recién empezada la nueva pasada, solo cuenta la anterior: "+all.realUsd+" · "+all.passes);
        // Y ya cobró una parte.
        state(dir,k,started.put("costUsd",0.02));
        Metrics.Data m=Metrics.compute(c,dir,at(9,24,15,0),"Konrad",true,true);all=m.all;
        check(near(all.realUsd,0.09)&&all.passes==2,"La parte ya cobrada de la nueva pasada se suma a la anterior: "+all.realUsd+" · "+all.passes);
        // Lo cobrado de la pasada sin terminar va en el día en que se pidió (23-09), no en el «doneAt» de la anterior (05-09).
        Metrics.Totals week=m.of(Metrics.Period.WEEK);
        check(near(week.realUsd,0.02)&&week.passes==1,"La parte cobrada de la nueva pasada cae en los últimos 7 días: "+week.realUsd+" · "+week.passes);
        check(near(m.weekReal[7],0.02)&&near(m.weekReal[4],0.07),"Por semana: la nueva en la del 21-09 y la anterior en la del 31-08: "+m.weekReal[7]+" · "+m.weekReal[4]);
    }

    /**
     * Transcripciones sin costo conocido: el pie de «Costos» dice de qué servicio son. Un modelo de OpenRouter sin tarifa
     * por minuto (no está en el catálogo ni en la tabla) cuyo costo no vino (0 = dato que no vino) no es «tu servidor».
     */
    private static void unknownByService(Context c,File dir)throws Exception{
        long now=at(9,24,15,0);String or="openrouter",model="prueba/modelo-sin-tarifa-x";
        check(MetricsActivity.unknownNote(new Metrics.Totals()).isEmpty(),"Sin transcripciones sin tarifa, el pie no dice nada");
        String n=rec(dir,at(9,20,9,0),600_000);
        transcript(dir,n,or,model,false,new Object[][]{{"text",0,600,50}},null);
        state(dir,n,new JSONObject().put("provider",or).put("model",model).put("audioMs",600_000).put("costUsd",0).put("doneAt",at(9,20,9,10)));
        Metrics.Totals all=Metrics.compute(c,dir,now,"Konrad",true,true).all;
        check(all.unknown==1&&near(all.realUsd,0)&&near(all.estUsd,0),"OpenRouter sin costo ni tarifa: se cuenta aparte, sin inventar un costo: "+all.unknown);
        String note=MetricsActivity.unknownNote(all);
        check(note.equals("1 transcripción de OpenRouter sin precio conocido: no informó el costo y el modelo no tiene tarifa por minuto.\n"),"El pie no habla de un servidor para OpenRouter: «"+note+"»");
        // Con una de tu servidor y una de OpenAI con un modelo sin tarifa: una línea por servicio.
        String s=rec(dir,at(9,21,9,0),300_000);
        transcript(dir,s,"custom","mi-modelo",false,new Object[][]{{"text",0,300,20}},null);
        state(dir,s,new JSONObject().put("provider","custom").put("model","mi-modelo").put("audioMs",300_000).put("doneAt",at(9,21,9,10)));
        String o=rec(dir,at(9,22,9,0),300_000);
        transcript(dir,o,"openai","modelo-viejo",false,new Object[][]{{"text",0,300,20}},null);
        state(dir,o,new JSONObject().put("provider","openai").put("model","modelo-viejo").put("audioMs",300_000).put("doneAt",at(9,22,9,10)));
        all=Metrics.compute(c,dir,now,"Konrad",true,true).all;note=MetricsActivity.unknownNote(all);
        check(all.unknown==3,"Tres sin tarifa: "+all.unknown);
        check(note.equals("1 transcripción de OpenRouter sin precio conocido: no informó el costo y el modelo no tiene tarifa por minuto.\n"
            +"1 transcripción de OpenAI sin tarifa conocida para su modelo.\n1 transcripción con tu servidor: sin tarifa conocida.\n"),"Una línea por servicio: «"+note+"»");
    }

    // ---------- Grabaciones sintéticas ----------
    private static String rec(File dir,long created,long duration)throws Exception{
        String id=UUID.randomUUID().toString();
        check(new File(dir,id+".m4a").createNewFile(),"No se pudo crear el audio de prueba");
        FilesStore.write(new File(dir,id+".json"),new JSONObject().put("id",id).put("title","Prueba de métricas").put("created",created).put("duration",duration));
        return id;
    }
    private static void state(File dir,String id,JSONObject st)throws Exception{FilesStore.write(new File(dir,id+".sync.json"),st);}
    /** segs: {voz, desde (s), hasta (s), cantidad de palabras}. */
    private static void transcript(File dir,String id,String provider,String model,boolean diarized,Object[][] segs,JSONObject names)throws Exception{
        JSONArray s=new JSONArray();
        for(Object[] seg:segs){StringBuilder t=new StringBuilder();int n=(Integer)seg[3];for(int k=0;k<n;k++)t.append(k==0?"":" ").append("palabra");
            s.put(new JSONObject().put("speaker",seg[0]).put("start",((Integer)seg[1]).doubleValue()).put("end",((Integer)seg[2]).doubleValue()).put("text",t.toString()));}
        JSONObject data=new JSONObject().put("segments",s).put("parts",1).put("diarized",diarized).put("names",names==null?new JSONObject():names).put("provider",provider).put("model",model);
        FilesStore.write(new File(dir,id+".transcript.json"),data);
    }
    private static void deleteTree(File f){File[] kids=f.listFiles();if(kids!=null)for(File k:kids)deleteTree(k);if(!f.delete()&&f.exists())android.util.Log.w("MetricsChecks","No se pudo borrar "+f.getName());}
}
