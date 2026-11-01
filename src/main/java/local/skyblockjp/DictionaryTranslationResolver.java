package local.skyblockjp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Local-only lookup, semantic validation and native-style adaptation; never reads an API cache. */
final class DictionaryTranslationResolver {
    private final Config config;
    private final BundledDictionary dictionary;
    private final Map<String, Translator.DictionaryDecision> decisions =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(128, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, Translator.DictionaryDecision> entry) {
                            return size() > 4096;
                        }
                    });

    DictionaryTranslationResolver(Config config, BundledDictionary dictionary) {
        this.config = config;
        this.dictionary = dictionary;
    }

    Translator.DictionaryDecision inspect(
            StyledParagraph paragraph,
            String channel,
            String speaker,
            Map<String, Object> context) {
        String fingerprint =
                channel
                        + "|"
                        + speaker
                        + "|"
                        + TranslationFingerprint.context(context)
                        + "|"
                        + config.dictionaryPlainFallback
                        + "|"
                        + paragraph.dictionaryFingerprint();
        var cached = decisions.get(fingerprint);
        if (cached != null) return cached;
        var result = resolveDictionary(paragraph, channel, speaker, context);
        decisions.put(fingerprint, result);
        return result;
    }

    private Translator.DictionaryDecision resolveDictionary(
            StyledParagraph paragraph,
            String channel,
            String speaker,
            Map<String, Object> dictionaryContext) {
        var lookup =
                dictionary.inspect(channel, paragraph.sourceText(), speaker, dictionaryContext);
        if (lookup.match().isEmpty()
                && lookup.reason().equals("not_found")
                && channel.equals("ui")) {
            var scoped =
                    ScopedDescriptionFallbacks.inspect(
                            dictionary, channel, paragraph.sourceText(), dictionaryContext);
            if (!scoped.reason().equals("not_found")) lookup = scoped;
        }
        if (lookup.match().isEmpty())
            return new Translator.DictionaryDecision(
                    null,
                    lookup.reason(),
                    lookup.mode(),
                    channel,
                    List.of(),
                    dictionaryDetails("DICTIONARY_LOOKUP", Map.of()));
        var match = lookup.match().get();
        if (match.keepOriginal())
            return new Translator.DictionaryDecision(
                    paragraph.encoded(),
                    "KEEP_ORIGINAL",
                    lookup.mode(),
                    channel,
                    match.sourceIds(),
                    dictionaryDetails("ACCEPTED", Map.of()));
        var result = paragraph.dictionaryEncoding(match, config.dictionaryPlainFallback);
        var details = new LinkedHashMap<String, Object>(result.details());
        details.put("dictionaryBuildId", dictionary.stats().buildId());
        details.put("sourceSha256", TranslationFingerprint.sha256(paragraph.sourceText()));
        details.put("sourceHashFormat", "UTF8_JOINED_SOURCE_TEXT");
        details.put(
                "normalizedSourceSha256",
                TranslationFingerprint.sha256(BundledDictionary.normalize(paragraph.sourceText())));
        details.put("alignmentNormalization", "bundled-source-v1");
        details.put("targetSha256", TranslationFingerprint.sha256(match.text()));
        details.put("targetCandidate", match.text());
        details.put("normalizationVersion", "ascii_case_single_period_stat_glyphs_v1");
        details.put(
                "dictionaryContext",
                new TreeMap<>(dictionaryContext == null ? Map.of() : dictionaryContext));
        details.put("plainFallbackEnabled", config.dictionaryPlainFallback);
        var ordinaryWords = OrdinaryTokenRoles.wordsForSource(paragraph.sourceText());
        if (!ordinaryWords.isEmpty())
            details.put("ordinaryTokenWords", ordinaryWords.stream().sorted().toList());
        boolean semantic =
                String.valueOf(details.getOrDefault("validationStage", "")).startsWith("TYPED_")
                        || result.reason().startsWith("PROTECTED_")
                        || result.reason().startsWith("SEMANTIC_")
                        || Set.of("INVALID_TARGET", "INVALID_BINDING", "INVALID_DICTIONARY_VALUE")
                                .contains(result.reason());
        return new Translator.DictionaryDecision(
                result.text(),
                result.reason(),
                lookup.mode(),
                channel,
                match.sourceIds(),
                dictionaryDetails(
                        result.accepted()
                                ? "ACCEPTED"
                                : semantic ? "SEMANTIC_VALIDATION" : "STYLE_RESTORE",
                        details));
    }

    private static Map<String, Object> dictionaryDetails(
            String stage, Map<String, Object> details) {
        var result = new LinkedHashMap<String, Object>(details);
        result.put("stage", stage);
        return result;
    }
}
