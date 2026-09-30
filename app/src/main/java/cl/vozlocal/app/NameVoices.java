package cl.vozlocal.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.io.File;
import java.util.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Hoja «Nombrar voces» en una sola pasada (0.6.0). Cada voz trae lo necesario para reconocerla sin salir de la hoja:
 * su color e inicial, ▶ para escuchar un tramo limpio de 3–8 s, sus primeras palabras, cuánto habla y nombres
 * sugeridos con un toque («Yo», tus nombres recientes y, si casi no habla, «Es <otra voz>» para unirla).
 * Todo se guarda de una vez al cerrar («Listo») y se puede deshacer. Mismo nombre = misma persona.
 * 0.7.0 (Verbapp): cada voz va en su propia tarjeta suave, con su círculo de color, una barra de cuánto habla y el ▶
 * redondo en menta que pasa a tinta mientras suena; al unir una voz con otra, su círculo toma el color de esa persona.
 */
final class NameVoices {
    private NameVoices(){}
    /** Nombres usados hace poco (en SharedPreferences "settings"), para sugerirlos con un toque. */
    static final String RECENT_KEY="recentNames";
    static final int RECENT_MAX=8;
    /** Bajo esta proporción del tiempo, una voz suele ser un error de la separación: se ofrece unirla a otra. */
    static final double SMALL_SHARE=0.05;
    static final double SAMPLE_MIN_S=3,SAMPLE_MAX_S=8;

    /** Una fila: una voz de la transcripción. */
    private static final class Voice {
        String key,label,initial,firstWords;int color;double share;double[] sample;
        EditText field;TextView avatar;ImageButton play;LinearLayout chips;LinearLayout row;
        /** Rellenos que cambian: el círculo de la inicial (toma el color de la voz a la que se une), el ▶ y la tarjeta. */
        GradientDrawable avatarFill,playFill,cardFill;
        /** Si no es null, esta voz se une a esa otra al guardar («Es Persona 1»). */
        String mergeInto;
        String typed(){return field.getText().toString().trim();}
    }

    /** Abre la hoja y la devuelve (para cerrarla, y así guardar lo escrito, si la pantalla se destruye); null si no se abrió. */
    static Sheet show(Screen s,String id,boolean demo,Transcript t,Runnable changed){
        Transcript tr=t;
        // Marca de la versión que se lee: si la transcripción cambia mientras la hoja está abierta, no se le aplican estos
        // nombres (null = sin comprobar: el ejemplo, o si solo se pudo usar la transcripción que ya tenía la pantalla).
        String stamp=null;
        if(!demo)synchronized(FilesStore.LOCK){try{stamp=stamp(s,id);tr=Transcript.load(s,id);}catch(Exception e){stamp=null;if(tr==null){s.message("Nombrar voces","No se pudo abrir la transcripción.");return null;}}}
        if(tr==null){s.message("Nombrar voces","No se pudo abrir la transcripción.");return null;}
        try{return build(s,id,demo,tr,stamp,changed);}
        catch(Exception e){Diagnostics.event("name_voices_failed",demo?null:id,"error_class",e.getClass().getSimpleName());s.message("Nombrar voces","No se pudieron cargar las voces.");return null;}
    }
    /** «fecha:tamaño» de la transcripción guardada; cambia con cada escritura (corrección, versión nueva o anterior). */
    static String stamp(Context c,String id){File f=FilesStore.file(c,id,".transcript.json");return f.isFile()?f.lastModified()+":"+f.length():"";}

