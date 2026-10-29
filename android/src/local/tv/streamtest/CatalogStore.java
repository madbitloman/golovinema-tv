package local.tv.streamtest;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public final class CatalogStore {
    private final Context context;
    private final SharedPreferences preferences;
    private final LinkedHashMap<String, Movie> known = new LinkedHashMap<>();
    public CatalogStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("cinema", 0);
        // Small starter selection from the user-supplied page, not a fabricated live feed.
        String[][] starter = {
            {"the-sun-never-sets-2101", "The Sun Never Sets"}, {"hope-2107", "Hope"},
            {"officer-black-belt-1630857664", "Officer Black Belt"}, {"smugglers-1371", "Smugglers"},
            {"home-sweet-home-rebirth-1630858821", "Home Sweet Home: Rebirth"}, {"12-12-the-day-1630857034", "12.12: The Day"},
            {"the-point-men-1630855452", "The Point Men"}, {"the-king-20127", "The King"},
            {"asura-the-city-of-madness-21069", "Asura: The City of Madness"}, {"the-wailing-14809", "The Wailing"},
            {"the-phone-1630853872", "The Phone"}, {"veteran-14665", "Veteran"}
        };
        for (String[] entry : starter) { Movie movie = CatalogParser.movie("/movie/" + entry[0] + "/", entry[1]); known.put(movie.id, movie); }
        // Genre verified from the catalog's The Wailing detail page, October 14, 2026.
        known.get("14809").genres = "Mystery, Horror, Fantasy";
        for (String[] entry : new String[][]{{"east-new-york-season-1-1630854209", "East New York - Season 1"}, {"the-accidental-narco-season-1-1630854062", "Narco-Saints - Season 1"}}) {
            Movie show = CatalogParser.movie("/season/" + entry[0] + "/", entry[1]); known.put(show.id, show);
        }
        try {
            JSONArray saved = new JSONArray(preferences.getString("known", "[]"));
            for (int i = 0; i < saved.length(); i++) { Movie movie = Movie.fromJson(saved.getJSONObject(i)); if (!"123movies".equals(movie.provider)) continue; movie.retainDetails(known.get(movie.id)); known.put(movie.id, movie); }
        } catch (Exception ignored) { }
    }
    public synchronized List<Movie> all() { return new ArrayList<>(known.values()); }
    public synchronized Movie cached(Movie movie) { Movie current = known.get(movie.id); return current == null ? movie : current; }
    public synchronized void remember(List<Movie> movies) {
        for (Movie movie : movies) { movie.retainDetails(known.get(movie.id)); known.put(movie.id, movie); }
        // Keep saved/history records even when trimming the browsing cache.
        while (known.size() > 800) {
            String remove = null;
            for (String id : known.keySet()) if (!preferences.getBoolean("saved_" + id, false) && lastWatched(id) == 0) { remove = id; break; }
            if (remove == null) break;
            known.remove(remove);
        }
        JSONArray data = new JSONArray(); for (Movie movie : known.values()) data.put(movie.json());
        preferences.edit().putString("known", data.toString()).apply();
    }
    public boolean saved(Movie movie) { return preferences.getBoolean("saved_" + movie.id, false); }
    public void toggleSaved(Movie movie) {
        remember(java.util.Collections.singletonList(movie));
        preferences.edit().putBoolean("saved_" + movie.id, !saved(movie)).apply();
    }
    public List<Movie> saved() { List<Movie> list = new ArrayList<>(); for (Movie movie : all()) if (saved(movie)) list.add(movie); return list; }
    public String lastEpisode(Movie movie) { return preferences.getString("last_episode_" + movie.id, ""); }
    public int position(Movie movie, String episode) { return preferences.getInt("position_" + movie.playbackKey(episode), 0); }
    public int position(Movie movie) { return position(movie, movie.series() ? lastEpisode(movie) : "1"); }
    public List<Movie> horror() { List<Movie> list = new ArrayList<>(); for (Movie movie : all()) if (movie.hasGenre("Horror")) list.add(movie); return list; }
    public List<Movie> shows() { List<Movie> list = new ArrayList<>(); for (Movie movie : all()) if (movie.series()) list.add(movie); return list; }
    public List<Movie> continuing() {
        List<Movie> list = new ArrayList<>();
        for (Movie movie : all()) {
            String key = movie.playbackKey(movie.series() ? lastEpisode(movie) : "1");
            int position = position(movie), duration = preferences.getInt("duration_" + key, 0);
            if (position >= 10000 && (duration <= 0 || position < duration - 30000)) list.add(movie);
        }
        list.sort((a, b) -> Long.compare(preferences.getLong("watched_" + b.id, 0), preferences.getLong("watched_" + a.id, 0)));
        return list;
    }
    /** Recorded when playback starts, so browser playback (which reports no position) still counts as watched. */
    public void opened(Movie movie) {
        remember(java.util.Collections.singletonList(movie));
        preferences.edit().putLong("opened_" + movie.id, System.currentTimeMillis()).apply();
    }
    private long lastWatched(String id) { return Math.max(preferences.getLong("opened_" + id, 0), preferences.getLong("watched_" + id, 0)); }
    /** Most recently played titles first, up to 20. */
    public List<Movie> recent() {
        List<Movie> list = new ArrayList<>();
        for (Movie movie : all()) if (lastWatched(movie.id) > 0) list.add(movie);
        list.sort((a, b) -> Long.compare(lastWatched(b.id), lastWatched(a.id)));
        return new ArrayList<>(list.subList(0, Math.min(list.size(), 20)));
    }
    public CatalogParser.Page page(String path) throws Exception {
        if (!path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("Invalid catalog path");
        String html = fetch(CatalogParser.ORIGIN + path, 3145728);
        CatalogParser.Page page = CatalogParser.listing(html);
        if (path.startsWith("/movies/")) page.movies.removeIf(Movie::series);
        if (path.startsWith("/tv-series/")) page.movies.removeIf(movie -> !movie.series());
        java.util.regex.Matcher genre = java.util.regex.Pattern.compile("^/genre/([a-z-]+)/").matcher(path);
        if (genre.find()) {
            String name = genre.group(1); name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            for (Movie movie : page.movies) movie.addGenre(name);
        }
        if (page.movies.isEmpty()) throw new CatalogHttp.Failure("Catalog returned no titles");
        Log.i("Cinema", "Catalog parsed: " + page.movies.size() + " titles; next page=" + !page.next.isEmpty());
        remember(page.movies);
        return page;
    }
    public CatalogParser.Page search(String query, int offset) throws Exception {
        String q = URLEncoder.encode(query, "UTF-8");
        CatalogParser.Page result;
        try { result = CatalogParser.search(fetch(CatalogParser.ORIGIN + "/searching?q=" + q + "&limit=40&offset=" + offset, 1048576)); }
        catch (Exception failure) {
            // Full search is also exposed as a normal page. Only its first page is
            // used here; subsequent pages follow the explicit Next link.
            if (offset != 0) throw failure;
            String html = fetch(CatalogParser.ORIGIN + "/search/?q=" + q, 3145728);
            result = CatalogParser.listing(html);
            if (result.movies.isEmpty() && !html.toLowerCase(Locale.ROOT).contains("no result") && !html.toLowerCase(Locale.ROOT).contains("not found")) throw failure;
        }
        remember(result.movies);
        Log.i("Cinema", "Search parsed: " + result.movies.size() + " titles; raw count=" + result.rawCount);
        return result;
    }
    public CatalogParser.Page offline(String query) {
        Log.i("Cinema", "Using offline catalog");
        CatalogParser.Page page = new CatalogParser.Page();
        String q = query.toLowerCase(Locale.ROOT).trim();
        for (Movie movie : all()) if (q.isEmpty() || movie.title.toLowerCase(Locale.ROOT).contains(q)) page.movies.add(movie);
        page.cached = true;
        page.notice = "Catalog unavailable · showing saved catalog";
        return page;
    }
    public Movie details(Movie movie) throws Exception {
        Movie result = CatalogParser.details(movie, fetch(CatalogParser.ORIGIN + movie.pagePath(), 3145728));
        remember(java.util.Collections.singletonList(result)); return result;
    }
    public CatalogParser.Page offlinePage(String path) {
        CatalogParser.Page page = new CatalogParser.Page(); page.cached = true;
        String genre = path.startsWith("/genre/") ? path.substring(7).split("/")[0] : "";
        for (Movie movie : all()) {
            if (path.startsWith("/tv-series/") && !movie.series()) continue;
            if (path.startsWith("/movies/") && movie.series()) continue;
            if (!genre.isEmpty() && !movie.hasGenre(genre)) continue;
            page.movies.add(movie);
        }
        page.notice = page.movies.isEmpty() ? "Catalog unavailable · no saved titles in this category yet" : "Catalog unavailable · showing saved titles in this category";
        return page;
    }
    static byte[] bytes(String url, int limit) throws Exception { return CatalogHttp.bytes(url, limit); }
    private static String fetch(String url, int limit) throws Exception { return CatalogHttp.text(url, limit); }
}
