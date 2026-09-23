package cl.vozlocal.app;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

abstract class Screen extends Activity {
    int BG,INK,MUTED,GREEN,SURFACE,SOFT,BORDER,RED;
    AppTheme.Palette palette;
    LinearLayout page;
    ScrollView scroll;
    private int savedScroll;
    @Override public void onCreate(Bundle state){
        palette=AppTheme.apply(this);BG=palette.background;INK=palette.ink;MUTED=palette.muted;GREEN=palette.accent;SURFACE=palette.surface;SOFT=palette.soft;BORDER=palette.border;RED=palette.danger;
        super.onCreate(state);if(state!=null)savedScroll=state.getInt("screen_scroll",0);
    }
    @Override protected void onResume(){super.onResume();if(palette.dark!=AppTheme.isDark(this))recreate();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("screen_scroll",scroll==null?0:scroll.getScrollY());super.onSaveInstanceState(state);}
    void setup(String title,String subtitle){
        int position=scroll==null?savedScroll:scroll.getScrollY();AppTheme.window(this,palette);
        LinearLayout root=column();root.setBackgroundColor(BG);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        page=column();page.setPadding(dp(24),dp(20),dp(24),dp(28));scroll.addView(page);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        boolean primary=this instanceof SettingsActivity;
        if(primary){BottomNav nav=new BottomNav(this,palette,2,destination->{if(destination==2)return;startActivity(new Intent(this,MainActivity.class).putExtra("library",destination==1).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();});root.addView(nav,new LinearLayout.LayoutParams(-1,-2));}
        setContentView(root);root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom);}else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});root.requestApplyInsets();
        if(!primary){Button back=button("Volver",false);back.setOnClickListener(v->onBackPressed());page.addView(back);}
        TextView heading=text(title,32,INK);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.setPadding(0,dp(primary?4:16),0,dp(8));if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);page.addView(heading);
        if(!subtitle.isEmpty()){TextView detail=text(subtitle,16,MUTED);detail.setPadding(0,0,0,dp(8));page.addView(detail);}
        scroll.post(()->scroll.scrollTo(0,position));
    }
    int dp(int n){return AppTheme.dp(this,n);}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);return t;}
    void label(LinearLayout p,String value){TextView t=text(value,16,INK);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(12),0,dp(6));p.addView(t);}
    void help(LinearLayout p,String value){TextView t=text(value,14,MUTED);t.setPadding(0,dp(6),0,dp(10));p.addView(t);}
    LinearLayout card(String title){LinearLayout p=column();p.setPadding(dp(20),dp(12),dp(20),dp(20));p.setBackground(AppTheme.shape(this,SURFACE,22));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(16);page.addView(p,lp);if(!title.isEmpty())label(p,title);return p;}
    Button button(String s,boolean primary){Button b=new Button(this){@Override public boolean performClick(){Diagnostics.event("ui_action",null,"screen",Screen.this.getClass().getSimpleName(),"action",s);return super.performClick();}};b.setText(s);AppTheme.styleButton(b,palette,primary);return b;}
    EditText input(LinearLayout p,String title,String hint){label(p,title);EditText e=new EditText(this);e.setTextSize(16);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setHint(hint);e.setSingleLine(true);e.setContentDescription(title);e.setMinHeight(dp(52));p.addView(e);return e;}
    void shareFile(String filename,String title){android.net.Uri uri=android.net.Uri.parse("content://cl.vozlocal.app.audio/"+filename);Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(android.content.ClipData.newRawUri(title,uri));startActivity(Intent.createChooser(share,title));}
    void message(String title,String value){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle(title).setMessage(value).setPositiveButton("Entendido",null).show();}
}
