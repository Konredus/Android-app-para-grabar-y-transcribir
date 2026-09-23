package cl.vozlocal.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Barra de pestañas: tres destinos estables, siempre con ícono + texto (los íconos solos se malinterpretan).
 * El destino activo cambia de color; un punto indica trabajo en curso en Biblioteca.
 */
final class BottomNav extends LinearLayout {
    interface Listener{void select(int destination);}
    static final String[] TITLES={"Grabar","Biblioteca","Ajustes"};
    private static final int[] ICONS={R.drawable.ic_tab_record,R.drawable.ic_tab_library,R.drawable.ic_tab_settings};
    private final LinearLayout[] items=new LinearLayout[3];
    private final TextView[] labels=new TextView[3];
    private final ImageView[] icons=new ImageView[3];
    private final View[] badges=new View[3];
    private final Palette palette;private int selected;

    BottomNav(Context c,Palette palette,int selected,Listener listener){
        super(c);this.palette=palette;setOrientation(VERTICAL);setBackgroundColor(palette.surface);
        View line=new View(c);line.setBackgroundColor(palette.separator);addView(line,new LayoutParams(-1,Math.max(1,dp(0.5f))));
        LinearLayout bar=new LinearLayout(c);bar.setOrientation(HORIZONTAL);bar.setPadding(dp(S2),dp(6),dp(S2),dp(4));addView(bar,new LayoutParams(-1,-2));
        for(int i=0;i<3;i++){
            int index=i;LinearLayout item=new LinearLayout(c);items[i]=item;item.setOrientation(VERTICAL);item.setGravity(Gravity.CENTER);item.setMinimumHeight(dp(52));
            item.setContentDescription(TITLES[i]);item.setFocusable(true);item.setClickable(true);
            item.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());info.setSelected(index==BottomNav.this.selected);}});
            FrameLayout iconFrame=new FrameLayout(c);ImageView icon=new ImageView(c);icons[i]=icon;icon.setImageResource(ICONS[i]);icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);iconFrame.addView(icon,new FrameLayout.LayoutParams(dp(26),dp(26),Gravity.CENTER));
            View badge=new View(c);badge.setBackground(oval(palette.record));badge.setVisibility(GONE);badges[i]=badge;FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(8),dp(8),Gravity.TOP|Gravity.END);iconFrame.addView(badge,bp);
            item.addView(iconFrame,new LayoutParams(dp(34),dp(28)));
            TextView label=new TextView(c);labels[i]=label;label.setText(TITLES[i]);label.setTextSize(11);label.setTypeface(typeface(Weight.MEDIUM));label.setGravity(Gravity.CENTER);label.setPadding(0,dp(2),0,0);label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(label,new LayoutParams(-1,-2));
            item.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(palette.ripple),null,shape(c,0xFF000000,R_CONTROL)));
            bar.addView(item,new LayoutParams(0,-2,1));
            item.setOnClickListener(v->{Diagnostics.event("navigation",null,"screen",TITLES[index]);Ui.haptic(v);listener.select(index);});
        }
        select(selected);
    }
    void select(int selected){this.selected=selected;for(int i=0;i<items.length;i++){boolean active=i==selected;items[i].setSelected(active);int color=active?palette.accent:palette.muted;labels[i].setTextColor(color);icons[i].setImageTintList(ColorStateList.valueOf(color));}}
    void badge(int index,boolean visible){badges[index].setVisibility(visible?VISIBLE:GONE);items[index].setContentDescription(TITLES[index]+(visible?", trabajo en curso":""));}
    private int dp(float n){return AppTheme.dp(getContext(),n);}
}
