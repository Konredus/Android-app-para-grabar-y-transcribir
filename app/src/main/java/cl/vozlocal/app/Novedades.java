package cl.vozlocal.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Novedades de cada versión, en lenguaje simple (assets/novedades.json, distinto del CHANGELOG técnico).
 * - maybeShow: hoja «Novedades de la X» una sola vez por versión (compara versionCode con el último visto);
 *   nunca en la primera instalación, donde ya está la bienvenida.
 * - showAll: historial (la más nueva arriba), cada versión con su fecha y plegable.
 * Convención de cada punto: «Titular: detalle.» El titular va destacado y el detalle debajo.
 */
final class Novedades {
    private Novedades(){}

    static final class Entry {
        final String version,date,title;final List<String> items;
        Entry(String version,String date,String title,List<String> items){this.version=version;this.date=date;this.title=title;this.items=items;}
    }

    /** Todas las versiones del archivo, la más nueva primero. Lista vacía si el archivo falta o está dañado. */
    static List<Entry> all(Context c){
        List<Entry> out=new ArrayList<>();
        try(InputStream in=c.getAssets().open("novedades.json")){out.addAll(parse(read(in)));}catch(Exception ignored){}
        return out;
    }
    /** Lógica pura (sin Android): JSON → entradas válidas, ordenadas de la más nueva a la más antigua. */
    static List<Entry> parse(String json)throws JSONException{
        List<Entry> out=new ArrayList<>();JSONArray all=new JSONArray(json);
        for(int i=0;i<all.length();i++){
            JSONObject o=all.optJSONObject(i);if(o==null)continue;
            String version=o.optString("version","").trim();if(version.isEmpty())continue;
            List<String> items=new ArrayList<>();JSONArray list=o.optJSONArray("items");
            if(list!=null)for(int j=0;j<list.length();j++){String item=list.optString(j,"").trim();if(!item.isEmpty())items.add(item);}
            out.add(new Entry(version,o.optString("date","").trim(),o.optString("title","").trim(),items));
        }
        out.sort((a,b)->compare(b.version,a.version));
        return out;
    }
    /** Compara «0.4.10» con «0.5.0» por número, no por texto. */
    static int compare(String a,String b){
        String[] x=a.split("\\."),y=b.split("\\.");
        for(int i=0;i<Math.max(x.length,y.length);i++){int d=Integer.compare(number(x,i),number(y,i));if(d!=0)return d;}
        return 0;
    }
    private static int number(String[] parts,int i){if(i>=parts.length)return 0;try{return Integer.parseInt(parts[i].replaceAll("[^0-9].*$",""));}catch(Exception e){return 0;}}

    /** Versión visible («0.6.0»), sin sufijos de compilación. */
    static String versionName(Context c){
        try{String v=info(c).versionName;return v==null?"":v.trim().replaceAll("[ -].*$","");}catch(Exception e){return "";}
    }
    static int versionCode(Context c){
        try{PackageInfo i=info(c);return Build.VERSION.SDK_INT>=28?(int)i.getLongVersionCode():i.versionCode;}catch(Exception e){return 0;}
    }
    private static PackageInfo info(Context c)throws Exception{return c.getPackageManager().getPackageInfo(c.getPackageName(),0);}

    static Entry entry(Context c,String version){for(Entry e:all(c))if(e.version.equals(version))return e;return null;}
    /** Puntos de la versión instalada (vacío si esa versión no trae novedades). */
    static List<String> current(Context c){Entry e=entry(c,versionName(c));return e==null?new ArrayList<>():new ArrayList<>(e.items);}

    /**
     * Llamar en onResume de Inicio, después de la bienvenida. Muestra la hoja una vez por versión.
     * Primera instalación (sin grabaciones, o nunca actualizada): solo recuerda la versión, sin mostrar nada.
     */
    static void maybeShow(Screen s){
        try{
            Settings settings=new Settings(s);int code=versionCode(s),last=settings.lastSeenVersion();
            if(code<=0||last>=code)return;
            if(RecorderService.activeId!=null)return; // no interrumpir una grabación: se muestra la próxima vez
            boolean fresh=last==0&&(!hasRecordings(s)||neverUpdated(s));
            Entry e=entry(s,versionName(s));
            settings.setLastSeenVersion(code); // antes de mostrar: nunca dos veces, aunque Android cierre la app
            if(fresh||e==null||e.items.isEmpty())return;
            Sheet sheet=s.sheet("Novedades de la "+e.version,e.title.isEmpty()?null:e.title+".");
            for(String item:e.items)sheet.add(bullet(s,item));
            sheet.primary("Entendido",()->{}).secondary("Ver todas las versiones",()->showAll(s)).show();
            Diagnostics.event("ui_action",null,"screen","Novedades","action","shown","result",code);
        }catch(Exception ignored){}
    }
    private static boolean hasRecordings(Context c){String[] names=Recording.directory(c).list((dir,name)->name.endsWith(".m4a"));return names!=null&&names.length>0;}
    private static boolean neverUpdated(Context c){try{PackageInfo i=info(c);return i.firstInstallTime==i.lastUpdateTime;}catch(Exception e){return false;}}

