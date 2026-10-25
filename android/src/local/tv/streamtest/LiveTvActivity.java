package local.tv.streamtest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** TiviMate-style live TV: full-screen channel playback, channel list overlay, now/next guide. */
public final class LiveTvActivity extends Activity {
    private static final String TAG = "LiveTv";
    private static final int BG = Color.rgb(11, 14, 20), PANEL = Color.argb(235, 18, 22, 30), INK = Color.rgb(246, 244, 239),
            MUTED = Color.rgb(163, 172, 187), ACCENT = Color.rgb(255, 194, 98);
    private static final String ALL = "All channels", FAVOURITES = "★ Favourites", ACE_GROUP = "AceStream links";
    // Public, legal free-to-air lists maintained by iptv-org; guides from iptv-epg.org.
    private static final String[][] PRESETS = {
            {"All channels · by category", "https://iptv-org.github.io/iptv/index.m3u", ""},
            {"All channels · by country", "https://iptv-org.github.io/iptv/index.country.m3u", ""},
            {"Canada", "https://iptv-org.github.io/iptv/countries/ca.m3u", "https://iptv-epg.org/files/epg-ca.xml.gz"},
            {"USA", "https://iptv-org.github.io/iptv/countries/us.m3u", "https://iptv-epg.org/files/epg-us.xml.gz"},
            {"United Kingdom", "https://iptv-org.github.io/iptv/countries/uk.m3u", "https://iptv-epg.org/files/epg-gb.xml.gz"},
            {"Sports channels (worldwide)", "https://iptv-org.github.io/iptv/categories/sports.m3u", ""},
            {"Movie channels (worldwide)", "https://iptv-org.github.io/iptv/categories/movies.m3u", ""},
    };
    private static final long PLAYLIST_MAX_AGE = 12 * 3600 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private Future<?> playlistTask, guideTask;
    private SharedPreferences prefs;
    private PosterLoader logos;
    private FrameLayout screen;
    private VideoView video;
    private ProgressBar spinner;
    private LinearLayout overlay, banner;
    private TextView groupLabel, overlayStatus, bannerTitle, bannerNow, bannerNext, message;
    private ListView list;
    private final ChannelAdapter adapter = new ChannelAdapter();
    private IptvPlaylist.Playlist playlist;
    private Map<String, List<IptvPlaylist.Programme>> guide = Collections.emptyMap();
    private final List<IptvPlaylist.Channel> visible = new ArrayList<>();
    private Set<String> favourites = new HashSet<>();
    private String group = ALL;
    private IptvPlaylist.Playlist base;
    private IptvPlaylist.Channel playing;
    private int generation;
    private final StringBuilder digits = new StringBuilder();
    private final Runnable hideBanner = () -> banner.setVisibility(View.GONE);
    private final Runnable commitDigits = this::jumpToTypedNumber;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
        prefs = getSharedPreferences("livetv", MODE_PRIVATE);
        favourites = new HashSet<>(prefs.getStringSet("favourites", Collections.emptySet()));
        group = prefs.getString("group", ALL);
        logos = new PosterLoader(this);
        build();
        if (prefs.getString("playlist", "").isEmpty()) choosePreset(true); else loadPlaylist(false);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private GradientDrawable shape(int color) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(8)); return shape; }
    private Button button(String title, Runnable action) {
        Button view = new Button(this); view.setText(title); view.setTextSize(15); view.setAllCaps(false);
        view.setMinHeight(0); view.setMinimumHeight(0); view.setPadding(dp(16), 0, dp(16), 0);
        view.setTextColor(INK); view.setBackground(shape(Color.rgb(37, 44, 58)));
        view.setOnFocusChangeListener((v, focus) -> { view.setBackground(shape(focus ? ACCENT : Color.rgb(37, 44, 58))); view.setTextColor(focus ? BG : INK); });
        view.setOnClickListener(v -> action.run()); return view;
    }

    private void build() {
        screen = new FrameLayout(this); screen.setBackgroundColor(Color.BLACK);
        spinner = new ProgressBar(this); spinner.setVisibility(View.GONE);
        screen.addView(spinner, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        message = text("", 18, INK); message.setGravity(Gravity.CENTER); message.setPadding(dp(80), 0, dp(80), 0);
        screen.addView(message, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        banner = new LinearLayout(this); banner.setOrientation(LinearLayout.VERTICAL); banner.setBackground(shape(PANEL));
        banner.setPadding(dp(24), dp(14), dp(24), dp(14)); banner.setVisibility(View.GONE);
        bannerTitle = text("", 22, INK); bannerTitle.setTypeface(null, Typeface.BOLD); banner.addView(bannerTitle);
        bannerNow = text("", 16, ACCENT); bannerNow.setSingleLine(true); bannerNow.setEllipsize(TextUtils.TruncateAt.END); banner.addView(bannerNow);
        bannerNext = text("", 14, MUTED); bannerNext.setSingleLine(true); bannerNext.setEllipsize(TextUtils.TruncateAt.END); banner.addView(bannerNext);
        FrameLayout.LayoutParams bannerLayout = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); bannerLayout.setMargins(dp(40), 0, dp(40), dp(28));
        screen.addView(banner, bannerLayout);

        overlay = new LinearLayout(this); overlay.setOrientation(LinearLayout.VERTICAL); overlay.setBackgroundColor(PANEL);
        overlay.setPadding(dp(20), dp(18), dp(12), dp(12));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("LIVE TV", 15, ACCENT); brand.setTypeface(null, Typeface.BOLD); brand.setLetterSpacing(.12f); brand.setPadding(0, 0, dp(14), 0); top.addView(brand);
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(-2, dp(38)); small.setMargins(0, 0, dp(8), 0);
        top.addView(button("Groups", this::chooseGroup), small);
        top.addView(button("Settings", this::settings), small);
        top.addView(button("‹ Movies", this::finish), small);
        overlay.addView(top);
        groupLabel = text(group, 19, INK); groupLabel.setTypeface(null, Typeface.BOLD); groupLabel.setPadding(0, dp(12), 0, 0); overlay.addView(groupLabel);
        overlayStatus = text("", 13, MUTED); overlayStatus.setPadding(0, dp(2), 0, dp(8)); overlay.addView(overlayStatus);
        list = new ListView(this); list.setAdapter(adapter); list.setDivider(null); list.setSelector(android.R.color.transparent);
        list.setItemsCanFocus(false);
        list.setOnItemClickListener((parent, view, position, id) -> { play(visible.get(position)); hideOverlay(); });
        list.setOnItemLongClickListener((parent, view, position, id) -> { toggleFavourite(visible.get(position)); return true; });
        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { adapter.notifyDataSetChanged(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        overlay.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView hint = text("OK play · ▶ or hold OK favourite · ▲▼ zap when hidden · digits jump · Back hide", 12, MUTED); hint.setPadding(0, dp(8), 0, 0); overlay.addView(hint);
        screen.addView(overlay, new FrameLayout.LayoutParams(dp(520), -1, Gravity.START));
        setContentView(screen);
    }

    // ---- data ----
    private File cacheFile() { return new File(getCacheDir(), "livetv-playlist.m3u"); }
    private void loadPlaylist(boolean force) {
        String address = prefs.getString("playlist", "");
        if (playlistTask != null) playlistTask.cancel(true);
        int token = ++generation;
        File cache = cacheFile();
        boolean fresh = cache.isFile() && System.currentTimeMillis() - cache.lastModified() < PLAYLIST_MAX_AGE
                && address.equals(prefs.getString("cachedFrom", ""));
        if (!force && cache.isFile() && address.equals(prefs.getString("cachedFrom", ""))) {
            try { applyPlaylist(IptvPlaylist.parse(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8)), "Saved channel list"); }
            catch (Exception error) { Log.w(TAG, "Cached playlist unreadable: " + error.getClass().getSimpleName()); }
            if (fresh) return;
        }
        if (playlist == null) showMessage("Loading channels…", true);
        overlayStatus.setText("Updating channel list…");
        playlistTask = network.submit(() -> {
            try {
                String body = IptvPlaylist.text(address, 16 * 1024 * 1024);
                IptvPlaylist.Playlist parsed = IptvPlaylist.parse(body);
                try (FileOutputStream out = new FileOutputStream(cache)) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
                prefs.edit().putString("cachedFrom", address).apply();
                main.post(() -> { if (token == generation) applyPlaylist(parsed, "Updated just now"); });
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()) return;
                Log.w(TAG, "Playlist load failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                main.post(() -> {
                    if (token != generation) return;
                    if (playlist == null) { showMessage("Couldn’t load the channel list.\n" + error.getMessage() + "\nPress OK for settings.", false); showOverlay(); }
                    else overlayStatus.setText("Update failed; showing saved list");
                });
            }
        });
    }
    private void applyPlaylist(IptvPlaylist.Playlist parsed, String note) {
        base = parsed; playlist = withLinks(parsed);
        if (!group.equals(ALL) && !group.equals(FAVOURITES) && !playlist.groups.contains(group)) group = ALL;
        filter();
        Log.i(TAG, "Playlist: " + parsed.channels.size() + " channels, " + parsed.groups.size() + " groups, " + parsed.skipped + " skipped, " + parsed.duplicates + " duplicates merged");
        overlayStatus.setText(parsed.channels.size() + " channels · " + note + (parsed.duplicates > 0 ? " · " + parsed.duplicates + " duplicates merged" : "") + (parsed.skipped > 0 ? " · " + parsed.skipped + " unsupported skipped" : ""));
        if (playing == null) {
            String last = prefs.getString("last", "");
            IptvPlaylist.Channel start = null;
            for (IptvPlaylist.Channel channel : playlist.channels) if (channel.has(last)) start = channel;
            if (start != null) { play(start); hideOverlay(); } else { showMessage("", false); showOverlay(); }
        }
        loadGuide();
    }
    /** The user's own AceStream links, appended after the playlist so its channel numbers stay put. */
    private IptvPlaylist.Playlist withLinks(IptvPlaylist.Playlist parsed) {
        List<String[]> links = aceLinks();
        if (links.isEmpty()) return parsed;
        List<IptvPlaylist.Channel> channels = new ArrayList<>(parsed.channels);
        for (String[] link : links)
            channels.add(new IptvPlaylist.Channel(channels.size() + 1, link[0], AceStream.link(link[1]), "", ACE_GROUP, "", "", ""));
        List<String> groups = new ArrayList<>(); groups.add(ACE_GROUP); groups.addAll(parsed.groups);
        return new IptvPlaylist.Playlist(channels, groups, parsed.guideUrl, parsed.skipped, parsed.duplicates);
    }
    private List<String[]> aceLinks() {
        List<String[]> links = new ArrayList<>();
        try {
            org.json.JSONArray saved = new org.json.JSONArray(prefs.getString("acelinks", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                org.json.JSONObject link = saved.getJSONObject(i);
                String id = AceStream.contentId(link.optString("id"));
                if (!id.isEmpty()) links.add(new String[]{link.optString("name", "AceStream " + id.substring(0, 8)), id});
            }
        } catch (Exception error) { Log.w(TAG, "AceStream links unreadable"); }
        return links;
    }
    private void saveAceLinks(List<String[]> links) {
        org.json.JSONArray saved = new org.json.JSONArray();
        try { for (String[] link : links) saved.put(new org.json.JSONObject().put("name", link[0]).put("id", link[1])); }
        catch (org.json.JSONException impossible) { return; }
        prefs.edit().putString("acelinks", saved.toString()).apply();
        if (base != null) { playlist = withLinks(base); if (!group.equals(ALL) && !group.equals(FAVOURITES) && !playlist.groups.contains(group)) group = ALL; filter(); }
    }
    private void addAceLink() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText address = new EditText(this); address.setSingleLine(true); address.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("acestream://… or 40-character content ID"); form.addView(address);
        EditText name = new EditText(this); name.setSingleLine(true); name.setHint("Name (optional)"); form.addView(name);
        new AlertDialog.Builder(this).setTitle("Add AceStream link").setView(form).setPositiveButton("Add", (d, which) -> {
            String id = AceStream.contentId(address.getText().toString());
            if (id.isEmpty()) { overlayStatus.setText("That isn’t an AceStream link or content ID"); return; }
            List<String[]> links = aceLinks();
            for (String[] link : links) if (link[1].equals(id)) { overlayStatus.setText("That AceStream link is already saved"); return; }
            String label = name.getText().toString().trim();
            links.add(new String[]{label.isEmpty() ? "AceStream " + id.substring(0, 8) : label, id});
            saveAceLinks(links); group = ACE_GROUP; filter();
            overlayStatus.setText("Added " + links.get(links.size() - 1)[0]);
        }).setNegativeButton("Cancel", null).show();
    }
    private void removeAceLink() {
        List<String[]> links = aceLinks();
        if (links.isEmpty()) { overlayStatus.setText("No AceStream links saved"); return; }
        String[] names = new String[links.size()];
        for (int i = 0; i < names.length; i++) names[i] = links.get(i)[0];
        new AlertDialog.Builder(this).setTitle("Remove an AceStream link").setItems(names, (d, which) -> {
            String removed = links.remove(which)[0]; saveAceLinks(links); overlayStatus.setText("Removed " + removed);
        }).show();
    }
    private String installedAcePackage() {
        for (String name : AceStream.PACKAGES) {
            try { getPackageManager().getPackageInfo(name, 0); return name; }
            catch (Exception notInstalled) { }
        }
        return "";
    }
    /** Engine down: start it if installed, otherwise point to Google Play. Never installs anything itself. */
    private void aceUnavailable(IptvPlaylist.Channel channel, String id) {
        spinner.setVisibility(View.GONE);
        String installed = installedAcePackage();
        if (installed.isEmpty()) {
            showMessage("AceStream channels need the free Ace Stream Engine app.\nInstall it from Google Play, open it once, then come back.", false);
            new AlertDialog.Builder(this).setTitle("Ace Stream Engine not installed")
                    .setMessage("“" + channel.name + "” is an AceStream link. Install Ace Stream Engine from Google Play, open it once, then come back here.")
                    .setPositiveButton("Open Google Play", (d, which) -> {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + AceStream.STORE_PACKAGE))); }
                        catch (ActivityNotFoundException noStore) {
                            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + AceStream.STORE_PACKAGE))); }
                            catch (ActivityNotFoundException noBrowser) { showMessage("Google Play isn’t available on this TV.\nInstall Ace Stream Engine (" + AceStream.STORE_PACKAGE + ") another way.", false); }
                        }
                    }).setNegativeButton("Cancel", null).show();
            return;
        }
        showMessage("Ace Stream Engine isn’t running.", false);
        new AlertDialog.Builder(this).setTitle("Start Ace Stream Engine")
                .setMessage("Open the Ace Stream app so its engine starts, then press Back to return. The channel retries automatically.")
                .setPositiveButton("Open Ace Stream", (d, which) -> {
                    Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(installed);
                    if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(installed);
                    if (launch != null) startActivity(launch); else showMessage("The Ace Stream app can’t be opened from here.", false);
                })
                .setNeutralButton("Play in Ace Stream", (d, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AceStream.link(id))).setPackage(installed)); }
                    catch (ActivityNotFoundException error) { showMessage("The Ace Stream app didn’t accept the link.", false); }
                }).setNegativeButton("Cancel", null).show();
    }
    private void loadGuide() {
        String address = prefs.getString("guide", "");
        if (address.isEmpty() && playlist != null) address = playlist.guideUrl;
        if (address.isEmpty() || playlist == null) return;
        if (guideTask != null) guideTask.cancel(true);
        Set<String> wanted = new HashSet<>();
        for (IptvPlaylist.Channel channel : playlist.channels) wanted.add(channel.guideKey());
        String source = address;
        guideTask = network.submit(() -> {
            long now = System.currentTimeMillis();
            Log.i(TAG, "Guide: loading");
            try (InputStream input = IptvPlaylist.open(source, 400L * 1024 * 1024)) {
                Map<String, List<IptvPlaylist.Programme>> parsed = IptvPlaylist.guide(input, wanted, now - 3 * 3600 * 1000L, now + 24 * 3600 * 1000L, 60000);
                Log.i(TAG, "Guide: " + parsed.size() + " of " + wanted.size() + " channels matched");
                main.post(() -> { guide = parsed; adapter.notifyDataSetChanged(); if (playing != null && banner.getVisibility() == View.VISIBLE) showBanner(playing); });
            } catch (Throwable error) {
                if (!Thread.currentThread().isInterrupted()) Log.w(TAG, "Guide load failed after " + (System.currentTimeMillis() - now) + "ms", error);
            }
        });
    }
    private void filter() {
        visible.clear();
        if (playlist != null) for (IptvPlaylist.Channel channel : playlist.channels)
            if (group.equals(ALL) || (group.equals(FAVOURITES) ? favourite(channel) : channel.group.equals(group))) visible.add(channel);
        groupLabel.setText(group + " · " + visible.size());
        adapter.notifyDataSetChanged();
    }

    // ---- playback ----
    private boolean favourite(IptvPlaylist.Channel channel) {
        if (favourites.contains(channel.url)) return true;
        for (IptvPlaylist.Channel backup : channel.backups) if (favourites.contains(backup.url)) return true;
        return false;
    }
    private void play(IptvPlaylist.Channel channel) { play(channel, 0); }
    /** attempt 0 is the main stream; later attempts walk the merged duplicate streams. */
    private void play(IptvPlaylist.Channel channel, int attempt) {
        IptvPlaylist.Channel stream = attempt == 0 ? channel : channel.backups.get(attempt - 1);
        playing = channel;
        prefs.edit().putString("last", channel.url).apply();
        if (video != null) { video.setOnPreparedListener(null); video.setOnErrorListener(null); video.setOnInfoListener(null); video.stopPlayback(); screen.removeView(video); }
        video = new VideoView(this);
        // The remote drives the overlay and zapping; the video surface must never take focus.
        video.setFocusable(false); video.setFocusableInTouchMode(false);
        screen.addView(video, 0, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        Map<String, String> headers = new HashMap<>();
        if (!stream.userAgent.isEmpty()) headers.put("User-Agent", stream.userAgent);
        if (!stream.referrer.isEmpty()) headers.put("Referer", stream.referrer);
        showMessage("", true);
        int token = ++generation;
        Log.i(TAG, "Tuning channel " + channel.number + " (" + channel.group + ") stream " + (attempt + 1) + "/" + (channel.backups.size() + 1));
        video.setOnPreparedListener(player -> { if (token == generation) { spinner.setVisibility(View.GONE); player.start(); } });
        video.setOnInfoListener((player, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) spinner.setVisibility(View.VISIBLE);
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) spinner.setVisibility(View.GONE);
            return false;
        });
        video.setOnErrorListener((player, what, extra) -> {
            if (token != generation) return true;
            Log.w(TAG, "Channel " + channel.number + " stream " + (attempt + 1) + " failed: " + what + "/" + extra);
            if (attempt < channel.backups.size()) { main.post(() -> { if (token == generation) play(channel, attempt + 1); }); return true; }
            showMessage(channel.name + " is unavailable right now.\nTry another channel.", false);
            return true;
        });
        String ace = AceStream.contentId(stream.url);
        if (ace.isEmpty()) video.setVideoURI(Uri.parse(stream.url), headers);
        else {
            showMessage("Connecting to AceStream…\nThis can take up to 30 seconds.", true);
            VideoView target = video;
            network.submit(() -> {
                boolean running = AceStream.engineRunning(2000);
                main.post(() -> {
                    if (token != generation || target != video) return;
                    Log.i(TAG, "AceStream engine " + (running ? "running" : "unavailable"));
                    if (running) target.setVideoURI(Uri.parse(AceStream.streamUrl(ace, "golovinema")), Collections.emptyMap());
                    else if (attempt < channel.backups.size()) play(channel, attempt + 1);
                    else aceUnavailable(channel, ace);
                });
            });
        }
        if (attempt == 0) showBanner(channel);
    }
    private void zap(int step) {
        List<IptvPlaylist.Channel> source = visible.isEmpty() && playlist != null ? playlist.channels : visible;
        if (source.isEmpty()) return;
        int index = source.indexOf(playing);
        play(source.get(((index < 0 ? 0 : index + step) % source.size() + source.size()) % source.size()));
    }
    private void jumpToTypedNumber() {
        String typed = digits.toString(); digits.setLength(0);
        if (playlist == null || typed.isEmpty()) return;
        int number = Integer.parseInt(typed);
        for (IptvPlaylist.Channel channel : playlist.channels) if (channel.number == number) { play(channel); return; }
        bannerTitle.setText("No channel " + number);
    }

    // ---- overlay, banner, dialogs ----
    private void showMessage(String value, boolean busy) {
        message.setText(value); message.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
    }
    private String clock(long time) { return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(time)); }
    private void showBanner(IptvPlaylist.Channel channel) {
        long now = System.currentTimeMillis();
        List<IptvPlaylist.Programme> programmes = guide.get(channel.guideKey());
        IptvPlaylist.Programme current = IptvPlaylist.now(programmes, now), next = IptvPlaylist.next(programmes, now);
        bannerTitle.setText(channel.number + "  " + channel.name + (favourite(channel) ? "  ★" : ""));
        bannerNow.setText(current == null ? channel.group : "Now  " + clock(current.start) + "–" + clock(current.stop) + "  " + current.title);
        bannerNext.setText(next == null ? "" : "Next  " + clock(next.start) + "  " + next.title);
        bannerNext.setVisibility(next == null ? View.GONE : View.VISIBLE);
        banner.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideBanner); main.postDelayed(hideBanner, 5000);
    }
    private void showOverlay() {
        overlay.setVisibility(View.VISIBLE); banner.setVisibility(View.GONE);
        int index = Math.max(0, visible.indexOf(playing));
        list.setSelection(index);
        main.post(() -> { list.requestFocus(); list.setSelection(index); adapter.notifyDataSetChanged(); });
    }
    private void hideOverlay() { overlay.setVisibility(View.GONE); }
    private void toggleFavourite(IptvPlaylist.Channel channel) {
        boolean removed = favourites.remove(channel.url);
        for (IptvPlaylist.Channel backup : channel.backups) removed |= favourites.remove(backup.url);
        if (!removed) favourites.add(channel.url);
        prefs.edit().putStringSet("favourites", new HashSet<>(favourites)).apply();
        if (group.equals(FAVOURITES)) filter(); else adapter.notifyDataSetChanged();
        overlayStatus.setText(favourite(channel) ? "Added " + channel.name + " to favourites" : "Removed " + channel.name + " from favourites");
    }
    private void chooseGroup() {
        if (playlist == null) return;
        List<String> options = new ArrayList<>(); options.add(ALL); options.add(FAVOURITES); options.addAll(playlist.groups);
        new AlertDialog.Builder(this).setTitle("Groups").setItems(options.toArray(new String[0]), (dialog, which) -> {
            group = options.get(which); prefs.edit().putString("group", group).apply(); filter(); showOverlay();
        }).show();
    }
    private void settings() {
        String[] items = {"Choose a channel list", "Custom playlist URL (M3U)", "TV guide URL (XMLTV)", "Add AceStream link", "Remove an AceStream link", "Reload channels and guide"};
        new AlertDialog.Builder(this).setTitle("Live TV settings").setItems(items, (dialog, which) -> {
            if (which == 0) choosePreset(false);
            else if (which == 1) ask("Playlist URL", "playlist", true);
            else if (which == 2) ask("TV guide URL (blank uses the playlist’s own)", "guide", false);
            else if (which == 3) addAceLink();
            else if (which == 4) removeAceLink();
            else loadPlaylist(true);
        }).show();
    }
    private void choosePreset(boolean first) {
        String[] names = new String[PRESETS.length + 1];
        for (int i = 0; i < PRESETS.length; i++) names[i] = PRESETS[i][0];
        names[PRESETS.length] = "Custom playlist URL…";
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("Choose a channel list").setItems(names, (d, which) -> {
            if (which == PRESETS.length) { ask("Playlist URL", "playlist", true); return; }
            prefs.edit().putString("playlist", PRESETS[which][1]).putString("guide", PRESETS[which][2]).putString("last", "").apply();
            reset(); loadPlaylist(true);
        });
        if (first) dialog.setOnCancelListener(d -> { if (prefs.getString("playlist", "").isEmpty()) finish(); });
        dialog.show();
    }
    private void ask(String title, String key, boolean playlistKey) {
        EditText field = new EditText(this); field.setSingleLine(true); field.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(prefs.getString(key, ""));
        new AlertDialog.Builder(this).setTitle(title).setView(field).setPositiveButton("Save", (d, which) -> {
            String value = field.getText().toString().trim();
            if (!value.isEmpty() && !IptvPlaylist.playable(value)) { overlayStatus.setText("That address isn’t an http(s) URL"); return; }
            if (playlistKey && value.isEmpty()) return;
            prefs.edit().putString(key, value).apply();
            if (playlistKey) { prefs.edit().putString("last", "").apply(); reset(); loadPlaylist(true); } else loadGuide();
        }).setNegativeButton("Cancel", null).show();
    }
    private void reset() { base = null; playlist = null; playing = null; guide = Collections.emptyMap(); group = ALL; filter(); }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        boolean overlayShown = overlay.getVisibility() == View.VISIBLE;
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9 && digits.length() < 4) {
            digits.append(key - KeyEvent.KEYCODE_0); bannerTitle.setText(digits.toString()); bannerNow.setText(""); bannerNext.setVisibility(View.GONE);
            banner.setVisibility(View.VISIBLE); main.removeCallbacks(commitDigits); main.postDelayed(commitDigits, 1600);
            main.removeCallbacks(hideBanner); main.postDelayed(hideBanner, 5000); return true;
        }
        if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP) { zap(-1); return true; }
        if (key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN) { zap(1); return true; }
        if (key == KeyEvent.KEYCODE_MENU) { settings(); return true; }
        if (key == KeyEvent.KEYCODE_BACK) {
            if (overlayShown && playing != null) { hideOverlay(); return true; }
            return super.onKeyDown(key, event);
        }
        if (overlayShown && key == KeyEvent.KEYCODE_DPAD_RIGHT && list.hasFocus()) {
            int position = list.getSelectedItemPosition();
            if (position >= 0 && position < visible.size()) toggleFavourite(visible.get(position));
            return true;
        }
        if (!overlayShown) {
            if (key == KeyEvent.KEYCODE_DPAD_UP) { zap(-1); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { zap(1); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_INFO) { if (playing != null) showBanner(playing); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_LEFT) { showOverlay(); return true; }
        }
        return super.onKeyDown(key, event);
    }

    @Override protected void onPause() { super.onPause(); if (video != null) video.stopPlayback(); }
    @Override protected void onRestart() { super.onRestart(); if (playing != null) play(playing); }
    @Override protected void onDestroy() {
        super.onDestroy(); generation++; main.removeCallbacksAndMessages(null); network.shutdownNow();
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(LiveTvActivity.this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10), dp(6), dp(10), dp(6));
                TextView number = text("", 15, MUTED); number.setGravity(Gravity.END); number.setPadding(0, 0, dp(12), 0);
                row.addView(number, new LinearLayout.LayoutParams(dp(44), -2));
                ImageView logo = new ImageView(LiveTvActivity.this); logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                row.addView(logo, new LinearLayout.LayoutParams(dp(54), dp(36)));
                LinearLayout names = new LinearLayout(LiveTvActivity.this); names.setOrientation(LinearLayout.VERTICAL); names.setPadding(dp(12), 0, 0, 0);
                TextView name = text("", 17, INK); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); names.addView(name);
                TextView now = text("", 13, MUTED); now.setSingleLine(true); now.setEllipsize(TextUtils.TruncateAt.END); names.addView(now);
                row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            }
            IptvPlaylist.Channel channel = visible.get(position);
            TextView number = (TextView) row.getChildAt(0);
            ImageView logo = (ImageView) row.getChildAt(1);
            LinearLayout names = (LinearLayout) row.getChildAt(2);
            TextView name = (TextView) names.getChildAt(0), now = (TextView) names.getChildAt(1);
            boolean selected = list.getSelectedItemPosition() == position && list.hasFocus();
            row.setBackground(selected ? shape(ACCENT) : channel == playing ? shape(Color.rgb(37, 44, 58)) : null);
            number.setText(String.valueOf(channel.number)); number.setTextColor(selected ? BG : MUTED);
            name.setText((favourite(channel) ? "★ " : "") + channel.name); name.setTextColor(selected ? BG : INK);
            IptvPlaylist.Programme current = IptvPlaylist.now(guide.get(channel.guideKey()), System.currentTimeMillis());
            now.setText(current != null ? current.title : channel.group); now.setTextColor(selected ? BG : MUTED);
            logo.setImageDrawable(null);
            if (!channel.logo.isEmpty()) logos.load(channel.logo, logo);
            return row;
        }
    }
}
