package cl.vozlocal.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Hoja inferior (bottom sheet): reemplaza los AlertDialog del sistema para menús, confirmaciones y formularios cortos.
 * Queda al alcance del pulgar y mantiene el contexto visible detrás. Ver docs/diseno/CRITERIOS.md → "Hojas".
 */
final class Sheet {
    final Dialog dialog;final LinearLayout body;final Ui ui;private final Activity activity;
    private LinearLayout list,buttons;

    Sheet(Activity activity,Ui ui,String title,String message){
        this.activity=activity;this.ui=ui;dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout frame=ui.column();GradientDrawable bg=new GradientDrawable();bg.setColor(ui.p.surfaceContainerLow);float r=ui.dp(R_SHEET);bg.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});frame.setBackground(bg);
        View grabber=new View(activity);grabber.setBackground(shape(activity,ui.p.onSurfaceVariant,99));grabber.setAlpha(0.4f);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(ui.dp(32),ui.dp(4));gp.gravity=Gravity.CENTER_HORIZONTAL;gp.topMargin=ui.dp(S4);frame.addView(grabber,gp);
        ScrollView scroll=new ScrollView(activity);scroll.setVerticalScrollBarEnabled(false);body=ui.column();body.setClipToPadding(false);body.setPadding(ui.dp(S6),ui.dp(S5),ui.dp(S6),ui.dp(S5));scroll.addView(body);frame.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        ((LinearLayout.LayoutParams)scroll.getLayoutParams()).height=-2;((LinearLayout.LayoutParams)scroll.getLayoutParams()).weight=0;
        if(title!=null&&!title.isEmpty()){TextView t=ui.heading(title,Type.TITLE_LARGE);t.setPadding(0,0,0,ui.dp(message==null||message.isEmpty()?S3:S2));body.addView(t);}
        if(message!=null&&!message.isEmpty()){TextView m=ui.text(message,Type.BODY_MEDIUM,ui.p.onSurfaceVariant);m.setPadding(0,0,0,ui.dp(S4));body.addView(m);}
        dialog.setContentView(frame);
        Window w=dialog.getWindow();
        if(w!=null){w.setBackgroundDrawable(new ColorDrawable(0));w.setLayout(-1,-2);w.setGravity(Gravity.BOTTOM);w.setWindowAnimations(android.R.style.Animation_InputMethod);w.setDimAmount(0.35f);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);w.setNavigationBarColor(ui.p.surfaceContainerLow);}
    }
    Sheet add(View v){body.addView(v,Ui.fill());return this;}

    /** Opción de menú (lista de Material, sin tarjeta). Íconos neutros; las destructivas van en color error y al final. */
    Sheet action(int icon,String label,boolean destructive,Runnable run){
        if(list==null){list=ui.column();LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(-ui.dp(S6),0,-ui.dp(S6),0);body.addView(list,lp);}
        int color=destructive?ui.p.error:ui.p.onSurface;
        LinearLayout row=ui.row();row.setMinimumHeight(ui.dp(56));row.setPadding(ui.dp(S6),ui.dp(S3),ui.dp(S6),ui.dp(S3));
        row.addView(ui.icon(icon,destructive?ui.p.error:ui.p.onSurfaceVariant,24));row.addView(ui.space(S4));TextView t=ui.text(label,Type.BODY_LARGE,color);row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setFocusable(true);row.setContentDescription(label);row.setAccessibilityDelegate(Ui.buttonRole());
        row.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen",activity.getClass().getSimpleName(),"action",label);dialog.dismiss();run.run();});
        list.addView(row,Ui.fill());return this;
    }
    /** Opción de selección única con botón de radio (patrón de Material para elegir uno entre varios). */
    Sheet choice(String label,String detail,boolean selected,Runnable run){
        if(list==null){list=ui.column();LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(-ui.dp(S6),0,-ui.dp(S6),0);body.addView(list,lp);}
        LinearLayout row=ui.row();row.setMinimumHeight(ui.dp(56));row.setPadding(ui.dp(S6),ui.dp(S3),ui.dp(S6),ui.dp(S3));
        row.addView(ui.icon(selected?R.drawable.ic_radio_on:R.drawable.ic_radio_off,selected?ui.p.primary:ui.p.onSurfaceVariant,24));row.addView(ui.space(S4));
        LinearLayout texts=ui.column();texts.addView(ui.text(label,Type.BODY_LARGE,ui.p.onSurface));if(detail!=null&&!detail.isEmpty()){TextView d=ui.text(detail,Type.BODY_MEDIUM,ui.p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);texts.addView(d);}
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setContentDescription(label+(selected?", seleccionado":""));row.setAccessibilityDelegate(Ui.buttonRole());
        row.setOnClickListener(v->{dialog.dismiss();if(!selected)run.run();});
        list.addView(row,Ui.fill());return this;
    }
    private LinearLayout buttons(){if(buttons==null){buttons=ui.column();buttons.setPadding(0,ui.dp(S4),0,0);body.addView(buttons,Ui.fill());}return buttons;}
    /** Botón principal. Si run devuelve false la hoja queda abierta (p. ej. validación). */
    interface Check{boolean run();}
    Sheet primary(String label,Ui.Style style,Check run){Ui.Btn b=ui.button(label,0,style,v->{if(run.run())dialog.dismiss();});buttons().addView(b,Ui.fill());return this;}
    Sheet primary(String label,Runnable run){return primary(label,Ui.Style.PRIMARY,()->{run.run();return true;});}
    Sheet secondary(String label,Runnable run){Ui.Btn b=ui.button(label,0,Ui.Style.PLAIN,v->{dialog.dismiss();if(run!=null)run.run();});LinearLayout.LayoutParams lp=Ui.fill();lp.topMargin=ui.dp(S1);buttons().addView(b,lp);return this;}
    Sheet onDismiss(Runnable run){dialog.setOnDismissListener(d->run.run());return this;}
    Sheet secure(){if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);return this;}
    Sheet show(){if(!activity.isFinishing()&&!activity.isDestroyed())dialog.show();return this;}
    void dismiss(){dialog.dismiss();}
}
