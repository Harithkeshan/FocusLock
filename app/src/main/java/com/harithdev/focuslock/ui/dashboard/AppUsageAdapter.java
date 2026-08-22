package com.harithdev.focuslock.ui.dashboard;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.harithdev.focuslock.databinding.ItemAppUsageBinding;
import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.util.AppUtils;

import java.util.List;

public class AppUsageAdapter extends RecyclerView.Adapter<AppUsageAdapter.UsageViewHolder> {

    public static class AppUsageItem {
        public final AppRestriction restriction;
        public final long usedMs;

        public AppUsageItem(AppRestriction restriction, long usedMs) {
            this.restriction = restriction;
            this.usedMs      = usedMs;
        }
    }

    private final List<AppUsageItem> items;

    public AppUsageAdapter(List<AppUsageItem> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public UsageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAppUsageBinding binding = ItemAppUsageBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new UsageViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull UsageViewHolder holder, int position) {
        AppUsageItem item = items.get(position);
        Context context = holder.itemView.getContext();

        holder.binding.txtAppName.setText(item.restriction.appName);

        try {
            Drawable icon = context.getPackageManager().getApplicationIcon(item.restriction.packageName);
            holder.binding.imgAppIcon.setImageDrawable(icon);
        } catch (Exception e) {
            holder.binding.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        long usedMins  = item.usedMs / 60_000L;
        long limitMins = item.restriction.enforcedDailyLimitMinutes;

        holder.binding.txtUsageTime.setText(formatTime(usedMins) + " / " + formatTime(limitMins));

        int percentage = limitMins > 0 ? (int) Math.min(100, (usedMins * 100) / limitMins) : 0;
        holder.binding.txtUsagePercentage.setText(percentage + "%");
        holder.binding.progressAppUsage.setMax(100);
        holder.binding.progressAppUsage.setProgress(percentage);
    }

    private String formatTime(long minutes) {
        if (minutes < 60) return minutes + "m";
        long h = minutes / 60;
        long m = minutes % 60;
        return m > 0 ? h + "h " + m + "m" : h + "h";
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    static class UsageViewHolder extends RecyclerView.ViewHolder {
        final ItemAppUsageBinding binding;

        UsageViewHolder(ItemAppUsageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
