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
        LinearLayout frame=ui.column();GradientDrawable bg=new GradientDrawable();bg.setColor(ui.p.dark?ui.p.surface:ui.p.background);float r=ui.dp(R_SHEET);bg.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});frame.setBackground(bg);
        View grabber=new View(activity);grabber.setBackground(shape(activity,ui.p.faint,99));grabber.setAlpha(0.6f);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(ui.dp(36),ui.dp(5));gp.gravity=Gravity.CENTER_HORIZONTAL;gp.topMargin=ui.dp(S2);frame.addView(grabber,gp);
        ScrollView scroll=new ScrollView(activity);scroll.setVerticalScrollBarEnabled(false);body=ui.column();body.setPadding(ui.dp(S5),ui.dp(S4),ui.dp(S5),ui.dp(S5));scroll.addView(body);frame.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        ((LinearLayout.LayoutParams)scroll.getLayoutParams()).height=-2;((LinearLayout.LayoutParams)scroll.getLayoutParams()).weight=0;
        if(title!=null&&!title.isEmpty()){TextView t=ui.heading(title,Type.TITLE_SMALL);t.setPadding(ui.dp(S1),0,ui.dp(S1),ui.dp(message==null||message.isEmpty()?S3:S2));body.addView(t);}
        if(message!=null&&!message.isEmpty()){TextView m=ui.text(message,Type.SUBHEAD,ui.p.muted);m.setPadding(ui.dp(S1),0,ui.dp(S1),ui.dp(S4));body.addView(m);}
        dialog.setContentView(frame);
        Window w=dialog.getWindow();
        if(w!=null){w.setBackgroundDrawable(new ColorDrawable(0));w.setLayout(-1,-2);w.setGravity(Gravity.BOTTOM);w.setWindowAnimations(android.R.style.Animation_InputMethod);w.setDimAmount(0.35f);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);w.setNavigationBarColor(ui.p.dark?ui.p.surface:ui.p.background);}
    }
    Sheet add(View v){body.addView(v,Ui.fill());return this;}

    /** Opción de menú con ícono. Las destructivas van en rojo y al final. */
    Sheet action(int icon,String label,boolean destructive,Runnable run){
        if(list==null){list=ui.group();body.addView(list,Ui.fill());}
        int color=destructive?ui.p.danger:ui.p.ink;
        LinearLayout row=ui.row();row.setMinimumHeight(ui.dp(54));row.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S3));
        row.addView(ui.icon(icon,destructive?ui.p.danger:ui.p.accent,22));row.addView(ui.space(S3));TextView t=ui.text(label,Type.BODY,color);row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setFocusable(true);row.setContentDescription(label);row.setAccessibilityDelegate(Ui.buttonRole());
        row.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen",activity.getClass().getSimpleName(),"action",label);dialog.dismiss();run.run();});
        if(list.getChildCount()>0)list.addView(ui.separator(S4+22+S3));list.addView(row,Ui.fill());return this;
    }
    /** Opción de selección única con marca de verificación. */
    Sheet choice(String label,String detail,boolean selected,Runnable run){
        if(list==null){list=ui.group();body.addView(list,Ui.fill());}
        LinearLayout row=ui.row();row.setMinimumHeight(ui.dp(54));row.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S3));
        LinearLayout texts=ui.column();texts.addView(ui.text(label,Type.BODY,ui.p.ink));if(detail!=null&&!detail.isEmpty()){TextView d=ui.text(detail,Type.FOOTNOTE,ui.p.muted);d.setPadding(0,ui.dp(2),0,0);texts.addView(d);}
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));ImageView check=ui.icon(R.drawable.ic_check,ui.p.accent,22);check.setVisibility(selected?View.VISIBLE:View.INVISIBLE);row.addView(check);
        row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setContentDescription(label+(selected?", seleccionado":""));row.setAccessibilityDelegate(Ui.buttonRole());
        row.setOnClickListener(v->{dialog.dismiss();if(!selected)run.run();});
        if(list.getChildCount()>0)list.addView(ui.separator(S4));list.addView(row,Ui.fill());return this;
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
