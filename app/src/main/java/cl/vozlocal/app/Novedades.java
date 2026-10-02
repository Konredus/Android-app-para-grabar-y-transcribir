package cl.vozlocal.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
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
 * 0.7.0 (Verbapp): titulares en Outfit, viñeta ✦ verde (el destello del logo) y la versión en una píldora.
 * 0.9.0: un archivo por idioma, con las mismas versiones y puntos: novedades.json (español, el original),
 * novedades-en.json y novedades-pt.json. Cada versión nueva se agrega en los tres.
 */
final class Novedades {
    private Novedades(){}

    static final class Entry {
        final String version,date,title;final List<String> items;
        Entry(String version,String date,String title,List<String> items){this.version=version;this.date=date;this.title=title;this.items=items;}
    }

    /** El archivo de un idioma: el español es el original, sin sufijo. */
    static String file(String lang){return Lang.ES.equals(lang)?"novedades.json":"novedades-"+lang+".json";}
    /** Idiomas a probar, en orden: el de la app, inglés (el respaldo de la app) y español (el original). */
    private static List<String> languages(Context c){
        List<String> out=new ArrayList<>();for(String l:new String[]{Lang.current(c),Lang.EN,Lang.ES})if(!out.contains(l))out.add(l);return out;
    }
    /** Las versiones en el idioma de la app, la más nueva primero; si su archivo falta o está dañado, en inglés y luego en español. */
    static List<Entry> all(Context c){
        for(String lang:languages(c)){List<Entry> list=all(c,lang);if(!list.isEmpty())return list;}
        return new ArrayList<>();
    }
    /** Las versiones del archivo de ese idioma, la más nueva primero. Lista vacía si el archivo falta o está dañado. */
    static List<Entry> all(Context c,String lang){
        List<Entry> out=new ArrayList<>();
        try(InputStream in=c.getAssets().open(file(lang))){out.addAll(parse(read(in)));}catch(Exception ignored){}
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

    /** Una versión en el idioma de la app; si ese archivo aún no la trae, en inglés o en español antes que no mostrarla. */
    static Entry entry(Context c,String version){for(String lang:languages(c))for(Entry e:all(c,lang))if(e.version.equals(version))return e;return null;}
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
            // 0.7.0: sin título de hoja; arriba va la píldora menta «Novedades de la X» y el titular grande en Outfit.
            Sheet sheet=s.sheet(null,null);sheet.add(hero(s,e));
            for(String item:e.items)sheet.add(bullet(s,item));
            sheet.primary(s.getString(R.string.common_ok),()->{}).secondary(s.getString(R.string.news_see_all),()->showAll(s)).show();
            Diagnostics.event("ui_action",null,"screen","Novedades","action","shown","result",code);
        }catch(Exception ignored){}
    }
    private static boolean hasRecordings(Context c){String[] names=Recording.directory(c).list((dir,name)->name.endsWith(".m4a"));return names!=null&&names.length>0;}
    private static boolean neverUpdated(Context c){try{PackageInfo i=info(c);return i.firstInstallTime==i.lastUpdateTime;}catch(Exception e){return false;}}

    /** Historial de versiones: la más nueva arriba; la instalada viene abierta y el resto plegado. */
    static void showAll(Screen s){
        List<Entry> list=all(s);String current=versionName(s);
        Sheet sheet=s.sheet(s.getString(R.string.news_title),current.isEmpty()?null:s.getString(R.string.news_using,current));
        if(list.isEmpty())sheet.add(s.ui.text(s.getString(R.string.news_missing),Type.BODY_MEDIUM,s.p.onSurfaceVariant));
        boolean found=false;for(Entry e:list)if(e.version.equals(current))found=true;
        for(int i=0;i<list.size();i++){
            Entry e=list.get(i);boolean isCurrent=e.version.equals(current);
            if(i>0)sheet.body.addView(s.ui.separator(0));
            LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(-s.ui.dp(S6),0,-s.ui.dp(S6),0);
            sheet.body.addView(versionBlock(s,e,isCurrent,isCurrent||(!found&&i==0)),lp);
        }
        sheet.secondary(s.getString(R.string.news_close),null).show();
    }

    /**
     * Cabecera de las novedades de una versión (0.7.0): píldora menta con ✦ «Novedades de la X» y, debajo, el titular grande
     * en Outfit con su última palabra destacada en menta (como los títulos de la referencia). El titular es el encabezado.
     */
    private static View hero(Screen s,Entry e){
        Ui ui=s.ui;Palette p=s.p;
        LinearLayout box=ui.column();box.setPadding(0,0,0,ui.dp(S2));
        TextView pill=ui.chip(s.getString(R.string.news_of_version,e.version),p.onPrimaryContainer,p.primaryContainer);Ui.tabular(pill);pill.setPadding(ui.dp(S3),ui.dp(6),ui.dp(S4),ui.dp(6));
        Drawable spark=s.getDrawable(R.drawable.ic_sparkle).mutate();spark.setTint(p.primary);spark.setBounds(0,0,ui.dp(16),ui.dp(16));pill.setCompoundDrawablesRelative(spark,null,null,null);pill.setCompoundDrawablePadding(ui.dp(6));
        if(e.title.isEmpty()){if(Build.VERSION.SDK_INT>=28)pill.setAccessibilityHeading(true);}
        box.addView(pill,Ui.wrap());
        if(!e.title.isEmpty()){TextView t=ui.heading("",Type.HEADLINE_SMALL);ui.highlightLast(t,e.title);t.setPadding(0,ui.dp(S3),0,ui.dp(S1));box.addView(t,Ui.fill());}
        return box;
    }

    /**
     * Bloque plegable de una versión (0.7.0): la versión en una píldora (de tinta si es la que usas, con «Actual» en menta),
     * el titular en Outfit y la fecha; al abrirlo, sus puntos. La flecha gira al plegar y desplegar.
     */
    private static View versionBlock(Screen s,Entry e,boolean isCurrent,boolean open){
        Ui ui=s.ui;Palette p=s.p;
        LinearLayout block=ui.column();
        LinearLayout head=ui.row();head.setMinimumHeight(ui.dp(72));head.setPadding(ui.dp(S6),ui.dp(S3),ui.dp(S5),ui.dp(S3));
        LinearLayout texts=ui.column();
        LinearLayout line=ui.row();
        TextView version=Ui.tabular(ui.text(e.version,Type.LABEL_LARGE,isCurrent?p.onInk:p.onSurface));version.setPadding(ui.dp(S3),ui.dp(S1),ui.dp(S3),ui.dp(S1));
        version.setBackground(isCurrent?shape(s,p.ink,R_FULL):outline(s,p.dark?p.surfaceContainerHigh:p.surfaceContainerLow,p.outlineVariant,R_FULL,false));line.addView(version,Ui.wrap());
        if(isCurrent){TextView chip=ui.chip(s.getString(R.string.news_current),p.onPrimaryContainer,p.primaryContainer);AppTheme.type(chip,Type.LABEL_MEDIUM);chip.setPadding(ui.dp(S2),ui.dp(S1),ui.dp(S2),ui.dp(S1));LinearLayout.LayoutParams cp=Ui.wrap();cp.setMarginStart(ui.dp(S2));line.addView(chip,cp);}
        texts.addView(line,Ui.wrap());
        if(!e.title.isEmpty()){TextView t=ui.text(e.title,Type.TITLE_MEDIUM,p.onSurface);t.setPadding(0,ui.dp(S2),0,0);texts.addView(t);}
        String date=date(e.date);
        if(!date.isEmpty()){TextView d=ui.text(date,Type.BODY_SMALL,p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);texts.addView(d);}
        head.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout arrow=ui.tile(R.drawable.ic_chevron_down,p.onSurfaceVariant,p.dark?p.surfaceContainerHigh:p.surfaceContainerLow,32,20);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(ui.dp(32),ui.dp(32));ap.setMarginStart(ui.dp(S3));head.addView(arrow,ap);
        block.addView(head,Ui.fill());

        LinearLayout body=ui.column();body.setPadding(ui.dp(S6),0,ui.dp(S6),ui.dp(S4));
        for(String item:e.items)body.addView(bullet(s,item),Ui.fill());
        block.addView(body,Ui.fill());

        String label=s.getString(R.string.news_version,e.version)+(e.title.isEmpty()?"":", "+e.title)+(date.isEmpty()?"":", "+date)+(isCurrent?", "+s.getString(R.string.news_in_use):"");
        head.setBackground(ui.ripple(null,0));head.setClickable(true);head.setFocusable(true);head.setAccessibilityDelegate(Ui.buttonRole());
        // Flecha ▾ plegada y ▴ abierta; al tocar gira (sin animaciones del sistema, cambia de golpe).
        Runnable apply=()->{boolean shown=body.getVisibility()==View.VISIBLE;head.setContentDescription(s.getString(shown?R.string.news_open:R.string.news_closed,label));};
        body.setVisibility(open?View.VISIBLE:View.GONE);arrow.setRotation(open?180:0);apply.run();
        head.setOnClickListener(v->{boolean show=body.getVisibility()!=View.VISIBLE;body.setVisibility(show?View.VISIBLE:View.GONE);if(show)ui.fadeIn(body);
            arrow.animate().cancel();if(AppTheme.motion())arrow.animate().rotation(show?180:0).setStartDelay(0).setDuration(MOTION_BASE).setInterpolator(EMPHASIZED).start();else arrow.setRotation(show?180:0);
            apply.run();Ui.haptic(v,Ui.Haptic.TICK);});
        return block;
    }

    /**
     * Un punto: viñeta ✦ verde (el destello del logo) + titular destacado en Outfit + detalle (o un solo texto si no sigue
     * la convención «Titular: detalle»).
     */
    private static View bullet(Screen s,String item){
        Ui ui=s.ui;Palette p=s.p;
        LinearLayout row=ui.row();row.setGravity(Gravity.TOP);row.setPadding(0,ui.dp(S2),0,ui.dp(S2));
        // La viñeta de 16 dp queda centrada en la primera línea (unos 24 dp de alto).
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(ui.dp(16),ui.dp(16));dp.topMargin=ui.dp(S1);dp.setMarginEnd(ui.dp(S3));row.addView(new Spark(s,p.primary),dp);
        LinearLayout texts=ui.column();int cut=headline(item);
        if(cut>0){
            texts.addView(ui.text(item.substring(0,cut).trim(),Type.TITLE_MEDIUM,p.onSurface));
            TextView d=ui.text(capitalize(item.substring(cut+1).trim(),Lang.locale(s)),Type.BODY_MEDIUM,p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);texts.addView(d);
        }else texts.addView(ui.text(item,Type.BODY_LARGE,p.onSurface));
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        return row;
    }
    /** Viñeta ✦: el destello de cuatro puntas del logo (Glass.sparkle), en verde. Es solo dibujo. */
    private static final class Spark extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();
        Spark(Context c,int color){super(c);paint.setColor(color);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){float w=getWidth(),h=getHeight();canvas.drawPath(Glass.sparkle(path,w/2f,h/2f,Math.min(w,h)*0.46f),paint);}
    }
    /** Posición de los «:» que cierran el titular, o -1 si el punto no tiene titular corto. */
    static int headline(String item){int i=item.indexOf(": ");return i>0&&i<=48?i:-1;}
    private static String capitalize(String v,Locale l){return v.isEmpty()?v:v.substring(0,1).toUpperCase(l)+v.substring(1);}
    /**
     * «2026-09-29» → «29 de septiembre de 2026» (español), «September 29, 2026» (inglés de EE. UU.), «29 de setembro de
     * 2026» (portugués): el día, el mes con su nombre y el año, en el orden de Lang.locale().
     */
    static String date(String iso){
        if(iso==null||iso.isEmpty())return "";
        try{SimpleDateFormat in=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);in.setLenient(false);Locale l=Lang.locale();
            return new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(l,"dMMMMy"),l).format(in.parse(iso));}catch(Exception e){return iso;}
    }
    private static String read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}
}
