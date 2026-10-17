package com.exotiice.eduvault;

import android.content.Intent;

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
        call.resolve();
    }

    @PluginMethod
    public void refresh(PluginCall call) {
        WidgetData.refreshAll(getContext());
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }
}
