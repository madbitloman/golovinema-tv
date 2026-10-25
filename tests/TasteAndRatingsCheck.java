import local.tv.streamtest.Movie;
import local.tv.streamtest.TasteProtocol;
import local.tv.streamtest.OmdbClient;
import local.tv.streamtest.ServiceKeyPayload;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class TasteAndRatingsCheck {
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private interface Operation { void run() throws Exception; }
    private static void rejects(Operation operation, String reason) throws Exception {
        try { operation.run(); } catch (Exception expected) { return; }
        throw new AssertionError(reason);
    }
    private static JSONObject fixture(JSONObject request) throws Exception {
        JSONObject answers = new JSONObject(), questions = request.getJSONObject("questions");
        for (String id : questions.keySet()) {
            JSONObject question = questions.getJSONObject(id), answer = new JSONObject().put("type", question.getString("type"));
            if (question.getString("type").equals("noul")) answer.put("noul", .8);
            else {
                JSONObject probabilities = new JSONObject(), legend = new JSONObject(); JSONArray criteria = question.getJSONArray("criteria");
                for (int i = 0; i < criteria.length(); i++) { probabilities.put("" + i, i == 2 ? 1 : 0); legend.put("" + i, criteria.getString(i)); }
                answer.put("score", 2).put("confidence", .8).put("probabilities", probabilities).put("legend", legend);
            }
            answers.put(id, answer);
        }
        return new JSONObject().put("model", TasteProtocol.MODEL).put("answers", answers);
    }
    public static void main(String[] args) throws Exception {
        long now = 1791990000000L; JSONArray history = new JSONArray();
        for (int i = 0; i < 50; i++) history = TasteProtocol.addSearch(history, "search " + i, now + i);
        require(history.length() == 30 && history.getJSONObject(0).getString("query").equals("search 49"), "Keep only 30 latest unique searches");
        history = TasteProtocol.addSearch(history, "SEARCH 49", now + 100);
        require(history.length() == 30, "Case changes must not duplicate a search");
        require(TasteProtocol.recent(history, now + 31L * 86400000).length() == 0, "Expire history after 30 days");
        List<Movie> movies = new ArrayList<>();
        for (int i = 0; i < 40; i++) { Movie movie = new Movie(); movie.id = "" + i; movie.title = "Candidate " + i; if (i == 39) movie.genres = "Mystery, Horror"; movies.add(movie); }
        JSONObject request = TasteProtocol.request(history, movies);
        JSONArray candidates = request.getJSONObject("state").getJSONArray("candidates");
        require(candidates.length() == 24 && candidates.getJSONObject(0).getString("id").equals("39"), "Shortlist is bounded and explicit Horror preference comes first");
        require(request.getJSONObject("state").getJSONArray("recent_searches_newest_first").length() == 30, "Include only bounded search text");
        require(!request.toString().contains("apikey") && !request.toString().contains("poster"), "No credentials or playback URLs in preference state");
        JSONObject answer = fixture(request), profile = TasteProtocol.validate(request, answer, now);
        require(TasteProtocol.rank(movies, profile).get(0).id.equals("39"), "Jev cannot override explicit Horror preference");
        JSONObject missing = fixture(request); missing.getJSONObject("answers").remove("fit_0");
        rejects(() -> TasteProtocol.validate(request, missing, now), "Reject missing answers");
        JSONObject invalid = fixture(request); invalid.getJSONObject("answers").getJSONObject("interest_0").put("noul", "0.9");
        rejects(() -> TasteProtocol.validate(request, invalid, now), "Reject coerced probability strings");
        JSONObject distribution = fixture(request); distribution.getJSONObject("answers").getJSONObject("fit_0").getJSONObject("probabilities").put("0", .4);
        rejects(() -> TasteProtocol.validate(request, distribution, now), "Reject invalid probability sums");
        JSONObject wrongModel = fixture(request).put("model", "unrequested-model");
        rejects(() -> TasteProtocol.validate(request, wrongModel, now), "Reject unexpected models");
        // Synthetic OMDb contract fixtures: these are not live provider responses.
        JSONObject omdb = new JSONObject().put("Response", "True").put("imdbID", "tt1234567").put("imdbRating", "7.4").put("imdbVotes", "120,005").put("Title", "Fixture").put("Year", "2001");
        require(OmdbClient.parseRating("tt1234567", omdb).getString("rating").equals("7.4"), "Read IMDb rating for confirmed identity");
        rejects(() -> OmdbClient.parseRating("tt7654321", omdb), "Reject a different IMDb title");
        require(OmdbClient.parseRating("tt1234567", new JSONObject(omdb.toString()).put("imdbRating", "N/A")).getString("rating").isEmpty(), "Unrated titles must not become zero stars");
        rejects(() -> OmdbClient.parseRating("tt1234567", new JSONObject(omdb.toString()).put("imdbRating", "NaN")), "Reject invalid rating numbers");
        Movie updated = new Movie(); updated.id = "1"; updated.imdbId = "tt1234567"; updated.imdbRating = "7.4"; updated.imdbChecked = now;
        Movie freshCatalog = new Movie(); freshCatalog.id = "1"; freshCatalog.retainDetails(updated);
        require(Movie.fromJson(freshCatalog.json()).ratingLabel().equals("IMDb 7.4 · OMDb"), "External rating and attribution survive sparse catalog refreshes");
        JSONObject setup = new JSONObject().put("version", 1).put("request", "0123456789abcdef0123456789abcdef").put("keys", new JSONObject().put("omdb", "fixture-only"));
        require(ServiceKeyPayload.parse(setup.toString()).getJSONObject("keys").getString("omdb").equals("fixture-only"), "Private handoff accepts the supported schema");
        rejects(() -> ServiceKeyPayload.parse(new JSONObject(setup.toString()).put("request", "invalid").toString()), "Reject invalid receipt identifiers");
        rejects(() -> ServiceKeyPayload.parse(new JSONObject(setup.toString()).put("keys", new JSONObject().put("omdb", 123)).toString()), "Reject non-string keys");
        rejects(() -> ServiceKeyPayload.parse(new JSONObject(setup.toString()).put("keys", new JSONObject().put("unknown", "fixture")).toString()), "Reject unknown providers");
        rejects(() -> ServiceKeyPayload.parse(new JSONObject(setup.toString()).put("keys", new JSONObject().put("omdb", "fixture\n")).toString()), "Reject control characters, including trailing newlines");
        rejects(() -> ServiceKeyPayload.parse(" ".repeat(ServiceKeyPayload.LIMIT + 1)), "Bound private setup input");
        System.out.println("PASS: bounded/expiring search history, Jev request/response validation, explicit-preference ranking, IMDb identity/rating checks and rating cache");
    }
}
