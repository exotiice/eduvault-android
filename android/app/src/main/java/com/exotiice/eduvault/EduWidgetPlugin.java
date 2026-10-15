package com.exotiice.eduvault;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Pont site -> widgets. Le site (native.js) appelle EduWidget.setData({ json }) avec un résumé
 * compact ; on le garde dans SharedPreferences et on redessine les widgets.
 */
@CapacitorPlugin(name = "EduWidget")
public class EduWidgetPlugin extends Plugin {

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
