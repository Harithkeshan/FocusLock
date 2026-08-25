package com.harithdev.focuslock.ui.onboarding;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.harithdev.focuslock.databinding.ItemOnboardingSlideBinding;

import java.util.List;

public class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.SlideViewHolder> {

    public static class SlideItem {
        public final int iconRes;
        public final String title;
        public final String description;

        public SlideItem(@DrawableRes int iconRes, String title, String description) {
            this.iconRes = iconRes;
            this.title = title;
            this.description = description;
        }
    }

    private final List<SlideItem> slides;

    public OnboardingAdapter(List<SlideItem> slides) {
        this.slides = slides;
    }

    @NonNull
    @Override
    public SlideViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemOnboardingSlideBinding binding = ItemOnboardingSlideBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new SlideViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull SlideViewHolder holder, int position) {
        SlideItem slide = slides.get(position);
        holder.binding.imgSlideIcon.setImageResource(slide.iconRes);
        holder.binding.txtTitle.setText(slide.title);
        holder.binding.txtDescription.setText(slide.description);
    }

    @Override
    public int getItemCount() {
        return slides.size();
    }

    static class SlideViewHolder extends RecyclerView.ViewHolder {
        final ItemOnboardingSlideBinding binding;

        SlideViewHolder(ItemOnboardingSlideBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
