package com.exotiice.eduvault;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/** Accès commun aux données des widgets (JSON envoyé par le site). */
final class WidgetData {
    static final String PREFS = "eduvault_widget";
    static final String KEY = "data";

    private WidgetData() {}

    static void save(Context c, String json) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).apply();
    }

    static JSONObject load(Context c) {
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- séances retirées localement (bouton « Pas cours » de la carte), en attendant que l'appli les enregistre ----

    static boolean isLocalSkip(Context c, String id, String day) {
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences("eduvault_modes", Context.MODE_PRIVATE).getString("local_skips", "[]"));
            String k = id + "|" + day;
            for (int i = 0; i < a.length(); i++) if (k.equals(a.optString(i))) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    static void addLocalSkip(Context c, String id, String day) {
        try {
            android.content.SharedPreferences p = c.getSharedPreferences("eduvault_modes", Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(p.getString("local_skips", "[]"));
            JSONArray out = new JSONArray();
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
            String k = id + "|" + day;
            for (int i = 0; i < a.length(); i++) {
                String v = a.optString(i);
                int bar = v.lastIndexOf('|');
                if (bar > 0 && v.substring(bar + 1).compareTo(today) >= 0 && !v.equals(k)) out.put(v);
            }
            out.put(k);
            p.edit().putString("local_skips", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---- « Sorti » : heure réelle de sortie d'un cours (bouton de la carte), en attendant l'enregistrement par l'appli ----

    static long earlyAt(Context c, String id, String day) {
        try {
            JSONObject o = new JSONObject(c.getSharedPreferences("eduvault_modes", Context.MODE_PRIVATE).getString("local_early", "{}"));
            return o.optLong(id + "|" + day, 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    static void setEarly(Context c, String id, String day, long ts) {
        try {
            android.content.SharedPreferences p = c.getSharedPreferences("eduvault_modes", Context.MODE_PRIVATE);
            JSONObject o = new JSONObject(p.getString("local_early", "{}"));
            JSONObject out = new JSONObject();
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                int bar = k.lastIndexOf('|');
                if (bar > 0 && k.substring(bar + 1).compareTo(today) >= 0) out.put(k, o.optLong(k));
            }
            out.put(id + "|" + day, ts);
            p.edit().putString("local_early", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** « 45 min » ou « 1 h 05 ». */
    static String fmtMin(int m) {
        m = Math.max(0, m);
        if (m < 60) return m + " min";
        return (m / 60) + " h" + (m % 60 > 0 ? String.format(Locale.US, " %02d", m % 60) : "");
    }

    static void refreshAll(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            for (int id : m.getAppWidgetIds(new ComponentName(c, TodayWidget.class))) {
                TodayWidget.update(c, m, id);
            }
            for (int id : m.getAppWidgetIds(new ComponentName(c, AgendaWidget.class))) {
                AgendaWidget.update(c, m, id);
            }
            for (int id : m.getAppWidgetIds(new ComponentName(c, MonthWidget.class))) {
                MonthWidget.update(c, m, id);
            }
            for (int id : m.getAppWidgetIds(new ComponentName(c, TasksWidget.class))) {
                TasksWidget.update(c, m, id);
            }
        } catch (Exception ignored) {
            // un widget ne doit jamais faire planter l'appli
        }
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static String isoDay(Calendar cal) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    static int minutes(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static String capitalize(String s) {
        if (s == null || s.length() == 0) return s;
        return s.substring(0, 1).toUpperCase(Locale.FRENCH) + s.substring(1);
    }
}
