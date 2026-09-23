package cl.vozlocal.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;
import android.widget.Button;

/** Shared native color and interaction tokens. Appearance applies before inflating controls/dialogs. */
final class AppTheme {
    static final class Palette {
        final boolean dark;
        final int background,surface,ink,muted,accent,primary,soft,border,danger;
        Palette(boolean dark){
            this.dark=dark;
            background=dark?0xFF10141D:0xFFF5F7FC;
            surface=dark?0xFF1B2230:0xFFFFFFFF;
            ink=dark?0xFFF3F5FA:0xFF172033;
            muted=dark?0xFFADB8CB:0xFF5D687B;
            accent=dark?0xFF91B6FF:0xFF1859DC;
            primary=0xFF205EDC;
            soft=dark?0xFF273751:0xFFEAF0FC;
            border=dark?0xFF344052:0xFFDEE5EF;
            danger=dark?0xFFFF949D:0xFFC62F42;
        }
    }
    static String appearance(Context c){return new Settings(c).prefs.getString("appearance","system");}
    static boolean isDark(Context c){String mode=appearance(c);return mode.equals("dark") || (mode.equals("system") && (c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    static Palette apply(Activity activity){Palette p=new Palette(isDark(activity));activity.setTheme(p.dark?R.style.AppThemeDark:R.style.AppTheme);return p;}
    static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static void window(Activity activity,Palette p){
        activity.getWindow().setStatusBarColor(p.background);activity.getWindow().setNavigationBarColor(p.surface);
        activity.getWindow().getDecorView().setSystemUiVisibility(p.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|(Build.VERSION.SDK_INT>=27?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0));
    }
    static GradientDrawable shape(Context c,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static void styleButton(Button b,Palette p,boolean primary){
        int[][] states={{-android.R.attr.state_enabled},{}};
        b.setTextColor(new ColorStateList(states,new int[]{p.muted,primary?0xFFFFFFFF:p.accent}));
        GradientDrawable shape=shape(b.getContext(),primary?p.primary:p.soft,16);
        b.setBackground(new InsetDrawable(new RippleDrawable(ColorStateList.valueOf(p.dark?0x3391B6FF:0x221859DC),shape,null),0,dp(b.getContext(),4),0,dp(b.getContext(),4)));
        b.setAllCaps(false);b.setTextSize(16);b.setMinHeight(dp(b.getContext(),56));b.setPadding(dp(b.getContext(),16),dp(b.getContext(),12),dp(b.getContext(),16),dp(b.getContext(),12));b.setElevation(0);b.setStateListAnimator(null);
    }
    private AppTheme(){}
}
