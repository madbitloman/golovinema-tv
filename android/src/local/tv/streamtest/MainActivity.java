package local.tv.streamtest;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.ArrayList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final String TAG = "TvStreamTest";
    private static final int[] SERVERS = {2, 1, 3, 5};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private Future<?> lookup;
    private FrameLayout screen;
    private VideoView video;
    private LinearLayout controls;
    private TextView status;
    private Button pause, rewind, forward, back, audio, subtitles;
    private MediaPlayer trackPlayer;
    private AlertDialog trackDialog;
    private TextView caption;
    private boolean audioChosen, preferredAudioApplied;
    private boolean playerScreen, ready, local;
    private int generation, serverIndex;
    private String movie = "2101", failures = "";
    private String movieTitle = "";
    private String episode = "1", historyKey;
    private boolean series;
    private String playerUrl = "", sourcePath = "", playbackReferer = "https://ployan.me/watch/";
    private String sourceName() { return "source " + SERVERS[serverIndex - 1]; }
    private boolean fromCatalog, browserOpening;
    private int resumePosition;
    private final Runnable hideControls = () -> {
        if (trackDialog == null && ready && video != null && video.isPlaying()) controls.setVisibility(View.GONE);
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
        fromCatalog = getIntent().hasExtra("movie");
        playerUrl = getIntent().getStringExtra("playerUrl"); sourcePath = getIntent().getStringExtra("sourcePath");
        movie = fromCatalog ? getIntent().getStringExtra("movie") : getPreferences(0).getString("movie", "2101");
        movieTitle = fromCatalog ? getIntent().getStringExtra("title") : "";
        series = fromCatalog && getIntent().getBooleanExtra("series", false);
        if (series) episode = getIntent().getStringExtra("episode");
        if (episode == null || !episode.matches("[0-9]{1,12}")) { finish(); return; }
        historyKey = series ? movie + "_ep_" + episode : movie;
        if (movieTitle == null || movieTitle.isEmpty()) movieTitle = "Movie " + movie;
        resumePosition = Math.max(0, getIntent().getIntExtra("resume", 0));
        if (fromCatalog) start(false); else menu("First check picture and sound, then try the live movie.");
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String content, int size) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }
    private Button button(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(17);
        button.setAllCaps(false);
        button.setPadding(dp(18), dp(8), dp(18), dp(8));
        button.setOnClickListener(v -> { action.run(); reveal(); });
        button.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable background = new GradientDrawable();
            background.setColor(focused ? Color.rgb(137, 224, 192) : Color.rgb(42, 57, 78));
            background.setCornerRadius(dp(8));
            button.setBackground(background);
            button.setTextColor(focused ? Color.rgb(12, 27, 37) : Color.WHITE);
        });
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(42, 57, 78));
        background.setCornerRadius(dp(8));
        button.setBackground(background);
        button.setTextColor(Color.WHITE);
        return button;
    }
    private void addButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, dp(48));
        layout.setMargins(0, dp(6), dp(12), dp(6));
        row.addView(button, layout);
    }

    private void invalidatePlayback() {
        savePosition();
        generation++;
        ready = false;
        if (trackDialog != null) { trackDialog.dismiss(); trackDialog = null; }
        trackPlayer = null; audioChosen = false; preferredAudioApplied = false;
        if (caption != null) caption.setText("");
        setTransportEnabled(false);
        handler.removeCallbacksAndMessages(null);
        if (lookup != null) { lookup.cancel(true); lookup = null; }
        if (video != null) {
            video.setOnPreparedListener(null);
            video.setOnErrorListener(null);
            video.setOnInfoListener(null);
            video.setOnCompletionListener(null);
            video.stopPlayback();
            video = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void savePosition() {
        if (!fromCatalog || local || !ready || video == null) return;
        try {
            resumePosition = video.getCurrentPosition();
            android.content.SharedPreferences.Editor edit = getSharedPreferences("cinema", 0).edit().putInt("position_" + historyKey, resumePosition)
                .putInt("duration_" + historyKey, video.getDuration()).putLong("watched_" + movie, System.currentTimeMillis());
            if (series) edit.putString("last_episode_" + movie, episode);
            edit.apply();
        } catch (RuntimeException ignored) { }
    }
    private void leavePlayer() { if (fromCatalog) { invalidatePlayback(); finish(); } else menu("Choose a test."); }

    private void menu(String message) {
        invalidatePlayback();
        playerScreen = false;
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(48), dp(28), dp(48), dp(20));
        column.setBackgroundColor(Color.rgb(15, 24, 40));
        TextView heading = text("GOLOVINEMA", 32);
        heading.setTypeface(null, Typeface.BOLD);
        column.addView(heading);
        column.addView(text(message, 18));
        Button sample = button("1. Check picture & sound", () -> start(true));
        addButton(column, sample);
        column.addView(text("2. Movie ID from the site’s page URL", 16));
        EditText input = new EditText(this);
        input.setText(movie);
        input.setTextSize(21);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSelectAllOnFocus(true);
        input.setContentDescription("Movie ID");
        column.addView(input, new LinearLayout.LayoutParams(dp(260), dp(52)));
        Button live = button("Play live movie", () -> {
            String selected = input.getText().toString().trim();
            if (!selected.matches("[0-9]{1,12}")) { input.setError("Enter a numeric movie ID"); return; }
            movie = selected;
            movieTitle = "Movie " + movie;
            historyKey = movie;
            getPreferences(0).edit().putString("movie", movie).apply();
            start(false);
        });
        addButton(column, live);
        column.addView(text("2101 is the original movie you shared.\nRemote: OK pauses · Left/Right seeks 30s · Back returns here", 15));
        setContentView(column);
        sample.requestFocus();
    }

    private void start(boolean sample) {
        invalidatePlayback();
        local = sample;
        playerScreen = true;
        serverIndex = 0;
        int savedSource = getSharedPreferences("cinema", 0).getInt("source_" + movie, 2);
        for (int i = 0; i < SERVERS.length; i++) if (SERVERS[i] == savedSource) serverIndex = i;
        failures = "";
        screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(32), dp(14), dp(32), dp(18));
        controls.setBackgroundColor(0xDD101828);
        status = text(sample ? "Opening the built-in test…" : "Finding the movie…", 18);
        controls.addView(status);
        LinearLayout buttons = new LinearLayout(this);
        pause = button("Pause", this::toggle);
        addButton(buttons, pause);
        rewind = button("−30s", () -> seek(-30000));
        forward = button("+30s", () -> seek(30000));
        addButton(buttons, rewind);
        addButton(buttons, forward);
        addButton(buttons, button("Retry", () -> start(local)));
        back = button("Back", this::leavePlayer);
        addButton(buttons, back);
        setTransportEnabled(false);
        controls.addView(buttons);
        LinearLayout tracks = new LinearLayout(this);
        audio = button("Audio", () -> chooseTrack(false));
        subtitles = button("Subtitles", () -> chooseTrack(true));
        addButton(tracks, audio); addButton(tracks, subtitles);
        if (!sample) addButton(tracks, button("Source", this::chooseSource));
        controls.addView(tracks); setTransportEnabled(false);
        caption = text("", 24); caption.setGravity(Gravity.CENTER);
        caption.setShadowLayer(4, 2, 2, Color.BLACK);
        FrameLayout.LayoutParams captionLayout = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        captionLayout.bottomMargin = dp(210);
        screen.addView(caption, captionLayout);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        screen.addView(controls, bottom);
        setContentView(screen);
        back.requestFocus();
        if (sample) play(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.test_clip), generation);
        else resolveNext();
    }

    private void resolveNext() {
        invalidatePlayback();
        if (serverIndex >= SERVERS.length) {
            Log.w(TAG, "No playable source: title=" + movie + "; episode=" + episode + "; " + failures);
            say("“" + movieTitle + "” couldn’t be played.\nTry again later or choose another title.");
            back.requestFocus();
            reveal();
            return;
        }
        int token = generation;
        int server = SERVERS[serverIndex++];
        say("Finding " + movieTitle + " · source " + server + "…");
        lookup = network.submit(() -> {
            try {
                JSONObject response = new JSONObject(readJson(PlayerProtocol.resolverUrl(movie, episode, server)));
                if (response.optInt("code") != 200) throw new Exception("lookup returned " + response.optInt("code"));
                if (server == 2 && "embed".equals(response.optString("mode"))) {
                    String address = PlayerProtocol.browserSource(response.getString("info"), server);
                    handler.post(() -> {
                        if (token != generation || !playerScreen) return;
                        say("Server 2 · browser playback. Back returns here to change Source.");
                        android.content.Intent browser = new android.content.Intent(this, SourceBrowserActivity.class);
                        browser.putExtra("address", address); browserOpening = true; startActivityForResult(browser, 810);
                    });
                    return;
                }
                if (!"direct".equals(response.optString("mode"))) throw new Exception("browser-only source");
                String info = response.getString("info");
                if (!info.matches("[a-fA-F0-9]+-[a-fA-F0-9]+-[a-fA-F0-9]+")) throw new Exception("unexpected source format");
                Uri uri = Uri.parse("https://ployan.me/hls/" + info + "/master.m3u8");
                PlaylistProbe.check(uri.toString());
                handler.post(() -> { if (token == generation && playerScreen) play(uri, token); });
            } catch (Exception error) {
                handler.post(() -> { if (token == generation && playerScreen) failed("Source " + server + ": " + shortError(error)); });
            }
        });
    }

    private void play(Uri uri, int token) {
        // Each attempt has its own view/listeners, so a stale error cannot stop a newer attempt.
        for (int i = screen.getChildCount() - 1; i >= 0; i--)
            if (screen.getChildAt(i) instanceof VideoView) screen.removeViewAt(i);
        VideoView candidate = new VideoView(this);
        video = candidate;
        screen.addView(candidate, 0, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        candidate.setFocusable(false);
        candidate.setFocusableInTouchMode(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        say(local ? "Opening the built-in test…" : "Loading " + movieTitle + " · " + sourceName() + "…");
        candidate.setOnPreparedListener(mp -> {
            if (token != generation || candidate != video) return;
            ready = true;
            trackPlayer = mp;
            mp.setOnTimedTextListener((player, text) -> {
                if (token == generation && candidate == video) caption.setText(text == null ? "" : text.getText());
            });
            preferAudio();
            setTransportEnabled(true);
            pause.requestFocus();
            say("Ready · " + mp.getVideoWidth() + " × " + mp.getVideoHeight() + " · " + clock(candidate.getDuration()));
            if (fromCatalog && resumePosition > 0 && resumePosition < candidate.getDuration() - 1000) candidate.seekTo(resumePosition);
            candidate.start();
            reveal();
        });
        candidate.setOnInfoListener((mp, what, extra) -> {
            if (token != generation || candidate != video) return true;
            if (what == MediaPlayer.MEDIA_INFO_METADATA_UPDATE) { preferAudio(); }
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                preferAudio();
                say(local ? "Picture & sound test · quiet tone" : movieTitle + " · " + sourceName());
                reveal();
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                say("Buffering…");
                controls.setVisibility(View.VISIBLE);
                handler.removeCallbacks(hideControls);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) { say("Playing"); reveal(); }
            return true;
        });
        candidate.setOnErrorListener((mp, what, extra) -> {
            if (token == generation && candidate == video) failed((local ? "Local clip" : sourceName()) + ": playback error " + what + "/" + extra);
            return true;
        });
        candidate.setOnCompletionListener(mp -> {
            if (token != generation || candidate != video) return;
            if (local) menu("Test finished. If you saw moving colors and heard a tone, try the live movie.");
            else { say("Playback finished"); pause.setText("Replay"); reveal(); }
        });
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Referer", playbackReferer);
        headers.put("User-Agent", "TVStreamTest/0.1 (Android TV)");
        candidate.setVideoURI(uri, headers);
        handler.postDelayed(() -> { if (token == generation && !ready) failed("Video did not load within 40 seconds"); }, 40000);
    }

    private void chooseSource() {
        new AlertDialog.Builder(this).setTitle("Source · different servers may have different audio")
            .setItems(new String[]{"Server 2 · website source", "Server 1 · native", "Source 3 · native if available", "Source 5 · native if available"}, (dialog, which) -> {
                getSharedPreferences("cinema", 0).edit().putInt("source_" + movie, SERVERS[which]).apply();
                serverIndex = which; failures = ""; resolveNext();
            }).setNegativeButton("Cancel", null).show();
    }

    private void preferAudio() {
        if (trackPlayer == null || audioChosen || preferredAudioApplied) return;
        String preferred = "en";
        try {
            MediaPlayer.TrackInfo[] tracks = trackPlayer.getTrackInfo();
            for (int i = 0; i < tracks.length; i++) {
                if (tracks[i].getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO
                        && preferred.equals(TrackLanguage.normalize(tracks[i].getLanguage()))) {
                    trackPlayer.selectTrack(i); preferredAudioApplied = true;
                    Log.i(TAG, "Preferred audio selected: " + preferred); return;
                }
            }
            Log.i(TAG, "Preferred audio not exposed; keeping source default");
        } catch (RuntimeException error) { Log.w(TAG, "Audio preference unavailable: " + error.getClass().getSimpleName()); }
    }
    private void chooseTrack(boolean captions) {
        final MediaPlayer player = trackPlayer;
        final int token = generation;
        if (!ready || player == null) return;
        try {
            MediaPlayer.TrackInfo[] tracks = player.getTrackInfo();
            ArrayList<Integer> indices = new ArrayList<>();
            ArrayList<String> labels = new ArrayList<>();
            int checked = -1;
            if (captions) { indices.add(-1); labels.add("Off"); checked = 0; }
            for (int i = 0; i < tracks.length; i++) {
                int type = tracks[i].getTrackType();
                boolean eligible = captions ? type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                    || type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT : type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO;
                if (!eligible) continue;
                if (player.getSelectedTrack(type) == i) checked = indices.size();
                indices.add(i); labels.add(TrackLanguage.label(tracks[i].getLanguage()) + " · Track " + (i + 1));
            }
            if (indices.size() == (captions ? 1 : 0)) {
                say(captions ? "This stream exposes no selectable subtitles." : "This stream exposes no alternate audio tracks."); return;
            }
            handler.removeCallbacks(hideControls);
            trackDialog = new AlertDialog.Builder(this).setTitle(captions ? "Subtitles" : "Audio · English preferred")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    if (token != generation || trackPlayer != player) { dialog.dismiss(); return; }
                    try {
                        int index = indices.get(which);
                        if (captions) {
                            for (int type : new int[]{MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE, MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT}) {
                                int old = player.getSelectedTrack(type); if (old >= 0 && old != index) player.deselectTrack(old);
                            }
                            caption.setText("");
                        }
                        if (index >= 0) player.selectTrack(index);
                        if (!captions) audioChosen = true;
                        say((captions ? "Subtitles: " : "Audio: ") + labels.get(which));
                    } catch (RuntimeException error) { say("This player could not switch that track. Try another track."); }
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).create();
            trackDialog.setOnDismissListener(dialog -> { trackDialog = null; reveal(); });
            trackDialog.show();
        } catch (RuntimeException error) { say("Track information is unavailable for this stream."); }
    }

    private void failed(String reason) {
        Log.w(TAG, reason);
        if (local) { invalidatePlayback(); say("Built-in test failed: " + reason); reveal(); }
        else { failures += reason + ". "; resolveNext(); }
    }
    private void setTransportEnabled(boolean enabled) {
        for (Button button : new Button[]{pause, rewind, forward, audio, subtitles}) if (button != null) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.4f);
        }
    }
    private void say(String message) { status.setText(message); Log.i(TAG, message); }
    private void reveal() {
        if (!playerScreen) return;
        controls.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, 6000);
    }
    private void toggle() {
        if (!ready || video == null) return;
        if (video.isPlaying()) { video.pause(); pause.setText("Play"); say("Paused · " + clock(video.getCurrentPosition())); }
        else { video.start(); pause.setText("Pause"); say("Playing"); }
        reveal();
    }
    private void seek(int delta) {
        if (!ready || video == null) return;
        int target = Math.max(0, Math.min(Math.max(0, video.getDuration() - 1000), video.getCurrentPosition() + delta));
        video.seekTo(target);
        say("Seek · " + clock(target) + " / " + clock(video.getDuration()));
        reveal();
    }
    private static String clock(int ms) { int seconds = Math.max(0, ms / 1000); return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60); }
    private static String shortError(Exception error) {
        String message = error.getMessage();
        if (message == null) return error.getClass().getSimpleName();
        // Do not put request tokens or entire network URLs into status/log output.
        if (message.contains("https://")) return error.getClass().getSimpleName();
        return message.length() > 100 ? message.substring(0, 100) : message;
    }
    private static String readJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Referer", "https://ployan.me/watch/");
        connection.setRequestProperty("User-Agent", "TVStreamTest/0.1 (Android TV)");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[4096];
                int length;
                while ((length = in.read(bytes)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (out.size() + length > 262144) throw new Exception("Lookup response too large");
                    out.write(bytes, 0, length);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (playerScreen) {
            if (key == KeyEvent.KEYCODE_BACK) { leavePlayer(); return true; }
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (event.getRepeatCount() == 0 && (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || (video != null && ((key == KeyEvent.KEYCODE_MEDIA_PLAY) != video.isPlaying())))) toggle();
                return true;
            }
            if (controls.getVisibility() != View.VISIBLE) {
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) { toggle(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) { seek(-30000); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { seek(30000); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) { reveal(); pause.requestFocus(); return true; }
            }
            reveal();
        }
        return super.onKeyDown(key, event);
    }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 810) { browserOpening = false; reveal(); back.requestFocus(); }
    }
    @Override protected void onStop() {
        super.onStop();
        if (playerScreen) {
            if (browserOpening) { invalidatePlayback(); return; }
            if (fromCatalog) { invalidatePlayback(); finish(); }
            else menu("Playback stopped when the app went into the background. Choose a test to restart.");
        }
    }
    @Override protected void onDestroy() { invalidatePlayback(); network.shutdownNow(); super.onDestroy(); }
}
