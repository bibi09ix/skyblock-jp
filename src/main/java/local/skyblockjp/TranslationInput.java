package local.skyblockjp;

/** Clarifies an ambiguous combat idiom without altering any protected markers. */
final class TranslationInput {
    static String clarify(String text) {
        return text.replaceAll(
                        "(?i)\\blanding\\s+(<n\\d+/>|\\d+)\\s+hits\\b", "hitting enemies $1 times")
                .replaceAll("(?i)\\bCan be cast after\\b", "Can be activated after");
    }
}
