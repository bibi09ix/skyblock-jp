package local.skyblockjp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** An explicit projection of the four diagnostic schemas. Never a general log scrubber. */
final class SharedLogSanitizer {
    private static final Set<String> CHANNELS = Set.of("item", "ui", "npc", "system");
    private static final Pattern CREDENTIAL =
            Pattern.compile(
                    "(?i)(?:Bearer\\s+[A-Za-z0-9._~-]{6,}|gsk_[A-Za-z0-9_-]{8,}|sk-proj-[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{12,})");
    private static final Pattern PRIVATE_PATH =
            Pattern.compile(
                    "(?i)(?:[a-z]:[\\\\/]|\\\\\\\\[^\\s\\\\]+\\\\|/(?:Users|home|private|var/folders|mnt/[a-z])/)");
    private static final Pattern PLAYER_HEADER =
            Pattern.compile(
                    "(?i)^(?:(?:Party|Guild|Officer|Co-op|Coop)\\s*>|(?:From|To)\\s+(?:\\[[^\\]\\r\\n]{1,32}\\]\\s*)?[A-Za-z0-9_]{1,16}:|(?:\\[[A-Za-z0-9+ #_-]{1,32}\\]\\s*)+[A-Za-z0-9_]{1,16}:)");
    private final List<String> secrets;
    private int omitted;
    private static final Set<String> BOOLEAN_FIELDS =
            Set.of(
                    "bold",
                    "italic",
                    "underlined",
                    "strikethrough",
                    "obfuscated",
                    "slotMatches",
                    "trustedMenu",
                    "statBreakdownMenu",
                    "controlEvidence",
                    "controlCarrierEvidence",
                    "dictionaryAccepted",
                    "dictionaryDisplayAllowed",
                    "manualRequested",
                    "dictionaryAutoDisplay",
                    "apiTranslationEnabled",
                    "originalStyledLinesComplete",
                    "sourceComplete",
                    "encodedSourceComplete",
                    "previousDialogueComplete",
                    "dictionaryEnabled",
                    "plainFallbackEnabled",
                    "sourceStyledComplete",
                    "keepOriginal",
                    "markersValid",
                    "quantitiesPreserved",
                    "textInputFocused",
                    "hasResult",
                    "force",
                    "forceApi",
                    "slotBoundsAvailable",
                    "finalDeferredTooltip",
                    "currentScreenEligible",
                    "screenEligible",
                    "inputEligible",
                    "enabled",
                    "scopeAllowed",
                    "onSkyblock",
                    "hovered",
                    "tooltipPresent",
                    "displayStyledLinesComplete",
                    "menuInstructionEvidence");
    private static final Set<String> NUMBER_FIELDS =
            Set.of(
                    "schemaVersion",
                    "slotIndex",
                    "containerSize",
                    "numericStatRows",
                    "start",
                    "end",
                    "paragraphIndex",
                    "lineStart",
                    "lineEnd",
                    "sourceIdCount",
                    "index",
                    "line",
                    "lineNumber",
                    "styleFingerprint",
                    "originalStyledGlyphCount",
                    "wrapWidthPixels",
                    "eventSequence",
                    "sourceStyledGlyphCount",
                    "expectedCount",
                    "candidateCount",
                    "sourceStart",
                    "sourceEnd",
                    "targetStart",
                    "targetEnd",
                    "sourceOffset",
                    "targetOffset",
                    "styleId",
                    "sourceCount",
                    "targetCount",
                    "protectedCount",
                    "ordinaryCount",
                    "markerCount",
                    "sourceUnitCount",
                    "targetUnitCount",
                    "displayX",
                    "displayY",
                    "paragraphCount",
                    "originalLineCount",
                    "lineCount",
                    "itemCount",
                    "color",
                    "observationCount",
                    "skippedDiagnosticRecords",
                    "displayStyledGlyphCount",
                    "attempt",
                    "sourceStyleFingerprint");

    record Result(JsonObject record, int omittedFields) {}

    SharedLogSanitizer(Collection<String> configuredSecrets) {
        secrets =
                configuredSecrets == null
                        ? List.of()
                        : configuredSecrets.stream()
                                .filter(s -> s != null && !s.isBlank())
                                .distinct()
                                .toList();
    }

