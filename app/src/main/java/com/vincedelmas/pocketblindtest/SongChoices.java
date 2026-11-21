package com.vincedelmas.pocketblindtest;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.radiobutton.MaterialRadioButton;
import java.util.*;
import java.util.function.Consumer;

/** Only visible song rows have views; the full match set can be scrolled. */
final class SongChoices extends RecyclerView.Adapter<SongChoices.Holder> {
    private List<MainActivity.Track> songs = List.of();
    private long selected = -1;
    private final boolean artistOnly;
    private final Consumer<MainActivity.Track> submit;

    SongChoices(Consumer<MainActivity.Track> submit, boolean artistOnly) {
        this.submit = submit;
        this.artistOnly = artistOnly;
        setHasStableIds(true);
    }

    void show(List<MainActivity.Track> matches) {
        songs = matches;
        selected = -1;
        notifyDataSetChanged();
    }

    @Override public long getItemId(int position) { return songs.get(position).id(); }
    @Override public int getItemCount() { return songs.size(); }
    @Override public Holder onCreateViewHolder(ViewGroup parent, int type) { return new Holder(parent.getContext()); }

    @Override public void onBindViewHolder(Holder holder, int position) {
        MainActivity.Track song = songs.get(position);
        boolean checked = selected == song.id();
        Context ctx = holder.card.getContext();
        holder.title.setText(artistOnly ? song.artist() : song.title());
        holder.artist.setText(song.artist());
        holder.artist.setVisibility(artistOnly ? View.GONE : View.VISIBLE);
        holder.radio.setChecked(checked);
        holder.card.setCardBackgroundColor(checked ? 0xFF211F1B : 0xFF16181A);
        holder.card.setStrokeWidth(dp(ctx, checked ? 2 : 1));
        holder.card.setStrokeColor(checked ? 0xFFEDE0C8 : 0xFF262A2E);
        holder.confirm.setVisibility(checked ? View.VISIBLE : View.GONE);
        holder.card.setContentDescription((artistOnly ? song.artist() : song.label()) + (checked ? ", selected" : ""));
        View.OnClickListener select = v -> {
            int index = holder.getBindingAdapterPosition();
            if (index == RecyclerView.NO_POSITION) return;
            long old = selected;
            selected = songs.get(index).id();
            for (int i = 0; i < songs.size(); i++) {
                if (songs.get(i).id() == old) { notifyItemChanged(i); break; }
            }
            notifyItemChanged(index);
        };
        holder.card.setOnClickListener(select);
        holder.radio.setOnClickListener(select);
        holder.confirm.setOnClickListener(v -> {
            int index = holder.getBindingAdapterPosition();
            if (index != RecyclerView.NO_POSITION) submit.accept(songs.get(index));
        });
    }

    static int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }

    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView title, artist;
        final MaterialRadioButton radio;
        final MaterialButton confirm;

        Holder(Context context) {
            super(new MaterialCardView(context));
            card = (MaterialCardView) itemView;
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2);
            params.setMargins(0, dp(context, 4), 0, dp(context, 4));
            card.setLayoutParams(params);
            card.setRadius(dp(context, 18));
            card.setCardElevation(0);
            card.setCardBackgroundColor(0xFF16181A);
            card.setRippleColor(ColorStateList.valueOf(0x1CEDE0C8));
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(dp(context, 16), dp(context, 10), dp(context, 10), dp(context, 10));
            card.addView(column);

            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            column.addView(row);

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(0, dp(context, 2), dp(context, 8), dp(context, 2));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

            title = new TextView(context);
            title.setTextSize(15.5f);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setTextColor(0xFFF5F1E8);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title);

            artist = new TextView(context);
            artist.setTextSize(13f);
            artist.setTextColor(0xFFA8A6A0);
            artist.setMaxLines(1);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            artist.setPadding(0, dp(context, 2), 0, 0);
            labels.addView(artist);

            radio = new MaterialRadioButton(context);
            radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            radio.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFEDE0C8, 0xFF5A5E63}
            ));
            row.addView(radio);

            confirm = new MaterialButton(context);
            confirm.setText("Submit guess");
            confirm.setAllCaps(false);
            confirm.setTextSize(15f);
            confirm.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            confirm.setCornerRadius(dp(context, 14));
            confirm.setBackgroundTintList(ColorStateList.valueOf(0xFFEDE0C8));
            confirm.setTextColor(0xFF1C160E);
            confirm.setMinHeight(dp(context, 48));
            LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(-1, -2);
            confirmLp.setMargins(0, dp(context, 10), dp(context, 6), dp(context, 2));
            column.addView(confirm, confirmLp);
        }
    }
}