    /** Historial de versiones: la más nueva arriba; la instalada viene abierta y el resto plegado. */
    static void showAll(Screen s){
        List<Entry> list=all(s);String current=versionName(s);
        Sheet sheet=s.sheet("Novedades y versiones",current.isEmpty()?null:"Estás usando la "+current+".");
        if(list.isEmpty())sheet.add(s.ui.text("No se encontró el historial de versiones.",Type.BODY_MEDIUM,s.p.onSurfaceVariant));
        boolean found=false;for(Entry e:list)if(e.version.equals(current))found=true;
        for(int i=0;i<list.size();i++){
            Entry e=list.get(i);boolean isCurrent=e.version.equals(current);
            if(i>0)sheet.body.addView(s.ui.separator(0));
            LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(-s.ui.dp(S6),0,-s.ui.dp(S6),0);
            sheet.body.addView(versionBlock(s,e,isCurrent,isCurrent||(!found&&i==0)),lp);
        }
        sheet.secondary("Cerrar",null).show();
    }

    /** Bloque plegable de una versión: cabecera tocable (versión, fecha, «Actual») y sus puntos. */
    private static View versionBlock(Screen s,Entry e,boolean isCurrent,boolean open){
        Ui ui=s.ui;Palette p=s.p;
        LinearLayout block=ui.column();
        LinearLayout head=ui.row();head.setMinimumHeight(ui.dp(64));head.setPadding(ui.dp(S6),ui.dp(S2),ui.dp(S5),ui.dp(S2));
        LinearLayout texts=ui.column();
        LinearLayout line=ui.row();line.addView(ui.text("Versión "+e.version,Type.TITLE_MEDIUM,p.onSurface));
        if(isCurrent){line.addView(ui.space(S2));TextView chip=ui.chip("Actual",p.onSecondaryContainer,p.secondaryContainer);AppTheme.type(chip,Type.LABEL_MEDIUM);chip.setPadding(ui.dp(S2),ui.dp(2),ui.dp(S2),ui.dp(2));line.addView(chip);}
        texts.addView(line);
        String date=date(e.date);
        if(!date.isEmpty()){TextView d=ui.text(date,Type.BODY_MEDIUM,p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);texts.addView(d);}
        head.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        ImageView arrow=ui.icon(R.drawable.ic_arrow_back,p.onSurfaceVariant,24);head.addView(arrow);
        block.addView(head,Ui.fill());

        LinearLayout body=ui.column();body.setPadding(ui.dp(S6),0,ui.dp(S6),ui.dp(S3));
        if(!e.title.isEmpty()){TextView t=ui.text(e.title+".",Type.BODY_MEDIUM,p.onSurfaceVariant);t.setPadding(0,0,0,ui.dp(S1));body.addView(t);}
        for(String item:e.items)body.addView(bullet(s,item),Ui.fill());
        block.addView(body,Ui.fill());

        String label="Versión "+e.version+(date.isEmpty()?"":", "+date)+(isCurrent?", la que usas":"");
        head.setBackground(ui.ripple(null,0));head.setClickable(true);head.setFocusable(true);head.setAccessibilityDelegate(Ui.buttonRole());
        Runnable apply=()->{boolean shown=body.getVisibility()==View.VISIBLE;arrow.setRotation(shown?90:270);head.setContentDescription(label+(shown?", abierta. Toca para plegar":", plegada. Toca para ver sus novedades"));};
        body.setVisibility(open?View.VISIBLE:View.GONE);apply.run();
        head.setOnClickListener(v->{boolean show=body.getVisibility()!=View.VISIBLE;body.setVisibility(show?View.VISIBLE:View.GONE);if(show)ui.fadeIn(body);apply.run();Ui.haptic(v,Ui.Haptic.TICK);});
        return block;
    }

    /** Un punto: marca de color + titular destacado + detalle (o un solo texto si no sigue la convención «Titular: detalle»). */
    private static View bullet(Screen s,String item){
        Ui ui=s.ui;Palette p=s.p;
        LinearLayout row=ui.row();row.setGravity(Gravity.TOP);row.setPadding(0,ui.dp(S2),0,ui.dp(S2));
        View dot=new View(s);dot.setBackground(oval(p.primary));dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(ui.dp(8),ui.dp(8));dp.topMargin=ui.dp(8);dp.setMarginEnd(ui.dp(S3));row.addView(dot,dp);
        LinearLayout texts=ui.column();int cut=headline(item);
        if(cut>0){
            texts.addView(ui.text(item.substring(0,cut).trim(),Type.TITLE_MEDIUM,p.onSurface));
            TextView d=ui.text(capitalize(item.substring(cut+1).trim()),Type.BODY_MEDIUM,p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);texts.addView(d);
        }else texts.addView(ui.text(item,Type.BODY_LARGE,p.onSurface));
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        return row;
    }
    /** Posición de los «:» que cierran el titular, o -1 si el punto no tiene titular corto. */
    static int headline(String item){int i=item.indexOf(": ");return i>0&&i<=48?i:-1;}
    private static String capitalize(String v){return v.isEmpty()?v:v.substring(0,1).toUpperCase(new Locale("es","CL"))+v.substring(1);}
    /** «2026-09-29» → «29 de septiembre de 2026». */
    static String date(String iso){
        if(iso==null||iso.isEmpty())return "";
        try{SimpleDateFormat in=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);in.setLenient(false);return new SimpleDateFormat("d 'de' MMMM 'de' yyyy",new Locale("es","CL")).format(in.parse(iso));}catch(Exception e){return iso;}
    }
    private static String read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}
}
