package com.exotiice.eduvault;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

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
        pruneAfterSave(c, json);
    }

    static JSONObject load(Context c) {
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- actions faites depuis la carte (Pas cours / Sorti / Absent), prises en compte tout de suite en attendant que l'appli les enregistre ----
    // Chaque marque = { "id|jour": horodatage }. Elle disparaît quand l'appli a pris le relais (nouveau résumé plus récent, file vide) ou le lendemain.

    private static final String PM = "eduvault_modes";
    static final String SKIPS = "local_skips", EARLY = "local_early", ABS = "local_abs", CHK = "once_chk";

    private static JSONObject marks(Context c, String key) {
        try {
            return new JSONObject(c.getSharedPreferences(PM, Context.MODE_PRIVATE).getString(key, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String todayIso() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
    }

    /** Retire les marques d'un jour passé, et (si minTs > 0) celles posées avant minTs. */
    private static JSONObject cleaned(JSONObject o, long minTs) {
        JSONObject out = new JSONObject();
        String today = todayIso();
        try {
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                int bar = k.lastIndexOf('|');
                long ts = o.optLong(k, 0L);
                if (bar > 0 && k.substring(bar + 1).compareTo(today) >= 0 && (minTs <= 0L || ts >= minTs)) out.put(k, ts);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static long markTs(Context c, String key, String id, String day) {
        return marks(c, key).optLong(id + "|" + day, 0L);
    }

    /** Pose la marque ; renvoie false si elle existait déjà. */
    static boolean putMark(Context c, String key, String id, String day, long ts) {
        try {
            JSONObject o = cleaned(marks(c, key), 0L);
            String k = id + "|" + day;
            boolean fresh = !o.has(k);
            o.put(k, ts);
            c.getSharedPreferences(PM, Context.MODE_PRIVATE).edit().putString(key, o.toString()).apply();
            return fresh;
        } catch (Exception e) {
            return true;
        }
    }

    static boolean isLocalSkip(Context c, String id, String day) {
        return id != null && markTs(c, SKIPS, id, day) > 0L;
    }

    static void addLocalSkip(Context c, String id, String day) {
        putMark(c, SKIPS, id, day, System.currentTimeMillis());
    }

    static long earlyAt(Context c, String id, String day) {
        return id == null ? 0L : markTs(c, EARLY, id, day);
    }

    static void setEarly(Context c, String id, String day, long ts) {
        putMark(c, EARLY, id, day, ts);
    }

    /** Une seule fois par cours et par jour (absence, bilan « compris »). */
    static boolean once(Context c, String key, String id, String day) {
        return putMark(c, key, id, day, System.currentTimeMillis());
    }

    /** Appelé à chaque nouveau résumé : si l'appli a déjà enregistré les actions en attente, le résumé est la référence → on oublie les marques plus anciennes. */
    private static void pruneAfterSave(Context c, String json) {
        try {
            long u = new JSONObject(json).optLong("u", 0L);
            android.content.SharedPreferences p = c.getSharedPreferences(PM, Context.MODE_PRIVATE);
            String pending = p.getString("rates", "[]");
            android.content.SharedPreferences.Editor e = p.edit();
            for (String key : new String[]{SKIPS, EARLY, ABS}) {
                JSONObject o = marks(c, key);
                if (o.length() == 0) continue;
                e.putString(key, cleaned(o, pending.length() <= 2 ? u : 0L).toString());
            }
            e.apply();
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