    boolean unsafeText(String value) {
        if (value == null) return false;
        for (String secret : secrets) if (value.contains(secret)) return true;
        return CREDENTIAL.matcher(value).find()
                || PRIVATE_PATH.matcher(value).find()
                || value.contains("[REDACTED]");
    }

    Result sanitize(String filename, JsonObject input) {
        omitted = 0;
        // Scan decoded strings before projection: encoded keys/tokens must not bypass the check.
        if (containsUnsafe(input) || hasPrivateOrigin(input) || !scalarTypesValid(input))
            return null;
        // A projected snapshot must not lose a source run while retaining its completeness/hash
        // claim.
        if (input.has("originalStyledLines") && !validStyledLines(input.get("originalStyledLines")))
            return null;
        if (input.has("sourceStyledRuns") && !validStyledRuns(input.get("sourceStyledRuns")))
            return null;
        if (input.has("displayStyledLines") && !validStyledLines(input.get("displayStyledLines")))
            return null;
        Schema schema;
        String basename =
                filename.endsWith(".1") ? filename.substring(0, filename.length() - 2) : filename;
        switch (basename) {
            case "translation-display.jsonl" -> {
                if (!(event(input, "TOOLTIP_DISPLAY", 2) || event(input, "TOOLTIP_DISPLAY", 3))
                        || !optionalTooltipChannel(input)
                        || !stringArray(input.get("originalLines"))
                        || !stringArray(input.get("displayLines"))) return null;
                schema = DISPLAY;
            }
            case "translation-pipeline.jsonl" -> {
                if (!(event(input, "TOOLTIP_PIPELINE", 2) || event(input, "TOOLTIP_PIPELINE", 3))
                        || !optionalTooltipChannel(input)
                        || !knownPipelineStage(string(input, "stage"))) return null;
                JsonElement details = input.get("details");
                if (details != null
                        && (!details.isJsonObject()
                                || !optionalTooltipChannel(details.getAsJsonObject()))) return null;
                boolean original = input.has("originalLines"),
                        observed = input.has("observedDisplayLines");
                if (original == observed
                        || !(original
                                ? stringArray(input.get("originalLines"))
                                : stringArray(input.get("observedDisplayLines")))) return null;
                if (event(input, "TOOLTIP_PIPELINE", 2)) {
                    if (observed != "OBSERVATION".equals(string(input, "stage"))) return null;
                    if (!(observed ? "UNMATCHED_DISPLAY" : "CALLER_SOURCE")
                            .equals(string(input, "sourceSnapshotKind"))) return null;
                } else {
                    boolean observation = "OBSERVATION".equals(string(input, "stage"));
                    boolean matched =
                            Set.of("SOURCE_LIST_IDENTITY", "UNIQUE_STYLED_CONTENT")
                                    .contains(string(input, "reason"));
                    String expected =
                            observation
                                    ? (matched ? "MATCHED_ORIGINAL" : "UNMATCHED_DISPLAY")
                                    : "CALLER_SOURCE";
                    if (!expected.equals(string(input, "sourceSnapshotKind"))
                            || observed != (observation && !matched)) return null;
                    if (input.has("recordKind")
                            && !Set.of("STATE_CHANGE", "STATE_SUMMARY")
                                    .contains(string(input, "recordKind"))) return null;
                }
                schema = PIPELINE;
            }
            case "npc-dictionary-decisions.jsonl" -> {
                if (event(input, "NPC_TRANSLATION_STATUS", 2)) {
                    if (!"npc".equals(channel(input))
                            || !string(input, "traceId").matches("[0-9a-f]{16}")
                            || !Set.of("POLICY", "CACHE", "REQUEST", "RESULT")
                                    .contains(string(input, "stage"))
                            || !Set.of(
                                            "API_DISABLED",
                                            "CACHE_HIT",
                                            "CACHE_PROVISIONAL",
                                            "CACHE_FAILED_ORIGINAL",
                                            "CACHE_ORIGINAL",
                                            "INPUT_TOO_LONG",
                                            "QUEUED",
                                            "RUNNING",
                                            "PROVISIONAL",
                                            "UNCHANGED",
                                            "TRANSLATED",
                                            "FAILED",
                                            "QUEUE_FULL")
                                    .contains(string(input, "outcome"))) return null;
                    if (!input.has("attempt")
                            || !input.get("attempt").isJsonPrimitive()
                            || !input.get("attempt").getAsJsonPrimitive().isNumber()
                            || !input.get("attempt").getAsString().matches("[0-3]")) return null;
                    for (String body :
                            List.of(
                                    "source",
                                    "translation",
                                    "encodedSource",
                                    "previousDialogue",
                                    "speaker",
                                    "context")) if (input.has(body)) return null;
                    schema = NPC_STATUS;
                } else {
                    if (!event(input, "NPC_DICTIONARY_DECISION", 1)
                            || !"npc".equals(channel(input))
                            || !validSpeaker(input)
                            || !hasString(input, "source")
                            || npcContainsPlayerText(input)) return null;
                    schema = NPC;
                }
            }
            case "dictionary-translations.jsonl" -> {
                String channel = channel(input);
                if (!"bundled-dictionary".equals(string(input, "mode"))
                        || !CHANNELS.contains(channel)
                        || !hasString(input, "source")
                        || !hasString(input, "translation")
                        || !validContext(channel, string(input, "context"))) return null;
                if (channel.equals("npc") && (!validSpeaker(input) || npcContainsPlayerText(input)))
                    return null;
                schema = SUCCESS;
            }
            default -> {
                return null;
            }
        }
        JsonObject output;
        try {
            output = schema.copy(input, this).getAsJsonObject();
        } catch (InvalidSchema e) {
            return null;
        }
        if (schema == SUCCESS) output.addProperty("contextKind", channel(input));
        return new Result(output, omitted);
    }

