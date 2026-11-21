package com.vincedelmas.pocketblindtest;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
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

    // Studio Vinyl Dark palette
    private static final int BG = 0xFF0C0D0E;
    private static final int SURFACE = 0xFF16181A;
    private static final int SURFACE_HIGH = 0xFF1E2125;
    private static final int STROKE = 0xFF262A2E;
    private static final int STROKE_STRONG = 0xFF343A40;
    private static final int TEXT = 0xFFF5F1E8;
    private static final int MUTED = 0xFFA8A6A0;
    private static final int DIM = 0xFF6F6E6A;
    private static final int PRIMARY = 0xFFEDE0C8;
    private static final int ON_PRIMARY = 0xFF1C160E;
    private static final int MINT = 0xFF7EE0A3;
    private static final int MINT_BG = 0xFF15291E;
    private static final int MINT_STROKE = 0xFF244A35;
    private static final int ACCENT = 0xFFED4235;
    private static final int ACCENT_BG = 0xFF2B1615;
    private static final int ACCENT_STROKE = 0xFF4F2422;
    private static final int CORAL = 0xFFFFB4AB;

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
    private RecordArtwork currentArtwork;
    private final ExecutorService searchWorker = Executors.newSingleThreadExecutor();
    private Future<?> searchTask;
    private int searchVersion;
    private Runnable searchDebounce;
    private TextView status, message;
    private Button play, skip;
    private GameHistory history;
    private String sessionId, roundId, screen = "home";
    private int sessionSize, screenVersion;
    private boolean artistMode, newArtistMode;
    private final TreeSet<String> sessionGenres = new TreeSet<>(), newGenres = new TreeSet<>();
    private String statsMode = "song", statsGenres = "";
    private final HashSet<String> usedArtists = new HashSet<>();
    private List<GameHistory.Scope> statsScopes = List.of();
    private final HashSet<Long> excluded = new HashSet<>();
    private String statsSession, restoreScreen, restoreStats;
    private final Runnable playbackTick = new Runnable() {
        @Override public void run() {
            if (!playing || player == null || !(play instanceof PlaybackButton)) return;
            ((PlaybackButton) play).setProgress(player.progress());
            main.postDelayed(this, 32);
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
        history = new GameHistory(this);
        if (state != null) {
            restoreScreen = state.getString("screen");
            restoreStats = state.getString("statsSession");
            statsMode = state.getString("statsMode", "song");
            statsGenres = state.getString("statsGenres", "");
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            () -> { if (screen.equals("home")) finish(); else showHome(); }
        );
        downloadId = getPreferences(0).getLong("download", -1);
        if (!BuildConfig.VERSION_NAME.equals(getPreferences(0).getString("downloadFrom", BuildConfig.VERSION_NAME))) {
            downloadId = -1;
            getPreferences(0).edit().remove("download").remove("downloadFrom").apply();
        }
        audio = getSystemService(AudioManager.class);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_EXPORTED);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(28));
        scroll.addView(root);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            root.setPadding(dp(20) + bars.left, dp(8) + bars.top, dp(20) + bars.right, dp(28) + bars.bottom);
            return insets;
        });

        toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Pocket Blindtest");
        toolbar.setTitleTextColor(TEXT);
        toolbar.setContentInsetStartWithNavigation(0);
        toolbar.setOverflowIcon(getDrawable(R.drawable.ic_more_vert));
        toolbar.getMenu().add(1, 1, 0, "Game modes");
        toolbar.getMenu().add(1, 2, 1, "History & statistics");
        toolbar.getMenu().add(2, 3, 2, "Session summary").setEnabled(false);
        toolbar.getMenu().add(2, 4, 3, "Replace unplayable song").setVisible(false);
        toolbar.getMenu().add(3, 5, 4, "Refresh music library");
        toolbar.getMenu().add(3, 6, 5, "Check for updates");
        SpannableString resetLabel = new SpannableString("Reset statistics…");
        resetLabel.setSpan(new android.text.style.ForegroundColorSpan(CORAL), 0, resetLabel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        toolbar.getMenu().add(4, 7, 6, resetLabel);
        toolbar.getMenu().add(5, 8, 7, "Version " + BuildConfig.VERSION_NAME).setEnabled(false);
        toolbar.getMenu().setGroupDividerEnabled(true);
        toolbar.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
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
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, -2));

        if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) loadLibrary();
        else permissionScreen();
    }

    private void updateToolbar(String title, boolean showBack) {
        toolbar.setTitle(title);
        if (showBack) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
            toolbar.setNavigationContentDescription("Back to game modes");
            toolbar.setNavigationOnClickListener(v -> showHome());
        } else {
            toolbar.setNavigationIcon(null);
            toolbar.setNavigationOnClickListener(null);
        }
    }

    private void permissionScreen() {
        updateToolbar("Pocket Blindtest", false);
        content.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(8), dp(40), dp(8), dp(24));
        content.addView(box, new LinearLayout.LayoutParams(-1, -2));

        addArtwork(box, new RecordArtwork(this, false), 156);
        TextView heading = label(box, "Music access", 26, TEXT, true);
        heading.setGravity(Gravity.CENTER);
        setMargins(heading, 0, dp(24), 0, dp(8));

        TextView desc = label(box, "Allow access to the local songs stored on your phone. Your music stays completely offline.", 15, MUTED, false);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(dp(3), 1f);
        setMargins(desc, dp(8), 0, dp(8), dp(28));

        Button allow = button(box, "Allow music access");
        primary(allow);
        allow.setOnClickListener(v -> {
            if (getPreferences(0).getBoolean("asked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_AUDIO)) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } else {
                getPreferences(0).edit().putBoolean("asked", true).apply();
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, 1);
            }
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) loadLibrary();
        else permissionScreen();
    }

    private void loadLibrary() {
        if (loading) return;
        stopAudio(); saveGame(); screenVersion++;
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) { permissionScreen(); return; }
        stopAudio(); loading = true; currentArtwork = null;
        updateToolbar("Pocket Blindtest", false);
        content.removeAllViews();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(96), 0, dp(32));
        content.addView(box, new LinearLayout.LayoutParams(-1, -2));

        CircularProgressIndicator spinner = new CircularProgressIndicator(this);
        spinner.setIndeterminate(true);
        spinner.setIndicatorColor(PRIMARY);
        spinner.setTrackColor(SURFACE_HIGH);
        box.addView(spinner);

        TextView scanning = label(box, "Scanning your music library…", 15, MUTED, false);
        setMargins(scanning, 0, dp(18), 0, 0);

        io.execute(() -> {
            ArrayList<Track> found = new ArrayList<>(); String failure = null;
            String[] columns = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.GENRE, MediaStore.Audio.Media.YEAR};
            try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
                    MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " + MediaStore.Audio.Media.DURATION + " >= " + CLIPS[CLIPS.length - 1], null, MediaStore.Audio.Media.TITLE + " ASC")) {
                if (c != null) while (c.moveToNext()) found.add(new Track(c.getLong(0), c.getString(1) == null ? "Untitled" : c.getString(1), c.getString(2) == null ? "Unknown artist" : c.getString(2), genre(c.getString(3)), year(c.getInt(4))));
            } catch (Exception e) { failure = "Could not read music. Check audio permission, then refresh."; }
            String error = failure;
            main.post(() -> {
                if (isDestroyed()) return;
                loading = false; tracks.clear(); tracks.addAll(found); bag.clear();
                if (error != null || tracks.isEmpty()) {
                    content.removeAllViews();
                    LinearLayout empty = new LinearLayout(this);
                    empty.setOrientation(LinearLayout.VERTICAL);
                    empty.setGravity(Gravity.CENTER_HORIZONTAL);
                    empty.setPadding(dp(8), dp(48), dp(8), dp(24));
                    content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
                    addArtwork(empty, new RecordArtwork(this, false), 144);
                    TextView title = label(empty, error != null ? "Library unavailable" : "No songs found", 24, TEXT, true);
                    title.setGravity(Gravity.CENTER);
                    setMargins(title, 0, dp(22), 0, dp(8));
                    TextView msg = label(empty, error != null ? error : "Copy music files onto your phone, then refresh. Songs must be at least 16 seconds long and indexed as music by Android.", 15, MUTED, false);
                    msg.setGravity(Gravity.CENTER);
                    msg.setLineSpacing(dp(3), 1f);
                    setMargins(msg, dp(8), 0, dp(8), dp(24));
                    Button retry = button(empty, "Refresh music library");
                    primary(retry);
                    retry.setOnClickListener(v -> loadLibrary());
                    return;
                }
                restoreGame();
                String page = restoreScreen; restoreScreen = null;
                if ("stats".equals(page)) showStats(restoreStats);
                else if ("round".equals(page) && target != null) renderRound();
                else showHome();
            });
        });
    }

    private void nextRound() {
        stopAudio();
        if (sessionSize > 0 && rounds >= sessionSize) { showStats(sessionId); return; }
        if (bag.isEmpty() && sessionSize > 0 && rounds > 0) { exhausted = true; target = null; saveGame(); toast("No more playable songs in this session."); showStats(sessionId); return; }
        if (bag.isEmpty()) { bag.addAll(sessionPool()); Collections.shuffle(bag, random);
            if (bag.isEmpty()) { exhausted = true; target = null; saveGame(); toast("No playable songs remain. Start a new session to try them again."); showStats(sessionId); return; }
            if (bag.size() > 1 && target != null) {
                int last = bag.size() - 1;
                for (int i = 0; i < last; i++) if (artistMode ? !artistKey(bag.get(i).artist).equals(artistKey(target.artist)) : bag.get(i).id != target.id) {
                    boolean repeat = artistMode ? artistKey(bag.get(last).artist).equals(artistKey(target.artist)) : bag.get(last).id == target.id;
                    if (repeat) Collections.swap(bag, i, last);
                    break;
                }
            }
        }
        target = bag.remove(bag.size() - 1);
        if (artistMode) { String key = artistKey(target.artist); bag.removeIf(t -> artistKey(t.artist).equals(key)); }
        roundId = UUID.randomUUID().toString(); attempt = 0; finished = false; solved = false; saveGame(); renderRound();
    }

    private void renderRound() {
        cancelSearch(); screen = "round"; screenVersion++;
        toolbar.getMenu().findItem(4).setVisible(target != null);
        toolbar.getMenu().findItem(3).setEnabled(sessionId != null);
        if (finished) { reveal(); return; }
        updateToolbar(artistMode ? "Artist mode" : "Song mode", true);
        content.animate().cancel(); content.setAlpha(1f); content.setTranslationY(0);
        content.removeAllViews();
        content.post(() -> ((ScrollView) root.getParent()).smoothScrollTo(0, 0));

        // Top compact status bar: Round pill + live score pill
        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER);
        content.addView(topBar, new LinearLayout.LayoutParams(-1, -2));
        setMargins(topBar, 0, dp(4), 0, dp(18));

        String roundLabel = (artistMode ? "ARTIST " : "SONG ") + (rounds + 1) + (sessionSize > 0 ? " / " + sessionSize : "  ·  INFINITE");
        TextView roundPill = pill(topBar, roundLabel, SURFACE_HIGH, STROKE, MUTED);
        roundPill.setLetterSpacing(0.10f);
        if (rounds > 0) {
            TextView scorePill = pill(topBar, wins + " / " + rounds + " solved", MINT_BG, MINT_STROKE, MINT);
            setMargins(scorePill, dp(8), 0, 0, 0);
        }

        // Vinyl record centerpiece
        RecordArtwork artwork = new RecordArtwork(this, false);
        currentArtwork = artwork;
        addArtwork(content, artwork, 200);

        status = label(content, "", 24, TEXT, true);
        status.setGravity(Gravity.CENTER);
        setMargins(status, 0, dp(16), 0, dp(2));

        TextView detail = label(content, artistMode ? "Listen closely and identify the artist" : "Listen closely and identify the track", 13.5f, MUTED, false);
        detail.setGravity(Gravity.CENTER);
        setMargins(detail, 0, 0, 0, dp(16));

        // 6-stage clip timeline
        stages = new LinearLayout(this);
        content.addView(stages, new LinearLayout.LayoutParams(-1, -2));
        setMargins(stages, 0, 0, 0, dp(14));
        for (int i = 0; i < CLIPS.length; i++) {
            TextView stage = new TextView(this);
            stage.setText(duration(CLIPS[i]) + "s");
            stage.setTextSize(12f);
            stage.setGravity(Gravity.CENTER);
            stage.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
            lp.setMargins(i == 0 ? 0 : dp(3), 0, i == CLIPS.length - 1 ? 0 : dp(3), 0);
            stages.addView(stage, lp);
        }

        // Primary playback button
        play = playbackButton(content, "▶   Play " + clipLabel());
        primary(play);
        play.setOnClickListener(v -> playClip());

        // Feedback line
        message = label(content, "Attempt 1 of " + CLIPS.length, 13f, MUTED, false);
        message.setGravity(Gravity.CENTER);
        setMargins(message, 0, dp(10), 0, dp(14));

        // Sleek rounded search input
        TextInputLayout searchBox = new TextInputLayout(this);
        searchBox.setHint(artistMode ? "Search artists…" : "Search song title or artist…");
        searchBox.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        searchBox.setBoxBackgroundColor(SURFACE);
        float boxRadius = dp(18);
        searchBox.setBoxCornerRadii(boxRadius, boxRadius, boxRadius, boxRadius);
        searchBox.setBoxStrokeColor(PRIMARY);
        searchBox.setDefaultHintTextColor(ColorStateList.valueOf(MUTED));
        searchBox.setHintTextColor(ColorStateList.valueOf(PRIMARY));
        searchBox.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        searchBox.setEndIconTintList(ColorStateList.valueOf(MUTED));

        search = new TextInputEditText(searchBox.getContext());
        search.setSingleLine(true);
        search.setTextSize(15.5f);
        search.setTextColor(TEXT);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        searchBox.addView(search, new LinearLayout.LayoutParams(-1, -2));
        content.addView(searchBox, new LinearLayout.LayoutParams(-1, -2));

        matchCount = label(content, "Type to search your library", 12f, DIM, false);
        setMargins(matchCount, dp(4), dp(8), dp(4), dp(6));

        choices = new RecyclerView(this);
        choices.setLayoutManager(new LinearLayoutManager(this));
        choices.setItemAnimator(null);
        songChoices = new SongChoices(this::guess, artistMode);
        choices.setAdapter(songChoices);
        choices.setNestedScrollingEnabled(true);
        // Keep the viewport bounded inside the outer page: never measure every matching row.
        content.addView(choices, new LinearLayout.LayoutParams(-1, dp(340)));
        choices.setVisibility(View.GONE);

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            public void onTextChanged(CharSequence value, int start, int before, int count) { showMatches(value.toString()); }
            public void afterTextChanged(Editable value) {}
        });

        skip = button(content, "Skip to next clip   →");
        outlined(skip);
        setMargins(skip, 0, dp(10), 0, dp(4));
        skip.setOnClickListener(v -> miss("Skipped."));
        updateAttempt();
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private void cancelSearch() {
        searchVersion++;
        if (searchDebounce != null) main.removeCallbacks(searchDebounce);
        if (searchTask != null) searchTask.cancel(true);
    }

    private void showMatches(String query) {
        cancelSearch(); final int version = searchVersion;
        songChoices.show(List.of()); choices.setVisibility(View.GONE);
        if (finished || query.isBlank()) { matchCount.setText("Type to search your library"); matchCount.setTextColor(DIM); return; }
        matchCount.setText("Searching…"); matchCount.setTextColor(MUTED);
        List<Track> library = eligibleTracks(artistMode, sessionGenres);
        final boolean artists = artistMode;
        searchDebounce = () -> searchTask = searchWorker.submit(() -> {
            String key = normalize(query); ArrayList<Track> matches = new ArrayList<>(); HashSet<String> seen = new HashSet<>();
            for (Track song : library) {
                if (Thread.currentThread().isInterrupted()) return;
                if (normalize(artists ? song.artist : song.label()).contains(key) && (!artists || seen.add(artistKey(song.artist)))) matches.add(song);
            }
            main.post(() -> {
                if (isDestroyed() || searchVersion != version || !screen.equals("round") || finished) return;
                songChoices.show(matches); choices.setVisibility(matches.isEmpty() ? View.GONE : View.VISIBLE);
                matchCount.setTextColor(matches.isEmpty() ? CORAL : MUTED);
                matchCount.setText(matches.isEmpty() ? "No matching " + (artists ? "artists" : "songs") : matches.size() + (artists ? " artists" : " songs") + " · Scroll to browse");
                choices.scrollToPosition(0);
            });
        });
        main.postDelayed(searchDebounce, 150);
    }

    private void guess(Track choice) {
        if (finished) return;
        // Treat duplicate files with identical title and artist as the same answer.
        if (artistMode ? artistKey(choice.artist).equals(artistKey(target.artist)) :
                choice.id == target.id || normalize(choice.label()).equals(normalize(target.label()))) {
            solved = true; finishRound();
        } else miss("Incorrect. A longer clip is unlocked.");
    }

    private void miss(String feedback) {
        if (finished) return; stopAudio();
        if (attempt == CLIPS.length - 1) finishRound();
        else {
            attempt++;
            search.setText("");
            message.setText(feedback + "  ·  Attempt " + (attempt + 1) + " of " + CLIPS.length);
            message.setTextColor(feedback.startsWith("Incorrect") ? CORAL : MUTED);
            updateAttempt(); saveGame();
        }
    }

    private void updateAttempt() {
        status.setText(duration(CLIPS[attempt]) + " second" + (CLIPS[attempt] == 1000 ? "" : "s"));
        play.setText("▶   Play " + clipLabel());
        if (attempt == 0) {
            message.setText("Attempt 1 of " + CLIPS.length);
            message.setTextColor(MUTED);
        }
        if (skip != null) {
            skip.setText(attempt == CLIPS.length - 1 ? "Give up & reveal   →" : "Skip to next clip   →");
        }
        for (int i = 0; i < stages.getChildCount(); i++) {
            TextView stage = (TextView) stages.getChildAt(i);
            if (i == attempt) {
                stage.setTextColor(ON_PRIMARY);
                stage.setBackground(pillBg(PRIMARY, PRIMARY, 12));
            } else if (i < attempt) {
                stage.setTextColor(CORAL);
                stage.setBackground(pillBg(ACCENT_BG, ACCENT_STROKE, 12));
            } else {
                stage.setTextColor(DIM);
                stage.setBackground(pillBg(SURFACE, STROKE, 12));
            }
        }
    }

    private static String duration(int ms) { return java.math.BigDecimal.valueOf(ms, 3).stripTrailingZeros().toPlainString(); }
    private String clipLabel() { return duration(CLIPS[attempt]) + " s"; }

    private void finishRound() {
        stopAudio();
        try { history.record(roundId, sessionId, target.id, target.title, target.artist, target.genre, target.year, solved, CLIPS[attempt], attempt + 1); }
        catch (Exception e) { solved = false; message.setText("Could not save the result. Try submitting again."); return; }
        finished = true; rounds++; if (solved) wins++; if (artistMode) usedArtists.add(artistKey(target.artist)); saveGame();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(content.getWindowToken(), 0);
        reveal();
    }

    private void reveal() {
        cancelSearch(); screen = "round"; screenVersion++; currentArtwork = null;
        toolbar.getMenu().findItem(4).setVisible(target != null);
        toolbar.getMenu().findItem(3).setEnabled(sessionId != null);
        updateToolbar(artistMode ? "Artist mode" : "Song mode", true);
        content.animate().cancel();
        content.removeAllViews();
        content.post(() -> ((ScrollView) root.getParent()).smoothScrollTo(0, 0));

        // Status pill
        LinearLayout badgeWrap = new LinearLayout(this);
        badgeWrap.setGravity(Gravity.CENTER);
        content.addView(badgeWrap, new LinearLayout.LayoutParams(-1, -2));
        setMargins(badgeWrap, 0, dp(8), 0, dp(20));

        TextView result = pill(badgeWrap,
            solved ? "✓  SOLVED IN " + duration(CLIPS[attempt]) + "s" : "✕  NOT FOUND",
            solved ? MINT_BG : ACCENT_BG,
            solved ? MINT_STROKE : ACCENT_STROKE,
            solved ? MINT : CORAL);
        result.setLetterSpacing(0.12f);
        result.setPadding(dp(16), dp(8), dp(16), dp(8));

        // Cover artwork
        RecordArtwork artwork = new RecordArtwork(this, true);
        addArtwork(content, artwork, 240);

        // Track title & artist
        TextView title = label(content, target.title, 26, TEXT, true);
        title.setGravity(Gravity.CENTER);
        setMargins(title, dp(4), dp(22), dp(4), dp(4));

        TextView artist = label(content, target.artist, 17, MUTED, false);
        artist.setGravity(Gravity.CENTER);
        setMargins(artist, dp(4), 0, dp(4), dp(10));

        // Metadata chip (Genre · Year) when known
        ArrayList<String> metaParts = new ArrayList<>();
        if (!"Unknown".equals(target.genre)) metaParts.add(target.genre);
        if (target.year > 0) metaParts.add(String.valueOf(target.year));
        if (!metaParts.isEmpty()) {
            LinearLayout metaWrap = new LinearLayout(this);
            metaWrap.setGravity(Gravity.CENTER);
            content.addView(metaWrap, new LinearLayout.LayoutParams(-1, -2));
            setMargins(metaWrap, 0, 0, 0, dp(16));
            pill(metaWrap, String.join("  ·  ", metaParts), SURFACE_HIGH, STROKE, MUTED);
        } else {
            setMargins(artist, dp(4), 0, dp(4), dp(16));
        }

        // 6-segment attempt indicator bar
        LinearLayout resultRow = new LinearLayout(this);
        resultRow.setGravity(Gravity.CENTER);
        content.addView(resultRow, new LinearLayout.LayoutParams(-1, -2));
        setMargins(resultRow, 0, 0, 0, dp(8));
        for (int i = 0; i < CLIPS.length; i++) {
            View dot = new View(this);
            int color = (i == attempt && solved) ? MINT : (i <= attempt ? ACCENT : SURFACE_HIGH);
            dot.setBackground(surface(color, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(6));
            lp.setMargins(dp(3), 0, dp(3), 0);
            resultRow.addView(dot, lp);
        }

        TextView attemptCaption = label(content, solved ? "Found on attempt " + (attempt + 1) + " of " + CLIPS.length : "All " + CLIPS.length + " attempts used", 13f, MUTED, false);
        attemptCaption.setGravity(Gravity.CENTER);
        setMargins(attemptCaption, 0, 0, 0, dp(18));

        // Primary CTA
        Button next = button(content, sessionSize > 0 && rounds >= sessionSize ? "See session summary   →" : "Next song   →");
        primary(next);
        next.setOnClickListener(v -> nextRound());

        // Replay button
        play = playbackButton(content, "▶   Replay " + duration(CLIPS[CLIPS.length - 1]) + " s");
        tonal(play);
        play.setOnClickListener(v -> playClip());

        // Side-by-side secondary actions
        LinearLayout actions = new LinearLayout(this);
        content.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        setMargins(actions, 0, dp(10), 0, dp(14));

        Button listen = compactButton(actions, "Open in player ↗", true);
        listen.setOnClickListener(v -> openSong());
        Button statistics = compactButton(actions, "Session stats →", false);
        statistics.setOnClickListener(v -> showStats(sessionId));

        message = label(content, score(wins, rounds) + " this session", 12.5f, DIM, false);
        message.setGravity(Gravity.CENTER);
        setMargins(message, 0, dp(2), 0, dp(8));

        content.setAlpha(0f); content.setTranslationY(dp(12));
        content.animate().alpha(1f).translationY(0).setDuration(240).start();
        loadArtwork(artwork, target);
    }

    private void openSong() {
        if (target == null) return;
        stopAudio(); saveGame();
        Intent listen = new Intent(Intent.ACTION_VIEW).setDataAndType(target.uri(), "audio/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(listen, "Listen to the full song")); }
        catch (ActivityNotFoundException e) { toast("Install a music player to open this song."); }
        catch (SecurityException e) { toast("This song is no longer accessible. Refresh your music library."); }
    }

    // Keep artist tags intact: '&' and commas can be part of a band's name.
    private static String artistKey(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean knownArtist(String value) {
        String key = artistKey(value);
        return !key.isEmpty() && !key.equals("<unknown>") && !key.equals("unknown") && !key.equals("unknown artist");
    }

    private List<Track> eligibleTracks(boolean artists, Set<String> genres) {
        ArrayList<Track> pool = new ArrayList<>();
        for (Track t : tracks) if ((genres.isEmpty() || genres.contains(t.genre.toLowerCase(Locale.ROOT))) && (!artists || knownArtist(t.artist))) pool.add(t);
        return pool;
    }

    private List<Track> sessionPool() {
        ArrayList<Track> pool = new ArrayList<>();
        for (Track t : eligibleTracks(artistMode, sessionGenres))
            if (!excluded.contains(t.id) && (!artistMode || sessionSize == 0 || !usedArtists.contains(artistKey(t.artist)))) pool.add(t);
        return pool;
    }

    private int poolCount(boolean artists, Set<String> genres) {
        List<Track> pool = eligibleTracks(artists, genres);
        return artists ? (int) pool.stream().map(t -> artistKey(t.artist)).distinct().count() : pool.size();
    }

    private static String genreScope(Set<String> genres) {
        return genres.isEmpty() ? "" : new JSONArray(new TreeSet<>(genres)).toString();
    }

    private static String genreSummary(Set<String> genres) {
        if (genres.isEmpty()) return "All genres";
        ArrayList<String> list = new ArrayList<>();
        for (String g : genres) list.add(g.isEmpty() ? g : g.substring(0, 1).toUpperCase(Locale.ROOT) + g.substring(1));
        return String.join(", ", list);
    }

    private static String scopeLabel(String mode, String genres) {
        String label = "All genres";
        if (!genres.isEmpty()) try {
            JSONArray values = new JSONArray(genres); ArrayList<String> names = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) names.add(values.getString(i));
            label = String.join(", ", names);
        } catch (JSONException ignored) { label = "Selected genres"; }
        return (mode.equals("artist") ? "Artist" : "Song") + " · " + label;
    }

    private void chooseGenres() {
        TreeMap<String, String> names = new TreeMap<>();
        for (Track t : tracks) names.putIfAbsent(t.genre.toLowerCase(Locale.ROOT), t.genre);
        String[] keys = names.keySet().toArray(new String[0]), labels = names.values().toArray(new String[0]);
        boolean[] checked = new boolean[keys.length];
        TreeSet<String> selection = new TreeSet<>(newGenres);
        for (int i = 0; i < keys.length; i++) checked[i] = selection.contains(keys[i]);
        new MaterialAlertDialogBuilder(this).setTitle("Genres on this phone")
            .setMultiChoiceItems(labels, checked, (d, index, on) -> { if (on) selection.add(keys[index]); else selection.remove(keys[index]); })
            .setNeutralButton("All genres", (d, w) -> { newGenres.clear(); showHome(); })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", (d, w) -> { newGenres.clear(); newGenres.addAll(selection); showHome(); }).show();
    }

    private void loadArtwork(RecordArtwork artwork, Track song) {
        io.execute(() -> {
            Bitmap image = null;
            try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
                metadata.setDataSource(this, song.uri());
                byte[] bytes = metadata.getEmbeddedPicture();
                if (bytes != null && bytes.length <= 16 * 1024 * 1024) {
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 768) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                }
            } catch (Exception ignored) { /* The record illustration is also the no-artwork state. */ }
            Bitmap result = image;
            main.post(() -> {
                if (!isDestroyed() && finished && target != null && target.id == song.id && artwork.isAttachedToWindow()) artwork.setArtwork(result);
                else if (result != null) result.recycle();
            });
        });
    }

    private void addArtwork(LinearLayout parent, RecordArtwork artwork, int size) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        parent.addView(artwork, lp);
    }

    private void playClip() {
        if (playing) { stopAudio(); return; }
        if (target == null) return;
        stopAudio();
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> { if (change < 0) stopAudio(); }, main).build();
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { message.setText("Audio is busy. Try again."); return; }
        playing = true; play.setEnabled(false); play.setText("Preparing…");
        ClipAudio current = new ClipAudio(this, main); player = current;
        current.play(target.uri(), finished ? CLIPS[CLIPS.length - 1] : CLIPS[attempt], attributes,
            () -> {
                if (player == current) {
                    play.setEnabled(true);
                    play.setText("❚❚   Playing…");
                    if (currentArtwork != null) currentArtwork.setSpinning(true);
                    main.post(playbackTick);
                }
            },
            () -> { if (player == current) stopAudio(); },
            () -> { if (player == current) { stopAudio(); message.setText("Cannot play this song. Use Replace unplayable song in the menu."); message.setTextColor(CORAL); } });
    }

    private void stopAudio() {
        main.removeCallbacks(playbackTick);
        if (currentArtwork != null) currentArtwork.setSpinning(false);
        if (play instanceof PlaybackButton) ((PlaybackButton) play).setProgress(0);
        if (player != null) { player.close(); player = null; }
        if (focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
        playing = false;
        if (play != null) {
            play.setEnabled(true);
            play.setText(finished ? "▶   Replay " + duration(CLIPS[CLIPS.length - 1]) + " s" : "▶   Play " + clipLabel());
        }
    }

    private static String genre(String value) {
        return value == null || value.isBlank() || value.trim().equalsIgnoreCase("<unknown>") || value.trim().equalsIgnoreCase("unknown") ? "Unknown" : value.trim();
    }

    private static int year(int value) { return value >= 1000 && value <= 2100 ? value : 0; }

    private static String score(int found, int played) {
        return found + " / " + played + " solved · " + (played == 0 ? "—" : Math.round(100f * found / played) + "%");
    }

    private void beginPage(String page) {
        stopAudio(); cancelSearch(); currentArtwork = null;
        toolbar.getMenu().findItem(4).setVisible(false);
        toolbar.getMenu().findItem(3).setEnabled(sessionId != null);
        play = null; skip = null; screen = page; screenVersion++;
        content.animate().cancel(); content.setAlpha(1); content.setTranslationY(0); content.removeAllViews();
        content.post(() -> ((ScrollView) root.getParent()).smoothScrollTo(0, 0));
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(content.getWindowToken(), 0);
    }

    private void showHome() {
        beginPage("home");
        updateToolbar("Pocket Blindtest", false);

        // Library pill badge
        LinearLayout pillRow = new LinearLayout(this);
        content.addView(pillRow, new LinearLayout.LayoutParams(-1, -2));
        setMargins(pillRow, 0, dp(10), 0, dp(10));
        TextView libBadge = pill(pillRow, "♪  " + tracks.size() + " SONGS READY", SURFACE_HIGH, STROKE, MUTED);
        libBadge.setLetterSpacing(0.08f);

        // Hero headline
        TextView homeTitle = label(content, "Play your\nmusic library.", 34, TEXT, true);
        homeTitle.setLineSpacing(dp(2), 1f);
        setMargins(homeTitle, 0, 0, 0, dp(22));

        // Active / Resumable session highlight card
        if (sessionId != null) {
            MaterialCardView resumeCard = makeCard(content, 0xFF1A1D1B, MINT_STROKE, 22);
            setMargins(resumeCard, 0, 0, 0, dp(22));
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setPadding(dp(18), dp(16), dp(18), dp(16));
            resumeCard.addView(inner);

            LinearLayout headerRow = new LinearLayout(this);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            inner.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

            TextView tag = label(headerRow, sessionEnded() ? "SESSION COMPLETE" : "ACTIVE SESSION", 11f, MINT, true);
            tag.setLetterSpacing(0.12f);
            headerRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            TextView scopeTag = label(headerRow, scopeLabel(artistMode ? "artist" : "song", genreScope(sessionGenres)), 12f, MUTED, false);
            scopeTag.setMaxLines(1);
            scopeTag.setEllipsize(TextUtils.TruncateAt.END);

            TextView resumeTitle = label(inner, sessionSize == 0 ? "Infinite mode" : sessionSize + "-round session", 20f, TEXT, true);
            setMargins(resumeTitle, 0, dp(6), 0, dp(4));

            TextView resumeMeta = label(inner, score(wins, rounds) + (sessionSize > 0 ? "  ·  Round " + Math.min(rounds + (sessionEnded() ? 0 : 1), sessionSize) + " of " + sessionSize : ""), 13.5f, MUTED, false);
            setMargins(resumeMeta, 0, 0, 0, dp(12));

            if (sessionSize > 0) {
                LinearProgressIndicator progress = new LinearProgressIndicator(this);
                progress.setMax(sessionSize);
                progress.setProgress(Math.min(rounds, sessionSize));
                progress.setIndicatorColor(MINT);
                progress.setTrackColor(0xFF28332C);
                progress.setTrackThickness(dp(5));
                progress.setTrackCornerRadius(dp(3));
                LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
                barLp.bottomMargin = dp(14);
                inner.addView(progress, barLp);
            }

            Button resume = button(inner, sessionEnded() ? "View session summary   →" : "Continue session   →");
            primary(resume);
            setMargins(resume, 0, 0, 0, 0);
            resume.setOnClickListener(v -> {
                if (sessionEnded()) showStats(sessionId);
                else if (target != null) renderRound();
                else nextRound();
            });
        }

        // Section header
        TextView section = label(content, "NEW SESSION", 11.5f, MUTED, true);
        section.setLetterSpacing(0.14f);
        setMargins(section, dp(2), dp(2), 0, dp(10));

        // Inline 2-segment Song / Artist toggle
        LinearLayout segmentContainer = new LinearLayout(this);
        segmentContainer.setBackground(pillBg(SURFACE, STROKE, 18));
        segmentContainer.setPadding(dp(4), dp(4), dp(4), dp(4));
        content.addView(segmentContainer, new LinearLayout.LayoutParams(-1, dp(50)));
        setMargins(segmentContainer, 0, 0, 0, dp(10));

        addSegment(segmentContainer, "Guess Song", !newArtistMode, () -> { if (newArtistMode) { newArtistMode = false; showHome(); } });
        addSegment(segmentContainer, "Guess Artist", newArtistMode, () -> { if (!newArtistMode) { newArtistMode = true; showHome(); } });

        // Genre filter selector card
        int eligible = poolCount(newArtistMode, newGenres);
        String unit = newArtistMode ? "artists" : "songs";

        MaterialCardView genreCard = makeCard(content, SURFACE, STROKE, 18);
        genreCard.setClickable(true); genreCard.setFocusable(true);
        genreCard.setRippleColor(ColorStateList.valueOf(0x1CEDE0C8));
        setMargins(genreCard, 0, 0, 0, dp(14));
        genreCard.setOnClickListener(v -> chooseGenres());

        LinearLayout genreRow = new LinearLayout(this);
        genreRow.setGravity(Gravity.CENTER_VERTICAL);
        genreRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        genreCard.addView(genreRow);

        LinearLayout genreLabels = new LinearLayout(this);
        genreLabels.setOrientation(LinearLayout.VERTICAL);
        genreRow.addView(genreLabels, new LinearLayout.LayoutParams(0, -2, 1));

        TextView genreEyebrow = label(genreLabels, "GENRES", 10.5f, DIM, true);
        genreEyebrow.setLetterSpacing(0.12f);
        TextView genreValue = label(genreLabels, genreSummary(newGenres), 15.5f, TEXT, true);
        genreValue.setMaxLines(1);
        genreValue.setEllipsize(TextUtils.TruncateAt.END);
        setMargins(genreValue, 0, dp(2), dp(8), 0);

        TextView poolBadge = pill(genreRow, eligible + " " + unit, SURFACE_HIGH, STROKE_STRONG, eligible > 0 ? PRIMARY : CORAL);
        setMargins(poolBadge, 0, 0, dp(8), 0);
        TextView chevron = label(genreRow, "›", 22f, MUTED, false);
        chevron.setPadding(0, 0, 0, dp(2));

        // 2x2 Session length grid
        LinearLayout row = null;
        int[] counts = {5, 10, 15, 20};
        for (int i = 0; i < counts.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                content.addView(row, new LinearLayout.LayoutParams(-1, -2));
                setMargins(row, 0, 0, 0, dp(8));
            }
            int count = counts[i];
            boolean available = eligible >= count;
            MaterialCardView mode = new MaterialCardView(this);
            mode.setRadius(dp(20));
            mode.setCardElevation(0);
            mode.setCardBackgroundColor(SURFACE);
            mode.setStrokeColor(available ? STROKE_STRONG : STROKE);
            mode.setStrokeWidth(dp(1));
            mode.setRippleColor(ColorStateList.valueOf(0x1CEDE0C8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(i % 2 == 0 ? 0 : dp(4), 0, i % 2 == 0 ? dp(4) : 0, 0);
            row.addView(mode, lp);

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(18), dp(14), dp(16), dp(16));
            mode.addView(labels);

            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            labels.addView(top, new LinearLayout.LayoutParams(-1, -2));

            TextView number = label(top, String.format(Locale.ROOT, "%02d", count), 34f, PRIMARY, false);
            number.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            label(top, "↗", 16f, DIM, false);

            TextView caption = label(labels, newArtistMode ? "ARTISTS" : "SONGS", 11f, MUTED, true);
            caption.setLetterSpacing(0.12f);
            setMargins(caption, 0, dp(4), 0, 0);

            mode.setEnabled(available);
            mode.setAlpha(available ? 1f : 0.36f);
            mode.setClickable(available);
            mode.setFocusable(available);
            mode.setContentDescription(count + "-" + unit + " session" + (available ? "" : ". Requires " + count + " matching " + unit));
            if (available) mode.setOnClickListener(v -> startSession(count, newArtistMode, newGenres));
        }

        // Full-width Infinite mode card
        boolean infAvailable = eligible > 0;
        MaterialCardView infiniteCard = makeCard(content, SURFACE, infAvailable ? STROKE_STRONG : STROKE, 20);
        infiniteCard.setRippleColor(ColorStateList.valueOf(0x1CEDE0C8));
        infiniteCard.setEnabled(infAvailable);
        infiniteCard.setAlpha(infAvailable ? 1f : 0.36f);
        infiniteCard.setClickable(infAvailable);
        infiniteCard.setFocusable(infAvailable);
        setMargins(infiniteCard, 0, 0, 0, dp(16));
        if (infAvailable) infiniteCard.setOnClickListener(v -> startSession(0, newArtistMode, newGenres));

        LinearLayout infRow = new LinearLayout(this);
        infRow.setGravity(Gravity.CENTER_VERTICAL);
        infRow.setPadding(dp(18), dp(16), dp(18), dp(16));
        infiniteCard.addView(infRow);

        TextView infSymbol = label(infRow, "∞", 28f, PRIMARY, false);
        setMargins(infSymbol, 0, 0, dp(16), 0);

        LinearLayout infText = new LinearLayout(this);
        infText.setOrientation(LinearLayout.VERTICAL);
        infRow.addView(infText, new LinearLayout.LayoutParams(0, -2, 1));
        label(infText, "Infinite mode", 16.5f, TEXT, true);
        TextView infSub = label(infText, "Endless shuffled rounds until you stop", 12.5f, MUTED, false);
        setMargins(infSub, 0, dp(2), 0, 0);

        label(infRow, "→", 18f, MUTED, false);

        // History & statistics card
        MaterialCardView statsCard = makeCard(content, 0xFF121416, STROKE, 18);
        statsCard.setClickable(true); statsCard.setFocusable(true);
        statsCard.setRippleColor(ColorStateList.valueOf(0x1CEDE0C8));
        statsCard.setOnClickListener(v -> showStats(null));

        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setGravity(Gravity.CENTER_VERTICAL);
        statsRow.setPadding(dp(18), dp(15), dp(18), dp(15));
        statsCard.addView(statsRow);

        TextView statsTitle = label(statsRow, "History & statistics", 15f, TEXT, true);
        statsTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        label(statsRow, "→", 16f, MUTED, false);
    }

    private void addSegment(LinearLayout parent, String title, boolean active, Runnable onSelect) {
        TextView seg = new TextView(this);
        seg.setText(title);
        seg.setTextSize(14f);
        seg.setGravity(Gravity.CENTER);
        seg.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        seg.setTextColor(active ? ON_PRIMARY : MUTED);
        seg.setBackground(active ? pillBg(PRIMARY, PRIMARY, 14) : null);
        seg.setClickable(true);
        seg.setFocusable(true);
        seg.setOnClickListener(v -> onSelect.run());
        parent.addView(seg, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private boolean sessionEnded() { return exhausted || (sessionSize > 0 && rounds >= sessionSize); }
    private void startSession(int size) { startSession(size, artistMode, sessionGenres); }

    private void startSession(int size, boolean artists, Set<String> genres) {
        if (tracks.isEmpty()) { toast("Refresh the music library first."); return; }
        if (poolCount(artists, genres) < Math.max(1, size)) { toast("Not enough matching " + (artists ? "artists" : "songs") + " for this session."); return; }
        stopAudio();
        try { sessionId = history.start(size, artists ? "artist" : "song", genreScope(genres)); }
        catch (Exception e) { toast("Could not start a session. Check available storage."); return; }
        TreeSet<String> selected = new TreeSet<>(genres);
        artistMode = artists; sessionGenres.clear(); sessionGenres.addAll(selected); usedArtists.clear();
        sessionSize = size; exhausted = false; rounds = 0; wins = 0; target = null; finished = false; solved = false; excluded.clear(); bag.clear();
        nextRound();
    }

    private void saveGame() {
        if (sessionId == null) return;
        try {
            JSONObject state = new JSONObject(); state.put("session", sessionId); state.put("size", sessionSize); state.put("target", target == null ? -1 : target.id); state.put("exhausted", exhausted);
            state.put("artistMode", artistMode); state.put("genres", new JSONArray(sessionGenres));
            state.put("usedArtists", new JSONArray(usedArtists));
            state.put("round", roundId == null ? "" : roundId); state.put("attempt", attempt); state.put("finished", finished); state.put("solved", solved);
            JSONArray deck = new JSONArray(); for (Track t : bag) deck.put(t.id); state.put("deck", deck);
            JSONArray removed = new JSONArray(); for (long id : excluded) removed.put(id); state.put("excluded", removed);
            getPreferences(0).edit().putString("game", state.toString()).apply();
        } catch (JSONException e) { toast("Could not save the current game."); }
    }

    private void restoreGame() {
        try {
            String saved = getPreferences(0).getString("game", null); if (saved == null) return;
            JSONObject state = new JSONObject(saved); sessionId = state.getString("session"); sessionSize = state.getInt("size"); exhausted = state.optBoolean("exhausted");
            artistMode = state.optBoolean("artistMode"); sessionGenres.clear(); usedArtists.clear();
            JSONArray genres = state.optJSONArray("genres"), used = state.optJSONArray("usedArtists");
            if (genres != null) for (int i = 0; i < genres.length(); i++) sessionGenres.add(genres.getString(i));
            if (used != null) for (int i = 0; i < used.length(); i++) usedArtists.add(used.getString(i));
            roundId = state.getString("round"); attempt = Math.max(0, Math.min(CLIPS.length - 1, state.getInt("attempt")));
            finished = state.getBoolean("finished"); solved = state.getBoolean("solved");
            Boolean recorded = history.found(roundId); if (recorded != null) { finished = true; solved = recorded; }
            long id = state.getLong("target"); target = tracks.stream().filter(t -> t.id == id).findFirst().orElse(null);
            if (finished && artistMode && target != null) usedArtists.add(artistKey(target.artist));
            Map<Long, Track> byId = new HashMap<>(); for (Track t : tracks) byId.put(t.id, t);
            excluded.clear(); JSONArray removed = state.optJSONArray("excluded"); if (removed != null) for (int i = 0; i < removed.length(); i++) excluded.add(removed.getLong(i));
            bag.clear(); JSONArray deck = state.getJSONArray("deck"); for (int i = 0; i < deck.length(); i++) { Track t = byId.get(deck.getLong(i)); if (t != null && !excluded.contains(t.id)) bag.add(t); }
            if (target != null && excluded.contains(target.id)) { target = null; finished = false; solved = false; }
            GameHistory.Summary summary = history.summary(sessionId); rounds = summary.played(); wins = summary.found();
        } catch (Exception e) { sessionId = null; target = null; bag.clear(); excluded.clear(); toast("Could not resume the game. Saved statistics are still available."); }
    }

    private void confirmReplacement() {
        if (target == null || !screen.equals("round")) return;
        new MaterialAlertDialogBuilder(this).setTitle("Replace this song?")
            .setMessage("Use this if the file is silent or cannot be played. This round is removed from all statistics and another song takes its place.")
            .setNegativeButton("Keep song", null).setPositiveButton("Replace song", (dialog, which) -> reportSong()).show();
    }

    private void confirmReset() {
        var confirmation = new MaterialAlertDialogBuilder(this).setTitle("Reset all statistics?")
            .setMessage("Permanently delete all session history and statistics, and end the current game. Your music files are kept. This cannot be undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Reset statistics", (dialog, which) -> resetStats()).show();
        confirmation.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(CORAL);
    }

    private void resetStats() {
        stopAudio();
        try { history.reset(); }
        catch (Exception e) { toast("Could not reset statistics. Nothing was changed."); return; }
        sessionId = null; roundId = null; target = null; sessionSize = 0; rounds = 0; wins = 0; attempt = 0;
        finished = false; solved = false; exhausted = false; bag.clear(); excluded.clear();
        usedArtists.clear();
        getPreferences(0).edit().remove("game").apply();
        showHome(); toast("Statistics reset.");
    }

    private void reportSong() {
        if (target == null || sessionId == null) return;
        stopAudio();
        try { history.remove(roundId); }
        catch (Exception e) { message.setText("Could not remove this result. Try again."); return; }
        if (finished) { rounds--; if (solved) wins--; }
        excluded.add(target.id); bag.removeIf(t -> t.id == target.id);
        if (artistMode) {
            usedArtists.remove(artistKey(target.artist));
            List<Track> remaining = sessionPool();
            for (Track t : remaining) if (artistKey(t.artist).equals(artistKey(target.artist)) && bag.stream().noneMatch(b -> b.id == t.id)) bag.add(t);
            Collections.shuffle(bag, random);
        }
        finished = false; solved = false;
        saveGame(); toast("Song replaced. It will not count in your statistics."); nextRound();
    }

    private void showStats(String id) {
        beginPage("stats"); statsSession = id; int version = screenVersion;
        updateToolbar(id == null ? "History & statistics" : "Session summary", true);

        LinearLayout loadingBox = new LinearLayout(this);
        loadingBox.setOrientation(LinearLayout.VERTICAL);
        loadingBox.setGravity(Gravity.CENTER_HORIZONTAL);
        loadingBox.setPadding(0, dp(80), 0, dp(24));
        content.addView(loadingBox, new LinearLayout.LayoutParams(-1, -2));
        CircularProgressIndicator spinner = new CircularProgressIndicator(this);
        spinner.setIndeterminate(true);
        spinner.setIndicatorColor(PRIMARY);
        spinner.setTrackColor(SURFACE_HIGH);
        loadingBox.addView(spinner);

        final String requestedMode = statsMode, requestedGenres = statsGenres;
        io.execute(() -> {
            try {
                GameHistory.Scope scope = id == null ? new GameHistory.Scope(requestedMode, requestedGenres) : history.context(id);
                List<GameHistory.Scope> scopes = history.contexts();
                GameHistory.Summary summary = history.summary(id, scope.mode(), scope.genres());
                List<GameHistory.Session> sessions = id == null ? history.sessions(scope.mode(), scope.genres()) : List.of();
                List<GameHistory.Result> results = id == null ? List.of() : history.results(id);
                main.post(() -> { if (!isDestroyed() && screenVersion == version) {
                    statsMode = scope.mode(); statsGenres = scope.genres(); statsScopes = scopes;
                    renderStats(id, summary, sessions, results);
                } });
            } catch (Exception e) {
                main.post(() -> {
                    if (screenVersion == version && !isDestroyed()) {
                        content.removeAllViews();
                        label(content, "Could not load statistics.", 18f, TEXT, true);
                        Button back = button(content, "Back to game modes");
                        primary(back);
                        back.setOnClickListener(v -> showHome());
                    }
                });
            }
        });
    }

    private void renderStats(String id, GameHistory.Summary summary, List<GameHistory.Session> sessions, List<GameHistory.Result> results) {
        content.removeAllViews();

        // Scope selector pill card (interactive on All-Time view)
        MaterialCardView scopeCard = makeCard(content, SURFACE, STROKE, 16);
        setMargins(scopeCard, 0, dp(6), 0, dp(14));
        LinearLayout scopeRow = new LinearLayout(this);
        scopeRow.setGravity(Gravity.CENTER_VERTICAL);
        scopeRow.setPadding(dp(16), dp(12), dp(14), dp(12));
        scopeCard.addView(scopeRow);

        TextView scopeText = label(scopeRow, scopeLabel(statsMode, statsGenres), 14.5f, TEXT, true);
        scopeText.setMaxLines(1);
        scopeText.setEllipsize(TextUtils.TruncateAt.END);
        scopeText.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        if (id == null) {
            scopeCard.setClickable(true); scopeCard.setFocusable(true);
            scopeCard.setRippleColor(ColorStateList.valueOf(0x1CEDE0C8));
            pill(scopeRow, "Change ▾", SURFACE_HIGH, STROKE_STRONG, PRIMARY);
            scopeCard.setOnClickListener(v -> {
                String[] labels = statsScopes.stream().map(c -> scopeLabel(c.mode(), c.genres())).toArray(String[]::new);
                int selected = statsScopes.indexOf(new GameHistory.Scope(statsMode, statsGenres));
                new MaterialAlertDialogBuilder(this).setTitle("Separate statistics")
                    .setSingleChoiceItems(labels, selected, (d, index) -> {
                        GameHistory.Scope scope = statsScopes.get(index); statsMode = scope.mode(); statsGenres = scope.genres();
                        d.dismiss(); showStats(null);
                    }).setNegativeButton("Cancel", null).show();
            });
        }

        // Hero Score Card
        boolean complete = id != null && id.equals(sessionId) && sessionEnded();
        MaterialCardView hero = makeCard(content, SURFACE, STROKE_STRONG, 22);
        setMargins(hero, 0, 0, 0, dp(10));
        LinearLayout heroCol = new LinearLayout(this);
        heroCol.setOrientation(LinearLayout.VERTICAL);
        heroCol.setGravity(Gravity.CENTER_HORIZONTAL);
        heroCol.setPadding(dp(20), dp(22), dp(20), dp(20));
        hero.addView(heroCol);

        TextView heroEyebrow = label(heroCol, id == null ? "ALL-TIME ACCURACY" : complete ? (exhausted ? "SESSION ENDED" : "SESSION COMPLETE") : "SESSION ACCURACY", 11f, MUTED, true);
        heroEyebrow.setLetterSpacing(0.14f);

        int pct = summary.played() == 0 ? 0 : Math.round(100f * summary.found() / summary.played());
        TextView bigScore = label(heroCol, summary.played() == 0 ? "—" : pct + "%", 52f, summary.played() == 0 ? DIM : PRIMARY, true);
        setMargins(bigScore, 0, dp(4), 0, dp(2));

        TextView subScore = label(heroCol, summary.played() == 0 ? "No results yet" : summary.found() + " of " + summary.played() + " solved", 14.5f, MUTED, false);
        setMargins(subScore, 0, 0, 0, dp(14));

        if (summary.played() > 0) {
            LinearProgressIndicator bar = new LinearProgressIndicator(this);
            bar.setMax(summary.played());
            bar.setProgress(summary.found());
            bar.setIndicatorColor(MINT);
            bar.setTrackColor(SURFACE_HIGH);
            bar.setTrackThickness(dp(6));
            bar.setTrackCornerRadius(dp(3));
            heroCol.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        }

        // 2-Column metric cards
        LinearLayout metrics = new LinearLayout(this);
        content.addView(metrics, new LinearLayout.LayoutParams(-1, -2));
        setMargins(metrics, 0, 0, 0, dp(8));
        metric(metrics, summary.found() == 0 ? "—" : String.format(Locale.ROOT, "%.1f s", summary.meanMs() / 1000), "AVG CLIP TO SOLVE", true);
        metric(metrics, String.valueOf(summary.played() - summary.found()), "NOT FOUND", false);

        TextView avgNote = label(content, "Average uses solved rounds and the clip length unlocked.", 12f, DIM, false);
        avgNote.setGravity(Gravity.CENTER);
        setMargins(avgNote, 0, 0, 0, dp(14));

        // Primary & Secondary actions
        boolean canContinue = id != null && id.equals(sessionId) && !complete;
        Button action = button(content, canContinue ? "Continue session   →" : "Game modes");
        primary(action);
        action.setOnClickListener(v -> {
            if (canContinue) { if (target != null) renderRound(); else nextRound(); }
            else showHome();
        });
        if (complete && sessionSize > 0) {
            Button replaySession = button(content, "Play " + sessionSize + " rounds again");
            tonal(replaySession);
            replaySession.setOnClickListener(v -> startSession(sessionSize));
        }
        if (id != null) {
            Button allTime = button(content, "All-time statistics   →");
            outlined(allTime);
            allTime.setOnClickListener(v -> showStats(null));
        }

        if (summary.played() == 0) {
            TextView empty = label(content, "Finish a round to start building your statistics. Replaced songs never count.", 14f, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            setMargins(empty, dp(8), dp(20), dp(8), dp(12));
        } else {
            TextView strengthsTitle = label(content, "Your strengths", 20f, TEXT, true);
            setMargins(strengthsTitle, dp(2), dp(22), 0, dp(4));
            TextView strengthsSub = label(content, "Rankings require 3 plays per category. Unknown tags are excluded.", 12.5f, DIM, false);
            setMargins(strengthsSub, dp(2), 0, 0, dp(10));
            groupSection("Genres", summary.genres());
            groupSection("Five-year periods", summary.fiveYears());
            groupSection("Decades", summary.decades());
        }

        if (id == null) {
            if (!sessions.isEmpty()) {
                TextView recentTitle = label(content, "Recent sessions", 20f, TEXT, true);
                setMargins(recentTitle, dp(2), dp(22), 0, dp(10));
            }
            for (GameHistory.Session session : sessions) {
                String date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new Date(session.started()));
                String title = session.size() == 0 ? "Infinite mode" : session.size() + " rounds · " + session.played() + " / " + session.size();
                int sPct = session.played() == 0 ? 0 : Math.round(100f * session.found() / session.played());

                MaterialCardView c = makeCard(content, SURFACE, STROKE, 18);
                c.setClickable(true); c.setFocusable(true);
                c.setRippleColor(ColorStateList.valueOf(0x1CEDE0C8));
                c.setContentDescription(title + ", " + score(session.found(), session.played()) + ", " + date);
                setMargins(c, 0, 0, 0, dp(8));
                c.setOnClickListener(v -> showStats(session.id()));

                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(14), dp(14), dp(14));
                c.addView(row);

                LinearLayout left = new LinearLayout(this);
                left.setOrientation(LinearLayout.VERTICAL);
                row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
                label(left, title, 15.5f, TEXT, true);
                TextView meta = label(left, session.found() + " / " + session.played() + " solved  ·  " + date, 12.5f, MUTED, false);
                setMargins(meta, 0, dp(3), dp(8), 0);

                TextView badge = pill(row, session.played() == 0 ? "—" : sPct + "%", MINT_BG, MINT_STROKE, MINT);
                setMargins(badge, 0, 0, dp(8), 0);
                label(row, "›", 20f, DIM, false);
            }
        } else {
            if (!results.isEmpty()) {
                TextView songsTitle = label(content, "Songs · newest first", 20f, TEXT, true);
                setMargins(songsTitle, dp(2), dp(22), 0, dp(10));
            }
            for (GameHistory.Result result : results) {
                MaterialCardView c = makeCard(content, SURFACE, STROKE, 16);
                setMargins(c, 0, 0, 0, dp(7));
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(12), dp(14), dp(12));
                c.addView(row);

                LinearLayout left = new LinearLayout(this);
                left.setOrientation(LinearLayout.VERTICAL);
                row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
                TextView t = label(left, result.title(), 15f, TEXT, true);
                t.setMaxLines(1); t.setEllipsize(TextUtils.TruncateAt.END);
                TextView a = label(left, result.artist(), 13f, MUTED, false);
                a.setMaxLines(1); a.setEllipsize(TextUtils.TruncateAt.END);
                setMargins(a, 0, dp(2), dp(8), 0);

                pill(row,
                    result.found() ? "✓ " + duration(result.clipMs()) + "s" : "✕",
                    result.found() ? MINT_BG : ACCENT_BG,
                    result.found() ? MINT_STROKE : ACCENT_STROKE,
                    result.found() ? MINT : CORAL);
            }
            if (summary.played() > 50) {
                TextView note = label(content, "Showing the latest 50 songs. Totals include every result.", 12f, DIM, false);
                note.setGravity(Gravity.CENTER);
                setMargins(note, 0, dp(6), 0, dp(8));
            }
        }
    }

    private void metric(LinearLayout row, String value, String caption, boolean isLeft) {
        MaterialCardView c = new MaterialCardView(this);
        c.setRadius(dp(18)); c.setCardElevation(0);
        c.setCardBackgroundColor(SURFACE); c.setStrokeColor(STROKE); c.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(isLeft ? 0 : dp(4), 0, isLeft ? dp(4) : 0, 0);
        row.addView(c, lp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(12), dp(16), dp(12), dp(16));
        c.addView(col);

        label(col, value, 24f, TEXT, true);
        TextView cap = label(col, caption, 10.5f, MUTED, true);
        cap.setLetterSpacing(0.10f);
        setMargins(cap, 0, dp(4), 0, 0);
    }

    private void groupSection(String title, List<GameHistory.Group> groups) {
        if (groups.isEmpty()) return;
        TextView header = label(content, title, 16f, TEXT, true);
        setMargins(header, dp(2), dp(14), 0, dp(6));

        ArrayList<GameHistory.Group> ranked = new ArrayList<>();
        for (GameHistory.Group g : groups) if (!g.name().equals("Unknown") && g.played() >= 3) ranked.add(g);
        ranked.sort(Comparator.comparingDouble((GameHistory.Group g) -> (double) g.found() / g.played()).thenComparingInt(GameHistory.Group::played));

        if (ranked.size() >= 2) {
            GameHistory.Group worst = ranked.get(0), best = ranked.get(ranked.size() - 1);
            if ((long) best.found() * worst.played() == (long) worst.found() * best.played()) {
                TextView tied = label(content, "Eligible categories are tied by success rate.", 12.5f, MUTED, false);
                setMargins(tied, dp(2), 0, 0, dp(8));
            } else {
                LinearLayout chips = new LinearLayout(this);
                content.addView(chips, new LinearLayout.LayoutParams(-1, -2));
                setMargins(chips, 0, dp(2), 0, dp(8));
                TextView bestPill = pill(chips, "★ Best: " + best.name(), MINT_BG, MINT_STROKE, MINT);
                bestPill.setMaxLines(1); bestPill.setEllipsize(TextUtils.TruncateAt.END);
                TextView worstPill = pill(chips, "▲ Hardest: " + worst.name(), ACCENT_BG, ACCENT_STROKE, CORAL);
                worstPill.setMaxLines(1); worstPill.setEllipsize(TextUtils.TruncateAt.END);
                setMargins(worstPill, dp(6), 0, 0, 0);
            }
        } else {
            TextView more = label(content, "More results needed to compare categories.", 12f, DIM, false);
            setMargins(more, dp(2), 0, 0, dp(8));
        }

        for (GameHistory.Group group : groups) {
            MaterialCardView c = makeCard(content, SURFACE, STROKE, 16);
            setMargins(c, 0, 0, 0, dp(7));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(16), dp(12), dp(16), dp(12));
            c.addView(col);

            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            col.addView(top, new LinearLayout.LayoutParams(-1, -2));

            TextView name = label(top, group.name(), 14.5f, TEXT, true);
            name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            int pct = Math.round(100f * group.found() / group.played());
            label(top, group.found() + "/" + group.played() + "  ·  " + pct + "%", 13f, MINT, true);

            LinearProgressIndicator bar = new LinearProgressIndicator(this);
            bar.setMax(group.played()); bar.setProgress(group.found());
            bar.setIndicatorColor(MINT); bar.setTrackColor(SURFACE_HIGH);
            bar.setTrackThickness(dp(5)); bar.setTrackCornerRadius(dp(3));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
            barLp.setMargins(0, dp(8), 0, dp(6));
            col.addView(bar, barLp);

            label(col, group.found() == 0 ? "No correct answers yet" : String.format(Locale.ROOT, "%.1f s average to solve", group.meanMs() / 1000), 12f, MUTED, false);
        }
    }

    private PlaybackButton playbackButton(LinearLayout parent, String label) {
        PlaybackButton b = new PlaybackButton(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(16.5f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setCornerRadius(dp(28));
        b.setPadding(dp(20), dp(14), dp(20), dp(14));
        b.setMinHeight(dp(58));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(4));
        parent.addView(b, lp);
        return b;
    }

    private void checkUpdate() {
        if (downloadId != -1) {
            DownloadManager dm = getSystemService(DownloadManager.class);
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
                if (c != null && c.moveToFirst()) {
                    int state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (state == DownloadManager.STATUS_SUCCESSFUL) { installDownload(dm); return; }
                    if (state != DownloadManager.STATUS_FAILED) { toast("Update is downloading. Tap again when it finishes."); return; }
                }
            }
            downloadId = -1; getPreferences(0).edit().remove("download").apply();
        }
        toolbar.getMenu().findItem(6).setEnabled(false);
        io.execute(() -> {
            String outcome; JSONObject release = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(REPO + "/releases/latest").openConnection();
                connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/vnd.github+json"); connection.setRequestProperty("User-Agent", "PocketBlindtest/" + BuildConfig.VERSION_NAME);
                int code = connection.getResponseCode();
                if (code == 404) outcome = "No release available yet.";
                else if (code != 200) outcome = "GitHub is unavailable (HTTP " + code + "). Try again later.";
                else {
                    try (var stream = connection.getInputStream()) {
                        String json = new String(stream.readNBytes(1048576), StandardCharsets.UTF_8);
                        release = new JSONObject(json);
                    }
                    outcome = "You’re up to date.";
                }
            } catch (Exception e) { outcome = "Could not check updates. Connect to the internet and try again."; }
            finally { if (connection != null) connection.disconnect(); }
            JSONObject result = release; String info = outcome;
            main.post(() -> {
                if (isDestroyed()) return;
                toolbar.getMenu().findItem(6).setEnabled(true);
                try {
                    if (result != null && newer(result.getString("tag_name"), BuildConfig.VERSION_NAME)) {
                        JSONArray assets = result.getJSONArray("assets");
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            if (asset.getString("name").equals("PocketBlindtest.apk")) {
                                String url = asset.getString("browser_download_url");
                                if (!url.startsWith("https://github.com/vincedelmas/PocketBlindtest/releases/download/")) throw new IllegalArgumentException();
                                new MaterialAlertDialogBuilder(this).setTitle("Update available: " + result.getString("tag_name"))
                                    .setMessage("Download the signed APK from GitHub? Android will ask before installing.")
                                    .setNegativeButton("Later", null).setPositiveButton("Download", (d, w) -> download(url)).show(); return;
                            }
                        }
                        toast("The release APK is not ready yet. Try again shortly."); return;
                    }
                    new MaterialAlertDialogBuilder(this).setTitle("App updates").setMessage(info).setPositiveButton("OK", null).show();
                } catch (Exception e) { toast("Could not read release information."); }
            });
        });
    }

    static boolean newer(String tag, String current) {
        String[] a = tag.replaceFirst("^v", "").split("\\."), b = current.split("\\.");
        if (a.length != 3 || b.length != 3) return false;
        for (int i = 0; i < 3; i++) { int x = Integer.parseInt(a[i]), y = Integer.parseInt(b[i]); if (x != y) return x > y; }
        return false;
    }

    private void download(String url) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url)).setTitle("Pocket Blindtest update")
                .setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            downloadId = getSystemService(DownloadManager.class).enqueue(request);
            getPreferences(0).edit().putLong("download", downloadId).putString("downloadFrom", BuildConfig.VERSION_NAME).apply();
            toast("Downloading. Tap Check for updates again to install when complete.");
        } catch (Exception e) { toast("Could not start the download. Try again."); }
    }

    private void installDownload(DownloadManager dm) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new MaterialAlertDialogBuilder(this).setTitle("Allow app updates").setMessage("Enable installation from Pocket Blindtest, then return and tap Check for updates again.")
                .setNegativeButton("Cancel", null).setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())))).show(); return;
        }
        try {
            Uri uri = dm.getUriForDownloadedFile(downloadId);
            if (uri == null) { toast("Download unavailable. Please retry."); downloadId = -1; getPreferences(0).edit().remove("download").apply(); return; }
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) { toast("Open the completed APK download to install it."); }
    }

    // --- UI helpers ---

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void setMargins(View v, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams current = v.getLayoutParams();
        LinearLayout.LayoutParams lp = current instanceof LinearLayout.LayoutParams
            ? (LinearLayout.LayoutParams) current
            : new LinearLayout.LayoutParams(current != null ? current.width : -1, current != null ? current.height : -2);
        lp.setMargins(left, top, right, bottom);
        v.setLayoutParams(lp);
    }

    private TextView label(LinearLayout parent, String value, float sizeSp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        parent.addView(t);
        return t;
    }

    private TextView pill(LinearLayout parent, String text, int bg, int stroke, int textColor) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11.5f);
        t.setTextColor(textColor);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackground(pillBg(bg, stroke, 50));
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        parent.addView(t, new LinearLayout.LayoutParams(-2, -2));
        return t;
    }

    private MaterialCardView makeCard(LinearLayout parent, int bg, int stroke, int radiusDp) {
        MaterialCardView c = new MaterialCardView(this);
        c.setRadius(dp(radiusDp));
        c.setCardElevation(0);
        c.setCardBackgroundColor(bg);
        c.setStrokeColor(stroke);
        c.setStrokeWidth(dp(1));
        parent.addView(c, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        return bg;
    }

    private GradientDrawable pillBg(int color, int stroke, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), stroke);
        return bg;
    }

    private void primary(Button b) {
        MaterialButton m = (MaterialButton) b;
        m.setBackgroundTintList(ColorStateList.valueOf(PRIMARY));
        m.setTextColor(ON_PRIMARY);
        m.setStrokeWidth(0);
        m.setTextSize(16f);
        m.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        m.setCornerRadius(dp(28));
        m.setMinHeight(dp(58));
    }

    private void tonal(Button b) {
        MaterialButton m = (MaterialButton) b;
        m.setBackgroundTintList(ColorStateList.valueOf(SURFACE_HIGH));
        m.setTextColor(TEXT);
        m.setStrokeWidth(dp(1));
        m.setStrokeColor(ColorStateList.valueOf(STROKE_STRONG));
        m.setCornerRadius(dp(24));
        m.setMinHeight(dp(54));
    }

    private void outlined(Button b) {
        MaterialButton m = (MaterialButton) b;
        m.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        m.setTextColor(MUTED);
        m.setStrokeWidth(dp(1));
        m.setStrokeColor(ColorStateList.valueOf(STROKE_STRONG));
        m.setCornerRadius(dp(20));
        m.setMinHeight(dp(50));
    }

    private Button button(LinearLayout parent, String label) {
        MaterialButton b = new MaterialButton(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(15f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(18), dp(12), dp(18), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8); lp.bottomMargin = dp(2);
        parent.addView(b, lp);
        return b;
    }

    private Button compactButton(LinearLayout row, String label, boolean isLeft) {
        MaterialButton b = new MaterialButton(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(13.5f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackgroundTintList(ColorStateList.valueOf(SURFACE));
        b.setTextColor(MUTED);
        b.setStrokeWidth(dp(1));
        b.setStrokeColor(ColorStateList.valueOf(STROKE));
        b.setCornerRadius(dp(16));
        b.setMinHeight(dp(48));
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(isLeft ? 0 : dp(4), 0, isLeft ? dp(4) : 0, 0);
        row.addView(b, lp);
        return b;
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("screen", screen); out.putString("statsSession", statsSession); out.putString("statsMode", statsMode); out.putString("statsGenres", statsGenres); saveGame(); }
    @Override protected void onStop() { stopAudio(); saveGame(); super.onStop(); }
    @Override protected void onDestroy() { unregisterReceiver(noisy); cancelSearch(); searchWorker.shutdownNow(); io.execute(history::close); io.shutdown(); super.onDestroy(); }
}
