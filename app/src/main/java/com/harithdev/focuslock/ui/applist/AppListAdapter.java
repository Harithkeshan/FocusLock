package com.harithdev.focuslock.ui.applist;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.harithdev.focuslock.R;

import java.util.List;

/**
 * AppListAdapter — RecyclerView adapter for the app list screen.
 *
 * Each row shows:
 *   - App icon
 *   - App name
 *   - A small "RESTRICTED" badge if the app has an active restriction
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/ui/applist/AppListAdapter.java
 */
public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    // Callback interface — triggered when user taps a row
    public interface OnAppClickListener {
        void onAppClick(AppInfo appInfo);
    }

    private List<AppInfo> appList;
    private final OnAppClickListener clickListener;

    public AppListAdapter(List<AppInfo> appList, OnAppClickListener clickListener) {
        this.appList       = appList;
        this.clickListener = clickListener;
    }

    // ── RecyclerView boilerplate ──────────────────────────────

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo app = appList.get(position);

        holder.appIcon.setImageDrawable(app.icon);
        holder.appName.setText(app.appName);

        // Show "RESTRICTED" badge only if restriction is active
        holder.restrictedBadge.setVisibility(
                app.isRestricted ? View.VISIBLE : View.GONE
        );

        // Tap the row → notify activity
        holder.itemView.setOnClickListener(v -> clickListener.onAppClick(app));
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    // ── Update list (called by search filter or on resume) ────

    public void updateList(List<AppInfo> newList) {
        this.appList = newList;
        notifyDataSetChanged();
    }

    // ── ViewHolder ────────────────────────────────────────────

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView appIcon;
        TextView  appName;
        TextView  restrictedBadge;

        ViewHolder(View itemView) {
            super(itemView);
            appIcon         = itemView.findViewById(R.id.img_app_icon);
            appName         = itemView.findViewById(R.id.txt_app_name);
            restrictedBadge = itemView.findViewById(R.id.txt_restricted_badge);
        }
    }
}