    private boolean containsUnsafe(JsonElement element) {
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet())
                if (unsafeText(entry.getKey()) || containsUnsafe(entry.getValue())) return true;
        } else if (element.isJsonArray()) {
            for (var child : element.getAsJsonArray()) if (containsUnsafe(child)) return true;
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
            return unsafeText(element.getAsString());
        return false;
    }

    private static boolean hasPrivateOrigin(JsonElement element) {
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                if (key.equals("channel") || key.equals("scope")) {
                    if (!value.isJsonPrimitive()
                            || !value.getAsJsonPrimitive().isString()
                            || !CHANNELS.contains(value.getAsString().toLowerCase(Locale.ROOT)))
                        return true;
                }
                if (key.equals("speaker") || key.equals("previousSpeaker")) {
                    if (value.isJsonPrimitive()
                            && value.getAsJsonPrimitive().isString()
                            && privateSpeaker(value.getAsString())) return true;
                }
                if (key.equals("context")
                        && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isString()) {
                    String text = value.getAsString().strip().toLowerCase(Locale.ROOT);
                    if (text.matches("(?s)(?:chat|player|player_chat)(?:[=; ].*)?")
                            || text.matches(
                                    "(?s)scope\\s*=\\s*(?:chat|player|player_chat)(?:[; ].*)?"))
                        return true;
                }
                if (hasPrivateOrigin(value)) return true;
            }
        } else if (element.isJsonArray())
            for (var child : element.getAsJsonArray()) if (hasPrivateOrigin(child)) return true;
        return false;
    }

    private static boolean privateSpeaker(String speaker) {
        return speaker.strip().matches("(?i)(?:CHAT|PLAYER(?:_CHAT)?|チャット)(?:[=:; ].*)?");
    }

    private static boolean validSpeaker(JsonObject object) {
        return hasString(object, "speaker")
                && !string(object, "speaker").isBlank()
                && !privateSpeaker(string(object, "speaker"));
    }

    private static boolean npcContainsPlayerText(JsonObject object) {
        for (String field : List.of("source", "encodedSource", "previousDialogue"))
            if (playerText(string(object, field))) return true;
        JsonElement context = object.get("dictionaryContext");
        if (context != null
                && context.isJsonObject()
                && playerText(string(context.getAsJsonObject(), "previousDialogue"))) return true;
        return false;
    }

    private static boolean playerText(String text) {
        return text.lines()
                .anyMatch(
                        line ->
                                PLAYER_HEADER
                                        .matcher(
                                                line.replaceAll("§[0-9a-fk-or]", "").stripLeading())
                                        .find());
    }

    private static boolean validContext(String channel, String context) {
        if (context.equalsIgnoreCase(channel))
            return true; // Early diagnostic fixtures / historical records.
        if (channel.equals("npc"))
            return context.startsWith("NPC=") && !context.substring(4).isBlank();
        return context.matches("(?s)^scope=" + channel + "\\s*;.*$");
    }

    private static boolean knownPipelineStage(String stage) {
        return Set.of(
                        "REQUEST_DISPATCH",
                        "REQUEST_COMPLETE",
                        "ELIGIBILITY",
                        "OBSERVATION",
                        "MANUAL_INPUT",
                        "RETRANSLATION")
                .contains(stage);
    }

    private static boolean event(JsonObject object, String event, int schema) {
        return event.equals(string(object, "event"))
                && object.has("schemaVersion")
                && object.get("schemaVersion").isJsonPrimitive()
                && object.get("schemaVersion").getAsJsonPrimitive().isNumber()
                && object.get("schemaVersion").getAsString().equals(Integer.toString(schema));
    }

    private static boolean optionalTooltipChannel(JsonObject object) {
        return !object.has("channel") || Set.of("item", "ui").contains(channel(object));
    }

    private static String channel(JsonObject object) {
        return string(object, "channel").toLowerCase(Locale.ROOT);
    }

    private static boolean hasString(JsonObject object, String key) {
        return object.has(key)
                && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isString();
    }

    private static String string(JsonObject object, String key) {
        return hasString(object, key) ? object.get(key).getAsString() : "";
    }

    private static boolean stringArray(JsonElement value) {
        if (value == null || !value.isJsonArray()) return false;
        for (var item : value.getAsJsonArray())
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) return false;
        return true;
    }

    private static boolean scalarTypesValid(JsonElement value) {
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                JsonElement child = entry.getValue();
                if (BOOLEAN_FIELDS.contains(entry.getKey())
                        && (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isBoolean()))
                    return false;
                if (NUMBER_FIELDS.contains(entry.getKey())
                        && (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isNumber()))
                    return false;
                if (!scalarTypesValid(child)) return false;
            }
        } else if (value.isJsonArray())
            for (var child : value.getAsJsonArray()) if (!scalarTypesValid(child)) return false;
        return true;
    }

    private static boolean validStyledLines(JsonElement value) {
        if (value == null || !value.isJsonArray()) return false;
        for (var line : value.getAsJsonArray()) if (!validStyledRuns(line)) return false;
        return true;
    }

    private static boolean validStyledRuns(JsonElement value) {
        if (value == null || !value.isJsonArray()) return false;
        for (var run : value.getAsJsonArray()) {
            if (!run.isJsonObject() || !hasString(run.getAsJsonObject(), "text")) return false;
            var object = run.getAsJsonObject();
            for (String flag :
                    List.of("bold", "italic", "underlined", "strikethrough", "obfuscated")) {
                if (object.has(flag)
                        && (!object.get(flag).isJsonPrimitive()
                                || !object.get(flag).getAsJsonPrimitive().isBoolean()))
                    return false;
            }
            if (object.has("color")) {
                JsonElement color = object.get("color");
                if (!color.isJsonPrimitive() || !color.getAsJsonPrimitive().isNumber())
                    return false;
                try {
                    int rgb = new BigDecimal(color.getAsString()).intValueExact();
                    if (rgb < 0 || rgb > 0xFFFFFF) return false;
                } catch (ArithmeticException | NumberFormatException e) {
                    return false;
                }
            }
        }
        return true;
    }

    private interface Rule {
        JsonElement copy(JsonElement value, SharedLogSanitizer owner);
    }

    private static final Rule ATOM =
            (value, owner) ->
                    value.isJsonPrimitive() || value.isJsonNull() ? value.deepCopy() : null;
    private static final Rule TEXT =
            (value, owner) ->
                    value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                            ? value.deepCopy()
                            : null;
    private static final Rule NULLABLE_TEXT =
            (value, owner) -> value.isJsonNull() ? JsonNull.INSTANCE : TEXT.copy(value, owner);
    private static final Rule NUMBER =
            (value, owner) ->
                    value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                            ? value.deepCopy()
                            : null;

    private static Rule array(Rule child) {
        return (value, owner) -> {
            if (!value.isJsonArray()) return null;
            var result = new JsonArray();
            for (var item : value.getAsJsonArray()) {
                JsonElement copy = child.copy(item, owner);
                if (copy == null) throw new InvalidSchema();
                result.add(copy);
            }
            return result;
        };
    }

    private static final class Schema implements Rule {
        private final Map<String, Rule> fields = new LinkedHashMap<>();

        Schema atoms(String names) {
            for (String key : names.split(" ")) if (!key.isEmpty()) fields.put(key, ATOM);
            return this;
        }

        Schema field(String name, Rule rule) {
            fields.put(name, rule);
            return this;
        }

        public JsonElement copy(JsonElement value, SharedLogSanitizer owner) {
            if (!value.isJsonObject()) return null;
            var result = new JsonObject();
            for (var entry : value.getAsJsonObject().entrySet()) {
                Rule rule = fields.get(entry.getKey());
                JsonElement copy = rule == null ? null : rule.copy(entry.getValue(), owner);
                if (rule == null) owner.omitted++;
                else if (copy == null) throw new InvalidSchema();
                else result.add(entry.getKey(), copy);
            }
            return result;
        }
    }

    private static final class InvalidSchema extends RuntimeException {}

    private static final Schema STYLE =
            new Schema()
                    .atoms("text color colorRGB bold italic underlined strikethrough obfuscated");
    private static final Schema CONTEXT =
            new Schema()
                    .atoms(
                            "containerTitle location slotMatches slotIndex containerSize skyblockItemId minecraftItemId screenClass previousDialogue previousSpeaker paragraphRole tooltipTitle tooltipKind");
    private static final Schema EVIDENCE =
            new Schema()
                    .atoms(
                            "containerTitle location slotMatches slotIndex containerSize skyblockItemId minecraftItemId screenClass trustedMenu statBreakdownMenu controlEvidence controlCarrierEvidence numericStatRows tooltipTitle tooltipKind menuInstructionEvidence");
    private static final Schema CLASSIFICATION =
            new Schema().atoms("kind reason").field("evidence", EVIDENCE);
    private static final Schema DICTIONARY_DETAILS =
            new Schema()
                    .atoms(
                            "stage dictionaryBuildId sourceSha256 sourceHashFormat normalizedSourceSha256 alignmentNormalization targetSha256 targetCandidate normalizationVersion plainFallbackEnabled "
                                    + "validationStage validationStep alignmentId spanId marker sourceLiteral styleLiteral targetLiteral anchorLiteral anchorStage "
                                    + "expectedCount candidateCount sourceStart sourceEnd targetStart targetEnd sourceOffset targetOffset offsetUnit originalStyledFragment styleId cause reason "
                                    + "sourceCount targetCount protectedCount ordinaryCount markerCount sourceUnitCount targetUnitCount alignmentMode markersValid quantitiesPreserved variable renderPolicy")
                    .field("dictionaryContext", CONTEXT)
                    .field("ordinaryTokenWords", array(TEXT))
                    .field("sourceEffectLevels", array(TEXT))
                    .field("targetEffectLevels", array(TEXT))
                    .field("sourceRoles", array(TEXT))
                    .field("targetRoles", array(TEXT))
                    .field("sourceCommands", array(TEXT))
                    .field("targetCommands", array(TEXT))
                    .field("sourceRates", array(TEXT))
                    .field("targetRates", array(TEXT))
                    .field("unknownTargetWords", array(TEXT))
                    .field("excessTargetWords", array(TEXT))
                    .field("sourceMembers", stringCounts())
                    .field("targetMembers", stringCounts())
                    .field("plainStyleIds", array(TEXT))
                    .field(
                            "candidateOffsets",
                            array(
                                    (value, owner) ->
                                            value.isJsonArray()
                                                    ? array(NUMBER).copy(value, owner)
                                                    : NUMBER.copy(value, owner)))
                    .field("style", STYLE);
    private static final Schema PARAGRAPH =
            new Schema()
                    .atoms(
                            "start end source paragraphIndex paragraphId lineStart lineEnd channel reason matchMode sourceIdCount supplier encodedDictionaryTranslation "
                                    + "dictionaryAccepted dictionaryStage stopStage displayReason apiState apiTranslationEnabled displayedText role parseReason")
                    .field("sourceIds", array(TEXT))
                    .field("dictionaryDetails", DICTIONARY_DETAILS);
    private static final Schema LINE =
            new Schema().atoms("index line lineNumber source role action reason paragraphIndex");
    private static final Rule COUNTS =
            (value, owner) -> {
                if (!value.isJsonObject()) return null;
                var copy = new JsonObject();
                for (var entry : value.getAsJsonObject().entrySet()) {
                    if (entry.getKey().matches("[A-Z0-9_:.-]{1,160}")
                            && entry.getValue().isJsonPrimitive()
                            && entry.getValue().getAsJsonPrimitive().isNumber())
                        copy.add(entry.getKey(), entry.getValue().deepCopy());
                    else owner.omitted++;
                }
                return copy;
            };
    private static final Schema DISPLAY =
            new Schema()
                    .atoms(
                            "time schemaVersion event tooltipId session version item observedAt provider apiProvider wrapWidthPixels displayText stage channel screenTitle displayMode "
                                    + "dictionaryDisplayAllowed manualRequested dictionaryAutoDisplay apiTranslationEnabled classification styleFingerprint originalStyledLinesComplete originalStyledGlyphCount normalizationVersion "
                                    + "traceId skippedDiagnosticRecords displayStyledLinesComplete displayStyledGlyphCount")
                    .field("originalLines", array(TEXT))
                    .field("translatedParagraphs", array(NULLABLE_TEXT))
                    .field("displayLines", array(TEXT))
                    .field("originalStyledLines", array(array(STYLE)))
                    .field("displayStyledLines", array(array(STYLE)))
                    .field("paragraphDecisions", array(PARAGRAPH))
                    .field("tooltipClassification", CLASSIFICATION)
                    .field("lineDecisions", array(LINE))
                    .field("lineDecisionCounts", COUNTS);
    private static final Schema PIPELINE_DETAILS =
            new Schema()
                    .atoms(
                            "screenClass screenTitle textInputFocused paragraphIndex tooltipId paragraphId channel cacheState hasResult exceptionClass force forceApi pressResult "
                                    + "source dictionaryReason apiTranslationEnabled displayX displayY slotBoundsAvailable finalDeferredTooltip slotIndex "
                                    + "currentScreenEligible screenEligible inputEligible enabled scopeAllowed onSkyblock dictionaryEnabled dictionaryAutoDisplay "
                                    + "manualRequested dictionaryDisplayAllowed hovered tooltipPresent paragraphCount originalLineCount lineCount itemCount classification reason")
                    .field("dictionaryContext", CONTEXT)
                    .field("tooltipClassification", CLASSIFICATION)
                    .field("evidence", EVIDENCE);
    private static final Schema PIPELINE =
            new Schema()
                    .atoms(
                            "time schemaVersion event session version tooltipId stage reason item sourceSnapshotKind eventSequence observedAt channel "
                                    + "traceId recordKind observationCount firstObservedAt lastObservedAt skippedDiagnosticRecords sourceStyleFingerprint")
                    .field("originalLines", array(TEXT))
                    .field("observedDisplayLines", array(TEXT))
                    .field("details", PIPELINE_DETAILS);
    private static final Schema NPC =
            new Schema()
                    .atoms(
                            "time schemaVersion event version session decisionId channel speaker source sourceComplete encodedSource encodedSourceComplete sourceSha256 normalizedSourceSha256 "
                                    + "styleFingerprintSha256 sourceHashFormat normalizationVersion previousDialogue previousDialogueComplete previousDialogueSha256 dictionaryBuildId dictionaryAccepted "
                                    + "dictionaryEnabled stage reason matchMode sourceIdCount apiTranslationEnabled plainFallbackEnabled fallbackAction stopStage sourceStyledOrigin sourceStyledGlyphCount sourceStyledComplete traceId")
                    .field("dictionaryContext", CONTEXT)
                    .field("dictionaryDetails", DICTIONARY_DETAILS)
                    .field("sourceIds", array(TEXT))
                    .field("sourceStyledRuns", array(STYLE));
    private static final Schema SUCCESS =
            new Schema()
                    .atoms(
                            "time mode tier buildId sourceIdCount channel matchMode speaker source translation keepOriginal")
                    .field("sourceIds", array(TEXT))
                    .field("decisionDetails", DICTIONARY_DETAILS);
    private static final Schema NPC_STATUS =
            new Schema()
                    .atoms(
                            "time observedAt schemaVersion event version session channel traceId stage outcome attempt skippedDiagnosticRecords");

    private static Rule stringCounts() {
        return (value, owner) -> {
            if (!value.isJsonObject() || value.getAsJsonObject().size() > 32) return null;
            var copy = new JsonObject();
            for (var entry : value.getAsJsonObject().entrySet()) {
                var number = NUMBER.copy(entry.getValue(), owner);
                if (number == null) return null;
                copy.add(entry.getKey(), number);
            }
            return copy;
        };
    }

    /** Strict bounded parser: Gson's general tree parser accepts unbounded nesting and duplicate keys. */
    static JsonObject parse(String text) throws IllegalArgumentException {
        var reader = new BoundedJson(text);
        JsonElement result = reader.value(0);
        reader.space();
        if (reader.at != text.length() || !result.isJsonObject())
            throw new IllegalArgumentException("Invalid diagnostic JSON");
        return result.getAsJsonObject();
    }

    private static final class BoundedJson {
        private final String text;
        private int at, nodes;

        BoundedJson(String text) {
            this.text = text;
        }

        void space() {
            while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++;
        }

        char take() {
            if (at >= text.length()) throw invalid();
            return text.charAt(at++);
        }

        void expect(char c) {
            if (take() != c) throw invalid();
        }

        JsonElement value(int depth) {
            if (depth > 24 || ++nodes > 50_000) throw invalid();
            space();
            if (at >= text.length()) throw invalid();
            char c = text.charAt(at);
            if (c == '{') {
                at++;
                var result = new JsonObject();
                space();
                if (at < text.length() && text.charAt(at) == '}') {
                    at++;
                    return result;
                }
                do {
                    space();
                    String key = quoted();
                    if (result.has(key)) throw invalid();
                    space();
                    expect(':');
                    result.add(key, value(depth + 1));
                    space();
                    char delimiter = take();
                    if (delimiter == '}') return result;
                    if (delimiter != ',') throw invalid();
                } while (true);
            }
            if (c == '[') {
                at++;
                var result = new JsonArray();
                space();
                if (at < text.length() && text.charAt(at) == ']') {
                    at++;
                    return result;
                }
                do {
                    result.add(value(depth + 1));
                    space();
                    char delimiter = take();
                    if (delimiter == ']') return result;
                    if (delimiter != ',') throw invalid();
                } while (true);
            }
            if (c == '"') return new JsonPrimitive(quoted());
            if (text.startsWith("true", at)) {
                at += 4;
                return new JsonPrimitive(true);
            }
            if (text.startsWith("false", at)) {
                at += 5;
                return new JsonPrimitive(false);
            }
            if (text.startsWith("null", at)) {
                at += 4;
                return JsonNull.INSTANCE;
            }
            int start = at;
            while (at < text.length() && ",]} \t\r\n".indexOf(text.charAt(at)) < 0) at++;
            String number = text.substring(start, at);
            if (number.length() > 128
                    || !number.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))
                throw invalid();
            try {
                return new JsonPrimitive(new BigDecimal(number));
            } catch (NumberFormatException e) {
                throw invalid();
            }
        }

        String quoted() {
            expect('"');
            var out = new StringBuilder();
            while (true) {
                char c = take();
                if (c == '"') break;
                if (c < 32) throw invalid();
                if (c == '\\') {
                    c = take();
                    switch (c) {
                        case '"', '\\', '/' -> out.append(c);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                int digit = Character.digit(take(), 16);
                                if (digit < 0) throw invalid();
                                code = (code << 4) | digit;
                            }
                            out.append((char) code);
                        }
                        default -> throw invalid();
                    }
                } else out.append(c);
            }
            for (int i = 0; i < out.length(); i++) {
                char c = out.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= out.length() || !Character.isLowSurrogate(out.charAt(i)))
                        throw invalid();
                } else if (Character.isLowSurrogate(c)) throw invalid();
            }
            return out.toString();
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid diagnostic JSON");
        }
    }
}
