package com.vincedelmas.pocketblindtest;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
    private List<MainActivity.Track> songs=List.of();
    private long selected=-1;
    private final Consumer<MainActivity.Track> submit;
    SongChoices(Consumer<MainActivity.Track> submit) { this.submit=submit; setHasStableIds(true); }
    void show(List<MainActivity.Track> matches) { songs=matches; selected=-1; notifyDataSetChanged(); }
    @Override public long getItemId(int position) { return songs.get(position).id(); }
    @Override public int getItemCount() { return songs.size(); }
    @Override public Holder onCreateViewHolder(ViewGroup parent,int type) { return new Holder(parent.getContext()); }
    @Override public void onBindViewHolder(Holder holder,int position) {
        MainActivity.Track song=songs.get(position); boolean checked=selected==song.id();
        holder.title.setText(song.title()); holder.artist.setText(song.artist()); holder.radio.setChecked(checked);
        holder.card.setStrokeWidth(dp(holder.card.getContext(),checked?2:1));
        holder.card.setStrokeColor(checked?0xffe8d8bd:0xff41403c);
        holder.confirm.setVisibility(checked?View.VISIBLE:View.GONE);
        holder.card.setContentDescription(song.label()+(checked?", selected":""));
        View.OnClickListener select=v -> {
            int index=holder.getBindingAdapterPosition(); if(index==RecyclerView.NO_POSITION) return;
            long old=selected; selected=songs.get(index).id();
            for(int i=0;i<songs.size();i++) if(songs.get(i).id()==old) { notifyItemChanged(i); break; }
            notifyItemChanged(index);
        };
        holder.card.setOnClickListener(select); holder.radio.setOnClickListener(select);
        holder.confirm.setOnClickListener(v -> { int index=holder.getBindingAdapterPosition(); if(index!=RecyclerView.NO_POSITION) submit.accept(songs.get(index)); });
    }
    static int dp(Context c,int value) { return Math.round(value*c.getResources().getDisplayMetrics().density); }
    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView title,artist;
        final MaterialRadioButton radio;
        final MaterialButton confirm;
        Holder(Context context) {
            super(new MaterialCardView(context)); card=(MaterialCardView)itemView;
            RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(-1,-2); params.setMargins(0,dp(context,4),0,dp(context,4)); card.setLayoutParams(params);
            card.setRadius(dp(context,20)); card.setCardElevation(0); card.setCardBackgroundColor(0xff202123); card.setClickable(true); card.setFocusable(true);
            LinearLayout column=new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL); column.setPadding(dp(context,16),dp(context,8),dp(context,8),dp(context,8)); card.addView(column);
            LinearLayout row=new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); column.addView(row);
            LinearLayout labels=new LinearLayout(context); labels.setOrientation(LinearLayout.VERTICAL); row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            title=new TextView(context); title.setTextSize(16); title.setTextColor(0xfff0ebe1); title.setPadding(0,dp(context,6),0,dp(context,4)); labels.addView(title);
            artist=new TextView(context); artist.setTextSize(13); artist.setTextColor(0xffc8c5be); artist.setPadding(0,0,0,dp(context,6)); labels.addView(artist);
            radio=new MaterialRadioButton(context); radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(radio);
            confirm=new MaterialButton(context); confirm.setText("Submit guess"); column.addView(confirm,new LinearLayout.LayoutParams(-1,-2));
        }
    }
}
