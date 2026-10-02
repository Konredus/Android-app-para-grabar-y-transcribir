package cl.vozlocal.app;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Idiomas (0.9.0): cada texto existe en inglés, español y portugués, con los mismos argumentos (%1$s, %d…), sin quedar
 * vacío ni sin traducir por error; y Lang elige y aplica bien el idioma.
 */
final class I18nChecks {
    private I18nChecks(){}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static final Pattern ARG=Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXc%n]");

    static void run(Context c)throws Exception{
        String before=null;
        try{
            // 1. Elegir el idioma.
            check(Lang.ES.equals(Lang.normalize("es-CL"))&&Lang.PT.equals(Lang.normalize("pt_BR"))&&Lang.PT.equals(Lang.normalize("pt-PT")),"Lang.normalize wrong for regional tags");
            check(Lang.normalize("fr")==null&&Lang.normalize(null)==null,"Lang.normalize accepted an unsupported language");
            check(Lang.locale(Lang.PT).getLanguage().equals("pt")&&Lang.locale(Lang.ES).getLanguage().equals("es")&&Lang.locale(Lang.EN).getLanguage().equals("en"),"Lang.locale language wrong");
            // Sin el teléfono en portugués (el emulador va en inglés), la región de referencia: Brasil, la de sus textos.
            if(!"pt".equals(java.util.Locale.getDefault().getLanguage()))check(Lang.locale(Lang.PT).toLanguageTag().equals("pt-BR"),"Portuguese must default to pt-BR: "+Lang.locale(Lang.PT));
            for(String lang:Lang.SUPPORTED){
                Lang.override(lang);
                check(lang.equals(Lang.current(c)),"Lang.override did not apply "+lang);
                check(lang.equals(Lang.normalize(Lang.wrap(c).getResources().getConfiguration().getLocales().get(0).getLanguage())),"Lang.wrap did not localize to "+lang);
            }
            Lang.override(Lang.EN);check(Lang.str(c,R.string.common_cancel).equals("Cancel"),"English text wrong: "+Lang.str(c,R.string.common_cancel));
            Lang.override(Lang.ES);check(Lang.str(c,R.string.common_cancel).equals("Cancelar"),"Spanish text wrong");
            Lang.override(Lang.PT);check(Lang.str(c,R.string.common_back_to,"Grabar").equals("Voltar para Grabar"),"Portuguese format wrong: "+Lang.str(c,R.string.common_back_to,"Grabar"));

            // 2. Todos los textos, en los tres idiomas, con los mismos argumentos.
            Resources en=resources(c,Locale.ENGLISH),es=resources(c,new Locale("es")),pt=resources(c,new Locale("pt","BR"));
            List<String> problems=new ArrayList<>();int count=0;
            for(Field f:R.string.class.getFields()){
                int id=f.getInt(null);String name=f.getName();count++;
                String e=en.getString(id),s=es.getString(id),p=pt.getString(id);
                if(e.trim().isEmpty()||s.trim().isEmpty()||p.trim().isEmpty()){problems.add(name+": empty");continue;}
                if(!args(e).equals(args(s))||!args(e).equals(args(p)))problems.add(name+": arguments differ "+args(e)+" / "+args(s)+" / "+args(p));
            }
            // R.plurals existe solo si la app declara plurales: se busca por nombre para no depender de eso al compilar.
            Field[] plurals=new Field[0];try{plurals=Class.forName(R.class.getName()+"$plurals").getFields();}catch(ClassNotFoundException ignored){}
            for(Field f:plurals){
                int id=f.getInt(null);
                for(Resources r:new Resources[]{en,es,pt})for(int n:new int[]{0,1,2,7}){String v=r.getQuantityString(id,n);if(v.trim().isEmpty())problems.add(f.getName()+": empty plural");}
            }
            check(problems.isEmpty(),"String resources: "+problems.size()+" problems, e.g. "+problems.subList(0,Math.min(8,problems.size())));
            check(count>=10,"Too few string resources: "+count);
        }finally{Lang.override(Lang.ES);}
    }
    /** Argumentos de formato de un texto, en orden ("%1$s", "%d"…); "%%" y "%n" no cuentan. */
    private static List<String> args(String text){
        List<String> out=new ArrayList<>();Matcher m=ARG.matcher(text);
        while(m.find()){String a=m.group();if(a.equals("%%")||a.equals("%n"))continue;out.add(a.substring(a.length()-1)+(m.group(1)==null?"":m.group(1)));}
        java.util.Collections.sort(out);return out;
    }
    private static Resources resources(Context c,Locale l){
        Configuration conf=new Configuration(c.getResources().getConfiguration());conf.setLocale(l);conf.setLocales(new LocaleList(l));
        return c.createConfigurationContext(conf).getResources();
    }
}
