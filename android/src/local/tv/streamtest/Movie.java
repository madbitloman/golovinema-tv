package local.tv.streamtest;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

public final class Movie {
    public String id = "", title = "", slug = "", poster = "", backdrop = "";
    public String description = "", year = "", rating = "", quality = "", genres = "";
    public String type = "movie";
    public String provider = "123movies", sourcePath = "", playerUrl = "", audio = "";
    public String imdbId = "", imdbRating = "", imdbVotes = "", imdbTitle = "";
    public long imdbChecked;
    public String ratingLabel() { return !imdbRating.isEmpty() ? "IMDb " + imdbRating + " · OMDb" : rating.isEmpty() ? "" : "IMDb " + rating + " · catalog"; }
    public final List<Episode> episodes = new ArrayList<>();
    public static final class Episode {
        public final String id, title;
        public Episode(String id, String title) { this.id = id; this.title = title; }
    }
    public boolean series() { return "season".equals(type); }
    public String pagePath() { return "/" + (series() ? "season" : "movie") + "/" + slug + "/"; }
    public String playbackKey(String episode) { return series() ? id + "_ep_" + episode : id; }
    public boolean hasGenre(String genre) {
        for (String value : genres.split(",")) if (value.trim().equalsIgnoreCase(genre)) return true;
        return false;
    }
    public void addGenre(String genre) { if (!hasGenre(genre)) genres = genres.isEmpty() ? genre : genres + ", " + genre; }
    public void retainDetails(Movie old) {
        if (old == null) return;
        for (String genre : old.genres.split(",")) if (!genre.trim().isEmpty()) addGenre(genre.trim());
        if (description.isEmpty()) description = old.description;
        if (playerUrl.isEmpty()) playerUrl = old.playerUrl;
        if (audio.isEmpty()) audio = old.audio;
        if (year.isEmpty()) year = old.year;
        if (rating.isEmpty()) rating = old.rating;
        if (episodes.isEmpty()) episodes.addAll(old.episodes);
        if (imdbChecked < old.imdbChecked) { imdbId = old.imdbId; imdbRating = old.imdbRating; imdbVotes = old.imdbVotes; imdbTitle = old.imdbTitle; imdbChecked = old.imdbChecked; }
    }
    public JSONObject json() {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id).put("title", title).put("slug", slug).put("poster", poster).put("backdrop", backdrop)
                .put("description", description).put("year", year).put("rating", rating).put("quality", quality).put("genres", genres).put("type", type);
            object.put("provider", provider).put("sourcePath", sourcePath).put("playerUrl", playerUrl).put("audio", audio);
            JSONArray items = new JSONArray();
            for (Episode episode : episodes) items.put(new JSONObject().put("id", episode.id).put("title", episode.title));
            object.put("episodes", items);
            object.put("imdbId", imdbId).put("imdbRating", imdbRating).put("imdbVotes", imdbVotes).put("imdbTitle", imdbTitle).put("imdbChecked", imdbChecked);
        } catch (Exception ignored) { }
        return object;
    }
    public static Movie fromJson(JSONObject object) {
        Movie movie = new Movie();
        movie.provider = object.optString("provider", "123movies"); movie.sourcePath = object.optString("sourcePath"); movie.playerUrl = object.optString("playerUrl"); movie.audio = object.optString("audio");
        movie.id = object.optString("id"); movie.title = object.optString("title"); movie.slug = object.optString("slug");
        movie.poster = object.optString("poster"); movie.backdrop = object.optString("backdrop");
        movie.description = object.optString("description"); movie.year = object.optString("year");
        movie.rating = object.optString("rating"); movie.quality = object.optString("quality"); movie.genres = object.optString("genres");
        movie.type = object.optString("type", movie.slug.contains("-season-") ? "season" : "movie");
        movie.imdbId = object.optString("imdbId"); movie.imdbRating = object.optString("imdbRating"); movie.imdbVotes = object.optString("imdbVotes"); movie.imdbTitle = object.optString("imdbTitle"); movie.imdbChecked = object.optLong("imdbChecked");
        JSONArray items = object.optJSONArray("episodes");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && item.optString("id").matches("[0-9]{1,12}")) movie.episodes.add(new Episode(item.optString("id"), item.optString("title")));
        }
        return movie;
    }
    public String metadata() {
        StringBuilder value = new StringBuilder();
        for (String part : new String[]{year, genres, ratingLabel(), quality, audio}) {
            if (!part.isEmpty()) { if (value.length() > 0) value.append("   ·   "); value.append(part); }
        }
        return value.toString();
    }
}
