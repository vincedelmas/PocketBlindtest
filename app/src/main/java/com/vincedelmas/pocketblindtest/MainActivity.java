package com.vincedelmas.pocketblindtest;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

/** Local music never leaves the device. Networking is exclusively user-triggered updates. */
public final class MainActivity extends Activity {
    private static final int[] CLIPS = {150, 500, 2000, 5000};
    private static final String REPO = "https://api.github.com/repos/vincedelmas/PocketBlindtest";
    private static final int BG = Color.rgb(17,18,20), MUTED = Color.rgb(163,166,173), ACCENT = Color.rgb(255,98,79);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<Track> tracks = new ArrayList<>();
    private final ArrayList<Track> bag = new ArrayList<>();
    private final Random random = new Random();
    private LinearLayout root, content, choices;
    private TextView status, counter, message;
    private Button play, skip;
    private EditText search;
    private Track target;
    private int attempt, rounds, wins;
    private boolean finished, loading, playing;
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private AudioManager audio;
    private Bundle restored;
    private long downloadId = -1;
    private final Runnable endClip = this::stopAudio;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { stopAudio(); }
    };

    private record Track(long id, String title, String artist) {
        String label() { return title + " — " + artist; }
        Uri uri() { return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id); }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        restored = state;
        if (state != null) { rounds = state.getInt("rounds"); wins = state.getInt("wins"); }
        downloadId = getPreferences(0).getLong("download", -1);
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
        text(root,"POCKET / BLINDTEST",13,ACCENT).setLetterSpacing(.18f);
        TextView title = text(root,"Know your music?",32,Color.WHITE); title.setTypeface(null,Typeface.BOLD);
        text(root,"Your library. Four chances. No hints.",15,MUTED);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); root.addView(content);
        Button library = button(root,"Refresh music library"); library.setOnClickListener(v -> loadLibrary());
        Button update = button(root,"Check for updates"); update.setOnClickListener(v -> checkUpdate(update));
        text(root,"v"+BuildConfig.VERSION_NAME+" · Offline play · No analytics",12,MUTED);
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) loadLibrary();
        else permissionScreen();
    }

    private void permissionScreen() {
        content.removeAllViews();
        text(content,"Let’s find your songs",24,Color.WHITE);
        text(content,"Allow audio access to use music stored on this phone. Nothing is uploaded.",16,MUTED);
        button(content,"Choose audio permission").setOnClickListener(v -> {
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
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)!=PackageManager.PERMISSION_GRANTED) { permissionScreen(); return; }
        stopAudio(); loading=true; content.removeAllViews(); text(content,"Reading your library…",18,MUTED);
        io.execute(() -> {
            ArrayList<Track> found = new ArrayList<>(); String failure = null;
            String[] columns = {MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST};
            try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
                    MediaStore.Audio.Media.IS_MUSIC+" != 0 AND "+MediaStore.Audio.Media.DURATION+" >= 5000",null,MediaStore.Audio.Media.TITLE+" ASC")) {
                if(c!=null) while(c.moveToNext()) found.add(new Track(c.getLong(0),c.getString(1)==null?"Untitled":c.getString(1),c.getString(2)==null?"Unknown artist":c.getString(2)));
            } catch (Exception e) { failure="Could not read music. Check audio permission, then refresh."; }
            String error=failure;
            main.post(() -> {
                if(isDestroyed()) return;
                loading=false; tracks.clear(); tracks.addAll(found); bag.clear();
                if(error!=null || tracks.isEmpty()) {
                    content.removeAllViews(); text(content,error!=null?error:"No songs found",23,Color.WHITE);
                    text(content,"Copy music files onto your phone, then refresh. Songs must be at least 5 seconds long and indexed as music by Android.",16,MUTED); return;
                }
                if(restored!=null) {
                    long id=restored.getLong("target",-1);
                    target=tracks.stream().filter(t->t.id==id).findFirst().orElse(null);
                    attempt=Math.min(3,restored.getInt("attempt")); finished=restored.getBoolean("finished"); restored=null;
                    if(target!=null) { renderRound(); return; }
                }
                nextRound();
            });
        });
    }
    private void nextRound() {
        stopAudio();
        if(bag.isEmpty()) { bag.addAll(tracks); Collections.shuffle(bag,random);
            if(bag.size()>1 && target!=null && bag.get(bag.size()-1).id==target.id) Collections.swap(bag,0,bag.size()-1);
        }
        target=bag.remove(bag.size()-1); attempt=0; finished=false; renderRound();
    }
    private void renderRound() {
        content.removeAllViews();
        counter=text(content,tracks.size()+" local tracks · "+wins+" / "+rounds+" solved",13,MUTED);
        text(content,"150 ms  /  500 ms  /  2 s  /  5 s",18,ACCENT);
        status=text(content,"",25,Color.WHITE);
        play=button(content,"Play excerpt"); play.setOnClickListener(v -> playClip());
        message=text(content,"Listen, then search for the title or artist.",15,MUTED);
        search=new EditText(this); search.setSingleLine(true); search.setTextColor(Color.WHITE); search.setTextSize(17); search.setHintTextColor(MUTED); search.setHint("Search your library…"); content.addView(search);
        choices=new LinearLayout(this); choices.setOrientation(LinearLayout.VERTICAL); content.addView(choices);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { showMatches(s.toString()); }
            public void afterTextChanged(Editable s) {}
        });
        skip=button(content,"Skip this attempt"); skip.setOnClickListener(v -> miss("Skipped."));
        if(finished) reveal(); else updateAttempt();
    }
    private static String normalize(String s) {
        return Normalizer.normalize(s,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).trim();
    }
    private void showMatches(String query) {
        choices.removeAllViews(); if(finished || query.isBlank()) return;
        String key=normalize(query); int shown=0;
        for(Track t:tracks) if(normalize(t.label()).contains(key)) {
            button(choices,t.label()).setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Your guess")
                .setMessage(t.label()).setNegativeButton("Cancel",null).setPositiveButton("Submit",(d,w)->guess(t)).show());
            if(++shown==8) break;
        }
        if(shown==0) text(choices,"No matching songs",14,MUTED);
    }
    private void guess(Track choice) {
        if(finished) return;
        // Treat duplicate files with identical title and artist as the same answer.
        if(choice.id==target.id || normalize(choice.label()).equals(normalize(target.label()))) {
            wins++; finishRound("You got it! Solved on attempt "+(attempt+1)+".");
        } else miss("Not that one. Try a longer excerpt.");
    }
    private void miss(String feedback) {
        if(finished) return; stopAudio();
        if(attempt==3) finishRound("Out of guesses. Here’s the answer.");
        else { attempt++; search.setText(""); message.setText(feedback); updateAttempt(); }
    }
    private void updateAttempt() {
        status.setText("Attempt "+(attempt+1)+" of 4"); play.setText("▶  Play "+clipLabel());
    }
    private String clipLabel() { return attempt==0?"0.15 seconds":attempt==1?"0.5 seconds":attempt==2?"2 seconds":"5 seconds"; }
    private void finishRound(String feedback) { stopAudio(); finished=true; rounds++; reveal(); message.setText(feedback); }
    private void reveal() {
        status.setText(target.title); message.setText(target.artist);
        counter.setText(tracks.size()+" local tracks · "+wins+" / "+rounds+" solved");
        search.setVisibility(View.GONE); choices.removeAllViews(); skip.setVisibility(View.GONE);
        play.setText("▶  Replay 5-second reveal"); button(content,"Next song →").setOnClickListener(v -> nextRound());
    }
    private void playClip() {
        if(playing || target==null) return;
        stopAudio();
        AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> { if(change<0) stopAudio(); },main).build();
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { message.setText("Audio is busy. Try again."); return; }
        playing=true; play.setEnabled(false);
        MediaPlayer current=new MediaPlayer(); player=current;
        current.setAudioAttributes(attributes);
        current.setOnPreparedListener(p -> {
            if(player!=p) return;
            p.start();
            main.postDelayed(endClip, finished?5000:CLIPS[attempt]);
        });
        current.setOnCompletionListener(p -> stopAudio());
        current.setOnErrorListener((p,what,extra)-> { stopAudio(); message.setText("This file cannot be played. Skip it or try another song."); return true; });
        try { current.setDataSource(this,target.uri()); current.prepareAsync(); }
        catch(Exception e) { stopAudio(); message.setText("Song unavailable. Refresh your library or skip this song."); }
    }
    private void stopAudio() {
        main.removeCallbacks(endClip);
        if(player!=null) { player.release(); player=null; }
        if(focus!=null) { audio.abandonAudioFocusRequest(focus); focus=null; }
        playing=false; if(play!=null) play.setEnabled(true);
    }

    private void checkUpdate(Button button) {
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
        button.setEnabled(false); button.setText("Checking GitHub…");
        io.execute(() -> {
            String outcome; JSONObject release=null;
            HttpURLConnection connection=null;
            try {
                connection=(HttpURLConnection)new URL(REPO+"/releases/latest").openConnection();
                connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept","application/vnd.github+json"); connection.setRequestProperty("User-Agent","PocketBlindtest/"+BuildConfig.VERSION_NAME);
                int code=connection.getResponseCode();
                if(code==404) outcome="No public release available yet. During the private preview, download APKs from GitHub while signed in.";
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
                button.setEnabled(true); button.setText("Check for updates");
                try {
                    if(result!=null && newer(result.getString("tag_name"),BuildConfig.VERSION_NAME)) {
                        JSONArray assets=result.getJSONArray("assets");
                        for(int i=0;i<assets.length();i++) {
                            JSONObject asset=assets.getJSONObject(i);
                            if(asset.getString("name").equals("PocketBlindtest.apk")) {
                                String url=asset.getString("browser_download_url");
                                if(!url.startsWith("https://github.com/vincedelmas/PocketBlindtest/releases/download/")) throw new IllegalArgumentException();
                                new AlertDialog.Builder(this).setTitle("Update available: "+result.getString("tag_name"))
                                    .setMessage("Download the signed APK from GitHub? Android will ask before installing. Your game stays offline.")
                                    .setNegativeButton("Later",null).setPositiveButton("Download",(d,w)->download(url)).show(); return;
                            }
                        }
                        toast("The release APK is not ready yet. Try again shortly."); return;
                    }
                    new AlertDialog.Builder(this).setTitle("App updates").setMessage(info).setPositiveButton("OK",null).show();
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
            getPreferences(0).edit().putLong("download",downloadId).apply();
            toast("Downloading. Tap Check for updates again to install when complete.");
        } catch(Exception e) { toast("Could not start the download. Try again."); }
    }
    private void installDownload(DownloadManager dm) {
        if(!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this).setTitle("Allow app updates").setMessage("Enable installation from Pocket Blindtest, then return and tap Check for updates again.")
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
    private Button button(LinearLayout parent,String label) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(16); b.setGravity(Gravity.CENTER);
        GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.rgb(38,40,44)); bg.setCornerRadius(dp(14)); b.setBackground(bg); b.setPadding(dp(14),dp(14),dp(14),dp(14));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=dp(10); lp.bottomMargin=dp(4); parent.addView(b,lp); return b;
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); if(target!=null) out.putLong("target",target.id);
        out.putInt("attempt",attempt); out.putBoolean("finished",finished); out.putInt("rounds",rounds); out.putInt("wins",wins);
    }
    @Override protected void onStop() { stopAudio(); super.onStop(); }
    @Override protected void onDestroy() { unregisterReceiver(noisy); io.shutdownNow(); super.onDestroy(); }
}
