import local.tv.streamtest.TrackLanguage;
public final class TrackLanguageCheck {
    private static void equal(String expected, String actual) { if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual); }
    public static void main(String[] args) {
        for (String tag : new String[]{"en", "eng", "EN-us", "en_GB", " eng "}) equal("en", TrackLanguage.normalize(tag));
        for (String tag : new String[]{"uk", "ukr", "uk-UA"}) equal("uk", TrackLanguage.normalize(tag));
        for (String tag : new String[]{null, "", "und", "zxx"}) equal("", TrackLanguage.normalize(tag));
        equal("ru", TrackLanguage.normalize("ru-RU"));
        equal("Unknown language", TrackLanguage.label("und"));
        equal("English", TrackLanguage.label("eng"));
        System.out.println("PASS: language preference tags and unknown-language labels");
    }
}
