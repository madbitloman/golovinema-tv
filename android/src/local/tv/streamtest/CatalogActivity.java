package local.tv.streamtest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CatalogActivity extends Activity {
    private static final int BG = Color.rgb(11, 14, 20), PANEL = Color.rgb(25, 30, 40), INK = Color.rgb(246, 244, 239), MUTED = Color.rgb(163, 172, 187), ACCENT = Color.rgb(255, 194, 98);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private CatalogStore store;
    private PosterLoader posters;
    private TasteStore taste;
    private DeviceKeyStore ratingKeys;
    private final OmdbClient ratings = new OmdbClient();
    private int ratingGeneration;
    private LinearLayout root, body;
    private ScrollView scroll;
    private TextView notice;
    private EditText queryInput;
    private List<Movie> home = new ArrayList<>(), results = new ArrayList<>();
    private CatalogParser.Page currentPage;
    private String mode = "home", query = "", browseTitle = "Movies", browsePath = "/movies/", returnMode = "home", focusMovie = "";
    private int generation, searchOffset;
    private boolean loading, returnedFromPlayer;
    private boolean detailsLoading;
    private boolean homeRefreshing;
    private long homeRefreshAttempt;
    private int homeRequest;
    private List<Movie> freshHorror;
    private String homeNotice = "Saved catalog", detailsNotice = "";
    private Movie selected;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i("Cinema", "Catalog activity created");
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
        String imported = ServiceKeyImporter.importPending(this);
        store = new CatalogStore(this); posters = new PosterLoader(this); taste = new TasteStore(this, this::tasteChanged); ratingKeys = new DeviceKeyStore(this, "omdb");
        home = store.all(); showHome(true);
        if (!imported.isEmpty()) Toast.makeText(this, imported, Toast.LENGTH_LONG).show();
        taste.refresh(store.all(), false);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setFontFeatureSettings("kern"); return view;
    }
    private GradientDrawable background(int color, int border) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(8));
        if (border != 0) shape.setStroke(dp(2), border); return shape;
    }
    private Button button(String title, String key, Runnable action) {
        Button view = new Button(this); view.setText(title); view.setTag(key); view.setTextSize(16); view.setAllCaps(false);
        view.setMinHeight(0); view.setMinimumHeight(0); view.setMinWidth(0); view.setMinimumWidth(0);
        view.setPadding(dp(19), 0, dp(19), 0); view.setTextColor(INK); view.setBackground(background(PANEL, 0));
        view.setOnFocusChangeListener((v, focus) -> { view.setBackground(background(focus ? ACCENT : PANEL, 0)); view.setTextColor(focus ? BG : INK); });
        view.setOnClickListener(v -> action.run()); return view;
    }
    private void addButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, dp(42)); layout.setMargins(0, dp(4), dp(10), dp(4)); row.addView(button, layout);
    }
    private void shell(String active) {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        LinearLayout nav = new LinearLayout(this); nav.setGravity(Gravity.CENTER_VERTICAL); nav.setPadding(dp(36), dp(8), dp(30), dp(8));
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.logo); logo.setContentDescription("GOLOVINEMA"); nav.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView brand = text("GOLOVINEMA", 17, INK); brand.setLetterSpacing(.06f); brand.setTypeface(null, Typeface.BOLD); brand.setPadding(dp(8), 0, dp(20), 0); brand.setSingleLine(true); brand.setIncludeFontPadding(false); nav.addView(brand);
        addButton(nav, button("Home", "nav:home", () -> showHome(homeNeedsRefresh())));
        addButton(nav, button("TV Shows", "nav:shows", () -> browse("TV Shows", "/tv-series/")));
        addButton(nav, button("Live TV", "nav:live", () -> startActivity(new Intent(this, LiveTvActivity.class))));
        addButton(nav, button("Search", "nav:search", () -> { query = ""; results.clear(); currentPage = null; showSearch(); }));
        addButton(nav, button("My List", "nav:saved", this::showSaved));
        View spacer = new View(this); nav.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        addButton(nav, button("⋯", "nav:tools", this::tools));
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(66)));
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(0, 0, 0, dp(36));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }
    private void heading(String value, String sub) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(40), dp(24), dp(40), dp(18));
        TextView title = text(value, 30, INK); title.setTypeface(null, Typeface.BOLD); box.addView(title);
        if (!sub.isEmpty()) { TextView subtitle = text(sub, 15, MUTED); subtitle.setPadding(0, dp(8), 0, 0); box.addView(subtitle); }
        body.addView(box);
    }
    private void status(String value) {
        notice = text(value, 13, MUTED); notice.setPadding(dp(40), dp(8), dp(40), dp(12)); body.addView(notice);
    }
    private boolean homeNeedsRefresh() {
        return homeRefreshAttempt == 0 || android.os.SystemClock.elapsedRealtime() - homeRefreshAttempt >= 15 * 60 * 1000L;
    }
    private void showHome(boolean refresh) {
        mode = "home"; generation++; loading = false; homeRefreshing = refresh; shell("home");
        List<Movie> continuing = store.continuing();
        List<Movie> horror = freshHorror == null ? store.horror() : freshHorror;
        if (!horror.isEmpty()) hero(horror.get(0)); else if (!home.isEmpty()) hero(home.get(0));
        genreRow();
        if (!continuing.isEmpty()) row("Continue watching", continuing);
        row("Horror for you", horror);
        if (taste.hasProfile()) row("For you · from your searches", taste.rank(store.all()));
        row("TV Shows", store.shows());
        row("Tonight’s picks", home.subList(0, Math.min(home.size(), 12)));
        if (home.size() > 12) row("More to explore", home.subList(12, Math.min(home.size(), 36)));
        if (!store.saved().isEmpty()) row("My List", store.saved());
        status(refresh ? "Refreshing horror and the catalog…" : homeNotice);
        LinearLayout actions = new LinearLayout(this); actions.setPadding(dp(40), 0, 0, 0);
        addButton(actions, button("Browse all movies", "browse:all", () -> browse("Movies", "/movies/")));
        addButton(actions, button("Refresh", "home:refresh", () -> showHome(true))); body.addView(actions);
        restoreFocus("nav:home");
        if (refresh) {
            homeRefreshAttempt = android.os.SystemClock.elapsedRealtime();
            int request = ++homeRequest;
            network.execute(() -> {
                CatalogParser.Page horrorPage = null; CatalogParser.Page page = null; String failure = "Catalog unavailable";
                try { horrorPage = store.page("/genre/horror/"); }
                catch (Exception error) { if (CatalogHttp.cancelled(error)) return; failure = CatalogHttp.problem(error); Log.w("Cinema", "Horror catalog: " + failure); }
                try { page = store.page("/home/"); }
                catch (Exception error) { if (CatalogHttp.cancelled(error)) return; failure = CatalogHttp.problem(error); Log.w("Cinema", "Home catalog: " + failure); }
                CatalogParser.Page answer = page, horrorAnswer = horrorPage; boolean updated = horrorPage != null; String unavailable = failure;
                main.post(() -> {
                    if (request != homeRequest || isFinishing() || isDestroyed()) return;
                    // Keep fresh data even if the user browsed away during the request.
                    if (answer != null) home = answer.movies;
                    if (horrorAnswer != null) freshHorror = horrorAnswer.movies;
                    homeNotice = updated && answer != null ? "Horror first · updated just now" : updated || answer != null ? unavailable + " · catalog partly updated" : unavailable + " · showing saved titles. Search still works with saved titles.";
                    if (!mode.equals("home")) return;
                    String oldFocus = currentKey(); int oldY = scroll.getScrollY();
                    showHome(false); restore(oldFocus, oldY);
                });
            });
        }
    }
    private void hero(Movie movie) {
        FrameLayout frame = new FrameLayout(this);
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); frame.addView(image, new FrameLayout.LayoutParams(-1, -1)); posters.load(movie.backdrop, image);
        View shade = new View(this); shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{BG, 0xE60B0E14, 0x220B0E14})); frame.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        View fade = new View(this); fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x000B0E14, BG})); frame.addView(fade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setGravity(Gravity.CENTER_VERTICAL); content.setPadding(dp(40), dp(18), dp(16), dp(18));
        TextView overline = text(movie.hasGenre("Horror") ? "HORROR FIRST" : "YOUR NEXT WATCH", 11, ACCENT); overline.setLetterSpacing(.16f); content.addView(overline);
        TextView title = text(movie.title, 34, INK); title.setTypeface(null, Typeface.BOLD); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); title.setPadding(0, dp(8), 0, dp(8)); content.addView(title);
        TextView description = text(movie.description.isEmpty() ? "Settle in. Find something worth watching." : movie.description, 16, MUTED); description.setMaxLines(2); description.setEllipsize(TextUtils.TruncateAt.END); content.addView(description);
        LinearLayout actions = new LinearLayout(this); actions.setPadding(0, dp(12), 0, 0);
        addButton(actions, button(movie.series() ? "Choose episodes" : "▶  Play", "hero:play", () -> { if (movie.series()) details(movie); else launch(movie, store.position(movie)); }));
        addButton(actions, button("Details", "hero:details", () -> details(movie))); content.addView(actions);
        frame.addView(content, new FrameLayout.LayoutParams(dp(560), -1)); body.addView(frame, new LinearLayout.LayoutParams(-1, dp(250)));
    }
    private void genreRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this); row.setPadding(dp(40), dp(10), dp(40), dp(8));
        String[][] genres = {{"Horror", "/genre/horror/"}, {"All movies", "/movies/"}, {"Thriller", "/genre/thriller/"}, {"Mystery", "/genre/mystery/"}, {"Action", "/genre/action/"}, {"Comedy", "/genre/comedy/"}, {"Drama", "/genre/drama/"}, {"Sci-Fi", "/genre/sci-fi/"}, {"Animation", "/genre/animation/"}};
        addButton(row, button("Refresh", "home:refresh-top", () -> showHome(true)));
        for (String[] genre : genres) addButton(row, button(genre[0], "genre:" + genre[1], () -> browse(genre[0], genre[1])));
        scroll.addView(row); body.addView(scroll);
    }
    private View card(Movie candidate, int width) {
        Movie movie = store.cached(candidate);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(5), dp(5), dp(5), dp(7));
        card.setFocusable(true); card.setClickable(true); card.setTag("movie:" + movie.id); card.setContentDescription(movie.title); card.setBackground(background(PANEL, 0));
        FrameLayout cover = new FrameLayout(this); cover.setBackground(background(Color.rgb(30 + Math.abs(movie.id.hashCode() % 20), 38, 51), 0));
        TextView fallback = text(movie.title, 19, INK); fallback.setTypeface(null, Typeface.BOLD); fallback.setGravity(Gravity.CENTER); fallback.setPadding(dp(10), dp(10), dp(10), dp(10)); cover.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); cover.addView(image, new FrameLayout.LayoutParams(-1, -1)); posters.load(movie.poster, image);
        card.addView(cover, new LinearLayout.LayoutParams(-1, dp((width - 10) * 3 / 2)));
        TextView title = text(movie.title, 14, INK); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END); title.setPadding(dp(4), dp(8), dp(4), dp(2)); card.addView(title);
        TextView meta = text((movie.series() ? "TV season" : "Movie") + (movie.year.isEmpty() ? "" : " · " + movie.year), 11, MUTED); meta.setPadding(dp(4), 0, dp(4), 0); card.addView(meta);
        if (!movie.ratingLabel().isEmpty()) { TextView rating = text(movie.ratingLabel(), 11, ACCENT); rating.setPadding(dp(4), dp(3), 0, 0); card.addView(rating); }
        card.setOnFocusChangeListener((view, focus) -> { card.setBackground(background(focus ? Color.rgb(55, 48, 38) : PANEL, focus ? ACCENT : 0)); card.animate().scaleX(focus ? 1.035f : 1).scaleY(focus ? 1.035f : 1).setDuration(120).start(); if (focus) focusMovie = movie.id; });
        card.setOnClickListener(v -> details(movie)); return card;
    }
    private void row(String title, List<Movie> movies) {
        if (movies.isEmpty()) return;
        TextView heading = text(title, 20, INK); heading.setTypeface(null, Typeface.BOLD); heading.setPadding(dp(40), dp(18), dp(40), dp(6)); body.addView(heading);
        HorizontalScrollView horizontal = new HorizontalScrollView(this); horizontal.setHorizontalScrollBarEnabled(false); horizontal.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this); row.setPadding(dp(40), dp(8), dp(40), dp(10)); row.setClipChildren(false); row.setClipToPadding(false);
        for (Movie movie : movies.subList(0, Math.min(movies.size(), 24))) { LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(dp(132), -2); layout.setMargins(0, 0, dp(14), 0); row.addView(card(movie, 132), layout); }
        horizontal.addView(row); body.addView(horizontal);
    }
    private void grid(List<Movie> movies) {
        GridLayout grid = new GridLayout(this); int width = (int)(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int columns = Math.max(3, Math.min(7, (width - 80) / 148)); int cardWidth = (width - 80) / columns - 14;
        grid.setColumnCount(columns); grid.setPadding(dp(40), dp(10), dp(26), dp(10)); grid.setClipChildren(false); grid.setClipToPadding(false);
        for (Movie movie : movies) { GridLayout.LayoutParams layout = new GridLayout.LayoutParams(); layout.width = dp(cardWidth); layout.height = -2; layout.setMargins(0, 0, dp(14), dp(20)); grid.addView(card(movie, cardWidth), layout); }
        body.addView(grid);
    }
    private void showSaved() {
        mode = "saved"; generation++; loading = false; shell("saved"); List<Movie> saved = store.saved();
        heading("My List", saved.isEmpty() ? "Open a title and choose + My List to save it here." : saved.size() + " titles for later"); grid(saved); restoreFocus("nav:saved");
    }
    private void showSearch() {
        mode = "search"; generation++; loading = false; shell("search"); heading("Find something to watch", "Search movies and TV shows by title");
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(40), 0, dp(40), dp(16));
        queryInput = new EditText(this); queryInput.setSingleLine(true); queryInput.setText(query); queryInput.setTextSize(19); queryInput.setTextColor(INK); queryInput.setHintTextColor(MUTED); queryInput.setHint("Movie or show…");
        queryInput.setInputType(InputType.TYPE_CLASS_TEXT); queryInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH); queryInput.setPadding(dp(16), dp(8), dp(16), dp(8)); queryInput.setBackground(background(PANEL, 0)); queryInput.setTag("search:input");
        queryInput.setOnEditorActionListener((view, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) { submitSearch(); return true; } return false; });
        LinearLayout.LayoutParams field = new LinearLayout.LayoutParams(0, dp(48), 1); field.setMargins(0, 0, dp(12), 0); row.addView(queryInput, field);
        addButton(row, button("Search", "search:submit", this::submitSearch)); body.addView(row);
        status(query.isEmpty() ? "Type a title, then choose Search." : "Results for “" + query + "”");
        if (!query.isEmpty()) { grid(results); pagination(); }
        queryInput.requestFocus();
    }
    private void submitSearch() {
        String value = queryInput.getText().toString().trim(); if (value.isEmpty()) return;
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(queryInput.getWindowToken(), 0);
        query = value; results.clear(); searchOffset = 0; currentPage = null; showSearch(); queryInput.clearFocus(); loadSearch(false);
        taste.recordSearch(value); taste.refresh(store.all(), false);
    }
    private void loadSearch(boolean append) {
        if (loading) return; loading = true; notice.setText("Searching “" + query + "”…"); int token = ++generation; String requested = query; int offset = searchOffset;
        network.execute(() -> {
            CatalogParser.Page page;
            try { page = store.search(requested, offset); }
            catch (Exception error) { Log.w("Cinema", "Online search: " + CatalogHttp.problem(error)); page = store.offline(requested); page.notice = CatalogHttp.problem(error) + " · results from saved titles only"; }
            CatalogParser.Page answer = page;
            main.post(() -> {
                if (token != generation) return;
                if (append && answer.cached) {
                    showSearch(); notice.setText(answer.notice + " · select Load more to retry");
                    restoreFocus("more"); return;
                }
                int added = merge(answer.movies, append); searchOffset += answer.rawCount; currentPage = answer;
                if (append && added == 0 && (!answer.movies.isEmpty() || answer.rawCount == 0)) currentPage.next = "end";
                showSearch(); notice.setText(answer.cached ? answer.notice : results.size() + " titles found");
                focusFirst();
            });
        });
    }
    private int merge(List<Movie> movies, boolean append) {
        if (!append) results.clear(); int before = results.size();
        for (Movie movie : movies) { boolean exists = false; for (Movie old : results) if (old.id.equals(movie.id)) { exists = true; break; } if (!exists) results.add(movie); }
        int added = results.size() - before;
        // Keep a bounded number of decoded poster views on older TV hardware.
        while (results.size() > 120) results.remove(0);
        return added;
    }
    private void browse(String title, String path) {
        mode = "browse"; browseTitle = title; browsePath = path; results.clear(); currentPage = null; renderBrowse(); loadPage(path, false);
    }
    private void renderBrowse() {
        mode = "browse"; generation++; loading = false; shell("home"); heading(browseTitle, "Choose a title to see details and play"); status("");
        grid(results); pagination();
    }
    private void loadPage(String path, boolean append) {
        if (loading) return; loading = true; notice.setText("Loading titles…"); int token = ++generation; String requestMode = mode;
        network.execute(() -> {
            CatalogParser.Page page;
            try { page = store.page(path); }
            catch (Exception error) { Log.w("Cinema", "Catalog page: " + CatalogHttp.problem(error)); page = append ? new CatalogParser.Page() : store.offlinePage(path); page.cached = true; page.notice = CatalogHttp.problem(error) + (append ? " · more titles unavailable" : page.movies.isEmpty() ? " · no saved titles in this category yet" : " · showing saved titles in this category"); }
            CatalogParser.Page answer = page;
            main.post(() -> {
                if (token != generation) return;
                if (append && answer.cached) {
                    // Preserve the last successful page and its next URL for retry.
                    if (requestMode.equals("search")) showSearch(); else renderBrowse();
                    notice.setText(answer.notice + " · select Load more to retry");
                    restoreFocus("more"); return;
                }
                int added = merge(answer.movies, append); currentPage = answer;
                if (append && added == 0) currentPage.next = "end";
                if (requestMode.equals("search")) showSearch(); else renderBrowse();
                notice.setText(answer.cached ? answer.notice : results.size() + " titles"); focusFirst();
            });
        });
    }
    private void pagination() {
        if (currentPage == null || currentPage.cached || "end".equals(currentPage.next)) return;
        if (!currentPage.apiSearch && currentPage.next.isEmpty()) return;
        if (currentPage.apiSearch && currentPage.rawCount == 0) return;
        LinearLayout row = new LinearLayout(this); row.setPadding(dp(40), dp(4), dp(40), dp(20));
        addButton(row, button("Load more", "more", () -> {
            if (!currentPage.next.isEmpty()) loadPage(currentPage.next, true); else loadSearch(true);
        })); body.addView(row);
    }
    private void details(Movie candidate) {
        Movie movie = store.cached(candidate);
        if (!mode.equals("details")) returnMode = mode;
        selected = movie; mode = "details"; detailsLoading = true; detailsNotice = movie.series() ? "Loading episode list…" : "Loading title details…"; int token = ++generation; renderDetails(movie);
        network.execute(() -> {
            try { Movie full = store.details(movie); main.post(() -> { if (token == generation && mode.equals("details")) { String key = currentKey(); full.retainDetails(selected); selected = full; detailsLoading = false; detailsNotice = full.series() && full.episodes.isEmpty() ? "The source returned no episode list. Try again later." : ""; renderDetails(full); restoreFocus(key); } }); }
            catch (Exception error) { Log.w("Cinema", "Title details: " + error.getClass().getSimpleName()); main.post(() -> { if (token == generation && mode.equals("details")) { String key = currentKey(); detailsLoading = false;
                detailsNotice = !selected.description.isEmpty() ? "" : selected.series() ? "Episode list unavailable. Choose Retry episodes." : "Details unavailable · using saved title information"; renderDetails(selected); restoreFocus(key); } }); }
        });
        if (ratingKeys.present() && !movie.imdbId.isEmpty() && System.currentTimeMillis() - movie.imdbChecked > 7L * 86400000) fetchRating(movie, movie.imdbId);
    }
    private void renderDetails(Movie movie) {
        FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(BG);
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); frame.addView(image, new FrameLayout.LayoutParams(-1, -1)); posters.load(movie.backdrop, image);
        View shade = new View(this); shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{BG, 0xF00B0E14, 0x880B0E14})); frame.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(44), dp(24), dp(32), dp(30));
        LinearLayout top = new LinearLayout(this); addButton(top, button("‹  Back", "details:back", this::backToCatalog)); content.addView(top);
        TextView eyebrow = text(movie.series() ? "GOLOVINEMA  /  TV SEASON" : "GOLOVINEMA  /  MOVIE", 12, ACCENT); eyebrow.setPadding(0, dp(24), 0, dp(12)); eyebrow.setLetterSpacing(.08f); content.addView(eyebrow);
        TextView title = text(movie.title, 36, INK); title.setTypeface(null, Typeface.BOLD); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); content.addView(title);
        TextView metadata = text(movie.metadata(), 15, ACCENT); metadata.setPadding(0, dp(14), 0, dp(14)); content.addView(metadata);
        TextView description = text(movie.description.isEmpty() ? (movie.series() ? "Choose an episode to start watching." : "Choose Play to start watching.") : movie.description, 17, MUTED); description.setMaxLines(4); description.setEllipsize(TextUtils.TruncateAt.END); content.addView(description);
        LinearLayout actions = new LinearLayout(this); actions.setPadding(0, dp(20), 0, 0);
        int position = store.position(movie); Button play;
        if (movie.series()) {
            play = button(movie.episodes.isEmpty() ? (detailsLoading ? "Loading episodes…" : "Retry episodes") : "Episodes (" + movie.episodes.size() + ")", "details:play", () -> { if (movie.episodes.isEmpty()) details(movie); else chooseEpisode(movie); });
            play.setEnabled(!movie.episodes.isEmpty() || !detailsLoading); play.setAlpha(play.isEnabled() ? 1f : .5f); addButton(actions, play);
            String last = store.lastEpisode(movie);
            for (Movie.Episode episode : movie.episodes) if (episode.id.equals(last) && position >= 10000) {
                addButton(actions, button("▶ Resume episode", "details:resume", () -> launchEpisode(movie, episode, position))); break;
            }
        } else {
            play = button(position >= 10000 ? "▶  Resume" : "▶  Play", "details:play", () -> launch(movie, position)); addButton(actions, play);
            if (position >= 10000) addButton(actions, button("Start over", "details:restart", () -> launch(movie, 0)));
        }
        addButton(actions, button(store.saved(movie) ? "✓  My List" : "+  My List", "details:save", () -> { store.toggleSaved(movie); renderDetails(movie); restoreFocus("details:save"); })); content.addView(actions);
        LinearLayout ratingActions = new LinearLayout(this);
        addButton(ratingActions, button("IMDb rating", "details:rating", () -> ratingAction(movie))); content.addView(ratingActions);
        if (!movie.imdbId.isEmpty()) {
            TextView attribution = text((movie.imdbRating.isEmpty() ? "No IMDb rating yet" : "IMDb rating via OMDb" + (movie.imdbVotes.isEmpty() ? "" : " · " + movie.imdbVotes + " votes")) + "\nMatched: " + movie.imdbTitle, 12, MUTED); content.addView(attribution);
        }
        if (!detailsNotice.isEmpty()) { TextView message = text(detailsNotice, 14, MUTED); message.setPadding(0, dp(12), 0, 0); content.addView(message); }
        ScrollView detailScroll = new ScrollView(this); detailScroll.setFillViewport(true); detailScroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(dp(740), -1, Gravity.LEFT); frame.addView(detailScroll, layout); setContentView(frame); root = null;
        if (play.isEnabled()) play.requestFocus(); else restoreFocus("details:back");
    }
    private void chooseEpisode(Movie movie) {
        String[] labels = new String[movie.episodes.size()];
        for (int i = 0; i < labels.length; i++) { Movie.Episode episode = movie.episodes.get(i); labels[i] = episode.title + (store.position(movie, episode.id) >= 10000 ? " · Resume" : ""); }
        new AlertDialog.Builder(this).setTitle(movie.title).setItems(labels, (dialog, which) -> {
            Movie.Episode episode = movie.episodes.get(which); int position = store.position(movie, episode.id);
            if (position < 10000) launchEpisode(movie, episode, 0);
            else new AlertDialog.Builder(this).setTitle(episode.title).setItems(new String[]{"Resume", "Start over"}, (d, choice) -> launchEpisode(movie, episode, choice == 0 ? position : 0)).setNegativeButton("Cancel", null).show();
        }).setNegativeButton("Cancel", null).show();
    }
    private void launch(Movie movie, int position) {
        launchEpisode(movie, new Movie.Episode("1", ""), position);
    }
    private void launchEpisode(Movie movie, Movie.Episode episode, int position) {
        store.remember(java.util.Collections.singletonList(movie)); returnedFromPlayer = true; selected = movie;
        Intent intent = new Intent(this, MainActivity.class); intent.putExtra("provider", movie.provider); intent.putExtra("playerUrl", movie.playerUrl); intent.putExtra("sourcePath", movie.sourcePath); intent.putExtra("movie", movie.id); intent.putExtra("title", movie.title + (movie.series() ? " · " + episode.title : "")); intent.putExtra("series", movie.series()); intent.putExtra("episode", episode.id); intent.putExtra("resume", Math.max(0, position)); startActivity(intent);
    }
    private void backToCatalog() {
        generation++;
        if (returnMode.equals("saved")) showSaved(); else if (returnMode.equals("search")) showSearch(); else if (returnMode.equals("browse")) renderBrowse(); else showHome(false);
        if (selected != null) restoreFocus("movie:" + selected.id);
    }
    private String currentKey() { View focus = getCurrentFocus(); return focus != null && focus.getTag() instanceof String ? (String)focus.getTag() : ""; }
    private void restoreFocus(String key) { if (key == null || key.isEmpty()) return; View view = getWindow().getDecorView().findViewWithTag(key); if (view != null) view.requestFocus(); }
    private void restore(String key, int y) { main.post(() -> { restoreFocus(key); scroll.scrollTo(0, y); }); }
    private void focusFirst() { if (!results.isEmpty()) restoreFocus("movie:" + results.get(0).id); else restoreFocus(mode.equals("search") ? "search:submit" : "nav:home"); }
    private void tasteChanged() {
        if (mode.equals("home") && !homeRefreshing) { String key = currentKey(); int y = scroll.getScrollY(); showHome(false); restore(key, y); }
    }
    private void tasteSettings() {
        String toggle = taste.enabled() ? "Pause Jev" : "Enable Jev";
        new AlertDialog.Builder(this).setTitle("Your taste · Jev").setMessage(taste.summary())
            .setPositiveButton("Options", (dialog, which) -> new AlertDialog.Builder(this).setTitle("Preference settings")
                .setItems(new String[]{toggle, "Add or replace TypeSafe key", "Update preferences now", "Clear search history and learned preferences", "Remove API key"}, (d, action) -> {
                    if (action == 0) { if (!taste.hasKey()) tasteKey(); else { taste.setEnabled(!taste.enabled()); taste.refresh(store.all(), true); tasteChanged(); } }
                    if (action == 1) tasteKey();
                    if (action == 2) { taste.refresh(store.all(), true); Toast.makeText(this, taste.hasKey() ? "An update uses your recent searches; updates are limited to once per 30 seconds." : "Add a TypeSafe API key first", Toast.LENGTH_LONG).show(); }
                    if (action == 3) { taste.clearHistory(); tasteChanged(); Toast.makeText(this, "Search history and learned preferences cleared. Horror remains your choice.", Toast.LENGTH_LONG).show(); }
                    if (action == 4) { taste.removeKey(); tasteChanged(); }
                }).setNegativeButton("Back", null).show())
            .setNegativeButton("Close", null).show();
    }
    private void tasteKey() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); input.setHint("TypeSafe API key");
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); input.setSaveEnabled(false);
        new AlertDialog.Builder(this).setTitle("Connect Jev").setMessage("Your own key stays encrypted on this TV. Enabling Jev sends up to 30 recent search phrases and a small catalog shortlist to TypeSafe for preferences and recommendations.")
            .setView(input).setNegativeButton("Cancel", (d, w) -> input.setText(""))
            .setPositiveButton("Save & enable", (d, w) -> {
                try { taste.saveKey(input.getText().toString()); taste.refresh(store.all(), true); Toast.makeText(this, "Jev enabled. Future searches will help update your preferences.", Toast.LENGTH_LONG).show(); }
                catch (Exception error) { Toast.makeText(this, "Could not securely save this key", Toast.LENGTH_LONG).show(); }
                finally { input.setText(""); }
            }).show();
    }
    private void ratingSettings() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); input.setHint("OMDb API key"); input.setSaveEnabled(false); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        new AlertDialog.Builder(this).setTitle("IMDb ratings · OMDb").setMessage("Add your own OMDb API key. It stays encrypted on this TV. Movie/show title queries and selected IMDb IDs go to OMDb. Confirm the title and year before saving a match; ratings are cached for 7 days.")
            .setView(input).setNegativeButton("Cancel", (d, w) -> input.setText(""))
            .setNeutralButton("Remove key", (d, w) -> { ratingGeneration++; ratingKeys.remove(); input.setText(""); })
            .setPositiveButton("Save key", (d, w) -> {
                try { ratingKeys.save(input.getText().toString()); Toast.makeText(this, "Key saved. Open a title and choose IMDb rating.", Toast.LENGTH_LONG).show(); }
                catch (Exception error) { Toast.makeText(this, "Could not securely save this key", Toast.LENGTH_LONG).show(); }
                finally { input.setText(""); }
            }).show();
    }
    private void ratingAction(Movie movie) {
        if (!ratingKeys.present()) { ratingSettings(); return; }
        if (movie.imdbId.isEmpty()) { searchRatings(movie); return; }
        new AlertDialog.Builder(this).setTitle(movie.imdbTitle).setItems(new String[]{"Refresh IMDb rating", "Choose a different title match", "Clear IMDb match"}, (d, which) -> {
            if (which == 0) fetchRating(movie, movie.imdbId);
            if (which == 1) searchRatings(movie);
            if (which == 2) { ratingGeneration++; movie.imdbId = ""; movie.imdbRating = ""; movie.imdbVotes = ""; movie.imdbTitle = ""; movie.imdbChecked = System.currentTimeMillis(); store.remember(java.util.Collections.singletonList(movie)); selected = movie; renderDetails(movie); restoreFocus("details:rating"); }
        }).setNegativeButton("Cancel", null).show();
    }
    private boolean ratingCurrent(int token, int request, String id) { return token == generation && request == ratingGeneration && mode.equals("details") && selected != null && selected.id.equals(id); }
    private void searchRatings(Movie movie) {
        int token = generation, request = ++ratingGeneration;
        Toast.makeText(this, "Finding IMDb matches…", Toast.LENGTH_SHORT).show();
        network.execute(() -> {
            try {
                org.json.JSONArray matches = ratings.search(movie, ratingKeys.read());
                main.post(() -> {
                    if (!ratingCurrent(token, request, movie.id)) return;
                    if (matches.length() == 0) { Toast.makeText(this, "No matching titles found in OMDb", Toast.LENGTH_LONG).show(); return; }
                    String[] labels = new String[matches.length()];
                    for (int i = 0; i < labels.length; i++) { org.json.JSONObject item = matches.optJSONObject(i); labels[i] = TasteProtocol.bounded(item.optString("Title"), 120) + " (" + item.optString("Year") + ")"; }
                    new AlertDialog.Builder(this).setTitle("Confirm the title and year").setItems(labels, (d, choice) -> fetchRating(selected, matches.optJSONObject(choice).optString("imdbID"))).setNegativeButton("Cancel", null).show();
                });
            } catch (Exception error) { ratingFailure(token, request, movie.id, error); }
        });
    }
    private void fetchRating(Movie movie, String imdbId) {
        int token = generation, request = ++ratingGeneration;
        network.execute(() -> {
            try {
                org.json.JSONObject answer = ratings.rating(imdbId, ratingKeys.read());
                main.post(() -> {
                    if (!ratingCurrent(token, request, movie.id)) return;
                    String focus = currentKey(); Movie updated = Movie.fromJson(selected.json());
                    updated.imdbId = answer.optString("id"); updated.imdbRating = answer.optString("rating"); updated.imdbVotes = answer.optString("votes"); updated.imdbTitle = answer.optString("title") + " (" + answer.optString("year") + ")"; updated.imdbChecked = System.currentTimeMillis();
                    store.remember(java.util.Collections.singletonList(updated)); selected = updated; renderDetails(updated); restoreFocus(focus);
                });
            } catch (Exception error) { ratingFailure(token, request, movie.id, error); }
        });
    }
    private void ratingFailure(int token, int request, String id, Exception error) {
        Log.w("Cinema", "IMDb lookup failed: " + error.getClass().getSimpleName());
        main.post(() -> { if (ratingCurrent(token, request, id)) Toast.makeText(this, "IMDb lookup unavailable. Check your OMDb key and connection.", Toast.LENGTH_LONG).show(); });
    }
    private void tools() {
        new AlertDialog.Builder(this).setTitle("GOLOVINEMA").setItems(new String[]{"Refresh catalog", "Open a movie or season link", "Picture & sound test", "Your taste · Jev", "IMDb ratings · OMDb"}, (dialog, which) -> {
            if (which == 0) showHome(true);
            else if (which == 2) startActivity(new Intent(this, MainActivity.class));
            else if (which == 3) tasteSettings();
            else if (which == 4) ratingSettings();
            else {
                EditText input = new EditText(this); input.setSingleLine(true); input.setHint("Paste a movie or season page URL");
                new AlertDialog.Builder(this).setTitle("Open title").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Open", (d, w) -> {
                    Movie movie = CatalogParser.movie(input.getText().toString().trim(), "");
                    if (movie == null) Toast.makeText(this, "Use a movie or season page link from the catalog", Toast.LENGTH_LONG).show(); else details(movie);
                }).show();
            }
        }).show();
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_BACK) {
            if (mode.equals("details")) { backToCatalog(); return true; }
            if (!mode.equals("home")) { showHome(false); return true; }
        }
        return super.onKeyDown(key, event);
    }
    @Override protected void onResume() {
        super.onResume();
        if (returnedFromPlayer) { returnedFromPlayer = false; if (mode.equals("details") && selected != null) renderDetails(selected); }
        if (mode.equals("home") && homeNeedsRefresh()) showHome(true);
    }
    @Override protected void onDestroy() {
        Log.i("Cinema", "Catalog activity destroyed; changingConfiguration=" + isChangingConfigurations());
        generation++; main.removeCallbacksAndMessages(null); network.shutdownNow();
        if (posters != null) posters.close(); if (taste != null) taste.close(); super.onDestroy();
    }
}
