package cl.vozlocal.app;

import android.animation.LayoutTransition;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Barra de navegación flotante de Verbapp (0.7.0): una cápsula de vidrio centrada sobre el fondo, con los tres
 * destinos fijos (Grabar, Biblioteca, Ajustes). El destino activo se vuelve una píldora de tinta con su ícono y su
 * nombre; los demás son círculos con solo el ícono (el nombre lo anuncia el lector de pantalla). Al cambiar de destino,
 * la píldora se abre y se cierra con una transición suave. Un punto indica trabajo en curso.
 */
final class BottomNav extends LinearLayout {
    interface Listener{void select(int destination);}
    static final String[] TITLES={"Grabar","Biblioteca","Ajustes"};
    private static final int[] ICONS={R.drawable.ic_tab_record,R.drawable.ic_tab_library,R.drawable.ic_tab_settings};
    /** Destino activo: ícono relleno; inactivos: contorno. */
    private static final int[] ICONS_ON={R.drawable.ic_tab_record_fill,R.drawable.ic_tab_library_fill,R.drawable.ic_tab_settings_fill};
    private final LinearLayout capsule;
    private final LinearLayout[] items=new LinearLayout[3];
    private final TextView[] labels=new TextView[3];
    private final ImageView[] icons=new ImageView[3];
    private final View[] badges=new View[3];
    private final Palette palette;private int selected=-1;

    BottomNav(Context c,Palette palette,int selected,Listener listener){
        super(c);this.palette=palette;setOrientation(HORIZONTAL);setGravity(Gravity.CENTER);setPadding(dp(S4),dp(S2),dp(S4),dp(S3));setClipToPadding(false);setClipChildren(false);
        capsule=new LinearLayout(c);capsule.setOrientation(HORIZONTAL);capsule.setGravity(Gravity.CENTER_VERTICAL);capsule.setPadding(dp(6),dp(6),dp(6),dp(6));
        GradientDrawable glass=shape(c,palette.dark?0xE6182420:0xF2FFFFFF,R_FULL);glass.setStroke(Math.max(1,dp(1)),palette.glassStroke);capsule.setBackground(glass);
        capsule.setElevation(dp(palette.dark?0:6));if(Build.VERSION.SDK_INT>=28&&!palette.dark){capsule.setOutlineSpotShadowColor(palette.shadow);capsule.setOutlineAmbientShadowColor(palette.shadow);}
        LayoutTransition t=new LayoutTransition();t.enableTransitionType(LayoutTransition.CHANGING);t.setDuration(MOTION_BASE);t.setInterpolator(LayoutTransition.CHANGING,EMPHASIZED);capsule.setLayoutTransition(t);
        for(int i=0;i<3;i++){
            int index=i;LinearLayout item=new LinearLayout(c);items[i]=item;item.setOrientation(HORIZONTAL);item.setGravity(Gravity.CENTER);item.setMinimumWidth(dp(52));item.setMinimumHeight(dp(52));
            item.setContentDescription(TITLES[i]);item.setFocusable(true);item.setClickable(true);
            item.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());info.setSelected(index==BottomNav.this.selected);}});
            FrameLayout box=new FrameLayout(c);box.setClipChildren(false);
            ImageView icon=new ImageView(c);icons[i]=icon;icon.setImageResource(ICONS[i]);icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);box.addView(icon,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
            View badge=new View(c);GradientDrawable dot=oval(palette.record);dot.setStroke(dp(1.5f),palette.dark?0xFF182420:0xFFFFFFFF);badge.setBackground(dot);badge.setVisibility(GONE);badges[i]=badge;
            FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(10),dp(10),Gravity.TOP|Gravity.END);bp.setMargins(0,-dp(2),-dp(3),0);box.addView(badge,bp);
            item.addView(box,new LayoutParams(dp(24),dp(24)));
            TextView label=new TextView(c);labels[i]=label;label.setText(TITLES[i]);AppTheme.type(label,Type.LABEL_LARGE);label.setSingleLine(true);label.setPadding(dp(S2),0,0,0);label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);label.setVisibility(GONE);item.addView(label,new LayoutParams(-2,-2));
            LayoutParams lp=new LayoutParams(-2,dp(52));if(i>0)lp.setMarginStart(dp(4));capsule.addView(item,lp);
            item.setOnClickListener(v->{Diagnostics.event("navigation",null,"screen",TITLES[index]);Ui.haptic(v);listener.select(index);});
            Ui.pressable(item);
        }
        addView(capsule,new LayoutParams(-2,-2));
        select(selected);
    }
    void select(int selected){
        if(this.selected==selected)return;this.selected=selected;
        for(int i=0;i<items.length;i++){
            boolean active=i==selected;items[i].setSelected(active);icons[i].setImageResource(active?ICONS_ON[i]:ICONS[i]);
            icons[i].setImageTintList(ColorStateList.valueOf(active?palette.onInk:palette.onSurfaceVariant));
            labels[i].setTextColor(palette.onInk);labels[i].setVisibility(active?VISIBLE:GONE);
            items[i].setPadding(dp(active?S5:S3),0,dp(active?S5:S3),0);
            items[i].setBackground(new RippleDrawable(ColorStateList.valueOf(active?Ui.stateLayer(palette.onInk):palette.ripple),active?shape(getContext(),palette.ink,R_FULL):null,shape(getContext(),0xFF000000,R_FULL)));
        }
    }
    void badge(int index,boolean visible){badges[index].setVisibility(visible?VISIBLE:GONE);items[index].setContentDescription(TITLES[index]+(visible?", trabajo en curso":""));}
    private int dp(float n){return AppTheme.dp(getContext(),n);}
}
