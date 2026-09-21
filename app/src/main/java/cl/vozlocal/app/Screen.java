package cl.vozlocal.app;

import android.app.*;
import android.os.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.view.*;
import android.widget.*;

abstract class Screen extends Activity {
    static final int BG=0xFFF5F3ED,INK=0xFF1C2D27,MUTED=0xFF586860,GREEN=0xFF246654;
    LinearLayout page;
    void setup(String title,String subtitle){
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT>=27?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        page=column();page.setPadding(dp(24),dp(16),dp(24),dp(32));scroll.addView(page);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom);}else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});scroll.requestApplyInsets();
        Button back=button("Volver",false);back.setOnClickListener(v->finish());page.addView(back);
        TextView heading=text(title,30,INK);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(0,dp(16),0,dp(8));page.addView(heading);
        TextView detail=text(subtitle,16,MUTED);detail.setPadding(0,0,0,dp(16));page.addView(detail);
    }
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);return t;}
    void label(LinearLayout p,String value){TextView t=text(value,16,INK);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(12),0,dp(6));p.addView(t);}
    void help(LinearLayout p,String value){TextView t=text(value,14,MUTED);t.setPadding(0,dp(6),0,dp(10));p.addView(t);}
    LinearLayout card(String title){LinearLayout p=column();p.setPadding(dp(18),dp(12),dp(18),dp(18));GradientDrawable d=new GradientDrawable();d.setColor(Color.WHITE);d.setCornerRadius(dp(20));p.setBackground(d);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(16);page.addView(p,lp);label(p,title);return p;}
    Button button(String s,boolean primary){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(16);b.setMinHeight(dp(52));b.setTextColor(primary?Color.WHITE:GREEN);b.setBackgroundTintList(ColorStateList.valueOf(primary?GREEN:0xFFEDF4EF));return b;}
    EditText input(LinearLayout p,String title,String hint){label(p,title);EditText e=new EditText(this);e.setTextSize(16);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setHint(hint);e.setSingleLine(true);e.setContentDescription(title);e.setMinHeight(dp(52));p.addView(e);return e;}
    void message(String title,String value){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle(title).setMessage(value).setPositiveButton("Entendido",null).show();}
}
