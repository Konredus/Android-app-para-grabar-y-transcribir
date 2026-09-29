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
        split.setTonal(true);check(split.tonal()&&split.main.fg==p.onSecondaryContainer&&split.main.bg==p.secondaryContainer,"setTonal(true) no pasó a secondaryContainer");
        split.setTonal(true);split.setTonal(false);check(!split.tonal()&&split.main.fg==p.onPrimary&&split.main.bg==p.primary,"setTonal(false) no volvió al relleno primary");
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
