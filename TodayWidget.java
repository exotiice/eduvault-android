package com.exotiice.eduvault;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Widget « Aujourd'hui » : les cours restants de la journée. */
public class TodayWidget extends AppWidgetProvider {

    private static final int[] TITLE = {R.id.r1t, R.id.r2t, R.id.r3t, R.id.r4t};
    private static final int[] SUB = {R.id.r1s, R.id.r2s, R.id.r3s, R.id.r4s};
    private static final int[] ROW = {R.id.r1, R.id.r2, R.id.r3, R.id.r4};

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, android.os.Bundle opts) {
        update(context, manager, id);
    }

    static void update(Context c, AppWidgetManager m, int widgetId) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_today);
        Calendar now = Calendar.getInstance();
        String day = WidgetData.isoDay(now);
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        String date = new SimpleDateFormat("EEEE d MMMM", Locale.FRENCH).format(now.getTime());
        v.setTextViewText(R.id.hd, WidgetData.capitalize(date));
        for (int i = 0; i < ROW.length; i++) v.setViewVisibility(ROW[i], View.GONE);
        v.setViewVisibility(R.id.empty, View.GONE);

        String empty = null;
        try {
            JSONObject d = WidgetData.load(c);
            if (d == null) {
                empty = "Ouvre EduVault une fois pour afficher ton emploi du temps.";
            } else {
                JSONObject days = d.optJSONObject("days");
                JSONArray list = days == null ? null : days.optJSONArray(day);
                // compte à rebours du prochain examen dans l'en-tête
                try {
                    JSONArray tk = d.optJSONArray("tasks");
                    long nowMs = now.getTimeInMillis(), best = Long.MAX_VALUE;
                    String bn = "";
                    for (int q = 0; tk != null && q < tk.length(); q++) {
                        JSONObject t = tk.getJSONObject(q);
                        long due = t.optLong("t", 0);
                        if (t.optInt("x", 0) == 1 && due > nowMs && due < best) {
                            best = due;
                            bn = t.optString("m", "");
                        }
                    }
                    if (best != Long.MAX_VALUE) {
                        long dd = (best - nowMs + 86399999L) / 86400000L;
                        v.setTextViewText(R.id.hd, WidgetData.capitalize(date) + " · 🚨 " + (bn.length() > 0 ? bn + " " : "Exam ") + "J-" + dd);
                    }
                } catch (Exception ignored) {
                }
                boolean hol = d.optJSONObject("hol") != null && d.optJSONObject("hol").has(day);
                if (list == null) {
                    empty = "Données trop anciennes : ouvre EduVault pour actualiser.";
                } else {
                    int shown = 0;
                    int total = list.length();
                    for (int i = 0; i < total && shown < ROW.length; i++) {
                        JSONObject cl = list.getJSONObject(i);
                        if (WidgetData.isLocalSkip(c, cl.optString("i", ""), day)) continue; // « Pas cours » depuis la carte
                        long le = WidgetData.earlyAt(c, cl.optString("i", ""), day);
                        if (le > 0L && le <= now.getTimeInMillis()) continue;                // « Sorti » depuis la carte
                        int end = WidgetData.minutes(cl.optString("e", "00:00"));
                        if (end <= nowMin) continue;
                        int start = WidgetData.minutes(cl.optString("s", "00:00"));
                        boolean live = start <= nowMin;
                        String ty = cl.optString("ty", "");
                        String prof = cl.optString("p", "");
                        String title = (live ? "● " : "") + cl.optString("s") + " – " + cl.optString("e")
                                + (ty.length() > 0 ? " [" + ty + "]" : "")
                                + (live ? " · reste " + WidgetData.fmtMin(end - nowMin)
                                        : shown == 0 ? " · dans " + WidgetData.fmtMin(start - nowMin) : "")
                                + "  " + cl.optString("n");
                        int ab = cl.optInt("ab", 0);
                        String room = cl.optString("r", "");
                        String info = (room.length() > 0 ? "📍 " + room : "")
                                + (prof.length() > 0 ? (room.length() > 0 ? " · " : "") + "👤 " + prof : "")
                                + (ab > 0 ? " · ✋ " + ab + " abs." : "");
                        v.setViewVisibility(ROW[shown], View.VISIBLE);
                        v.setTextViewText(TITLE[shown], title);
                        v.setTextViewText(SUB[shown], info);
                        v.setViewVisibility(SUB[shown], info.length() > 0 ? View.VISIBLE : View.GONE);
                        shown++;
                    }
                    if (shown == 0) {
                        if (hol && total == 0) empty = "🌴 Vacances aujourd'hui";
                        else if (total == 0) empty = "Aucun cours aujourd'hui";
                        else empty = "Plus de cours aujourd'hui ✅";
                    }
                }
            }
        } catch (Exception e) {
            empty = "Ouvre EduVault pour actualiser.";
        }
        if (empty != null) {
            v.setViewVisibility(R.id.empty, View.VISIBLE);
            v.setTextViewText(R.id.empty, empty);
        }
        v.setOnClickPendingIntent(R.id.root, WidgetData.openApp(c));
        m.updateAppWidget(widgetId, v);
    }
}
