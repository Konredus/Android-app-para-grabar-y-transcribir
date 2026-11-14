package cl.vozlocal.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Pruebas de la parte de la 0.6.0 (ver docs/diseno/SPEC-0.6.md). Sin llamadas reales a APIs. */
final class UiChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    interface Body{void run()throws Exception;}

    static void run(Context c,Recording r)throws Exception{
        durations();
        motion();
        waiting(r);
        onMain(()->drawables(c));
        // Las vistas y sus animaciones necesitan el hilo principal (la instrumentación corre en otro hilo).
        onMain(()->views(c,false));
        onMain(()->views(c,true));
    }

    /** Duraciones en formato humano (PROPUESTA #2). */
    static void durations(){
        String[][] cases={{"0","0 s"},{"5000","5 s"},{"59000","59 s"},{"240000","4 min"},{"3120000","52 min"},{"3840000","1 h 04 min"},{"3600000","1 h"},{"9000000","2 h 30 min"},{"-2000","0 s"},{"400","0 s"}};
        for(String[] k:cases){String got=Ui.humanDuration(Long.parseLong(k[0]));check(k[1].equals(got),"humanDuration("+k[0]+") = «"+got+"», se esperaba «"+k[1]+"»");}
    }

    /**
     * Una grabación pedida que todavía no se procesa (0.8.0, tercera ronda, revisión): el botón, la nota del detalle y la
     * Biblioteca dicen por qué espera en vez de «Transcribiendo», y el detalle muestra el Wi-Fi sin el paréntesis que manda
     * al detalle. Solo funciones puras y un estado de mentira: no arranca ningún trabajo.
     */
    static void waiting(Recording r)throws Exception{
        String wifi="En cola · "+Pipeline.wifiWait();
        check("En cola · esperando Wi-Fi".equals(RecordingActivity.inDetail(wifi))&&"En cola · esperando Wi-Fi".equals(RecordingActivity.human(wifi)),"El detalle no debe repetir «desde el detalle»: «"+RecordingActivity.human(wifi)+"»");
        check("En pausa: esperando Wi-Fi · se retoma sola al cumplirse".equals(RecordingActivity.inDetail("En pausa: "+Pipeline.wifiWait()+" · se retoma sola al cumplirse")),"El paréntesis del Wi-Fi debe quitarse también a mitad del texto");
        check("En espera de Wi-Fi · ahora hay datos móviles".equals(RecordingActivity.inDetail("En espera de Wi-Fi · ahora hay datos móviles: puedes usarlos para esta grabación desde su detalle")),"La bitácora del motor tampoco debe mandar al detalle desde el detalle");
        String other="Intento 1 de 5 falló: el servidor no respondió (HTTP 503) · se reintentará";
        check(other.equals(RecordingActivity.inDetail(other))&&"".equals(RecordingActivity.inDetail(null)),"Solo se quita lo del Wi-Fi");
        // El botón de abajo (Next): «Transcribiendo…» solo si es ESTA la que se procesa.
        check("Transcribiendo…".equals(Next.working(false,true,null))&&"Volviendo a transcribir…".equals(Next.working(true,true,null)),"La que se procesa dice «Transcribiendo…»");
        check("Esperando Wi-Fi…".equals(Next.working(false,false,Pipeline.wifiWait()))&&"Esperando Wi-Fi…".equals(Next.working(true,false,Pipeline.wifiWait())),"Esperando Wi-Fi no debe decir «Transcribiendo…»");
        check("Esperando el cargador…".equals(Next.working(false,false,"esperando que conectes el cargador"))&&"Esperando conexión…".equals(Next.working(false,false,"esperando conexión a internet")),"El botón debe decir qué espera");
        check("En cola…".equals(Next.working(false,false,null))&&!Next.working(false,false,"batería baja: Android espera a que cargues").contains("Transcribiendo"),"En cola no debe decir «Transcribiendo…»");
        // La nota del detalle: «tarda más de lo normal» y «Puedes cerrar la app» solo para la que se procesa.
        check(RecordingActivity.waitingNote(true,true,false,true,null).contains("tarda más")&&RecordingActivity.waitingNote(true,true,false,false,null).startsWith("Puedes cerrar la app"),"La que se procesa: tranquilidad, o que tarda");
        String queued=RecordingActivity.waitingNote(false,true,true,true,null);
        check(!queued.contains("tarda más")&&queued.startsWith("En cola"),"Una en cola detrás de otra no está «en un paso»: «"+queued+"»");
        String waits=RecordingActivity.waitingNote(false,true,true,true,Pipeline.wifiWait());
        check(waits.startsWith("Esperando: Wi-Fi.")&&!waits.contains("(")&&!waits.contains("tarda más"),"Esperando Wi-Fi debe decirlo, aunque otra se esté transcribiendo: «"+waits+"»");
        check(RecordingActivity.waitingNote(false,false,false,false,null).contains("Empezar ahora"),"Sin trabajo andando ni espera, se ofrece «Empezar ahora»");
        // Biblioteca: el motivo de ESTA grabación, sin el paréntesis.
        MainActivity.Item item=new MainActivity.Item(r,new org.json.JSONObject().put("requested",true),false);item.blocker=Pipeline.wifiWait();
        if(!Pipeline.processing(r.id)){
            check("En cola · esperando Wi-Fi".equals(item.progressText()),"La Biblioteca debe decir que espera Wi-Fi: «"+item.progressText()+"»");
            MainActivity.Item parts=new MainActivity.Item(r,new org.json.JSONObject().put("requested",true).put("blocks",4).put("blocksDone",1),false);parts.blocker=Pipeline.wifiWait();
            check("En cola · esperando Wi-Fi · 1 de 4 partes".equals(parts.progressText()),"Con partes listas igual debe decir que espera: «"+parts.progressText()+"»");
            // Sin motivo y sin procesarse (Android negó el servicio y el trabajo de fondo aún no parte): «En cola», como el botón.
            MainActivity.Item idle=new MainActivity.Item(r,new org.json.JSONObject().put("requested",true),false);
            check("En cola".equals(idle.progressText()),"La Biblioteca no debe decir «Transcribiendo» de una que no se procesa: «"+idle.progressText()+"»");
            // Su anillo tampoco gira (avance desconocido y en cola: quieto); el avance real se muestra igual.
            check(idle.progress()<0&&idle.ring()==0f,"Una en cola no debe tener el anillo girando: "+idle.ring());
            MainActivity.Item idleParts=new MainActivity.Item(r,new org.json.JSONObject().put("requested",true).put("blocks",4).put("blocksDone",1),false);
            check("En cola · 1 de 4 partes".equals(idleParts.progressText()),"Con partes listas tampoco: «"+idleParts.progressText()+"»");
            check(idleParts.ring()==0.25f,"El avance real se muestra aunque espere: "+idleParts.ring());
        }
        // El aviso al pedirla: «En cola» si el trabajo andando es con otra grabación; el Wi-Fi sin paréntesis en el detalle.
        check("Transcribiendo · sigue aunque bloquees el teléfono".equals(RecordingActions.queuedToast("Transcribiendo",null,false,false)),"Sin espera ni otra en curso, empieza");
        check("En cola · empieza cuando termine la transcripción en curso".equals(RecordingActions.queuedToast("Volviendo a transcribir",null,true,true)),"Detrás de otra no debe decir «Transcribiendo»");
        check("En cola · esperando Wi-Fi".equals(RecordingActions.queuedToast("Transcribiendo",Pipeline.wifiWait(),true,true))&&("En cola · "+Pipeline.wifiWait()).equals(RecordingActions.queuedToast("Transcribiendo",Pipeline.wifiWait(),false,false)),"El aviso debe decir que espera Wi-Fi");
        // «Detrás de otra» (revisión r4): también si el trabajo espera para reintentar OTRA (currentId en null entre intentos).
        check(RecordingActions.behind(true,false,"otra",null)&&RecordingActions.behind(true,false,null,"otra"),"Detrás de una que se transcribe o espera su reintento, esta espera su turno");
        check(!RecordingActions.behind(true,true,null,"esta")&&!RecordingActions.behind(true,true,"esta",null),"La que se procesa (o se reintenta) no está detrás de nadie");
        check(!RecordingActions.behind(false,false,"otra","otra")&&!RecordingActions.behind(true,false,null,null),"Sin trabajo andando, o sin ninguna tomada, no hay delante de quién esperar");
        check(!Pipeline.processing(null),"Sin id no hay grabación que procesar");
        // La píldora de Grabar (MainActivity.queueText): «Transcribiendo» solo de la que se procesa; si no, por qué espera.
        check("«Reunión» · En cola · esperando Wi-Fi".equals(MainActivity.queueText(1,null,0,0,"Reunión","En cola · esperando Wi-Fi")),"Una sola en espera de Wi-Fi no debe decir «Transcribiendo»");
        check("«Reunión» · En cola".equals(MainActivity.queueText(1,null,0,0,"Reunión",null)),"Sin motivo, «En cola»");
        check("En cola: 3 grabaciones".equals(MainActivity.queueText(3,null,0,0,"Reunión","En cola")),"Varias sin ninguna en proceso: cuántas esperan");
        check("Transcribiendo «Reunión»".equals(MainActivity.queueText(1,"Reunión",0,0,"Reunión",null)),"La que se procesa, sola");
        check("Transcribiendo «Reunión» · 1 de 3 · 2 en cola".equals(MainActivity.queueText(3,"Reunión",3,1,"Otra",null)),"La que se procesa, con sus partes y las que esperan detrás: «"+MainActivity.queueText(3,"Reunión",3,1,"Otra",null)+"»");
    }

    /** Curvas: empiezan en 0, terminan en 1 y el resorte espacial rebota poco. */
    static void motion(){
        check(AppTheme.EMPHASIZED.getInterpolation(0f)<0.001f&&AppTheme.EMPHASIZED.getInterpolation(1f)>0.999f,"Curva emphasized fuera de rango");
        check(AppTheme.EMPHASIZED_DECELERATE.getInterpolation(0.5f)>0.5f,"Emphasized decelerate debería ir adelantada a la mitad");
        check(AppTheme.EMPHASIZED_ACCELERATE.getInterpolation(0.5f)<0.5f,"Emphasized accelerate debería ir atrasada a la mitad");
        AppTheme.Spring[] springs={AppTheme.SPATIAL_FAST,AppTheme.SPATIAL,AppTheme.SPATIAL_SLOW};
        for(AppTheme.Spring s:springs){
            check(s.getInterpolation(0f)==0f&&s.getInterpolation(1f)==1f,"El resorte no parte en 0 o no termina en 1");
            check(Math.abs(s.getInterpolation(0.999f)-1f)<0.01f,"El resorte no se asienta al final ("+s.duration+" ms)");
            check(s.duration>=150&&s.duration<=1000,"Duración del resorte fuera de rango: "+s.duration);
            float max=0f;for(int i=0;i<=200;i++)max=Math.max(max,s.getInterpolation(i/200f));
            check(max>=1f&&max<1.12f,"Rebote del resorte fuera de rango: "+max);
        }
        float spatial=0f;for(int i=0;i<=200;i++)spatial=Math.max(spatial,AppTheme.SPATIAL.getInterpolation(i/200f));
        check(spatial<1.03f,"El resorte espacial por defecto rebota demasiado: "+spatial);
        check(AppTheme.blend(0xFF000000,0xFFFFFFFF,0.5f)==0xFF808080,"blend no mezcla a la mitad");
        check(AppTheme.withAlpha(0xFF123456,0x1F)==0x1F123456,"withAlpha no cambia solo la opacidad");
    }

    /** Todos los íconos (y el ✓ animado) se pueden cargar y dibujar: un pathData malo recién falla al inflarse. */
    static void drawables(Context c)throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);int count=0;
        for(Field f:R.drawable.class.getFields()){
            String name=f.getName();if(!name.startsWith("ic_")&&!name.startsWith("avd_"))continue;
            Drawable d=c.getDrawable(f.getInt(null));check(d!=null,"No se pudo cargar "+name);
            if(!name.startsWith("ic_launcher"))check(d.getIntrinsicWidth()>0&&d.getIntrinsicHeight()==d.getIntrinsicWidth(),"Ícono no cuadrado: "+name);
            d.setBounds(0,0,96,96);d.draw(canvas);count++;
        }
        check(count>=80,"Faltan íconos: solo "+count);
        String[] required={"ic_star","ic_star_fill","ic_inbox","ic_chevron_down","ic_filter","ic_merge","ic_note","ic_forward_15","ic_replay_15","ic_waveform","ic_tag","avd_check",
            "ic_alert","ic_arrow_back","ic_battery","ic_bolt","ic_calendar","ic_chat","ic_check","ic_check_circle","ic_clock","ic_close","ic_copy","ic_cut","ic_doc","ic_edit","ic_folder","ic_globe","ic_info","ic_key","ic_lifebuoy","ic_mic_fill","ic_more","ic_notification","ic_palette","ic_pause","ic_people","ic_play","ic_radio_off","ic_radio_on","ic_refresh","ic_replay","ic_save","ic_search","ic_server","ic_share","ic_sparkle","ic_swap","ic_tab_library","ic_tab_record","ic_tab_settings","ic_title","ic_trash","ic_upload","ic_wave","ic_wifi"};
        for(String name:required)check(c.getResources().getIdentifier(name,"drawable",c.getPackageName())!=0,"Falta el ícono "+name);
        bitmap.recycle();
    }

    static void views(Context c,boolean dark){
        AppTheme.Palette p=new AppTheme.Palette(c,dark,false);Ui ui=new Ui(c,p);
        boolean[] clicked={false,false};
        Ui.Split split=ui.split("Guardar en 0-Inbox",R.drawable.ic_inbox,v->clicked[0]=true,v->clicked[1]=true);
        check(split.main.getLayoutParams().height==ui.dp(52)&&split.more.getLayoutParams().height==ui.dp(52),"El botón de dos partes debe medir 52 dp de alto");
        check(split.more.getContentDescription()!=null&&split.more.getContentDescription().length()>0,"La flecha del botón de dos partes necesita descripción");
        split.setLabel("Actualizar en 0-Inbox");
        check("Actualizar en 0-Inbox".equals(split.main.label.getText().toString())&&"Actualizar en 0-Inbox".contentEquals(split.main.getContentDescription()),"setLabel no cambió texto y descripción");
        split.setIcon(R.drawable.ic_refresh);split.setIcon(0);split.setIcon(R.drawable.ic_inbox);
        // 0.7.0: lo pendiente va en tinta; lo ya logrado, en menta (primaryContainer).
        check(split.main.fg==p.onInk&&split.main.bg==p.ink,"El botón de dos partes debe partir en tinta");
        split.setTonal(true);check(split.tonal()&&split.main.fg==p.onPrimaryContainer&&split.main.bg==p.primaryContainer,"setTonal(true) no pasó a menta (primaryContainer)");
        split.setTonal(true);split.setTonal(false);check(!split.tonal()&&split.main.fg==p.onInk&&split.main.bg==p.ink,"setTonal(false) no volvió a la tinta");
        split.setBusy(true);check(split.main.busy()&&!split.main.isClickable(),"setBusy(true) debe bloquear el botón");
        split.setBusy(true);split.setBusy(false);check(!split.main.busy()&&split.main.isClickable()&&split.main.glyph.getVisibility()==View.VISIBLE,"setBusy(false) no restauró el botón");
        split.setBusy(true);split.showDone();
        check(!split.main.busy()&&split.main.isClickable()&&split.main.glyph.getVisibility()==View.VISIBLE,"showDone debe mostrar el ✓ y liberar el botón");
        split.setTonal(true);split.setLabel("✓ En 0-Inbox · 16:09");split.setIcon(R.drawable.ic_check);
        split.main.callOnClick();check(clicked[0],"La parte principal no respondió");
        split.setEnabled(false);split.setEnabled(true);
        // Un botón sin ícono también puede mostrar carga y ✓.
        Ui.Btn plain=ui.button("Comprobar conexión",0,Ui.Style.TONAL,null);plain.setBusy(true);plain.showDone();plain.setIcon(0);plain.setText("Conectado");
        check("Conectado".contentEquals(plain.getContentDescription()),"Btn.setText no actualizó la descripción");
        // Vibraciones: seguras en esta versión de Android, aun con la vista fuera de pantalla.
        for(Ui.Haptic h:Ui.Haptic.values()){Ui.haptic(split,h);check(Ui.hapticConstant(h)>=0,"Vibración sin constante: "+h);}
        Ui.haptic(null,Ui.Haptic.CONFIRM);Ui.haptic(split);
        // Interruptor de Material 3: la fila entera lo cambia y avisa el nuevo valor.
        boolean[] value={false};int[] calls={0};
        Ui.SwitchRow row=ui.switchRow(R.drawable.ic_bolt,"Transcribir automáticamente","Al guardar una grabación",false,on->{value[0]=on;calls[0]++;});
        check(row.control.getThumbDrawable().getIntrinsicWidth()==ui.dp(20)&&row.control.getTrackDrawable().getIntrinsicWidth()==ui.dp(52)&&row.control.getTrackDrawable().getIntrinsicHeight()==ui.dp(32),"Medidas del interruptor distintas de 52×32 dp");
        draw(row,ui.dp(360));
        row.performClick();check(value[0]&&row.isChecked()&&calls[0]==1,"La fila no encendió el interruptor");
        draw(row,ui.dp(360));
        row.performClick();check(!value[0]&&!row.isChecked()&&calls[0]==2,"La fila no apagó el interruptor");
        Ui.SwitchRow on=ui.switchRow(0,"Encendido",null,true,x->{});check(on.isChecked(),"El valor inicial no se respetó");draw(on,ui.dp(360));
        // Botones de todos los estilos se dibujan (fondo con forma, capa de estado y esquinas).
        for(Ui.Style s:Ui.Style.values())draw(ui.button("Probar",R.drawable.ic_check,s,null),ui.dp(200));
        draw(split,ui.dp(360));
        ui.fadeIn(split);
        verbapp(c,p,ui);
    }

    /** Base visual de Verbapp (0.7.0, docs/diseno/SPEC-0.7.md): letra, fondo, palabra destacada, logo y barra flotante. */
    static void verbapp(Context c,AppTheme.Palette p,Ui ui){
        for(AppTheme.Weight w:AppTheme.Weight.values())check(AppTheme.outfit(c,w)!=null,"No se pudo cargar Outfit "+w);
        check(AppTheme.font(c,AppTheme.Type.BODY_LARGE)!=AppTheme.outfit(c,AppTheme.Weight.REGULAR),"El texto de lectura debe seguir en Roboto");
        check(AppTheme.font(c,AppTheme.Type.DISPLAY_LARGE)==AppTheme.outfit(c,AppTheme.Weight.MEDIUM),"El cronómetro debe ir en Outfit");
        // Fondo: se dibuja en ambos niveles y el verde de abajo cambia entre suave e intenso.
        Glass.Backdrop b=new Glass.Backdrop(c,p,false);int soft=b.bottomColor();b.setBounds(0,0,540,1200);
        Bitmap bitmap=Bitmap.createBitmap(540,1200,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);b.draw(canvas);
        check(bitmap.getPixel(270,4)==(p.gradTop|0xFF000000)||AppTheme.luminance(bitmap.getPixel(270,4))>(p.dark?0f:0.9f),"El fondo debe partir claro arriba");
        b.setVivid(1f);b.draw(canvas);check(b.bottomColor()!=soft&&b.bottomColor()==p.gradBottom,"El fondo intenso debe terminar en el verde de abajo");
        if(!p.dark)check(AppTheme.luminance(bitmap.getPixel(270,1195))<0.6f,"Abajo el fondo intenso debe ser verde oscuro");
        bitmap.recycle();
        // Palabra destacada: solo la última palabra y solo si es corta.
        android.widget.TextView t=new android.widget.TextView(c);ui.highlightLast(t,"Reunión con la Fran");
        CharSequence s=t.getText();check(s instanceof android.text.Spanned&&((android.text.Spanned)s).getSpans(0,s.length(),Glass.Highlight.class).length==1,"highlightLast no destacó la última palabra");
        check(((android.text.Spanned)s).getSpanStart(((android.text.Spanned)s).getSpans(0,s.length(),Glass.Highlight.class)[0])=="Reunión con la ".length(),"highlightLast destacó otra parte");
        ui.highlightLast(t,"Otorrinolaringólogo");check(!(t.getText() instanceof android.text.Spanned)||((android.text.Spanned)t.getText()).getSpans(0,t.length(),Glass.Highlight.class).length==0,"Una palabra larga no debe destacarse");
        ui.highlightLast(t,"");check(t.length()==0,"highlightLast con texto vacío");
        t.setTextSize(36);ui.highlightLast(t,"Nueva grabación");draw(t,ui.dp(320));
        // Logo, datos de resumen y botón redondo de vidrio.
        android.widget.LinearLayout brand=ui.brand(20);check("Verbapp".contentEquals(brand.getContentDescription()),"El logo debe anunciarse como Verbapp");paint(brand,ui.dp(200));
        View stat=ui.stat(R.drawable.ic_mic_fill,"12","Grabaciones");check(stat.getContentDescription()!=null&&stat.getContentDescription().toString().contains("12"),"El dato de resumen necesita descripción");draw(stat,ui.dp(110));
        android.widget.ImageButton glass=ui.glassButton(R.drawable.ic_arrow_back,"Volver");check(glass.getLayoutParams().width==ui.dp(48),"El botón de vidrio debe medir 48 dp");draw(glass,ui.dp(48));
        // Barra flotante: el destino activo muestra su nombre; los demás, solo el ícono.
        int[] chosen={-1};BottomNav nav=new BottomNav(c,p,0,i->chosen[0]=i);draw(nav,ui.dp(360));
        nav.select(2);nav.badge(1,true);nav.badge(1,false);draw(nav,ui.dp(360));
        check(p.ink!=p.primary&&p.onInk!=p.ink,"La tinta debe distinguirse del verde de marca");
    }

    /** Mide y dibuja sin exigir área táctil (piezas que no se tocan, como el logo). */
    private static void paint(View v,int width){
        v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));v.layout(0,0,v.getMeasuredWidth(),v.getMeasuredHeight());
        check(v.getMeasuredWidth()>0&&v.getMeasuredHeight()>0,"No se pudo medir "+v.getClass().getSimpleName());
        Bitmap b=Bitmap.createBitmap(v.getMeasuredWidth(),v.getMeasuredHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));b.recycle();
    }
    private static void draw(View v,int width){
        v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        v.layout(0,0,v.getMeasuredWidth(),v.getMeasuredHeight());
        check(v.getMeasuredHeight()>=AppTheme.dp(v.getContext(),44),"Área táctil menor a lo esperado: "+v.getClass().getSimpleName());
        Bitmap b=Bitmap.createBitmap(Math.max(1,v.getMeasuredWidth()),Math.max(1,v.getMeasuredHeight()),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));b.recycle();
    }

    static void onMain(Body body)throws Exception{
        Throwable[] error={null};CountDownLatch done=new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(()->{try{body.run();}catch(Throwable t){error[0]=t;}finally{done.countDown();}});
        check(done.await(30,TimeUnit.SECONDS),"Las pruebas de interfaz no terminaron");
        if(error[0] instanceof Error)throw (Error)error[0];if(error[0] instanceof Exception)throw (Exception)error[0];
    }
}
