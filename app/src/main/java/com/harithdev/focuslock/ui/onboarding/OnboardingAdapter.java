package com.harithdev.focuslock.ui.onboarding;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.harithdev.focuslock.databinding.ItemOnboardingSlideBinding;

import java.util.List;

public class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.SlideViewHolder> {

    public static class SlideItem {
        public final String emoji;
        public final String title;
        public final String description;

        public SlideItem(String emoji, String title, String description) {
            this.emoji = emoji;
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
        holder.binding.txtEmoji.setText(slide.emoji);
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
