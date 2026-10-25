package local.tv.streamtest;

import java.util.Locale;

/** Language tags are metadata, not an audio-language detector. */
public final class TrackLanguage {
    public static String normalize(String tag) {
        if (tag == null) return "";
        String language = tag.trim().toLowerCase(Locale.ROOT).replace('_', '-').split("-", 2)[0];
        if (language.equals("eng")) return "en";
        if (language.equals("ukr")) return "uk";
        if (language.equals("und") || language.equals("zxx")) return "";
        return language;
    }
    public static String label(String tag) {
        String language = normalize(tag);
        if (language.isEmpty()) return "Unknown language";
        String name = new Locale(language).getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() ? language : name;
    }
    private TrackLanguage() { }
}
