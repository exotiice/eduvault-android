package com.exotiice.eduvault;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

/** Widget « Échéances » : prochains examens/devoirs + fiches à réviser. */
public class TasksWidget extends AppWidgetProvider {

    private static final int[] TITLE = {R.id.t1t, R.id.t2t, R.id.t3t, R.id.t4t};
    private static final int[] SUB = {R.id.t1s, R.id.t2s, R.id.t3s, R.id.t4s};
    private static final int[] ROW = {R.id.t1, R.id.t2, R.id.t3, R.id.t4};
    private static final int RED = 0xFFDC2626;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, android.os.Bundle opts) {
        update(context, manager, id);
    }

    private static String when(long diffMs) {
        if (diffMs < 0) return "En retard";
        long min = diffMs / 60000L;
        if (min < 60) return "Dans " + Math.max(1, min) + " min";
        long h = min / 60;
        if (h < 24) return "Dans " + h + " h";
        return "Dans " + (h / 24) + " j";
    }

    static void update(Context c, AppWidgetManager m, int widgetId) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_tasks);
        for (int i = 0; i < ROW.length; i++) v.setViewVisibility(ROW[i], View.GONE);
        v.setViewVisibility(R.id.empty, View.GONE);
        v.setViewVisibility(R.id.ft, View.GONE);

        String empty = null;
        try {
            JSONObject d = WidgetData.load(c);
            if (d == null) {
                empty = "Ouvre EduVault une fois pour afficher tes échéances.";
            } else {
                JSONArray tasks = d.optJSONArray("tasks");
                long now = System.currentTimeMillis();
                int shown = 0;
                if (tasks != null) {
                    for (int i = 0; i < tasks.length() && shown < ROW.length; i++) {
                        JSONObject t = tasks.getJSONObject(i);
                        long due = t.optLong("t", 0);
                        if (due < now - 24L * 3600000L) continue;
                        boolean exam = t.optInt("x", 0) == 1;
                        v.setViewVisibility(ROW[shown], View.VISIBLE);
                        v.setTextViewText(TITLE[shown], (exam ? "🚨 " : "📝 ") + t.optString("n"));
                        String mod = t.optString("m", "");
                        v.setTextViewText(SUB[shown], when(due - now) + (mod.length() > 0 ? " · " + mod : ""));
                        if (exam) v.setTextColor(TITLE[shown], RED);
                        else v.setTextColor(TITLE[shown], c.getResources().getColor(R.color.ev_text));
                        shown++;
                    }
                }
                if (shown == 0) empty = "Rien à rendre pour le moment ✅";
                int cards = d.optInt("cards", 0);
                if (cards > 0) {
                    v.setViewVisibility(R.id.ft, View.VISIBLE);
                    v.setTextViewText(R.id.ft, "🧠 " + cards + (cards > 1 ? " fiches à réviser" : " fiche à réviser"));
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
