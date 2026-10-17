package com.exotiice.eduvault;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

/**
 * Pont site <-> widgets.
 * - setData({json}) : le site envoie un résumé compact, gardé dans SharedPreferences, widgets redessinés.
 * - getAction() : action demandée par un appui sur le widget Agenda ({k: s|t|add, i: id, d: jour}) ; vidée après lecture.
 * - événement « widgetAction » quand l'appli est déjà ouverte.
 */
@CapacitorPlugin(name = "EduWidget")
public class EduWidgetPlugin extends Plugin {

    private static String pendingAction = null;
    private static EduWidgetPlugin inst;

    @Override
    public void load() {
        inst = this;
    }

    /** Appelé par MainActivity (onCreate / onNewIntent) : lit les extras posés par le widget. */
    static void handle(Intent intent) {
        if (intent == null) return;
        String kind = intent.getStringExtra("ev_kind");
        if (kind == null || kind.length() == 0) return;
        try {
            JSONObject o = new JSONObject();
            o.put("k", kind);
            o.put("i", intent.getStringExtra("ev_id") == null ? "" : intent.getStringExtra("ev_id"));
            o.put("d", intent.getStringExtra("ev_day") == null ? "" : intent.getStringExtra("ev_day"));
            pendingAction = o.toString();
        } catch (Exception ignored) {
        }
        intent.removeExtra("ev_kind");
        EduWidgetPlugin p = inst;
        if (p != null) p.notifyListeners("widgetAction", new JSObject(), true);
    }

    @PluginMethod
    public void getAction(PluginCall call) {
        JSObject ret = new JSObject();
        String a = pendingAction;
        pendingAction = null;
        ret.put("action", a);
        call.resolve(ret);
    }

    @PluginMethod
    public void setData(PluginCall call) {
        String json = call.getString("json", "");
        if (json == null || json.length() == 0 || json.length() > 200000) {
            call.reject("json manquant ou trop gros");
            return;
        }
        WidgetData.save(getContext(), json);
        WidgetData.refreshAll(getContext());
        ModeScheduler.reschedule(getContext());
        call.resolve();
    }

    /** Réglages du « Mode cours » : {silent, card, checkin, alarm, lead, hour}. */
    @PluginMethod
    public void setPrefs(PluginCall call) {
        try {
            JSONObject o = new JSONObject(call.getString("json", "{}"));
            SharedPreferences.Editor e = ModeReceiver.modes(getContext()).edit();
            String[] bools = {"silent", "card", "checkin", "alarm"};
            for (String k : bools) if (o.has(k)) e.putBoolean(k, o.getBoolean(k));
            if (o.has("lead")) e.putInt("lead", o.getInt("lead"));
            if (o.has("hour")) e.putInt("hour", o.getInt("hour"));
            e.apply();
            if (o.has("silent") && !o.getBoolean("silent")) ModeReceiver.restoreSilent(getContext());
            ModeScheduler.reschedule(getContext());
            call.resolve();
        } catch (Exception ex) {
            call.reject(String.valueOf(ex.getMessage()));
        }
    }

    /** {dnd} : l'appli a-t-elle l'accès « Ne pas déranger » ? */
    @PluginMethod
    public void getModeInfo(PluginCall call) {
        JSObject r = new JSObject();
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        r.put("dnd", Build.VERSION.SDK_INT >= 23 && nm != null && nm.isNotificationPolicyAccessGranted());
        call.resolve(r);
    }

    /** Évaluations de compréhension faites depuis les notifications (lues puis vidées). */
    @PluginMethod
    public void getRates(PluginCall call) {
        JSObject r = new JSObject();
        SharedPreferences p = ModeReceiver.modes(getContext());
        try {
            r.put("rates", new JSArray(p.getString("rates", "[]")));
        } catch (Exception ex) {
            r.put("rates", new JSArray());
        }
        p.edit().remove("rates").apply();
        call.resolve(r);
    }

    @PluginMethod
    public void openDnd(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception ex) {
            call.reject(String.valueOf(ex.getMessage()));
        }
    }

    @PluginMethod
    public void refresh(PluginCall call) {
        WidgetData.refreshAll(getContext());
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }
}
