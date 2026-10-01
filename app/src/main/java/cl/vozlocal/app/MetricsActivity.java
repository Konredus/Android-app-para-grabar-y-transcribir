package cl.vozlocal.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * «Tus métricas» (0.8.0): tu voz en números, el embudo hacia tu segundo cerebro, costos y gráficos.
 * FASE 0 (contrato): la implementa la parte «metrics». Ver docs/diseno/SPEC-0.8b.md.
 */
public class MetricsActivity extends Screen {
    static void open(Context c){c.startActivity(new Intent(c,MetricsActivity.class));}
    @Override public void onCreate(Bundle state){super.onCreate(state);shell("Ajustes",-1);largeTitle(page,"Tus métricas",null);}
}
