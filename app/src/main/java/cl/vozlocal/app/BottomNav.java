package cl.vozlocal.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Barra de navegación de Material 3: tres destinos fijos con ícono y texto; el destino activo se marca con
 * una "píldora" (active indicator) en secondaryContainer. Un punto indica trabajo en curso.
 */
final class BottomNav extends LinearLayout {
    interface Listener{void select(int destination);}
    static final String[] TITLES={"Grabar","Biblioteca","Ajustes"};
    private static final int[] ICONS={R.drawable.ic_tab_record,R.drawable.ic_tab_library,R.drawable.ic_tab_settings};
    private final LinearLayout[] items=new LinearLayout[3];
    private final TextView[] labels=new TextView[3];
    private final ImageView[] icons=new ImageView[3];
    private final FrameLayout[] pills=new FrameLayout[3];
    private final View[] badges=new View[3];
    private final Palette palette;private int selected;

    BottomNav(Context c,Palette palette,int selected,Listener listener){
        super(c);this.palette=palette;setOrientation(HORIZONTAL);setBackgroundColor(palette.surfaceContainer);setPadding(dp(S2),dp(S3),dp(S2),dp(S4));
        for(int i=0;i<3;i++){
            int index=i;LinearLayout item=new LinearLayout(c);items[i]=item;item.setOrientation(VERTICAL);item.setGravity(Gravity.CENTER_HORIZONTAL);item.setMinimumHeight(dp(56));
            item.setContentDescription(TITLES[i]);item.setFocusable(true);item.setClickable(true);
            item.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());info.setSelected(index==BottomNav.this.selected);}});
            FrameLayout pill=new FrameLayout(c);pills[i]=pill;
            pill.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(palette.ripple),null,shape(c,0xFF000000,R_FULL)));
            ImageView icon=new ImageView(c);icons[i]=icon;icon.setImageResource(ICONS[i]);icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);pill.addView(icon,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
            View badge=new View(c);badge.setBackground(oval(palette.record));badge.setVisibility(GONE);badges[i]=badge;FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(8),dp(8),Gravity.TOP|Gravity.END);bp.setMargins(0,dp(4),dp(18),0);pill.addView(badge,bp);
            item.addView(pill,new LayoutParams(dp(64),dp(32)));
            TextView label=new TextView(c);labels[i]=label;label.setText(TITLES[i]);AppTheme.type(label,Type.LABEL_MEDIUM);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(S1),0,0);label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(label,new LayoutParams(-1,-2));
            addView(item,new LayoutParams(0,-2,1));
            item.setOnClickListener(v->{Diagnostics.event("navigation",null,"screen",TITLES[index]);Ui.haptic(v);listener.select(index);});
        }
        select(selected);
    }
    void select(int selected){
        this.selected=selected;
        for(int i=0;i<items.length;i++){
            boolean active=i==selected;items[i].setSelected(active);
            icons[i].setImageTintList(ColorStateList.valueOf(active?palette.onSecondaryContainer:palette.onSurfaceVariant));
            labels[i].setTextColor(active?palette.onSurface:palette.onSurfaceVariant);labels[i].setTypeface(typeface(active?Weight.BOLD:Weight.MEDIUM));
            pills[i].setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(palette.ripple),active?shape(getContext(),palette.secondaryContainer,R_FULL):null,shape(getContext(),0xFF000000,R_FULL)));
        }
    }
    void badge(int index,boolean visible){badges[index].setVisibility(visible?VISIBLE:GONE);items[index].setContentDescription(TITLES[index]+(visible?", trabajo en curso":""));}
    private int dp(float n){return AppTheme.dp(getContext(),n);}
}
