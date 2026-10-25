package local.tv.streamtest;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Search first and let the viewer confirm identity before saving an IMDb mapping. */
public final class OmdbClient {
    public JSONArray search(Movie movie, String key) throws Exception {
        String title = movie.title.replaceFirst("(?i)\\s*[-–:]?\\s*season\\s+\\d+.*$", "").trim();
        String query = "s=" + URLEncoder.encode(title, "UTF-8") + "&type=" + (movie.series() ? "series" : "movie");
        JSONObject response = get(query, key);
        JSONArray items = response.optJSONArray("Search"), matches = new JSONArray();
        if (items != null) for (int i = 0; i < items.length() && matches.length() < 10; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && item.optString("imdbID").matches("tt[0-9]{7,12}") && (movie.series() ? "series" : "movie").equals(item.optString("Type"))) matches.put(item);
        }
        return matches;
    }
    public JSONObject rating(String imdbId, String key) throws Exception {
        if (!imdbId.matches("tt[0-9]{7,12}")) throw new IllegalArgumentException("Invalid IMDb ID");
        return parseRating(imdbId, get("i=" + imdbId, key));
    }
    public static JSONObject parseRating(String expectedId, JSONObject response) throws Exception {
        if (!"True".equals(response.optString("Response")) || !expectedId.equals(response.optString("imdbID"))) throw new IllegalArgumentException("IMDb identity did not match");
        String raw = response.optString("imdbRating"), rating = "";
        if (!raw.isEmpty() && !raw.equals("N/A")) {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || value < 0 || value > 10) throw new IllegalArgumentException("Invalid IMDb rating");
            rating = String.format(Locale.ROOT, "%.1f", value);
        }
        String votes = response.optString("imdbVotes"); if (!votes.matches("[0-9,]{1,20}")) votes = "";
        return new JSONObject().put("id", expectedId).put("rating", rating).put("votes", votes).put("title", response.optString("Title")).put("year", response.optString("Year"));
    }
    private static JSONObject get(String query, String key) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL("https://www.omdbapi.com/?" + query + "&apikey=" + URLEncoder.encode(key, "UTF-8")).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(8000); connection.setReadTimeout(12000); connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new java.io.IOException("Ratings HTTP " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int length;
                while ((length = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (output.size() + length > 131072) throw new java.io.IOException("Ratings response too large");
                    output.write(buffer, 0, length);
                }
                JSONObject result = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                if ("False".equals(result.optString("Response")) && !result.optString("Error").equals("Movie not found!")) throw new java.io.IOException("Ratings service rejected the request");
                return result;
            }
        } finally { connection.disconnect(); }
    }
}
