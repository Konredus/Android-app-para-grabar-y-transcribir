package cl.vozlocal.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Three stable destinations, with both icons and labels for discoverability. */
final class BottomNav extends LinearLayout {
    interface Listener{void select(int destination);}
    private final LinearLayout[] items=new LinearLayout[3];
    private final TextView[] labels=new TextView[3];
    private final ImageView[] icons=new ImageView[3];
    private final AppTheme.Palette palette;
    BottomNav(Context c,AppTheme.Palette palette,int selected,Listener listener){
        super(c);this.palette=palette;setOrientation(HORIZONTAL);setBackgroundColor(palette.surface);setPadding(dp(12),dp(8),dp(12),dp(6));
        String[] titles={"Inicio","Biblioteca","Ajustes"};int[] art={R.drawable.ic_home,R.drawable.ic_library,R.drawable.ic_settings};
        for(int i=0;i<3;i++){
            int index=i;LinearLayout item=new LinearLayout(c);items[i]=item;item.setOrientation(VERTICAL);item.setGravity(Gravity.CENTER);item.setMinimumHeight(dp(64));item.setPadding(dp(4),dp(6),dp(4),dp(6));
            item.setContentDescription(titles[i]);item.setFocusable(true);item.setClickable(true);
            item.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});
            ImageView icon=new ImageView(c);icons[i]=icon;icon.setImageResource(art[i]);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(icon,new LayoutParams(dp(24),dp(24)));
            TextView label=new TextView(c);labels[i]=label;label.setText(titles[i]);label.setTextSize(12);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(4),0,0);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(label,new LayoutParams(-1,-2));
            LayoutParams lp=new LayoutParams(0,-1,1);lp.setMargins(dp(3),0,dp(3),0);addView(item,lp);
            item.setOnClickListener(v->{Diagnostics.event("navigation",null,"screen",titles[index]);listener.select(index);});
        }
        select(selected);
    }
    void select(int selected){for(int i=0;i<items.length;i++){boolean active=i==selected;items[i].setSelected(active);int color=active?palette.accent:palette.muted;labels[i].setTextColor(color);labels[i].setTypeface(null,active?Typeface.BOLD:Typeface.NORMAL);icons[i].setImageTintList(ColorStateList.valueOf(color));items[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x221859DC),AppTheme.shape(getContext(),active?palette.soft:palette.surface,16),null));}}
    private int dp(int n){return AppTheme.dp(getContext(),n);}
}
