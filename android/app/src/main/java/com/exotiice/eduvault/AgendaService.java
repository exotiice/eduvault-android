package com.exotiice.eduvault;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/** Fournit les lignes (défilantes) du widget Agenda. */
public class AgendaService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new AgendaFactory(getApplicationContext());
    }
}

class AgendaFactory implements RemoteViewsService.RemoteViewsFactory {
    private final Context ctx;
    private List<AgendaWidget.Entry> items = new ArrayList<>();

    AgendaFactory(Context c) {
        ctx = c;
    }

    @Override
    public void onCreate() {
    }

    @Override
    public void onDataSetChanged() {
        try {
            List<AgendaWidget.Entry> l = AgendaWidget.build(ctx);
            items = l == null ? new ArrayList<AgendaWidget.Entry>() : l;
        } catch (Exception e) {
            items = new ArrayList<>();
        }
    }

    @Override
    public void onDestroy() {
        items.clear();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public RemoteViews getViewAt(int position) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_agenda_item);
        if (position < 0 || position >= items.size()) return v;
        AgendaWidget.Entry e = items.get(position);
        v.setViewVisibility(R.id.dt, View.GONE);
        v.setViewVisibility(R.id.dx, View.GONE);
        if (e.first) {
            if (e.today) {
                v.setViewVisibility(R.id.dt, View.VISIBLE);
                v.setTextViewText(R.id.dt, e.dow.substring(0, 1) + "\n" + e.dom);
            } else {
                v.setViewVisibility(R.id.dx, View.VISIBLE);
                v.setTextViewText(R.id.dx, e.dow + "\n" + e.dom);
            }
        }
        v.setInt(R.id.bg, "setColorFilter", e.color);
        v.setTextViewText(R.id.t, e.title);
        v.setTextViewText(R.id.s, e.sub);
        v.setViewVisibility(R.id.s, e.sub.length() > 0 ? View.VISIBLE : View.GONE);
        Intent fill = new Intent();
        fill.putExtra("ev_kind", e.kind);
        fill.putExtra("ev_id", e.id);
        fill.putExtra("ev_day", e.day);
        v.setOnClickFillInIntent(R.id.item, fill);
        return v;
    }

    @Override
    public RemoteViews getLoadingView() {
        return null;
    }

    @Override
    public int getViewTypeCount() {
        return 1;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }
}
