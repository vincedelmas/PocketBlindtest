package com.vincedelmas.pocketblindtest;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.inputmethod.InputMethodManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;
import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

/** Local music never leaves the device. Networking is exclusively user-triggered updates. */
public final class MainActivity extends AppCompatActivity {
    private static final int[] CLIPS = {1000, 2000, 4000, 7000, 11000, 16000};
    private static final String REPO = "https://api.github.com/repos/vincedelmas/PocketBlindtest";
    private static final int BG = Color.rgb(14,14,14), MUTED = Color.rgb(160,160,157), ACCENT = Color.rgb(237,66,53);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<Track> tracks = new ArrayList<>();
    private final ArrayList<Track> bag = new ArrayList<>();
    private final Random random = new Random();
    private LinearLayout root, content, stages;
    private RecyclerView choices;
    private SongChoices songChoices;
    private TextView matchCount;
    private MaterialToolbar toolbar;
    private final ExecutorService searchWorker=Executors.newSingleThreadExecutor();
    private Future<?> searchTask;
    private int searchVersion;
    private Runnable searchDebounce;
    private TextView status, counter, message;
    private Button play, skip;
    private GameHistory history;
    private String sessionId, roundId, screen="home";
    private int sessionSize, screenVersion;
    private final HashSet<Long> excluded=new HashSet<>();
    private String statsSession, restoreScreen, restoreStats;
    private final Runnable playbackTick=new Runnable() {
        @Override public void run() {
            if(!playing || player==null || !(play instanceof PlaybackButton)) return;
            ((PlaybackButton)play).setProgress(player.progress());
            main.postDelayed(this,32);
        }
    };
    private EditText search;
    private Track target;
    private int attempt, rounds, wins;
    private boolean finished, loading, playing, solved, exhausted;
    private ClipAudio player;
    private AudioFocusRequest focus;
    private AudioManager audio;
    private long downloadId = -1;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { stopAudio(); }
    };

    record Track(long id, String title, String artist, String genre, int year) {
        String label() { return title + " — " + artist; }
        Uri uri() { return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id); }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        history=new GameHistory(this);
        if(state!=null) { restoreScreen=state.getString("screen"); restoreStats=state.getString("statsSession"); }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> { if(screen.equals("home")) finish(); else showHome(); });
        downloadId = getPreferences(0).getLong("download", -1);
        if (!BuildConfig.VERSION_NAME.equals(getPreferences(0).getString("downloadFrom", BuildConfig.VERSION_NAME))) {
            downloadId = -1;
            getPreferences(0).edit().remove("download").remove("downloadFrom").apply();
        }
        audio = getSystemService(AudioManager.class);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_EXPORTED);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(24),dp(24),dp(24),dp(24));
        scroll.addView(root); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            root.setPadding(dp(24)+bars.left,dp(20)+bars.top,dp(24)+bars.right,dp(24)+bars.bottom); return insets;
        });
        toolbar=new MaterialToolbar(this); toolbar.setTitle("Pocket Blindtest");
        toolbar.setTitleTextColor(Color.rgb(240,235,225)); toolbar.setOverflowIcon(getDrawable(R.drawable.ic_more_vert));
        toolbar.getMenu().add(1,1,0,"Game modes");
        toolbar.getMenu().add(1,2,1,"History & statistics");
        toolbar.getMenu().add(2,3,2,"Session summary").setEnabled(false);
        toolbar.getMenu().add(2,4,3,"Replace unplayable song").setVisible(false);
        toolbar.getMenu().add(3,5,4,"Refresh music library");
        toolbar.getMenu().add(3,6,5,"Check for updates");
        SpannableString resetLabel=new SpannableString("Reset statistics…");
        resetLabel.setSpan(new android.text.style.ForegroundColorSpan(Color.rgb(255,180,171)),0,resetLabel.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        toolbar.getMenu().add(4,7,6,resetLabel);
        toolbar.getMenu().add(5,8,7,"Version "+BuildConfig.VERSION_NAME).setEnabled(false);
        toolbar.getMenu().setGroupDividerEnabled(true);
        toolbar.setOnMenuItemClickListener(item -> {
            switch(item.getItemId()) {
                case 1 -> showHome();
                case 2 -> showStats(null);
                case 3 -> showStats(sessionId);
                case 4 -> confirmReplacement();
                case 5 -> loadLibrary();
                case 6 -> checkUpdate();
                case 7 -> confirmReset();
                default -> { return false; }
            }
            return true;
        });
        root.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(64)));
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); root.addView(content);
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) loadLibrary();
        else permissionScreen();
    }

    private void permissionScreen() {
        content.removeAllViews();
        text(content,"Music access",24,Color.WHITE);
        text(content,"Allow access to the songs stored on this phone.",16,MUTED);
        button(content,"Allow music access").setOnClickListener(v -> {
            if (getPreferences(0).getBoolean("asked",false) && !shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_AUDIO)) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:"+getPackageName())));
            } else {
                getPreferences(0).edit().putBoolean("asked",true).apply();
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO},1);
            }
        });
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if (code==1 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) loadLibrary();
        else permissionScreen();
    }
    private void loadLibrary() {
        if (loading) return;
        stopAudio(); saveGame(); screenVersion++;
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)!=PackageManager.PERMISSION_GRANTED) { permissionScreen(); return; }
        stopAudio(); loading=true; content.removeAllViews(); text(content,"Reading your library…",18,MUTED);
        io.execute(() -> {
            ArrayList<Track> found = new ArrayList<>(); String failure = null;
            String[] columns = {MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.GENRE,MediaStore.Audio.Media.YEAR};
            try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
                    MediaStore.Audio.Media.IS_MUSIC+" != 0 AND "+MediaStore.Audio.Media.DURATION+" >= "+CLIPS[CLIPS.length-1],null,MediaStore.Audio.Media.TITLE+" ASC")) {
                if(c!=null) while(c.moveToNext()) found.add(new Track(c.getLong(0),c.getString(1)==null?"Untitled":c.getString(1),c.getString(2)==null?"Unknown artist":c.getString(2),genre(c.getString(3)),year(c.getInt(4))));
            } catch (Exception e) { failure="Could not read music. Check audio permission, then refresh."; }
            String error=failure;
            main.post(() -> {
                if(isDestroyed()) return;
                loading=false; tracks.clear(); tracks.addAll(found); bag.clear();
                if(error!=null || tracks.isEmpty()) {
                    content.removeAllViews(); text(content,error!=null?error:"No songs found",23,Color.WHITE);
                    text(content,"Copy music files onto your phone, then refresh. Songs must be at least 16 seconds long and indexed as music by Android.",16,MUTED); return;
                }
                restoreGame();
                String page=restoreScreen; restoreScreen=null;
                if("stats".equals(page)) showStats(restoreStats);
                else if("round".equals(page) && target!=null) renderRound();
                else showHome();
            });
        });
    }
    private void nextRound() {
        stopAudio();
        if(sessionSize>0 && rounds>=sessionSize) { showStats(sessionId); return; }
        if(bag.isEmpty() && sessionSize>0 && rounds>0) { exhausted=true; target=null; saveGame(); toast("No more playable songs in this session."); showStats(sessionId); return; }
        if(bag.isEmpty()) { for(Track t:tracks) if(!excluded.contains(t.id)) bag.add(t); Collections.shuffle(bag,random);
            if(bag.isEmpty()) { exhausted=true; target=null; saveGame(); toast("No playable songs remain. Start a new session to try them again."); showStats(sessionId); return; }
            if(bag.size()>1 && target!=null && bag.get(bag.size()-1).id==target.id) Collections.swap(bag,0,bag.size()-1);
        }
        target=bag.remove(bag.size()-1); roundId=UUID.randomUUID().toString(); attempt=0; finished=false; solved=false; saveGame(); renderRound();
    }
    private void renderRound() {
        cancelSearch(); screen="round"; screenVersion++; toolbar.getMenu().findItem(4).setVisible(target!=null); toolbar.getMenu().findItem(3).setEnabled(sessionId!=null);
        if(finished) { reveal(); return; }
        content.animate().cancel(); content.setAlpha(1f); content.setTranslationY(0);
        content.removeAllViews();
        content.post(() -> ((ScrollView)root.getParent()).smoothScrollTo(0,0));
        counter=eyebrow(content,"SONG "+(rounds+1)+(sessionSize>0?" / "+sessionSize:"   ·   INFINITE"));
        RecordArtwork artwork=new RecordArtwork(this,false);
        addArtwork(content,artwork,212);
        status=centered(content,"",25,Color.WHITE);
        status.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        TextView detail=centered(content,"Identify the track",14,MUTED); detail.setPadding(0,0,0,dp(20));
        stages=new LinearLayout(this); content.addView(stages);
        for(int i=0;i<CLIPS.length;i++) {
            TextView stage=text(stages,duration(CLIPS[i])+"s",12,MUTED); stage.setPadding(0,0,0,0); stage.setGravity(Gravity.CENTER); stage.setTypeface(Typeface.MONOSPACE);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(40),1); lp.setMargins(dp(3),0,dp(3),dp(14)); stage.setLayoutParams(lp);
        }
        play=playbackButton(content,"Play"); primary(play); play.setOnClickListener(v -> playClip());
        message=centered(content,"Attempt 1 of "+CLIPS.length,13,MUTED);
        TextInputLayout searchBox=new TextInputLayout(this); searchBox.setHint("Search title or artist");
        searchBox.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        searchBox.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        search=new TextInputEditText(searchBox.getContext()); search.setSingleLine(true); search.setTextSize(16);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        searchBox.addView(search,new LinearLayout.LayoutParams(-1,-2)); content.addView(searchBox,new LinearLayout.LayoutParams(-1,-2));
        matchCount=text(content,"Search your library",12,MUTED);
        choices=new RecyclerView(this); choices.setLayoutManager(new LinearLayoutManager(this));
        choices.setItemAnimator(null); songChoices=new SongChoices(this::guess); choices.setAdapter(songChoices);
        choices.setNestedScrollingEnabled(true);
        // Keep the viewport bounded inside the outer page: never measure every matching row.
        content.addView(choices,new LinearLayout.LayoutParams(-1,dp(360))); choices.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value,int start,int count,int after) {}
            public void onTextChanged(CharSequence value,int start,int before,int count) { showMatches(value.toString()); }
            public void afterTextChanged(Editable value) {}
        });
        skip=button(content,"Skip attempt   →"); quiet(skip); skip.setOnClickListener(v -> miss("Skipped."));
        updateAttempt();
    }
    private static String normalize(String s) {
        return Normalizer.normalize(s,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).trim();
    }
    private void cancelSearch() {
        searchVersion++;
        if(searchDebounce!=null) main.removeCallbacks(searchDebounce);
        if(searchTask!=null) searchTask.cancel(true);
    }
    private void showMatches(String query) {
        cancelSearch(); final int version=searchVersion;
        songChoices.show(List.of()); choices.setVisibility(View.GONE);
        if(finished || query.isBlank()) { matchCount.setText("Search your library"); return; }
        matchCount.setText("Searching…");
        List<Track> library=new ArrayList<>(tracks);
        searchDebounce=() -> searchTask=searchWorker.submit(() -> {
            String key=normalize(query); ArrayList<Track> matches=new ArrayList<>();
            for(Track song:library) {
                if(Thread.currentThread().isInterrupted()) return;
                if(normalize(song.label()).contains(key)) matches.add(song);
            }
            main.post(() -> {
                if(isDestroyed() || searchVersion!=version || !screen.equals("round") || finished) return;
                songChoices.show(matches); choices.setVisibility(matches.isEmpty()?View.GONE:View.VISIBLE);
                matchCount.setText(matches.isEmpty()?"No matching songs":matches.size()+" songs · Scroll to browse");
                choices.scrollToPosition(0);
            });
        });
        main.postDelayed(searchDebounce,150);
    }
    private void guess(Track choice) {
        if(finished) return;
        // Treat duplicate files with identical title and artist as the same answer.
        if(choice.id==target.id || normalize(choice.label()).equals(normalize(target.label()))) {
            solved=true; finishRound();
        } else miss("Incorrect. A longer clip is available.");
    }
    private void miss(String feedback) {
        if(finished) return; stopAudio();
        if(attempt==CLIPS.length-1) finishRound();
        else { attempt++; search.setText(""); message.setText(feedback+"  "+(attempt+1)+" / "+CLIPS.length); updateAttempt(); saveGame(); }
    }
    private void updateAttempt() {
        status.setText(duration(CLIPS[attempt])+" second"+(CLIPS[attempt]==1000?"":"s")); play.setText("▶   Play "+clipLabel());
        if(attempt==0) message.setText("Attempt 1 of "+CLIPS.length);
        for(int i=0;i<stages.getChildCount();i++) {
            TextView stage=(TextView)stages.getChildAt(i);
            stage.setTextColor(i==attempt?Color.rgb(37,30,20):i<attempt?Color.rgb(165,218,179):MUTED);
            stage.setBackground(surface(i==attempt?Color.rgb(232,216,189):Color.rgb(31,33,32),14));
        }
    }
    private static String duration(int ms) { return java.math.BigDecimal.valueOf(ms,3).stripTrailingZeros().toPlainString(); }
    private String clipLabel() { return duration(CLIPS[attempt])+" s"; }
    private void finishRound() {
        stopAudio();
        try { history.record(roundId,sessionId,target.id,target.title,target.artist,target.genre,target.year,solved,CLIPS[attempt],attempt+1); }
        catch(Exception e) { solved=false; message.setText("Could not save the result. Try submitting again."); return; }
        finished=true; rounds++; if(solved) wins++; saveGame();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(content.getWindowToken(),0);
        reveal();
    }
    private void reveal() {
        cancelSearch(); screen="round"; screenVersion++; toolbar.getMenu().findItem(4).setVisible(target!=null); toolbar.getMenu().findItem(3).setEnabled(sessionId!=null);
        content.animate().cancel();
        content.removeAllViews();
        content.post(() -> ((ScrollView)root.getParent()).smoothScrollTo(0,0));
        TextView result=eyebrow(content,solved?"✓  IDENTIFIED":"THE TRACK");
        result.setTextColor(solved?Color.rgb(165,218,179):Color.rgb(237,225,206));
        result.setBackground(surface(solved?Color.rgb(31,48,37):Color.rgb(49,42,34),50)); result.setPadding(dp(18),dp(9),dp(18),dp(9));
        LinearLayout.LayoutParams badgeParams=new LinearLayout.LayoutParams(-2,-2); badgeParams.gravity=Gravity.CENTER_HORIZONTAL; badgeParams.setMargins(0,dp(18),0,dp(22)); result.setLayoutParams(badgeParams);
        RecordArtwork artwork=new RecordArtwork(this,true);
        addArtwork(content,artwork,256);
        TextView title=centered(content,target.title,28,Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        title.setPadding(0,dp(22),0,dp(6));
        TextView artist=centered(content,target.artist,18,Color.rgb(220,216,209));
        artist.setPadding(0,0,0,dp(22));
        LinearLayout resultRow=new LinearLayout(this); resultRow.setGravity(Gravity.CENTER); content.addView(resultRow);
        for(int i=0;i<CLIPS.length;i++) {
            View dot=new View(this);
            int color=i==attempt&&solved?Color.rgb(165,218,179):i<=attempt?ACCENT:Color.rgb(51,52,54);
            dot.setBackground(surface(color,4));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(22),dp(5)); lp.setMargins(dp(3),0,dp(3),0); resultRow.addView(dot,lp);
        }
        centered(content,solved?"Found in "+(attempt+1)+" of "+CLIPS.length+" attempts":"All "+CLIPS.length+" attempts played",13,MUTED);
        Button next=button(content,sessionSize>0 && rounds>=sessionSize?"See session summary   →":"Next song   →"); primary(next); next.setOnClickListener(v -> nextRound());
        play=playbackButton(content,"▶   Replay "+duration(CLIPS[CLIPS.length-1])+" s"); play.setOnClickListener(v -> playClip());
        message=centered(content,score(wins,rounds)+" this session",13,MUTED);
        Button statistics=button(content,"Session statistics   →"); quiet(statistics); statistics.setOnClickListener(v -> showStats(sessionId));
        content.setAlpha(0f); content.setTranslationY(dp(12));
        content.animate().alpha(1f).translationY(0).setDuration(240).start();
        loadArtwork(artwork,target);
    }
    private void loadArtwork(RecordArtwork artwork,Track song) {
        io.execute(() -> {
            Bitmap image=null;
            try(MediaMetadataRetriever metadata=new MediaMetadataRetriever()) {
                metadata.setDataSource(this,song.uri());
                byte[] bytes=metadata.getEmbeddedPicture();
                if(bytes!=null && bytes.length<=16*1024*1024) {
                    BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true;
                    BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    options.inSampleSize=1;
                    while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>768) options.inSampleSize*=2;
                    options.inJustDecodeBounds=false;
                    image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                }
            } catch(Exception ignored) { /* The record illustration is also the no-artwork state. */ }
            Bitmap result=image;
            main.post(() -> {
                if(!isDestroyed() && finished && target!=null && target.id==song.id && artwork.isAttachedToWindow()) artwork.setArtwork(result);
                else if(result!=null) result.recycle();
            });
        });
    }
    private TextView centered(LinearLayout parent,String value,int size,int color) {
        TextView t=text(parent,value,size,color); t.setGravity(Gravity.CENTER); return t;
    }
    private TextView eyebrow(LinearLayout parent,String value) {
        TextView t=centered(parent,value,11,MUTED); t.setLetterSpacing(.16f); t.setPadding(0,dp(16),0,dp(22)); return t;
    }
    private void addArtwork(LinearLayout parent,RecordArtwork artwork,int size) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(size),dp(size)); lp.gravity=Gravity.CENTER_HORIZONTAL;
        parent.addView(artwork,lp);
    }
    private void playClip() {
        if(playing || target==null) return;
        stopAudio();
        AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> { if(change<0) stopAudio(); },main).build();
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { message.setText("Audio is busy. Try again."); return; }
        playing=true; play.setEnabled(false); play.setText("Preparing…");
        ClipAudio current=new ClipAudio(this,main); player=current;
        current.play(target.uri(),finished?CLIPS[CLIPS.length-1]:CLIPS[attempt],attributes,
            () -> { if(player==current) { play.setText("Playing…"); main.post(playbackTick); } },
            () -> { if(player==current) stopAudio(); },
            () -> { if(player==current) { stopAudio(); message.setText("Cannot play this song. Use Replace unplayable song in the three-dot menu."); } });
    }
    private void stopAudio() {
        main.removeCallbacks(playbackTick);
        if(play instanceof PlaybackButton) ((PlaybackButton)play).setProgress(0);
        if(player!=null) { player.close(); player=null; }
        if(focus!=null) { audio.abandonAudioFocusRequest(focus); focus=null; }
        playing=false;
        if(play!=null) { play.setEnabled(true); play.setText(finished?"▶   Replay "+duration(CLIPS[CLIPS.length-1])+" s":"▶   Play "+clipLabel()); }
    }

    private static String genre(String value) {
        return value==null || value.isBlank() || value.trim().equalsIgnoreCase("<unknown>") || value.trim().equalsIgnoreCase("unknown")?"Unknown":value.trim();
    }
    private static int year(int value) { return value>=1000 && value<=2100?value:0; }
    private static String score(int found,int played) {
        return found+" / "+played+" solved · "+(played==0?"—":Math.round(100f*found/played)+"%");
    }
    private void beginPage(String page) {
        stopAudio(); cancelSearch(); toolbar.getMenu().findItem(4).setVisible(false); toolbar.getMenu().findItem(3).setEnabled(sessionId!=null); play=null; screen=page; screenVersion++;
        content.animate().cancel(); content.setAlpha(1); content.setTranslationY(0); content.removeAllViews();
        content.post(() -> ((ScrollView)root.getParent()).smoothScrollTo(0,0));
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(content.getWindowToken(),0);
    }
    private void showHome() {
        beginPage("home");
        TextView homeTitle=text(content,"Play your\nmusic library.",36,Color.rgb(240,235,225));
        homeTitle.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); homeTitle.setLineSpacing(dp(2),1); homeTitle.setPadding(dp(4),dp(24),0,dp(20));
        if(sessionId!=null) {
            Button resume=button(content,sessionEnded()?"View session summary":"Continue "+(sessionSize==0?"infinite mode":sessionSize+"-song session"));
            primary(resume); resume.setOnClickListener(v -> { if(sessionEnded()) showStats(sessionId); else if(target!=null) renderRound(); else nextRound(); });
            centered(content,score(wins,rounds),13,MUTED);
        }
        TextView section=text(content,"New session",20,Color.rgb(240,235,225)); section.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        LinearLayout row=null;
        for(int i=0;i<4;i++) {
            if(i%2==0) { row=new LinearLayout(this); content.addView(row); }
            int count=new int[]{5,10,15,20}[i];
            MaterialCardView mode=new MaterialCardView(this); mode.setRadius(dp(24)); mode.setCardElevation(0);
            mode.setCardBackgroundColor(Color.rgb(30,32,31)); mode.setStrokeColor(Color.rgb(60,63,59)); mode.setStrokeWidth(dp(1));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1); lp.setMargins(dp(4),dp(4),dp(4),dp(4)); row.addView(mode,lp);
            LinearLayout labels=new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL); labels.setPadding(dp(20),dp(10),dp(20),dp(16)); mode.addView(labels);
            TextView number=text(labels,String.format(Locale.ROOT,"%02d",count),36,Color.rgb(232,216,189));
            number.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL)); number.setPadding(0,dp(6),0,0);
            TextView caption=text(labels,"SONGS   ↗",11,Color.rgb(200,202,193)); caption.setLetterSpacing(.12f); caption.setPadding(0,dp(4),0,0);
            boolean available=tracks.size()>=count; mode.setEnabled(available); mode.setAlpha(available?1:.4f);
            mode.setClickable(available); mode.setFocusable(available); mode.setContentDescription(count+"-song session"+(available?"":". Requires "+count+" songs in your library"));
            if(available) mode.setOnClickListener(v -> startSession(count));
        }
        Button infinite=button(content,"∞   Infinite mode"); infinite.setMinHeight(dp(64)); infinite.setEnabled(!tracks.isEmpty()); infinite.setOnClickListener(v -> startSession(0));
        centered(content,tracks.size()+" songs in your library",12,MUTED);
        Button stats=button(content,"History & statistics   →"); quiet(stats); stats.setOnClickListener(v -> showStats(null));
    }
    private boolean sessionEnded() { return exhausted || (sessionSize>0 && rounds>=sessionSize); }
    private void startSession(int size) {
        if(tracks.isEmpty()) { toast("Refresh the music library first."); return; }
        if(size>tracks.size()) { toast("Not enough songs for this session."); return; }
        stopAudio();
        try { sessionId=history.start(size); }
        catch(Exception e) { toast("Could not start a session. Check available storage."); return; }
        sessionSize=size; exhausted=false; rounds=0; wins=0; target=null; finished=false; solved=false; excluded.clear(); bag.clear();
        nextRound();
    }
    private void saveGame() {
        if(sessionId==null) return;
        try {
            JSONObject state=new JSONObject(); state.put("session",sessionId); state.put("size",sessionSize); state.put("target",target==null?-1:target.id); state.put("exhausted",exhausted);
            state.put("round",roundId==null?"":roundId); state.put("attempt",attempt); state.put("finished",finished); state.put("solved",solved);
            JSONArray deck=new JSONArray(); for(Track t:bag) deck.put(t.id); state.put("deck",deck);
            JSONArray removed=new JSONArray(); for(long id:excluded) removed.put(id); state.put("excluded",removed);
            getPreferences(0).edit().putString("game",state.toString()).apply();
        } catch(JSONException e) { toast("Could not save the current game."); }
    }
    private void restoreGame() {
        try {
            String saved=getPreferences(0).getString("game",null); if(saved==null) return;
            JSONObject state=new JSONObject(saved); sessionId=state.getString("session"); sessionSize=state.getInt("size"); exhausted=state.optBoolean("exhausted");
            roundId=state.getString("round"); attempt=Math.max(0,Math.min(CLIPS.length-1,state.getInt("attempt")));
            finished=state.getBoolean("finished"); solved=state.getBoolean("solved");
            Boolean recorded=history.found(roundId); if(recorded!=null) { finished=true; solved=recorded; }
            long id=state.getLong("target"); target=tracks.stream().filter(t -> t.id==id).findFirst().orElse(null);
            Map<Long,Track> byId=new HashMap<>(); for(Track t:tracks) byId.put(t.id,t);
            excluded.clear(); JSONArray removed=state.optJSONArray("excluded"); if(removed!=null) for(int i=0;i<removed.length();i++) excluded.add(removed.getLong(i));
            bag.clear(); JSONArray deck=state.getJSONArray("deck"); for(int i=0;i<deck.length();i++) { Track t=byId.get(deck.getLong(i)); if(t!=null && !excluded.contains(t.id)) bag.add(t); }
            if(target!=null && excluded.contains(target.id)) { target=null; finished=false; solved=false; }
            GameHistory.Summary summary=history.summary(sessionId); rounds=summary.played(); wins=summary.found();
        } catch(Exception e) { sessionId=null; target=null; bag.clear(); excluded.clear(); toast("Could not resume the game. Saved statistics are still available."); }
    }
    private void confirmReplacement() {
        if(target==null || !screen.equals("round")) return;
        new MaterialAlertDialogBuilder(this).setTitle("Replace this song?")
            .setMessage("Use this if the file is silent or cannot be played. This round is removed from all statistics and another song takes its place.")
            .setNegativeButton("Keep song",null).setPositiveButton("Replace song",(dialog,which) -> reportSong()).show();
    }
    private void confirmReset() {
        var confirmation=new MaterialAlertDialogBuilder(this).setTitle("Reset all statistics?")
            .setMessage("Permanently delete all session history and statistics, and end the current game. Your music files are kept. This cannot be undone.")
            .setNegativeButton("Cancel",null).setPositiveButton("Reset statistics",(dialog,which) -> resetStats()).show();
        confirmation.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(Color.rgb(255,180,171));
    }
    private void resetStats() {
        stopAudio();
        try { history.reset(); }
        catch(Exception e) { toast("Could not reset statistics. Nothing was changed."); return; }
        sessionId=null; roundId=null; target=null; sessionSize=0; rounds=0; wins=0; attempt=0;
        finished=false; solved=false; exhausted=false; bag.clear(); excluded.clear();
        getPreferences(0).edit().remove("game").apply();
        showHome(); toast("Statistics reset.");
    }
    private void reportSong() {
        if(target==null || sessionId==null) return;
        stopAudio();
        try { history.remove(roundId); }
        catch(Exception e) { message.setText("Could not remove this result. Try again."); return; }
        if(finished) { rounds--; if(solved) wins--; }
        excluded.add(target.id); bag.removeIf(t -> t.id==target.id); finished=false; solved=false;
        saveGame(); toast("Song replaced. It will not count in your statistics."); nextRound();
    }
    private void showStats(String id) {
        beginPage("stats"); statsSession=id; int version=screenVersion;
        centered(content,"Loading statistics…",16,MUTED);
        io.execute(() -> {
            try {
                GameHistory.Summary summary=history.summary(id);
                List<GameHistory.Session> sessions=id==null?history.sessions():List.of();
                List<GameHistory.Result> results=id==null?List.of():history.results(id);
                main.post(() -> { if(!isDestroyed() && screenVersion==version) renderStats(id,summary,sessions,results); });
            } catch(Exception e) { main.post(() -> { if(screenVersion==version && !isDestroyed()) { content.removeAllViews(); text(content,"Could not load statistics.",18,Color.WHITE); button(content,"Back to game modes").setOnClickListener(v -> showHome()); } }); }
        });
    }
    private void renderStats(String id,GameHistory.Summary summary,List<GameHistory.Session> sessions,List<GameHistory.Result> results) {
        content.removeAllViews();
        boolean complete=id!=null && id.equals(sessionId) && sessionEnded();
        eyebrow(content,id==null?"ALL-TIME":complete?(exhausted?"SESSION ENDED":"SESSION COMPLETE"):"SESSION STATISTICS");
        centered(content,summary.played()==0?"No results yet":Math.round(100f*summary.found()/summary.played())+"%",48,Color.WHITE);
        centered(content,score(summary.found(),summary.played()),16,MUTED);
        LinearLayout metrics=new LinearLayout(this); content.addView(metrics);
        metric(metrics,summary.found()==0?"—":String.format(Locale.ROOT,"%.1f s",summary.meanMs()/1000),"Average clip to solve");
        metric(metrics,String.valueOf(summary.played()-summary.found()),"Not found");
        centered(content,"Average uses correct answers only and the clip length unlocked at the guess.",12,MUTED);
        button(content,id!=null && id.equals(sessionId) && !complete?"Continue session":"Game modes").setOnClickListener(v -> { if(id!=null && id.equals(sessionId) && !complete) { if(target!=null) renderRound(); else nextRound(); } else showHome(); });
        if(complete && sessionSize>0) button(content,"Play "+sessionSize+" songs again").setOnClickListener(v -> startSession(sessionSize));
        if(summary.played()==0) text(content,"Finish a song to start your history. Unplayable songs never count.",15,MUTED);
        else {
            text(content,"Your strengths",22,Color.WHITE);
            text(content,"Rankings need 3 plays per category. Missing tags are excluded from rankings.",12,MUTED);
            groupSection("Genres",summary.genres());
            groupSection("Five-year periods",summary.fiveYears());
            groupSection("Decades",summary.decades());
        }
        if(id==null) {
            text(content,"Recent sessions",22,Color.WHITE);
            for(GameHistory.Session session:sessions) {
                String date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(new Date(session.started()));
                String title=session.size()==0?"Infinite mode":session.size()+" songs · "+session.played()+" / "+session.size()+" completed";
                LinearLayout row=card(content);
                TextView heading=text(row,title,17,Color.rgb(240,235,225)); heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); heading.setPadding(0,dp(10),0,dp(4));
                TextView performance=text(row,score(session.found(),session.played()),14,Color.rgb(165,218,179)); performance.setPadding(0,dp(4),0,dp(6));
                TextView timestamp=text(row,date+"   →",12,MUTED); timestamp.setPadding(0,0,0,dp(12));
                View container=(View)row.getParent(); container.setClickable(true); container.setFocusable(true);
                container.setContentDescription(title+", "+score(session.found(),session.played())+", "+date);
                container.setOnClickListener(v -> showStats(session.id()));
            }
        } else {
            button(content,"All-time statistics").setOnClickListener(v -> showStats(null));
            text(content,"Songs · newest first",22,Color.WHITE);
            for(GameHistory.Result result:results) {
                LinearLayout card=card(content);
                text(card,result.title(),17,Color.WHITE); text(card,result.artist(),14,MUTED);
                text(card,result.found()?"✓ Solved · "+duration(result.clipMs())+" s":"Not found",13,result.found()?Color.rgb(165,218,179):MUTED);
            }
            if(summary.played()>50) text(content,"Showing the latest 50 songs. Totals include every result.",12,MUTED);
        }
    }
    private void metric(LinearLayout row,String value,String label) {
        LinearLayout column=card(row); ((View)column.getParent()).setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        centered(column,value,25,Color.WHITE); centered(column,label,12,MUTED);
    }
    private LinearLayout card(LinearLayout parent) {
        MaterialCardView container=new MaterialCardView(this); container.setRadius(dp(20)); container.setCardElevation(0);
        LinearLayout column=new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setPadding(dp(14),dp(6),dp(14),dp(6)); container.addView(column);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(dp(3),dp(6),dp(3),dp(6)); parent.addView(container,lp); return column;
    }
    private void groupSection(String title,List<GameHistory.Group> groups) {
        text(content,title,18,Color.WHITE);
        ArrayList<GameHistory.Group> ranked=new ArrayList<>(); for(GameHistory.Group g:groups) if(!g.name().equals("Unknown") && g.played()>=3) ranked.add(g);
        ranked.sort(Comparator.comparingDouble((GameHistory.Group g) -> (double)g.found()/g.played()).thenComparingInt(GameHistory.Group::played));
        if(ranked.size()>=2) {
            GameHistory.Group worst=ranked.get(0),best=ranked.get(ranked.size()-1);
            if((long)best.found()*worst.played()==(long)worst.found()*best.played()) text(content,"Eligible categories are tied by success rate.",13,MUTED);
            else text(content,"Best · "+best.name()+"    /    Hardest · "+worst.name(),13,Color.rgb(210,220,210));
        } else text(content,"More results needed to compare categories.",12,MUTED);
        for(GameHistory.Group group:groups) {
            LinearLayout card=card(content);
            text(card,group.name()+"  ·  "+score(group.found(),group.played()),14,Color.WHITE);
            LinearProgressIndicator bar=new LinearProgressIndicator(this); bar.setMax(group.played()); bar.setProgress(group.found());
            bar.setIndicatorColor(Color.rgb(165,218,179)); bar.setTrackColor(Color.rgb(60,60,60)); bar.setTrackThickness(dp(4)); bar.setTrackCornerRadius(dp(2));
            card.addView(bar,new LinearLayout.LayoutParams(-1,dp(5)));
            text(card,group.found()==0?"No correct answers yet":String.format(Locale.ROOT,"%.1f s average to solve",group.meanMs()/1000),12,MUTED);
        }
    }
    private PlaybackButton playbackButton(LinearLayout parent,String label) {
        PlaybackButton b=new PlaybackButton(this); b.setText(label); b.setAllCaps(false); b.setTextColor(Color.rgb(224,222,218)); b.setTextSize(16); b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16),dp(14),dp(16),dp(14)); b.setMinHeight(dp(56));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(10),0,dp(4)); parent.addView(b,lp); return b;
    }

    private void checkUpdate() {
        if(downloadId!=-1) {
            DownloadManager dm=getSystemService(DownloadManager.class);
            try(Cursor c=dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
                if(c!=null && c.moveToFirst()) {
                    int state=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if(state==DownloadManager.STATUS_SUCCESSFUL) { installDownload(dm); return; }
                    if(state!=DownloadManager.STATUS_FAILED) { toast("Update is downloading. Tap again when it finishes."); return; }
                }
            }
            downloadId=-1; getPreferences(0).edit().remove("download").apply();
        }
        toolbar.getMenu().findItem(6).setEnabled(false);
        io.execute(() -> {
            String outcome; JSONObject release=null;
            HttpURLConnection connection=null;
            try {
                connection=(HttpURLConnection)new URL(REPO+"/releases/latest").openConnection();
                connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept","application/vnd.github+json"); connection.setRequestProperty("User-Agent","PocketBlindtest/"+BuildConfig.VERSION_NAME);
                int code=connection.getResponseCode();
                if(code==404) outcome="No release available yet.";
                else if(code!=200) outcome="GitHub is unavailable (HTTP "+code+"). Try again later.";
                else {
                    try(var stream=connection.getInputStream()) {
                        String json=new String(stream.readNBytes(1048576),StandardCharsets.UTF_8);
                        release=new JSONObject(json);
                    }
                    outcome="You’re up to date.";
                }
            } catch(Exception e) { outcome="Could not check updates. Connect to the internet and try again."; }
            finally { if(connection!=null) connection.disconnect(); }
            JSONObject result=release; String info=outcome;
            main.post(() -> {
                if(isDestroyed()) return;
                toolbar.getMenu().findItem(6).setEnabled(true);
                try {
                    if(result!=null && newer(result.getString("tag_name"),BuildConfig.VERSION_NAME)) {
                        JSONArray assets=result.getJSONArray("assets");
                        for(int i=0;i<assets.length();i++) {
                            JSONObject asset=assets.getJSONObject(i);
                            if(asset.getString("name").equals("PocketBlindtest.apk")) {
                                String url=asset.getString("browser_download_url");
                                if(!url.startsWith("https://github.com/vincedelmas/PocketBlindtest/releases/download/")) throw new IllegalArgumentException();
                                new MaterialAlertDialogBuilder(this).setTitle("Update available: "+result.getString("tag_name"))
                                    .setMessage("Download the signed APK from GitHub? Android will ask before installing.")
                                    .setNegativeButton("Later",null).setPositiveButton("Download",(d,w)->download(url)).show(); return;
                            }
                        }
                        toast("The release APK is not ready yet. Try again shortly."); return;
                    }
                    new MaterialAlertDialogBuilder(this).setTitle("App updates").setMessage(info).setPositiveButton("OK",null).show();
                } catch(Exception e) { toast("Could not read release information."); }
            });
        });
    }
    static boolean newer(String tag,String current) {
        String[] a=tag.replaceFirst("^v","").split("\\."), b=current.split("\\.");
        if(a.length!=3 || b.length!=3) return false;
        for(int i=0;i<3;i++) { int x=Integer.parseInt(a[i]),y=Integer.parseInt(b[i]); if(x!=y) return x>y; }
        return false;
    }
    private void download(String url) {
        try {
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url)).setTitle("Pocket Blindtest update")
                .setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            downloadId=getSystemService(DownloadManager.class).enqueue(request);
            getPreferences(0).edit().putLong("download",downloadId).putString("downloadFrom",BuildConfig.VERSION_NAME).apply();
            toast("Downloading. Tap Check for updates again to install when complete.");
        } catch(Exception e) { toast("Could not start the download. Try again."); }
    }
    private void installDownload(DownloadManager dm) {
        if(!getPackageManager().canRequestPackageInstalls()) {
            new MaterialAlertDialogBuilder(this).setTitle("Allow app updates").setMessage("Enable installation from Pocket Blindtest, then return and tap Check for updates again.")
                .setNegativeButton("Cancel",null).setPositiveButton("Open settings",(d,w)->startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())))).show(); return;
        }
        try {
            Uri uri=dm.getUriForDownloadedFile(downloadId);
            if(uri==null) { toast("Download unavailable. Please retry."); downloadId=-1; getPreferences(0).edit().remove("download").apply(); return; }
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch(Exception e) { toast("Open the completed APK download to install it."); }
    }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent,String value,int size,int color) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(12),0,dp(12)); parent.addView(t); return t;
    }
    private GradientDrawable surface(int color,int radius) {
        GradientDrawable bg=new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg;
    }
    private void quiet(Button b) {
        MaterialButton material=(MaterialButton)b; material.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        material.setTextColor(Color.rgb(200,197,190)); material.setStrokeWidth(0);
    }
    private void primary(Button b) {
        MaterialButton material=(MaterialButton)b;
        material.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(232,216,189)));
        material.setTextColor(Color.rgb(37,30,20)); material.setTextSize(17); material.setMinHeight(dp(60));
    }
    private Button button(LinearLayout parent,String label) {
        MaterialButton b=new MaterialButton(this); b.setText(label); b.setAllCaps(false); b.setTextSize(15);
        b.setPadding(dp(16),dp(12),dp(16),dp(12));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=dp(8); lp.bottomMargin=dp(2); parent.addView(b,lp); return b;
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("screen",screen); out.putString("statsSession",statsSession); saveGame(); }
    @Override protected void onStop() { stopAudio(); saveGame(); super.onStop(); }
    @Override protected void onDestroy() { unregisterReceiver(noisy); cancelSearch(); searchWorker.shutdownNow(); io.execute(history::close); io.shutdown(); super.onDestroy(); }
}
