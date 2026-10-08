package cl.vozlocal.app;

import android.content.Context;
import android.media.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;
import org.json.*;

/**
 * Datos de muestra para las capturas de la ficha de Google Play (0.9.4). No es una prueba: se pide con
 * {@code am instrument -w -e demo es|en|pt …RecorderSmokeTest}. BORRA todas las grabaciones del emulador y deja cinco de
 * ejemplo, transcritas, con voces nombradas, momentos ★ y nota, en el idioma pedido. Solo en un emulador desechable.
 */
final class StoreDemo {
    private StoreDemo(){}
    private static JSONObject seg(String who,double a,double b,String text)throws JSONException{return new JSONObject().put("speaker",who).put("start",a).put("end",b).put("text",text);}
    private static final String A="A",B="B",ME=Voices.ME;

    /** Una grabación de muestra: título, hace cuántos minutos, duración (s), voces (A, B; null = sin separar), líneas y nota. */
    private static final class Demo{
        String title;long agoMin;int seconds;String nameA,nameB;Object[][] lines;JSONObject answer;long[] marks;String[] markLabels;
    }

    static void seed(Context c,String lang)throws Exception{
        Lang.override(lang);
        for(Recording r:Recording.list(c))r.delete(c);
        Settings s=new Settings(c);s.prefs.edit().putBoolean("welcomed",true).putBoolean("datePrefix",false).putBoolean("recordNoise",true).putBoolean("cleanLevel",true).commit();
        Consent.accept(c);
        // Una clave de mentira: así Grabar no pide «Configurar transcripción» en las capturas (no se envía nada).
        s.saveOpenRouterKey("sk-or-v1-store-demo-not-a-real-key");s.saveKey("sk-or-v1-store-demo-not-a-real-key");
        long now=System.currentTimeMillis();
        for(Demo d:demos(lang)){
            String id=UUID.randomUUID().toString();
            Recording r=new Recording(id,d.title,now-d.agoMin*60_000L,d.seconds*1000L);
            // El audio se arma una vez por duración y se copia desde la caché (armar 2 h de AAC tarda minutos en el emulador).
            File cached=new File(c.getCacheDir(),"store-demo-"+d.seconds+".m4a");
            if(!cached.isFile()||cached.length()<1000)silence(cached,d.seconds);
            java.nio.file.Files.copy(cached.toPath(),r.audio(c).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);r.save(c);
            JSONArray segs=new JSONArray();
            for(Object[] l:d.lines)segs.put(seg((String)l[0],((Number)l[1]).doubleValue(),((Number)l[2]).doubleValue(),(String)l[3]));
            // Relleno en los huecos (las mismas líneas, cada 6 s): las métricas cuentan palabras como en una grabación real.
            List<JSONObject> sorted=new ArrayList<>();for(int i=0;i<segs.length();i++)sorted.add(segs.getJSONObject(i));
            int k=0;for(double t0=0;t0<d.seconds-8;t0+=6)if(gap(d.lines,t0)){Object[] l=d.lines[k++%d.lines.length];sorted.add(seg((String)l[0],t0,t0+5.5,(String)l[3]));}
            sorted.sort((x,y)->Double.compare(x.optDouble("start"),y.optDouble("start")));segs=new JSONArray();for(JSONObject o:sorted)segs.put(o);
            boolean diarized=d.nameA!=null;
            JSONObject names=new JSONObject();if(diarized){names.put(A,d.nameA);if(d.nameB!=null)names.put(B,d.nameB);names.put(ME,me(lang));}
            Transcript t=new Transcript(new JSONObject().put("diarized",diarized).put("reviewed",true).put("names",names).put("segments",segs).put("parts",1));
            t.save(c,id);
            if(d.marks!=null)for(int i=0;i<d.marks.length;i++)Marks.add(c,id,d.marks[i]*1000,d.markLabels[i]);
            if(d.answer!=null){
                Notes.Prompt p=Notes.prompt(t,r,Marks.list(c,id),lang);
                JSONObject note=Notes.normalize(d.answer,p.tokens,r.duration);
                note.put("version",1).put("provider","openrouter").put("model",Models.NOTE_DEFAULT).put("modelUsed","anthropic/claude-sonnet-5.5").put("createdAt",now).put("speakers",new JSONObject(p.tokens)).put("lang",lang).put("costUsd",0.004).put("costReal",true);
                FilesStore.write(FilesStore.file(c,id,".note.json"),note);
                FilesStore.update(c,id,st->st.put("noteState","ready"));
            }
            FilesStore.update(c,id,st->st.put("costUsd",d.seconds/60.0*0.0012));
        }
    }

