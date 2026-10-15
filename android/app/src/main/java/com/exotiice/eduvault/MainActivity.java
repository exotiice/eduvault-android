package com.exotiice.eduvault;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Plugin maison : le site y envoie un résumé (cours, échéances, fiches) pour les widgets.
        registerPlugin(EduWidgetPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Rafraîchit les widgets quand on quitte l'appli (heure, cours en cours...).
        WidgetData.refreshAll(getApplicationContext());
    }
}
