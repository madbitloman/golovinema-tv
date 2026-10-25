import local.tv.streamtest.CatalogParser;
import local.tv.streamtest.Movie;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class CatalogParserCheck {
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        // Contract reconstructed from the supplied public JS; not represented as a live API capture.
        CatalogParser.Page search = CatalogParser.search("{\"data\":[{\"s\":\"the-sun-never-sets-2101\",\"t\":\"The Sun Never Sets\",\"d\":\"m\"},{\"s\":\"example-season-1-1234\",\"t\":\"Example\",\"d\":\"s\"}]}");
        require(search.rawCount == 2 && search.movies.size() == 2, "Search must include movies and seasons while preserving raw offsets");
        require(search.movies.get(1).series() && search.movies.get(1).pagePath().equals("/season/example-season-1-1234/"), "Series must retain their season URL");
        require(search.movies.get(0).id.equals("2101"), "Playback ID must come from the catalog slug, not a TMDB ID");
        require(CatalogParser.search("{\"data\":[{\"s\":\"show-1234\",\"t\":\"Show\",\"d\":\"tv\"}]}").movies.get(0).series(), "Non-m types follow the supplied site's season routing");
        String html = "<script type=application/ld+json>{\"@type\":\"ItemList\",\"itemListElement\":[{\"item\":{\"@type\":\"Movie\",\"name\":\"Hope\",\"url\":\"https://ww8.123moviesfree.net/movie/hope-2107/\"}}]}</script>"
            + "<a href=/movie/hope-2107/ class='rounded poster'><img alt='Hope' data-src=https://img.icdn.my.id/hope.jpg><span class=mlbq>HD</span></a>"
            + "<a href='/movie/officer-black-belt-1630857664/'><img alt='Officer &amp; Black Belt' data-src='https://img.icdn.my.id/officer.jpg'></a>"
            + "<a href='/movies/page/2/'>Next &raquo;</a>";
        CatalogParser.Page listing = CatalogParser.listing(html);
        require(listing.movies.size() == 2, "Deduplicate structured-data and poster links");
        require(listing.movies.get(1).id.equals("1630857664"), "Large catalog IDs cannot overflow integers");
        require(listing.movies.get(1).title.equals("Officer & Black Belt"), "Decode entities");
        require(listing.movies.get(0).quality.equals("HD"), "Read page quality badge");
        require(listing.next.equals("/movies/page/2/"), "Use explicit next-page link");
        require(CatalogParser.localPath("https://unrelated.example/movies/").isEmpty(), "Do not follow foreign pagination URLs");
        require(CatalogParser.movie("/season/example-123/", "TV").series(), "Season links must remain distinct from movies");
        Movie details = CatalogParser.details(listing.movies.get(0), "<h1 class=title>Hope &amp; Home</h1><meta name=description content='A film about home.'><p><strong>Genre:</strong><a>Drama</a>, <a>Mystery</a></p><p><strong>IMDb:</strong> 7.2/10</p>");
        require(details.id.equals("2107") && details.title.equals("Hope & Home") && details.rating.equals("7.2"), "Details must preserve playback identity");
        require(Movie.fromJson(details.json()).description.equals("A film about home."), "Cached metadata roundtrip");
        Movie season = CatalogParser.details(search.movies.get(1), "<button class='episode active' id=ep-407>Episode 1</button><button class=episode id=ep-812>Episode 2</button><button class=server id=srv-1>Server 1</button>");
        require(season.episodes.size() == 2 && season.episodes.get(1).id.equals("812"), "Use actual episode IDs, not button positions");
        require(Movie.fromJson(season.json()).episodes.get(0).title.equals("Episode 1"), "Episode lists survive caching");
        require(!season.playbackKey("407").equals(season.playbackKey("812")) && details.playbackKey("1").equals("2107"), "Separate episode progress and preserve existing movie progress keys");
        require(CatalogParser.details(search.movies.get(1), "<span>Eps 8</span>").episodes.isEmpty(), "Do not invent episode IDs from a count");
        Movie horror = CatalogParser.movie("/movie/horror-10/", "Horror"); horror.genres = "Horror, Mystery";
        Movie refreshed = CatalogParser.movie("/movie/horror-10/", "Horror"); refreshed.genres = "Thriller"; refreshed.retainDetails(horror);
        require(refreshed.hasGenre("horror") && refreshed.hasGenre("Thriller"), "Retain horror classification across sparse catalog refreshes");
        System.out.println("PASS: search mapping, raw offsets, HTML/JSON-LD deduplication, poster parsing, pagination, details and cache roundtrip");
        for (String path : args) {
            String body = new String(Files.readAllBytes(Paths.get(path)), "UTF-8");
            CatalogParser.Page page = path.endsWith(".json") ? CatalogParser.search(body) : CatalogParser.listing(body);
            System.out.println(path + ": " + page.movies.size() + " movies; raw=" + page.rawCount + "; next=" + page.next);
            if (!page.movies.isEmpty()) System.out.println("First: " + page.movies.get(0).title + " / " + page.movies.get(0).id);
        }
    }
}