    private static Sheet build(Screen s,String id,boolean demo,Transcript tr,String stamp,Runnable changed)throws Exception{
        Ui ui=s.ui;Palette p=s.p;
        LinkedHashMap<String,String> names=tr.speakers();
        if(names.isEmpty()){s.message("Nombrar voces","Esta transcripción no tiene intervenciones para nombrar.");return null;}
        if(!tr.diarized()){s.message("Nombrar voces","Esta transcripción se hizo sin separar voces. Para tener Persona 1, Persona 2…, vuelve a transcribir separando voces.");return null;}
        Map<String,Double> share=tr.talkShare();JSONArray segs=tr.segments();
        boolean mine=Voices.has(s);String myName=Voices.name(s);List<String> recent=recentNames(s);
        Recording recording=demo?null:FilesStore.recording(s,id);File audio=recording==null?null:recording.audio(s);
        boolean canListen=audio!=null&&audio.isFile();

        List<Voice> voices=new ArrayList<>();
        for(String key:names.keySet()){
            Voice v=new Voice();v.key=key;v.label=tr.defaultLabel(key);v.color=p.speaker(tr.colorIndex(key));
            v.share=share.containsKey(key)?share.get(key):0;v.firstWords=firstWords(segs,key,60);
            try{v.sample=canListen?sample(segs,key):null;}catch(Exception e){v.sample=null;}
            String current=names.get(key);v.initial=current.equals(v.label)?"":current;voices.add(v);
        }
        Sampler sampler=new Sampler(s,audio);
        Sheet sheet=s.sheet("Nombrar voces",(canListen?"Escucha cada voz y ponle su nombre. ":"Ponle nombre a cada voz. ")+"Si dos voces son la misma persona, dales el mismo nombre y se unen.");
        Runnable[] refresh={null};
        refresh[0]=()->{for(Voice v:voices)renderRow(s,v,voices,mine,myName,recent,refresh[0]);};
        // Una tarjeta por voz, separadas por 8 dp (en vez de divisores): cada persona se lee como un bloque.
        for(int i=0;i<voices.size();i++){
            Voice v=voices.get(i);boolean last=i==voices.size()-1;
            sheet.body.addView(row(s,v,last,canListen,sampler,refresh),ui.top(i==0?0:S2));
        }
        refresh[0].run();

        // Se guarda una sola vez al cerrar. «Listo» además cuenta como «ya revisé las voces» aunque no cambies nada.
        boolean[] closed={false};
        java.util.function.Consumer<Boolean> save=explicit->{if(closed[0])return;closed[0]=true;sampler.release();commit(s,id,demo,tr,stamp,voices,explicit,changed);};
        if(tr.edited()){
            Ui.Btn restore=ui.button("Restaurar voces originales",R.drawable.ic_refresh,Ui.Style.PLAIN,null);
            restore.setOnClickListener(v->{save.accept(false);sheet.dismiss();
                s.confirm("¿Restaurar voces originales?","Se deshacen todas las correcciones de quién habla. Los nombres se mantienen.","Restaurar",false,()->restore(s,id,demo,tr,changed));});
            LinearLayout.LayoutParams lp=Ui.wrap();lp.topMargin=ui.dp(S3);sheet.body.addView(restore,lp);
        }
        sheet.primary("Listo",Ui.Style.PRIMARY,()->{save.accept(true);return true;});
        // Tocar fuera o «atrás» también guarda lo escrito (con Deshacer): nunca se pierde. Si la pantalla se destruye
        // (giro, tema), ella cierra la hoja y esto mismo guarda lo escrito y libera el reproductor de muestras.
        sheet.onDismiss(()->save.accept(false));
        sheet.show();
        Diagnostics.event("name_voices_open",demo?null:id,"voices",voices.size(),"sample",canListen);
        return sheet;
    }

