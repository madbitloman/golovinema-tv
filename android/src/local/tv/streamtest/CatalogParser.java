package local.tv.streamtest;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads metadata only. Downloaded site scripts are never executed. */
public final class CatalogParser {
    public static final String ORIGIN = "https://ww8.123moviesfree.net";
    private static final Pattern MOVIE_PATH = Pattern.compile("(?:^|/)(movie|season)/([a-zA-Z0-9_-]+?)(?:/|\\.html|$)");
    public static final class Page {
        public final List<Movie> movies = new ArrayList<>();
        public String next = "", notice = "";
        public boolean cached;
        public boolean apiSearch;
        public int rawCount;
    }
    public static Movie movie(String url, String title) {
        Matcher match = MOVIE_PATH.matcher(url);
        if (!match.find()) return null;
        String slug = match.group(2);
        Matcher id = Pattern.compile("-([0-9]{1,12})$").matcher(slug);
        if (!id.find()) return null;
        Movie movie = new Movie();
        movie.type = match.group(1);
        movie.id = id.group(1); movie.slug = slug; movie.title = plain(title);
        if (movie.title.isEmpty()) movie.title = slug.substring(0, slug.lastIndexOf('-')).replace('-', ' ');
        movie.poster = "https://img.icdn.my.id/thumb/w_320/h_480/" + slug + ".jpg";
        movie.backdrop = "https://img.icdn.my.id/cover/w_1200/h_500/" + slug + ".jpg";
        return movie;
    }
    public static Page search(String json) throws Exception {
        JSONArray items = new JSONObject(json).getJSONArray("data");
        Page page = new Page();
        page.apiSearch = true; page.rawCount = items.length();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String slug = item.optString("s"), type = item.optString("d");
            // The supplied site client routes every non-m type to /season/.
            boolean season = (!type.isEmpty() && !"m".equals(type)) || slug.contains("-season-");
            Movie movie = movie("/" + (season ? "season" : "movie") + "/" + slug + "/", item.optString("t"));
            if (movie != null) page.movies.add(movie);
        }
        return page;
    }
    public static Page listing(String html) {
        LinkedHashMap<String, Movie> movies = new LinkedHashMap<>();
        Matcher scripts = Pattern.compile("<script\\b([^>]*)>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (scripts.find()) {
            if (!"application/ld+json".equals(attribute(scripts.group(1), "type"))) continue;
            try { collect(new JSONObject(scripts.group(2)), movies); } catch (Exception ignored) { }
        }
        Page result = new Page();
        Matcher anchors = Pattern.compile("<a\\b([^>]*)>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (anchors.find()) {
            String attrs = anchors.group(1), body = anchors.group(2), href = attribute(attrs, "href");
            String label = plain(body);
            if ("next".equalsIgnoreCase(attribute(attrs, "rel")) || label.startsWith("Next") || label.equals("»")) {
                result.next = localPath(href);
            }
            Matcher image = Pattern.compile("<img\\b([^>]*)>", Pattern.CASE_INSENSITIVE).matcher(body);
            if (!image.find()) continue;
            String imageAttrs = image.group(1);
            String title = attribute(imageAttrs, "alt");
            if (title.isEmpty()) title = label.replaceAll("\\s*(HD|SD|CAM)$", "");
            Movie movie = movie(href, title);
            if (movie == null) continue;
            String source = attribute(imageAttrs, "data-src");
            if (source.isEmpty()) source = attribute(imageAttrs, "src");
            if (source.startsWith("https://")) movie.poster = source;
            Matcher quality = Pattern.compile("class=[\"']?mlbq[\"']?[^>]*>([^<]+)", Pattern.CASE_INSENSITIVE).matcher(body);
            if (quality.find()) movie.quality = plain(quality.group(1));
            Movie old = movies.get(movie.id);
            if (old == null) movies.put(movie.id, movie);
            else { old.poster = movie.poster; old.quality = movie.quality; }
        }
        result.movies.addAll(movies.values());
        return result;
    }
    private static void collect(JSONObject object, LinkedHashMap<String, Movie> movies) {
        if ("Movie".equals(object.optString("@type")) || "TVSeason".equals(object.optString("@type")) || "TVSeries".equals(object.optString("@type"))) {
            Movie movie = movie(object.optString("url"), object.optString("name"));
            if (movie != null) {
                String image = object.optString("image");
                if (image.startsWith("https://")) movie.poster = image;
                String date = object.optString("dateCreated");
                if (date.matches("[0-9]{4}.*")) movie.year = date.substring(0, 4);
                JSONObject rating = object.optJSONObject("aggregateRating");
                if (rating != null) movie.rating = rating.optString("ratingValue");
                movie.description = object.optString("description");
                Object genres = object.opt("genre");
                if (genres instanceof JSONArray) {
                    JSONArray values = (JSONArray)genres;
                    for (int i = 0; i < values.length(); i++) movie.addGenre(values.optString(i));
                } else if (genres instanceof String) movie.genres = (String)genres;
                movies.put(movie.id, movie);
            }
        }
        JSONObject nested = object.optJSONObject("item");
        if (nested != null) collect(nested, movies);
        for (String name : new String[]{"itemListElement", "@graph"}) {
            JSONArray array = object.optJSONArray(name);
            if (array != null) for (int i = 0; i < array.length(); i++) if (array.optJSONObject(i) != null) collect(array.optJSONObject(i), movies);
        }
    }
    public static Movie details(Movie base, String html) {
        Movie movie = Movie.fromJson(base.json());
        Matcher heading = Pattern.compile("<h1\\b[^>]*>([\\s\\S]*?)</h1>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (heading.find()) movie.title = plain(heading.group(1));
        Matcher metas = Pattern.compile("<meta\\b([^>]*)>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (metas.find()) {
            String attrs = metas.group(1), name = attribute(attrs, "name"), property = attribute(attrs, "property"), value = attribute(attrs, "content");
            if (name.equals("description")) movie.description = plain(value);
            if (property.equals("og:image") && value.startsWith("https://")) movie.backdrop = value;
        }
        Matcher paragraphs = Pattern.compile("<p\\b[^>]*>([\\s\\S]*?)</p>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (paragraphs.find()) {
            String value = plain(paragraphs.group(1));
            if (value.startsWith("Genre:")) movie.genres = value.substring(6).trim();
            if (value.startsWith("Release:")) movie.year = value.substring(8).trim();
            if (value.startsWith("IMDb:")) movie.rating = value.substring(5).replace("/10", "").trim();
            if (value.startsWith("Quality:")) movie.quality = value.substring(8).trim();
        }
        if (movie.series()) {
            LinkedHashMap<String, Movie.Episode> episodes = new LinkedHashMap<>();
            Matcher buttons = Pattern.compile("<(?:button|a)\\b([^>]*)>([\\s\\S]*?)</(?:button|a)>", Pattern.CASE_INSENSITIVE).matcher(html);
            while (buttons.find()) {
                String attrs = buttons.group(1), id = attribute(attrs, "id");
                if (!id.matches("ep-[0-9]{1,12}") || !Pattern.compile("(?:^|\\s)episode(?:\\s|$)").matcher(attribute(attrs, "class")).find()) continue;
                id = id.substring(3);
                String title = plain(buttons.group(2));
                if (title.isEmpty()) title = attribute(attrs, "title");
                if (title.isEmpty()) title = "Episode " + id;
                episodes.put(id, new Movie.Episode(id, title));
            }
            // Never synthesize episode IDs from a badge count or list position.
            if (!episodes.isEmpty()) { movie.episodes.clear(); movie.episodes.addAll(episodes.values()); }
        }
        return movie;
    }
    public static String attribute(String tag, String name) {
        Matcher match = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE).matcher(tag);
        if (!match.find()) return "";
        return entities(match.group(1) != null ? match.group(1) : match.group(2) != null ? match.group(2) : match.group(3));
    }
    public static String localPath(String value) {
        try {
            URI url = new URI(ORIGIN + "/").resolve(value);
            if (!"ww8.123moviesfree.net".equalsIgnoreCase(url.getHost())) return "";
            return url.getRawPath() + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
        } catch (Exception error) { return ""; }
    }
    public static String plain(String html) { return entities(html.replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ").trim(); }
    private static String entities(String value) {
        String text = value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">");
        Matcher match = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);").matcher(text);
        StringBuffer output = new StringBuffer();
        while (match.find()) {
            try { String n = match.group(1); int cp = Integer.parseInt(n.startsWith("x") ? n.substring(1) : n, n.startsWith("x") ? 16 : 10); match.appendReplacement(output, Matcher.quoteReplacement(new String(Character.toChars(cp)))); }
            catch (Exception error) { match.appendReplacement(output, Matcher.quoteReplacement(match.group())); }
        }
        match.appendTail(output); return output.toString();
    }
}
