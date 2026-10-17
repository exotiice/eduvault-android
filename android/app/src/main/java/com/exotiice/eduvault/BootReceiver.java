package com.exotiice.eduvault;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reprogramme le « Mode cours » après un redémarrage ou une mise à jour de l'appli. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        ModeScheduler.reschedule(c, true);
    }
}