    // ---------- Filas ----------
    /**
     * Tarjeta de una voz (0.7.0), en este orden:
     * 1. su círculo de color con la inicial, «Persona 1», cuánto habla (barra en su color + «58 % del tiempo», en Outfit
     *    con cifras fijas) y el ▶ redondo de la muestra (menta; en tinta mientras suena);
     * 2. sus primeras palabras, para reconocerla sin escuchar;
     * 3. el campo del nombre y los nombres sugeridos.
     */
    private static View row(Screen s,Voice v,boolean last,boolean canListen,Sampler sampler,Runnable[] refresh){
        Ui ui=s.ui;Palette p=s.p;
        LinearLayout card=ui.column();v.cardFill=SheetParts.card(s,p,R_CARD);card.setBackground(v.cardFill);card.setPadding(ui.dp(S4),ui.dp(S4),ui.dp(S4),ui.dp(S4));v.row=card;
        LinearLayout head=ui.row();
        // Círculo con el color de la voz (el mismo punto que en la transcripción) y su inicial o número.
        v.avatarFill=oval(v.color);
        TextView avatar=ui.text("",Type.TITLE_MEDIUM,p.surface);avatar.setTypeface(outfit(s,Weight.SEMIBOLD));avatar.setGravity(Gravity.CENTER);avatar.setIncludeFontPadding(false);avatar.setBackground(v.avatarFill);avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(avatar,new LinearLayout.LayoutParams(ui.dp(40),ui.dp(40)));v.avatar=avatar;
        head.addView(ui.space(S3));
        LinearLayout who=ui.column();
        who.addView(ui.oneLine(ui.text(v.label,Type.TITLE_MEDIUM,p.onSurface)));
        // Cuánto habla: barra fina en su color (un vistazo) y el porcentaje escrito (el dato exacto).
        LinearLayout share=ui.row();
        LinearLayout track=ui.row();track.setBackground(shape(s,p.surfaceContainerHighest,R_FULL));track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        float part=v.share<=0?0f:(float)Math.max(0.03,Math.min(1.0,v.share));
        View fill=new View(s);fill.setBackground(shape(s,v.color,R_FULL));track.addView(fill,new LinearLayout.LayoutParams(0,-1,part));track.addView(new View(s),new LinearLayout.LayoutParams(0,-1,1f-part));
        share.addView(track,new LinearLayout.LayoutParams(0,ui.dp(6),1));share.addView(ui.space(S2));
        share.addView(Ui.tabular(ui.oneLine(ui.text(percent(v.share)+" del tiempo",Type.LABEL_MEDIUM,p.onSurfaceVariant))));
        LinearLayout.LayoutParams slp=Ui.fill();slp.topMargin=ui.dp(S1);who.addView(share,slp);
        head.addView(who,new LinearLayout.LayoutParams(0,-2,1));
        if(canListen){
            // ▶ redondo menta de 40 dp (se toca en 48 dp); mientras suena pasa a tinta con ❚❚ (Sampler.paint).
            ImageButton play=ui.iconButton(R.drawable.ic_play,"Escuchar a "+v.label,p.onPrimaryContainer,0,48);v.play=play;
            int in=ui.dp(S1);v.playFill=oval(p.primaryContainer);
            play.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.stateLayer(p.onPrimaryContainer)),new InsetDrawable(v.playFill,in),new InsetDrawable(oval(0xFF000000),in)));
            if(v.sample==null){play.setEnabled(false);play.setAlpha(0.38f);play.setContentDescription("Muestra no disponible para "+v.label);}
            else play.setOnClickListener(x->sampler.toggle(v));
            LinearLayout.LayoutParams plp=new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48));plp.setMarginStart(ui.dp(S2));plp.setMarginEnd(-ui.dp(S1));head.addView(play,plp);
        }
        card.addView(head,Ui.fill());
        // Qué dijo primero y si hay muestra: para saber quién es sin salir de la hoja.
        StringBuilder meta=new StringBuilder();
        if(!v.firstWords.isEmpty())meta.append("«").append(v.firstWords).append("»");
        if(canListen&&v.sample==null)meta.append(meta.length()>0?" · muestra no disponible":"Muestra no disponible");
        if(meta.length()>0){TextView m=ui.text(meta.toString(),Type.BODY_MEDIUM,p.onSurfaceVariant);m.setMaxLines(2);m.setEllipsize(android.text.TextUtils.TruncateAt.END);m.setPaddingRelative(ui.dp(S1),0,0,0);card.addView(m,ui.top(S3));}
        EditText f=ui.field(v.label,"Nombre para "+v.label);f.setText(v.initial);f.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        f.setImeOptions(last?EditorInfo.IME_ACTION_DONE:EditorInfo.IME_ACTION_NEXT);f.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        f.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence a,int b,int c,int d){}public void onTextChanged(CharSequence a,int b,int c,int d){}
            public void afterTextChanged(Editable e){if(v.mergeInto!=null&&e.length()>0)v.mergeInto=null;if(refresh[0]!=null)refresh[0].run();}});
        v.field=f;card.addView(f,ui.top(S3));
        // Los chips traen 8 dp de margen táctil arriba y abajo: el de abajo se come parte del relleno de la tarjeta.
        HorizontalScrollView hs=new HorizontalScrollView(s);hs.setHorizontalScrollBarEnabled(false);hs.setClipToPadding(false);
        LinearLayout chips=ui.row();hs.addView(chips);v.chips=chips;
        LinearLayout.LayoutParams hlp=ui.top(S1);hlp.bottomMargin=-ui.dp(S2);card.addView(hs,hlp);
        return card;
    }
    /** Actualiza inicial, estado del campo y chips de una fila según lo escrito (y lo que se unirá). */
    private static void renderRow(Screen s,Voice v,List<Voice> all,boolean mine,String myName,List<String> recent,Runnable refresh){
        Ui ui=s.ui;Palette p=s.p;String typed=v.typed();
        Voice target=v.mergeInto==null?null:find(all,v.mergeInto);
        String shown=target!=null?display(target):typed.isEmpty()?v.label:typed;
        v.avatar.setText(initial(shown));
        // Al unirla («Es Persona 1»), su círculo toma el color y la inicial de esa persona: se ve cómo quedará.
        if(v.avatarFill!=null)v.avatarFill.setColor(target!=null?target.color:v.color);
        v.field.setAlpha(target!=null?0.38f:1f);v.field.setHint(target!=null?"Se une con "+display(target):v.label);
        if(v.play!=null&&v.sample!=null)v.play.setContentDescription((v.play.getTag()!=null?"Detener muestra de ":"Escuchar a ")+(typed.isEmpty()?v.label:typed));
        v.chips.removeAllViews();
        // Nombres sugeridos: «Yo» (tu voz, si la grabaste) y los que usaste hace poco. Tocar uno activo lo quita.
        LinkedHashSet<String> suggestions=new LinkedHashSet<>();
        if(mine)suggestions.add(myName);
        for(String n:recent){if(suggestions.size()>=(mine?5:4))break;boolean dup=false;for(String x:suggestions)if(x.equalsIgnoreCase(n))dup=true;if(!dup)suggestions.add(n);}
        for(String name:suggestions){
            boolean on=target==null&&typed.equalsIgnoreCase(name);
            TextView chip=ui.filter(name,on,x->{Ui.haptic(x,Ui.Haptic.TICK);v.mergeInto=null;if(on)v.field.setText("");else{v.field.setText(name);v.field.setSelection(v.field.length());}refresh.run();});
            chip.setContentDescription(on?name+", seleccionado. Toca para quitar el nombre":"Ponerle «"+name+"» a "+v.label);
            addChip(s,v.chips,chip);
        }
        // Una voz que casi no habla suele ser otra que la separación partió en dos: «Es Persona 1» la une al guardar.
        if(v.share<SMALL_SHARE&&all.size()>1){
            List<Voice> big=new ArrayList<>();for(Voice o:all)if(o!=v&&o.share>=SMALL_SHARE)big.add(o);
            big.sort((a,b)->Double.compare(b.share,a.share));
            for(int i=0;i<Math.min(3,big.size());i++){Voice o=big.get(i);boolean on=o.key.equals(v.mergeInto);String label="Es "+display(o);
                TextView chip=ui.filter(label,on,x->{Ui.haptic(x,Ui.Haptic.TICK);if(on)v.mergeInto=null;else{v.mergeInto=o.key;if(v.field.length()>0)v.field.setText("");}refresh.run();});
                chip.setContentDescription(on?"Se une con "+display(o)+", seleccionado. Toca para no unir":"Es la misma persona que "+display(o));
                addChip(s,v.chips,chip);}
        }
        ((View)v.chips.getParent()).setVisibility(v.chips.getChildCount()==0?View.GONE:View.VISIBLE);
    }
    /** El chip se ve de 32 dp (Material) pero se toca en 48 dp: el fondo va con 8 dp de margen arriba y abajo. */
    private static void addChip(Screen s,LinearLayout chips,TextView chip){
        int inset=s.ui.dp(S2);chip.setMinHeight(s.ui.dp(48));chip.setMinimumHeight(s.ui.dp(48));
        if(chip.getBackground()!=null){
            // Un fondo con relleno propio (el InsetDrawable lo trae: 0, 8, 0, 8) reemplaza los cuatro lados del relleno de
            // la vista, así que el lateral que puso ui.filter quedaba en 0 y el texto tocaba los extremos redondos de la
            // píldora. Se guarda antes y se repone después; arriba y abajo se deja el del margen táctil, como antes.
            int start=chip.getPaddingStart(),end=chip.getPaddingEnd();
            chip.setBackground(new InsetDrawable(chip.getBackground(),0,inset,0,inset));
            chip.setPaddingRelative(start,chip.getPaddingTop(),end,chip.getPaddingBottom());
        }
        LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginEnd(s.ui.dp(S2));chips.addView(chip,lp);
    }
    private static Voice find(List<Voice> all,String key){for(Voice v:all)if(v.key.equals(key))return v;return null;}
    private static String display(Voice v){String t=v.typed();return t.isEmpty()?v.label:t;}
    /** «F» para Fran; «3» para Persona 3. */
    static String initial(String name){
        String n=name==null?"":name.trim();if(n.isEmpty())return "";
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^Persona (\\d+)$").matcher(n);if(m.matches())return m.group(1);
        return n.substring(0,n.offsetByCodePoints(0,1)).toUpperCase(new Locale("es","CL"));
    }
    /** «58 %», «< 1 %». */
    static String percent(double share){if(share<=0)return "0 %";if(share<0.01)return "< 1 %";return Math.round(share*100)+" %";}

    // ---------- Guardar (una sola vez) ----------
    private static void commit(Screen s,String id,boolean demo,Transcript tr,String stamp,List<Voice> voices,boolean explicit,Runnable changed){
        Map<String,String> names=new LinkedHashMap<>(),merges=new LinkedHashMap<>();boolean edited=false;
        for(Voice v:voices){
            Voice target=v.mergeInto==null?null:find(voices,v.mergeInto);
            if(target!=null&&target.mergeInto==null){merges.put(v.key,target.key);edited=true;}
            else{names.put(v.key,v.typed());if(!v.typed().equals(v.initial))edited=true;}
        }
        // Sin cambios: nada que guardar, salvo que «Listo» marque como revisadas unas voces que aún no lo estaban.
        if(!edited&&(tr.reviewed()||!explicit))return;
        int[] merged={0};
        Transcript.Edit op=x->{for(Map.Entry<String,String> m:merges.entrySet())x.merge(m.getKey(),m.getValue());merged[0]=merges.size()+x.applyNames(names);};
        try{
            JSONObject before;String after=null;
            if(demo){before=new JSONObject(tr.data.toString());op.apply(tr);writeDemo(s,tr);}
            else synchronized(FilesStore.LOCK){
                // Otra versión (p. ej. «Volver a la anterior») llegó mientras la hoja estaba abierta: estas voces son de
                // la otra transcripción y sus nombres irían a parar a personas equivocadas. No se aplica nada.
                if(stamp!=null&&!stamp.equals(stamp(s,id))){changedMeanwhile(s,id,"name");return;}
                before=Transcript.edit(s,id,op);after=stamp(s,id);
            }
            rememberNames(s,names.values());
            Diagnostics.event("voices_named",demo?null:id,"voices",voices.size(),"merged",merged[0],"changed",edited);
            Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.CONFIRM);
            if(changed!=null)changed.run();
            String done=!edited?"Voces revisadas":merged[0]==0?"Nombres guardados":merged[0]==1?"Nombres guardados · 2 voces quedaron en una":"Nombres guardados · "+merged[0]+" voces se unieron a otras";
            String saved=after;s.snackbar(done,"Deshacer",()->undo(s,id,demo,tr,before,saved,changed));
        }catch(Exception e){Diagnostics.event("voices_name_failed",demo?null:id,"error_class",e.getClass().getSimpleName());s.message("No se guardaron los nombres","Vuelve a intentarlo.");}
    }
    /** La transcripción cambió por otro lado (otra versión): se avisa en vez de escribir sobre ella. */
    private static void changedMeanwhile(Screen s,String id,String what){
        Diagnostics.event("voices_name_skipped",id,"reason","transcript_changed","kind",what);
        s.message("Nombrar voces","La transcripción cambió mientras la hoja estaba abierta (por ejemplo, volviste a la otra versión), así que no se aplicó nada. Ábrela de nuevo para nombrar estas voces.");
    }
    private static void restore(Screen s,String id,boolean demo,Transcript tr,Runnable changed){
        try{JSONObject before;String after=null;
            if(demo){before=new JSONObject(tr.data.toString());tr.restore();writeDemo(s,tr);}
            else synchronized(FilesStore.LOCK){before=Transcript.edit(s,id,Transcript::restore);after=stamp(s,id);}
            Diagnostics.event("transcript_edited",demo?null:id,"action","restore");
            if(changed!=null)changed.run();
            String saved=after;s.snackbar("Voces originales restauradas","Deshacer",()->undo(s,id,demo,tr,before,saved,changed));
        }catch(Exception e){s.message("Transcripción","No se pudieron restaurar las voces. Vuelve a intentarlo.");}
    }
    /** saved: marca de la transcripción justo después del cambio; si ya no coincide, «Deshacer» pisaría otra versión. */
    private static void undo(Screen s,String id,boolean demo,Transcript tr,JSONObject before,String saved,Runnable changed){
        try{
            if(demo){replaceData(tr.data,before);writeDemo(s,tr);}
            else synchronized(FilesStore.LOCK){
                if(saved!=null&&!saved.equals(stamp(s,id))){s.message("Deshacer","La transcripción cambió después (por ejemplo, volviste a la otra versión), así que ya no se puede deshacer este cambio.");return;}
                Transcript.replace(s,id,before);
            }
            Diagnostics.event("transcript_edited",demo?null:id,"action","undo");
            if(changed!=null)changed.run();
        }catch(Exception e){s.message("Transcripción","No se pudo deshacer el cambio.");}
    }
    /** En el ejemplo, la transcripción vive en memoria: se reemplaza su contenido sin cambiar el objeto que usa la pantalla. */
    static void replaceData(JSONObject target,JSONObject source)throws JSONException{
        List<String> keys=new ArrayList<>();for(Iterator<String> it=target.keys();it.hasNext();)keys.add(it.next());for(String k:keys)target.remove(k);
        JSONObject copy=new JSONObject(source.toString());for(Iterator<String> it=copy.keys();it.hasNext();){String k=it.next();target.put(k,copy.get(k));}
    }
    private static void writeDemo(Context c,Transcript tr)throws Exception{FilesStore.write(new File(c.getFilesDir(),"demo-transcript.json"),tr.data);}

    // ---------- Nombres recientes ----------
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("settings",Context.MODE_PRIVATE);}
    static List<String> recentNames(Context c){
        List<String> out=new ArrayList<>();
        try{JSONArray a=new JSONArray(prefs(c).getString(RECENT_KEY,"[]"));for(int i=0;i<a.length();i++){String n=a.optString(i).trim();if(!n.isEmpty())out.add(n);}}catch(Exception ignored){}
        return out;
    }
    /** Los nombres recién usados quedan primero; sin repetir, sin «Persona N» y como máximo {@link #RECENT_MAX}. */
    static void rememberNames(Context c,Collection<String> used){
        prefs(c).edit().putString(RECENT_KEY,new JSONArray(mergeRecent(recentNames(c),used)).toString()).apply();
    }
    static List<String> mergeRecent(List<String> previous,Collection<String> used){
        List<String> out=new ArrayList<>();
        List<String> all=new ArrayList<>(used);all.addAll(previous);
        for(String n:all){String t=n==null?"":n.trim();if(t.isEmpty()||t.matches("(?i)^persona \\d+$")||t.equalsIgnoreCase("Texto"))continue;
            boolean dup=false;for(String o:out)if(o.equalsIgnoreCase(t))dup=true;if(!dup)out.add(t);if(out.size()>=RECENT_MAX)break;}
        return out;
    }

    // ---------- Muestra de cada voz ----------
    /** Comienzo de la primera intervención de una voz. */
    static String firstWords(JSONArray segs,String key,int max){
        for(int i=0;i<segs.length();i++){JSONObject s=segs.optJSONObject(i);if(s==null||!key.equals(s.optString("speaker")))continue;
            String t=s.optString("text").trim().replaceAll("\\s+"," ");return t.length()>max?t.substring(0,max-1).trim()+"…":t;}
        return "";
    }
    /**
     * Un tramo limpio de 3–8 s de esa voz: intervenciones seguidas suyas, sin otra voz encima. Devuelve {desde, hasta}
     * en segundos, o null si esa voz casi siempre habla junto a otra (se muestra «Muestra no disponible»).
     * Se prefiere un tramo de unos 6 s; si ninguno llega a 3 s, sirve el más largo desde 1,5 s.
     */
    static double[] sample(JSONArray segs,String key)throws JSONException{
        double[] best=null;double bestScore=Double.MAX_VALUE;double[] fallback=null;int n=segs.length();
        for(int i=0;i<n;){
            JSONObject s=segs.getJSONObject(i);
            if(!key.equals(s.getString("speaker"))){i++;continue;}
            double from=s.getDouble("start"),to=s.optDouble("end",from);int j=i+1;
            while(j<n&&to-from<SAMPLE_MAX_S){JSONObject x=segs.getJSONObject(j);if(!key.equals(x.getString("speaker"))||x.getDouble("start")-to>1.0)break;to=Math.max(to,x.optDouble("end",to));j++;}
            if(clean(segs,key,i,j,from,to)){
                double len=Math.min(to-from,SAMPLE_MAX_S);
                if(len>=SAMPLE_MIN_S){double score=Math.abs(len-6);if(score<bestScore){bestScore=score;best=new double[]{from,from+len};if(score<1)return best;}}
                else if(len>=1.5&&(fallback==null||len>fallback[1]-fallback[0]))fallback=new double[]{from,from+len};
            }
            i=j;
        }
        return best!=null?best:fallback;
    }
    /** ¿Ninguna otra voz habla entre from y to? (los tramos vienen ordenados por inicio; se toleran 0,25 s en los bordes) */
    private static boolean clean(JSONArray segs,String key,int i,int j,double from,double to)throws JSONException{
        double a=from+0.25,b=to-0.25;
        for(int k=i-1;k>=0;k--){JSONObject x=segs.getJSONObject(k);if(x.getDouble("start")<from-120)break;if(!key.equals(x.getString("speaker"))&&x.optDouble("end",x.getDouble("start"))>a)return false;}
        for(int k=i+1;k<segs.length();k++){JSONObject x=segs.getJSONObject(k);if(x.getDouble("start")>=b)break;if(!key.equals(x.getString("speaker")))return false;}
        return true;
    }

    /** Reproductor propio de la hoja: escucha una muestra sin cerrarla, se detiene solo y se libera al cerrar. */
    private static final class Sampler {
        final Screen s;final File audio;final Handler handler=new Handler(Looper.getMainLooper());
        MediaPlayer player;AudioFocusRequest focus;Voice playing;long until;
        final Runnable tick=new Runnable(){public void run(){
            if(player==null||playing==null)return;
            try{if(!player.isPlaying()||player.getCurrentPosition()>=until){stop();return;}}catch(IllegalStateException e){stop();return;}
            handler.postDelayed(this,100);}};
        Sampler(Screen s,File audio){this.s=s;this.audio=audio;}
        void toggle(Voice v){
            if(playing==v){stop();return;}
            stop();
            if(RecorderService.activeId!=null){s.toast("Guarda la grabación actual antes de escuchar");return;}
            if(v.sample==null||audio==null)return;
            try{
                AudioAttributes attr=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                if(player==null){
                    player=new MediaPlayer();player.setAudioAttributes(attr);player.setDataSource(audio.getAbsolutePath());player.prepare();
                    player.setOnCompletionListener(mp->stop());
                    player.setOnErrorListener((mp,w,e)->{release();s.toast("No se pudo reproducir la muestra");return true;});
                }
                focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attr).setOnAudioFocusChangeListener(ch->{if(ch<0)stop();}).build();
                if(s.getSystemService(AudioManager.class).requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){focus=null;return;}
                player.seekTo((long)(v.sample[0]*1000),MediaPlayer.SEEK_CLOSEST);player.start();
                until=(long)(v.sample[1]*1000)+150;playing=v;
                v.play.setImageResource(R.drawable.ic_pause);v.play.setTag(Boolean.TRUE);v.play.setContentDescription("Detener muestra de "+display(v));paint(v,true);
                handler.removeCallbacks(tick);handler.postDelayed(tick,100);
                Diagnostics.event("voice_sample_played",null,"seconds",Math.round(v.sample[1]-v.sample[0]));
            }catch(Exception e){release();s.toast("No se pudo reproducir la muestra");}
        }
        void stop(){
            handler.removeCallbacks(tick);
            try{if(player!=null&&player.isPlaying())player.pause();}catch(IllegalStateException ignored){}
            if(focus!=null){s.getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);focus=null;}
            if(playing!=null&&playing.play!=null){playing.play.setImageResource(R.drawable.ic_play);playing.play.setTag(null);playing.play.setContentDescription("Escuchar a "+display(playing));paint(playing,false);}
            playing=null;
        }
        void release(){stop();if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}}
        /**
         * La voz que suena se nota: su ▶ pasa de menta a tinta (❚❚) y su tarjeta se enmarca con su color.
         * Al detenerse vuelve a menta y al borde de siempre (vidrio en oscuro, ninguno en claro).
         */
        void paint(Voice v,boolean on){
            Palette p=s.p;int fg=on?p.onInk:p.onPrimaryContainer;
            if(v.playFill!=null)v.playFill.setColor(on?p.ink:p.primaryContainer);
            if(v.play!=null){v.play.setImageTintList(ColorStateList.valueOf(fg));if(v.play.getBackground() instanceof RippleDrawable)((RippleDrawable)v.play.getBackground()).setColor(ColorStateList.valueOf(Ui.stateLayer(fg)));}
            if(v.cardFill!=null){if(on)v.cardFill.setStroke(s.ui.dp(2),v.color);else if(p.dark)v.cardFill.setStroke(Math.max(1,s.ui.dp(1)),p.glassStroke);else v.cardFill.setStroke(0,0);}
        }
    }
}
