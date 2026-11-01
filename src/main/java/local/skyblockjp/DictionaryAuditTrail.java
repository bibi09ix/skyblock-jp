package local.skyblockjp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/** Bounded dictionary-decision/status evidence. It never changes a translation decision. */
final class DictionaryAuditTrail {
    private static final int NPC_DICTIONARY_AUDIT_LIMIT = 512;
    private static final int NPC_DICTIONARY_AUDIT_TEXT_LIMIT = 8192;
    private final Config config;
    private final BundledDictionary dictionary;
    private final TranslationAuditLog audit;
    private final Logger log;
    private final BooleanSupplier open;
    private final Map<String, Boolean> npcDictionaryAuditSeen =
            boundedLru(NPC_DICTIONARY_AUDIT_LIMIT);
    private final Map<String, String> npcStatusSeen = boundedLru(NPC_DICTIONARY_AUDIT_LIMIT);
    private final String npcDictionaryAuditSession = UUID.randomUUID().toString();

    DictionaryAuditTrail(
            Config config,
            BundledDictionary dictionary,
            TranslationAuditLog audit,
            Logger log,
            BooleanSupplier open) {
        this.config = config;
        this.dictionary = dictionary;
        this.audit = audit;
        this.log = log;
        this.open = open;
    }

    private static <K, V> Map<K, V> boundedLru(int limit) {
        return Collections.synchronizedMap(
                new LinkedHashMap<>(128, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<K, V> entry) {
                        return size() > limit;
                    }
                });
    }

    static String traceId(StyledParagraph paragraph, String speaker, String previous) {
        return TranslationFingerprint.sha256(
                        "npc|" + speaker + "|" + previous + "|" + paragraph.dictionaryFingerprint())
                .substring(0, 16);
    }

    /** Safe status-only records join the miss trace; no API body, player chat, names or raw errors. */
    void status(String trace, String phase, String outcome, int attempt) {
        if (trace.isEmpty() || !open.getAsBoolean() || !config.dictionaryDiagnostics) return;
        String state = phase + '|' + outcome + '|' + attempt;
        if (state.equals(npcStatusSeen.put(trace, state))) return;
        var row = new LinkedHashMap<String, Object>();
        row.put("schemaVersion", 2);
        row.put("event", "NPC_TRANSLATION_STATUS");
        row.put("version", ModVersion.CURRENT);
        row.put("session", npcDictionaryAuditSession);
        row.put("channel", "npc");
        row.put("traceId", trace);
        row.put("stage", phase);
        row.put("outcome", outcome);
        row.put("attempt", attempt);
        row.put("observedAt", java.time.Instant.now().toString());
        row.put("skippedDiagnosticRecords", audit.skippedRecords());
        audit.submit(
                "npc-dictionary-decisions.jsonl",
                row,
                config.groqApiKey,
                () -> open.getAsBoolean() && config.dictionaryDiagnostics,
                () -> npcStatusSeen.remove(trace, state));
    }

    void record(
            StyledParagraph paragraph,
            String speaker,
            String encoded,
            String context,
            Translator.DictionaryDecision decision) {
        if (!open.getAsBoolean()) return;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("mode", "bundled-dictionary");
        record.put("tier", "candidate");
        record.put("buildId", dictionary.stats().buildId());
        record.put("sourceIds", decision.sourceIds().stream().limit(16).toList());
        record.put("sourceIdCount", decision.sourceIds().size());
        record.put("channel", decision.channel());
        record.put("matchMode", decision.mode());
        record.put("speaker", speaker == null ? "" : speaker);
        record.put("context", context);
        record.put("source", paragraph.sourceText());
        record.put("decisionDetails", decision.details());
        record.put("translation", paragraph.decode(encoded).getString());
        record.put("keepOriginal", decision.reason().equals("KEEP_ORIGINAL"));
        // Translation publication never waits for diagnostic disk IO.
        audit.submit(
                "dictionary-translations.jsonl",
                record,
                config.groqApiKey,
                () -> open.getAsBoolean(),
                () -> {});
    }

    /** Local rejection is evidence even when API OFF intentionally prevents any request/failure log. */
    void miss(
            StyledParagraph paragraph,
            String speaker,
            String previous,
            Map<String, Object> context,
            Translator.DictionaryDecision decision) {
        if (!open.getAsBoolean() || !config.dictionaryDiagnostics || decision.accepted()) return;
        boolean fallbackAllowed = (open.getAsBoolean() && config.apiTranslation);
        String stage =
                String.valueOf(decision.details().getOrDefault("stage", "DICTIONARY_LOOKUP"));
        String fingerprint =
                TranslationFingerprint.sha256(
                        speaker
                                + "|"
                                + paragraph.dictionaryFingerprint()
                                + "|"
                                + TranslationFingerprint.context(context)
                                + "|"
                                + stage
                                + "|"
                                + decision.reason()
                                + "|"
                                + fallbackAllowed
                                + "|"
                                + config.dictionaryPlainFallback);
        if (npcDictionaryAuditSeen.putIfAbsent(fingerprint, Boolean.TRUE) != null) return;
        try {
            var record =
                    npcDictionaryMissRecord(
                            paragraph,
                            speaker,
                            previous,
                            decision,
                            stage,
                            fingerprint,
                            fallbackAllowed);
            audit.submit(
                    "npc-dictionary-decisions.jsonl",
                    record,
                    config.groqApiKey,
                    () -> open.getAsBoolean() && config.dictionaryDiagnostics,
                    () -> npcDictionaryAuditSeen.remove(fingerprint));
        } catch (Exception e) {
            // Diagnostic serialization must never change translation/display behavior.
            npcDictionaryAuditSeen.remove(fingerprint);
            log.warn(
                    "SkyBlock JP: NPC dictionary audit preparation failed ({})",
                    e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> npcDictionaryMissRecord(
            StyledParagraph paragraph,
            String speaker,
            String previous,
            Translator.DictionaryDecision decision,
            String stage,
            String fingerprint,
            boolean fallbackAllowed) {
        previous = previous == null ? "" : previous;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schemaVersion", 1);
        record.put("event", "NPC_DICTIONARY_DECISION");
        record.put("version", ModVersion.CURRENT);
        record.put("session", npcDictionaryAuditSession);
        record.put("decisionId", fingerprint);
        record.put("traceId", traceId(paragraph, speaker, previous));
        record.put("channel", "npc");
        record.put("speaker", auditText(speaker));
        record.put("source", auditText(paragraph.sourceText()));
        record.put(
                "sourceComplete",
                paragraph.sourceText().length() <= NPC_DICTIONARY_AUDIT_TEXT_LIMIT);
        record.put("encodedSource", auditText(paragraph.encoded()));
        record.put(
                "encodedSourceComplete",
                paragraph.encoded().length() <= NPC_DICTIONARY_AUDIT_TEXT_LIMIT);
        record.put("sourceSha256", TranslationFingerprint.sha256(paragraph.sourceText()));
        record.put(
                "normalizedSourceSha256",
                TranslationFingerprint.sha256(BundledDictionary.normalize(paragraph.sourceText())));
        record.put(
                "styleFingerprintSha256",
                TranslationFingerprint.sha256(paragraph.dictionaryFingerprint()));
        record.put("sourceHashFormat", "UTF8_JOINED_SOURCE_TEXT");
        record.put("normalizationVersion", "ascii_case_single_period_stat_glyphs_v1");
        record.put("previousDialogue", auditText(previous));
        record.put(
                "previousDialogueComplete", previous.length() <= NPC_DICTIONARY_AUDIT_TEXT_LIMIT);
        record.put("previousDialogueSha256", TranslationFingerprint.sha256(previous));
        record.put("dictionaryContext", Map.of("previousDialogue", auditText(previous)));
        record.put("dictionaryBuildId", dictionary.stats().buildId());
        record.put("dictionaryAccepted", false);
        record.put("dictionaryEnabled", config.candidateDictionary);
        record.put("stage", stage);
        record.put("reason", decision.reason());
        record.put("matchMode", decision.mode());
        record.put("sourceIds", decision.sourceIds().stream().limit(16).toList());
        record.put("sourceIdCount", decision.sourceIds().size());
        record.put("apiTranslationEnabled", fallbackAllowed);
        record.put("plainFallbackEnabled", config.dictionaryPlainFallback);
        record.put("fallbackAction", fallbackAllowed ? "API_FALLBACK_ALLOWED" : "API_OFF_STOP");
        record.put("stopStage", fallbackAllowed ? "NONE" : "POLICY");
        // Keep exact stage/hash/fragment diagnostics without copying arbitrary large context
        // values.
        record.put("dictionaryDetails", DictionaryDiagnosticDetails.copy(decision.details()));
        // Display encoding deliberately omits style markers around whitespace.
        // Diagnostics must retain those native colors rather than reconstruct
        // them from the selected translated-prose base style.
        var glyphs = paragraph.sourceGlyphs();
        var runs = new ArrayList<Map<String, Object>>();
        int cursor = 0;
        while (cursor < glyphs.size()
                && cursor < NPC_DICTIONARY_AUDIT_TEXT_LIMIT
                && runs.size() < 256) {
            var style = glyphs.get(cursor).style();
            var text = new StringBuilder();
            do {
                text.append(glyphs.get(cursor++).text());
            } while (cursor < glyphs.size()
                    && cursor < NPC_DICTIONARY_AUDIT_TEXT_LIMIT
                    && glyphs.get(cursor).style().equals(style));
            var run = new LinkedHashMap<String, Object>();
            run.put("text", text.toString());
            if (style.getColor() != null) run.put("color", style.getColor().getValue());
            run.put("bold", style.isBold());
            run.put("italic", style.isItalic());
            run.put("underlined", style.isUnderlined());
            run.put("strikethrough", style.isStrikethrough());
            run.put("obfuscated", style.isObfuscated());
            runs.add(run);
        }
        record.put("sourceStyledRuns", runs);
        record.put("sourceStyledOrigin", "NATIVE_JOINED_SOURCE");
        record.put("sourceStyledGlyphCount", cursor);
        var sourceCheck = new StringBuilder();
        glyphs.forEach(g -> sourceCheck.append(g.text()));
        record.put(
                "sourceStyledComplete",
                cursor == glyphs.size() && sourceCheck.toString().equals(paragraph.sourceText()));
        return record;
    }

    private static String auditText(String value) {
        if (value == null) return "";
        if (value.length() <= NPC_DICTIONARY_AUDIT_TEXT_LIMIT) return value;
        int end = NPC_DICTIONARY_AUDIT_TEXT_LIMIT;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
