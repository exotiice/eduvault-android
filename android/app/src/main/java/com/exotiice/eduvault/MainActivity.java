package com.exotiice.eduvault;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Plugins maison : widgets, connexion Google native, « Partager vers EduVault ».
        registerPlugin(EduWidgetPlugin.class);
        registerPlugin(EduGooglePlugin.class);
        registerPlugin(EduSharePlugin.class);
        super.onCreate(savedInstanceState);
        EduSharePlugin.handle(getApplicationContext(), getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        EduSharePlugin.handle(getApplicationContext(), intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        EduGooglePlugin.onResult(this, requestCode, resultCode, data);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Rafraîchit les widgets quand on quitte l'appli (heure, cours en cours...).
        WidgetData.refreshAll(getApplicationContext());
    }
}