    /** AAC mono 16 kHz con un soplido muy bajo: el reproductor ve la duración real. */
    private static void silence(File out,int seconds)throws Exception{
        int rate=16000;MediaFormat f=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,rate,1);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);f.setInteger(MediaFormat.KEY_BIT_RATE,24000);
        MediaCodec enc=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);enc.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);enc.start();
        MediaMuxer mux=new MediaMuxer(out.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int track=-1;
        long total=(long)seconds*rate,fed=0;boolean inDone=false;Random rnd=new Random(7);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        while(true){
            if(!inDone){int i=enc.dequeueInputBuffer(10_000);if(i>=0){ByteBuffer b=enc.getInputBuffer(i);b.clear();int n=(int)Math.min(b.remaining()/2,total-fed);
                for(int k=0;k<n;k++){short v=(short)(rnd.nextGaussian()*30);b.put((byte)v).put((byte)(v>>8));}
                long pts=fed*1_000_000L/rate;fed+=n;inDone=fed>=total;enc.queueInputBuffer(i,0,n*2,pts,inDone?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);}}
            int o=enc.dequeueOutputBuffer(info,10_000);
            if(o==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=mux.addTrack(enc.getOutputFormat());mux.start();}
            else if(o>=0){if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0&&info.size>0)mux.writeSampleData(track,enc.getOutputBuffer(o),info);
                enc.releaseOutputBuffer(o,false);if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)break;}
        }
        enc.stop();enc.release();mux.stop();mux.release();
    }

    private static JSONObject note(String title,String summary,String[] decisions,String[][] tasks,Object[][] quotes,String... tags)throws JSONException{
        JSONArray ta=new JSONArray();for(String[] t:tasks)ta.put(new JSONObject().put("text",t[0]).put("who",t[1]).put("when",t[2]));
        JSONArray qa=new JSONArray();for(Object[] q:quotes)qa.put(new JSONObject().put("t",q[0]).put("text",q[1]).put("who",q[2]));
        return new JSONObject().put("title",title).put("summary",summary).put("decisions",new JSONArray(Arrays.asList(decisions))).put("tasks",ta).put("quotes",qa).put("tags",new JSONArray(Arrays.asList(tags)));
    }
    /** ¿Queda t0 lejos (más de 5 s) de toda línea escrita? Ahí va relleno. */
    private static boolean gap(Object[][] lines,double t0){
        for(Object[] l:lines){double a=((Number)l[1]).doubleValue()-5,b=((Number)l[2]).doubleValue()+5;if(t0+6>a&&t0<b)return false;}
        return true;
    }
    /** Quien graba (la voz «yo»), con un nombre como los demás. */
    private static String me(String lang){return Lang.EN.equals(lang)?"Jamie":Lang.DE.equals(lang)?"Lena":"Camila";}
    private static Demo demo(String title,long agoMin,int seconds,String nameA,String nameB,Object[][] lines,JSONObject answer,long[] marks,String[] labels){
        Demo d=new Demo();d.title=title;d.agoMin=agoMin;d.seconds=seconds;d.nameA=nameA;d.nameB=nameB;d.lines=lines;d.answer=answer;d.marks=marks;d.markLabels=labels;return d;
    }

    private static List<Demo> demos(String lang)throws JSONException{
        if(Lang.EN.equals(lang))return en();if(Lang.PT.equals(lang))return pt();if(Lang.DE.equals(lang))return de();return es();
    }

    private static List<Demo> de()throws JSONException{
        return Arrays.asList(
            demo("Launch der Herbst-App",35,1394,"Anna","Lukas",new Object[][]{
                {A,0,9,"Fangen wir mit dem Termin an. Wenn wir vor den Feiertagen draußen sein wollen, muss der Launch Mitte November sein."},
                {ME,10,19,"Passt für mich. Mir macht nur Sorgen, dass die Tablet-Version noch nicht getestet ist."},
                {B,20,31,"Die Tablet-Tests schaffe ich bis Donnerstag, wenn ich die Geräte am Montag bekomme."},
                {A,32,44,"Perfekt. Dann ist der 14. November unser vorläufiger Termin, und am Freitag bestätigen wir ihn."},
                {ME,252,266,"Zum Budget: Wir haben 3.000 Euro für Werbung. Ich würde halb auf Social Media und halb auf Presse setzen."},
                {B,267,280,"Ich würde mehr in Social Media stecken. Letztes Jahr hat uns die Presse kaum Downloads gebracht."},
                {A,281,295,"Machen wir 70 zu 30 und messen in zwei Wochen. Wenn es nicht funktioniert, schichten wir um."},
                {ME,940,956,"Noch etwas: Wir brauchen neue Screenshots für den Store, auch auf Englisch und Spanisch."},
                {A,957,968,"Das kläre ich mit dem Design. Bis Mittwoch hab ich sie."},
                {B,1370,1390,"Gut, dann sehen wir uns am Freitag und bestätigen den Termin."}},
                note("Launch der Herbst-App","{S1}, {S3} und {S2} haben den 14. November als vorläufigen Launch-Termin festgelegt, der am Freitag bestätigt wird. Das Werbebudget geht zu 70 % in Social Media und zu 30 % in Presse, mit einer Prüfung nach zwei Wochen.",
                    new String[]{"Vorläufiger Launch-Termin: 14. November.","Werbung: 70 % Social Media, 30 % Presse."},
                    new String[][]{{"Tablet-Tests abschließen","{S3}","Donnerstag"},{"Neue Store-Screenshots vorbereiten","{S1}","Mittwoch"},{"Launch-Termin bestätigen","","Freitag"}},
                    new Object[][]{{281,"Machen wir 70 zu 30 und messen in zwei Wochen.","{S1}"},{267,"Letztes Jahr hat uns die Presse kaum Downloads gebracht.","{S3}"}},
                    "launch","marketing","budget","tablet"),
                new long[]{32,252},new String[]{"termin","budget"}),
            demo("Idee für den Podcast",190,222,null,null,new Object[][]{
                {"text",0,14,"Idee für den Podcast: eine Folge darüber, wie man Sprachnotizen ordnet. Mit der Geschichte vom verlorenen Notizbuch anfangen."},
                {"text",15,30,"Carla einladen, die jeden Tag mit Obsidian arbeitet. Ende Oktober aufnehmen."}},
                note("Idee für den Podcast","Eine Folge darüber, wie man Sprachnotizen ordnet, mit der Geschichte vom verlorenen Notizbuch als Einstieg.",new String[]{},
                    new String[][]{{"Carla zur Folge einladen","","Ende Oktober"}},new Object[][]{},"podcast","ideen","notizen"),
                null,null),
            demo("Vorlesung: Designgeschichte",1500,2885,"Professorin",null,new Object[][]{
                {A,0,20,"Heute schauen wir uns an, wie das Bauhaus unseren Blick auf Alltagsgegenstände verändert hat."},
                {A,21,45,"Die Grundidee: Die Form folgt der Funktion, ohne auf Schönheit zu verzichten."}},
                null,new long[]{610},new String[]{"prüfung"}),
            demo("Telefonat mit dem Lieferanten",2950,1880,"Martin",null,new Object[][]{
                {A,0,12,"Ich bestätige dir: Die Bestellung geht am Dienstag raus und ist am Donnerstag da."},
                {ME,13,22,"Perfekt. Schickst du die Rechnung, oder kommt sie mit der Lieferung?"}},
                null,null,null),
            demo("Liste fürs Wochenende",4400,58,null,null,new Object[][]{
                {"text",0,20,"Brot, Kaffee und Obst kaufen. Am Samstag Oma anrufen. Das Buch in die Bibliothek zurückbringen."}},
                null,null,null));
    }

    // Orden de las voces en la reunión: A (S1), yo (S2), B (S3).
    private static List<Demo> es()throws JSONException{
        return Arrays.asList(
            demo("Lanzamiento de la app de otoño",35,1394,"Ana","Tomás",new Object[][]{
                {A,0,9,"Partamos con la fecha. Si queremos llegar antes de las fiestas, el lanzamiento tiene que ser a mediados de noviembre."},
                {ME,10,19,"Me parece bien. Lo que me preocupa es que la versión para tablet todavía no está probada."},
                {B,20,31,"Yo puedo tener las pruebas de tablet listas el jueves, si me pasan los equipos el lunes."},
                {A,32,44,"Perfecto. Entonces el 14 de noviembre queda como fecha tentativa y lo confirmamos el viernes."},
                {ME,252,266,"Sobre el presupuesto: tenemos 3 millones para difusión. Propongo la mitad en redes y la otra mitad en prensa."},
                {B,267,280,"Yo pondría más en redes. El año pasado la prensa nos trajo muy pocas descargas."},
                {A,281,295,"Hagamos 70 y 30, y medimos en dos semanas. Si no funciona, movemos la plata."},
                {ME,940,956,"Una cosa más: necesitamos capturas nuevas para la tienda, en inglés y en portugués también."},
                {A,957,968,"Eso lo veo yo con diseño. Las tengo para el miércoles."},
                {B,1370,1390,"Listo, entonces nos vemos el viernes para confirmar la fecha."}},
                note("Lanzamiento de la app de otoño","{S1}, {S3} y {S2} fijaron el 14 de noviembre como fecha tentativa del lanzamiento, que se confirma el viernes. El presupuesto de difusión se reparte 70 % en redes y 30 % en prensa, con revisión en dos semanas.",
                    new String[]{"Fecha tentativa de lanzamiento: 14 de noviembre.","Difusión: 70 % en redes y 30 % en prensa."},
                    new String[][]{{"Terminar las pruebas en tablet","{S3}","el jueves"},{"Preparar las capturas nuevas para la tienda","{S1}","el miércoles"},{"Confirmar la fecha de lanzamiento","","el viernes"}},
                    new Object[][]{{281,"Hagamos 70 y 30, y medimos en dos semanas.","{S1}"},{267,"El año pasado la prensa nos trajo muy pocas descargas.","{S3}"}},
                    "lanzamiento","marketing","presupuesto","tablet"),
                new long[]{32,252},new String[]{"fecha","presupuesto"}),
            demo("Idea para el pódcast",190,222,null,null,new Object[][]{
                {"text",0,14,"Idea para el pódcast: un episodio sobre cómo ordenar las notas de voz. Empezar con la historia del cuaderno perdido."},
                {"text",15,30,"Invitar a Carla, que trabaja con Obsidian todos los días. Grabar a fines de octubre."}},
                note("Idea para el pódcast","Episodio sobre cómo ordenar las notas de voz, partiendo con la historia del cuaderno perdido.",new String[]{},
                    new String[][]{{"Invitar a Carla al episodio","","fines de octubre"}},new Object[][]{},"podcast","ideas","notas"),
                null,null),
            demo("Clase: historia del diseño",1500,2885,"Profesora",null,new Object[][]{
                {A,0,20,"Hoy vamos a ver cómo la Bauhaus cambió la forma de pensar los objetos cotidianos."},
                {A,21,45,"La idea central es que la forma sigue a la función, pero sin renunciar a la belleza."}},
                null,new long[]{610},new String[]{"examen"}),
            demo("Llamada con el proveedor",2950,1880,"Martín",null,new Object[][]{
                {A,0,12,"Te confirmo que el pedido sale el martes y llega el jueves a Santiago."},
                {ME,13,22,"Perfecto, ¿y la factura la mandas tú o viene con el despacho?"}},
                null,null,null),
            demo("Lista para el fin de semana",4400,58,null,null,new Object[][]{
                {"text",0,20,"Comprar pan, café y fruta. Llamar a la abuela el sábado. Devolver el libro a la biblioteca."}},
                null,null,null));
    }

    private static List<Demo> en()throws JSONException{
        return Arrays.asList(
            demo("Fall app launch",35,1394,"Anna","Tom",new Object[][]{
                {A,0,9,"Let's start with the date. If we want to land before the holidays, the launch has to be mid-November."},
                {ME,10,19,"Sounds good. My worry is that the tablet version hasn't been tested yet."},
                {B,20,31,"I can have the tablet tests done by Thursday if I get the devices on Monday."},
                {A,32,44,"Perfect. So November 14 is our tentative date, and we confirm it on Friday."},
                {ME,252,266,"About the budget: we have 3,000 dollars for promotion. I'd split it half social, half press."},
                {B,267,280,"I'd put more into social. Last year the press brought us very few downloads."},
                {A,281,295,"Let's do 70/30 and measure in two weeks. If it doesn't work, we move the money."},
                {ME,940,956,"One more thing: we need new store screenshots, in Spanish and Portuguese too."},
                {A,957,968,"I'll handle that with design. I'll have them by Wednesday."},
                {B,1370,1390,"Great, see you Friday to confirm the date."}},
                note("Fall app launch","{S1}, {S3} and {S2} set November 14 as the tentative launch date, to be confirmed on Friday. The promotion budget is split 70% social and 30% press, with a review in two weeks.",
                    new String[]{"Tentative launch date: November 14.","Promotion: 70% social, 30% press."},
                    new String[][]{{"Finish the tablet tests","{S3}","Thursday"},{"Prepare new store screenshots","{S1}","Wednesday"},{"Confirm the launch date","","Friday"}},
                    new Object[][]{{281,"Let's do 70/30 and measure in two weeks.","{S1}"},{267,"Last year the press brought us very few downloads.","{S3}"}},
                    "launch","marketing","budget","tablet"),
                new long[]{32,252},new String[]{"date","budget"}),
            demo("Podcast idea",190,222,null,null,new Object[][]{
                {"text",0,14,"Podcast idea: an episode about organizing voice notes. Open with the story of the lost notebook."},
                {"text",15,30,"Invite Carla, who uses Obsidian every day. Record at the end of October."}},
                note("Podcast idea","An episode about organizing voice notes, opening with the story of the lost notebook.",new String[]{},
                    new String[][]{{"Invite Carla to the episode","","end of October"}},new Object[][]{},"podcast","ideas","notes"),
                null,null),
            demo("Lecture: history of design",1500,2885,"Professor",null,new Object[][]{
                {A,0,20,"Today we'll see how the Bauhaus changed the way we think about everyday objects."},
                {A,21,45,"The core idea is that form follows function, without giving up on beauty."}},
                null,new long[]{610},new String[]{"exam"}),
            demo("Call with the supplier",2950,1880,"Martin",null,new Object[][]{
                {A,0,12,"I can confirm the order ships Tuesday and arrives Thursday."},
                {ME,13,22,"Perfect. Will you send the invoice, or does it come with the delivery?"}},
                null,null,null),
            demo("Weekend to-do list",4400,58,null,null,new Object[][]{
                {"text",0,20,"Buy bread, coffee and fruit. Call grandma on Saturday. Return the book to the library."}},
                null,null,null));
    }

    private static List<Demo> pt()throws JSONException{
        return Arrays.asList(
            demo("Lançamento do app de outono",35,1394,"Ana","Tiago",new Object[][]{
                {A,0,9,"Vamos começar pela data. Se queremos chegar antes das festas, o lançamento precisa ser em meados de novembro."},
                {ME,10,19,"Concordo. O que me preocupa é que a versão para tablet ainda não foi testada."},
                {B,20,31,"Consigo terminar os testes de tablet na quinta, se receber os aparelhos na segunda."},
                {A,32,44,"Perfeito. Então 14 de novembro fica como data provisória, e confirmamos na sexta."},
                {ME,252,266,"Sobre o orçamento: temos 15 mil reais para divulgação. Proponho metade em redes e metade em imprensa."},
                {B,267,280,"Eu colocaria mais em redes. No ano passado a imprensa trouxe pouquíssimos downloads."},
                {A,281,295,"Vamos fazer 70 e 30 e medir em duas semanas. Se não funcionar, mudamos o dinheiro de lugar."},
                {ME,940,956,"Mais uma coisa: precisamos de capturas novas para a loja, em inglês e espanhol também."},
                {A,957,968,"Isso eu vejo com o design. Fico com elas para quarta."},
                {B,1370,1390,"Combinado, nos vemos na sexta para confirmar a data."}},
                note("Lançamento do app de outono","{S1}, {S3} e {S2} definiram 14 de novembro como data provisória do lançamento, a ser confirmada na sexta. O orçamento de divulgação fica 70% em redes e 30% em imprensa, com revisão em duas semanas.",
                    new String[]{"Data provisória de lançamento: 14 de novembro.","Divulgação: 70% em redes e 30% em imprensa."},
                    new String[][]{{"Terminar os testes no tablet","{S3}","quinta"},{"Preparar as capturas novas para a loja","{S1}","quarta"},{"Confirmar a data de lançamento","","sexta"}},
                    new Object[][]{{281,"Vamos fazer 70 e 30 e medir em duas semanas.","{S1}"},{267,"No ano passado a imprensa trouxe pouquíssimos downloads.","{S3}"}},
                    "lançamento","marketing","orçamento","tablet"),
                new long[]{32,252},new String[]{"data","orçamento"}),
            demo("Ideia para o podcast",190,222,null,null,new Object[][]{
                {"text",0,14,"Ideia para o podcast: um episódio sobre como organizar as notas de voz. Começar com a história do caderno perdido."},
                {"text",15,30,"Convidar a Carla, que usa o Obsidian todos os dias. Gravar no fim de outubro."}},
                note("Ideia para o podcast","Episódio sobre como organizar as notas de voz, começando com a história do caderno perdido.",new String[]{},
                    new String[][]{{"Convidar a Carla para o episódio","","fim de outubro"}},new Object[][]{},"podcast","ideias","notas"),
                null,null),
            demo("Aula: história do design",1500,2885,"Professora",null,new Object[][]{
                {A,0,20,"Hoje vamos ver como a Bauhaus mudou a forma de pensar os objetos do dia a dia."},
                {A,21,45,"A ideia central é que a forma segue a função, sem abrir mão da beleza."}},
                null,new long[]{610},new String[]{"prova"}),
            demo("Ligação com o fornecedor",2950,1880,"Martim",null,new Object[][]{
                {A,0,12,"Confirmo que o pedido sai na terça e chega na quinta."},
                {ME,13,22,"Perfeito. A nota fiscal você manda ou vem junto com a entrega?"}},
                null,null,null),
            demo("Lista para o fim de semana",4400,58,null,null,new Object[][]{
                {"text",0,20,"Comprar pão, café e frutas. Ligar para a vovó no sábado. Devolver o livro na biblioteca."}},
                null,null,null));
    }
}
