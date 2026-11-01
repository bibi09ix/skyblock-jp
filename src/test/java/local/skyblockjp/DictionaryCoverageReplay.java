package local.skyblockjp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Replays the actual local display path; output contains hashes, never observed text. */
final class DictionaryCoverageReplay {
    static final int SCHEMA_VERSION = 1;
    static final Set<String> INTENTS = Set.of("TRANSLATE", "PRESERVE", "UNREVIEWED");

    record SourceLine(String intent, String reason, Component component) {}

    record Input(
            String id,
            String screen,
            int weight,
            TooltipContext.Evidence evidence,
            List<SourceLine> lines) {}

    record LineResult(
            int line,
            String sourceSha256,
            String intent,
            String intentReason,
            String parserRole,
            String channel,
            String outcome,
            String stage,
            String reason,
            String matchMode,
            boolean japanese,
            String renderedSha256,
            String renderedStyleSha256) {}

    record CaseResult(
            String id,
            String screen,
            int weight,
            String classificationKind,
            String classificationReason,
            int sourceLineCount,
            int plannedParagraphs,
            int appliedParagraphs,
            List<LineResult> lines,
            List<String> problems) {}

    private DictionaryCoverageReplay() {}

    static Input input(JsonObject row) {
        String id = required(row, "id"), screen = required(row, "screen");
        if (!id.matches("[a-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid case id");
        int weight = row.has("weight") ? row.get("weight").getAsInt() : 1;
        if (weight < 1 || weight > 1_000_000) throw new IllegalArgumentException("Invalid weight");
        JsonObject e = row.getAsJsonObject("evidence");
        var evidence =
                new TooltipContext.Evidence(
                        required(e, "screenClass"),
                        string(e, "containerTitle"),
                        TooltipContext.Location.valueOf(required(e, "location")),
                        e.get("slotMatches").getAsBoolean(),
                        string(e, "skyblockItemId"),
                        string(e, "minecraftItemId"),
                        e.get("slotIndex").getAsInt(),
                        e.get("containerSize").getAsInt());
        var lines = new ArrayList<SourceLine>();
        JsonArray rows = row.getAsJsonArray("lines");
        if (rows == null || rows.isEmpty() || rows.size() > 256)
            throw new IllegalArgumentException("Invalid source lines");
        for (var value : rows) {
            JsonObject line = value.getAsJsonObject();
            String intent = required(line, "intent"), reason = required(line, "reason");
            if (!INTENTS.contains(intent)) throw new IllegalArgumentException("Invalid intent");
            lines.add(new SourceLine(intent, reason, nativeLine(line.getAsJsonArray("runs"))));
        }
        return new Input(id, screen, weight, evidence, List.copyOf(lines));
    }

    static Component nativeLine(JsonArray runs) {
        if (runs == null || runs.size() > 256) throw new IllegalArgumentException("Invalid runs");
        var result = Component.empty();
        int length = 0;
        for (var value : runs) {
            var run = value.getAsJsonObject();
            String text = required(run, "text");
            length += text.length();
            if (length > 16_384) throw new IllegalArgumentException("Source line too long");
            Style style = Style.EMPTY;
            if (run.has("color") && !run.get("color").isJsonNull()) {
                int color = run.get("color").getAsInt();
                if (color < 0 || color > 0xFFFFFF)
                    throw new IllegalArgumentException("Invalid RGB");
                style = style.withColor(color);
            }
            if (run.has("bold")) style = style.withBold(run.get("bold").getAsBoolean());
            if (run.has("italic")) style = style.withItalic(run.get("italic").getAsBoolean());
            if (run.has("underlined"))
                style = style.withUnderlined(run.get("underlined").getAsBoolean());
            if (run.has("strikethrough"))
                style = style.withStrikethrough(run.get("strikethrough").getAsBoolean());
            if (run.has("obfuscated"))
                style = style.withObfuscated(run.get("obfuscated").getAsBoolean());
            result.append(Component.literal(text).setStyle(style));
        }
        return result;
    }

    static CaseResult replay(Input input, Translator translator, Config config) {
        if (config.apiTranslation || !config.candidateDictionary || !config.dictionaryAutoDisplay)
            throw new IllegalArgumentException(
                    "Coverage requires immediate dictionary-only display");
        var original = input.lines().stream().map(SourceLine::component).toList();
        var strings = original.stream().map(Component::getString).toList();
        var classification = TooltipContext.analyze(input.evidence(), strings);
        var parsed = LoreParser.analyze(strings, classification, translator::knownItemLabel);
        var plan = TooltipPlan.prepare(original, parsed, classification, translator, false);
        var values = new IdentityHashMap<StyledParagraph, String>();
        for (int i = 0; i < plan.paragraphs().size(); i++)
            values.put(plan.paragraphs().get(i), plan.decisions().get(i).encoded());
        var displayed =
                ItemTooltipTranslation.prepare(
                        original,
                        plan.blocks(),
                        plan.paragraphs(),
                        config,
                        false,
                        values::get,
                        ignored -> null,
                        List::of,
                        ignored -> false);

        var results = new ArrayList<LineResult>();
        var problems = new ArrayList<String>();
        int sourceCursor = 0, outputCursor = 0, applied = 0;
        for (int p = 0; p < plan.blocks().size(); p++) {
            var block = plan.blocks().get(p);
            if (block.start() < sourceCursor
                    || block.end() > original.size()
                    || block.end() <= block.start())
                throw new IllegalStateException("Invalid/overlapping coverage source range");
            while (sourceCursor < block.start()) {
                Component actual = output(displayed.lines(), outputCursor++, problems);
                verifyUnchanged(original.get(sourceCursor), actual, sourceCursor, problems);
                results.add(
                        unplanned(input, plan.analysis(), classification, sourceCursor++, actual));
            }
            var paragraph = plan.paragraphs().get(p);
            var decision = plan.decisions().get(p);
            Component decoded = null;
            String failure = "";
            if (decision.accepted()) {
                try {
                    if (decision.encoded().isBlank()
                            || !StyledParagraph.validNumbers(
                                    paragraph.encoded(), decision.encoded()))
                        failure = "FINAL_VALUE_REJECTED";
                    else decoded = paragraph.decode(decision.encoded());
                } catch (IllegalArgumentException invalid) {
                    failure = "FINAL_DECODE_REJECTED";
                }
            }
            String outcome, stage, reason;
            Component actual = null;
            if (decoded != null) {
                actual = output(displayed.lines(), outputCursor++, problems);
                if (!sameNative(decoded, actual))
                    problems.add("NATIVE_REPLACEMENT_MISMATCH:" + block.start());
                if (decision.reason().equals("KEEP_ORIGINAL")) {
                    var retained = Component.empty();
                    for (var glyph : paragraph.sourceGlyphs())
                        retained.append(Component.literal(glyph.text()).setStyle(glyph.style()));
                    verifyUnchanged(retained, actual, block.start(), problems);
                }
                applied++;
                boolean changed = !decoded.getString().equals(paragraph.sourceText());
                outcome =
                        decision.reason().equals("KEEP_ORIGINAL")
                                ? "KEEP_ORIGINAL"
                                : changed && japanese(decoded.getString())
                                        ? "TRANSLATED"
                                        : "ACCEPTED_UNCHANGED";
                stage = "DISPLAY";
                reason = decision.reason();
            } else {
                outcome = "NOT_DISPLAYED";
                stage =
                        failure.isEmpty()
                                ? String.valueOf(
                                        decision.details()
                                                .getOrDefault("stage", "DICTIONARY_LOOKUP"))
                                : "DISPLAY";
                reason = failure.isEmpty() ? decision.reason() : failure;
            }
            for (int line = block.start(); line < block.end(); line++) {
                Component rendered = actual;
                if (decoded == null) {
                    rendered = output(displayed.lines(), outputCursor++, problems);
                    verifyUnchanged(original.get(line), rendered, line, problems);
                }
                var source = input.lines().get(line);
                results.add(
                        result(
                                line,
                                source,
                                plan.analysis().paragraphs().get(p).role(),
                                plan.channels().get(p),
                                outcome,
                                stage,
                                reason,
                                decision.mode(),
                                rendered));
            }
            sourceCursor = block.end();
        }
        while (sourceCursor < original.size()) {
            Component actual = output(displayed.lines(), outputCursor++, problems);
            verifyUnchanged(original.get(sourceCursor), actual, sourceCursor, problems);
            results.add(unplanned(input, plan.analysis(), classification, sourceCursor++, actual));
        }
        if (outputCursor != displayed.lines().size()) problems.add("EXTRA_OUTPUT_LINES");
        if (applied != displayed.complete() || applied != displayed.dictionaryMatches())
            problems.add("DISPLAY_COMPLETE_MISMATCH");
        return new CaseResult(
                input.id(),
                input.screen(),
                input.weight(),
                classification.kind().name(),
                classification.reason(),
                original.size(),
                plan.blocks().size(),
                applied,
                List.copyOf(results),
                List.copyOf(problems));
    }

    private static LineResult unplanned(
            Input input,
            LoreParser.Analysis analysis,
            TooltipContext.Decision classification,
            int index,
            Component rendered) {
        var source = input.lines().get(index);
        var parsed = analysis.lines().get(index);
        boolean unknown = classification.kind() == TooltipContext.Kind.UNKNOWN;
        String outcome =
                source.component().getString().isBlank()
                        ? "BLANK"
                        : unknown ? "UNCLASSIFIED" : "PARSER_SKIPPED";
        return result(
                index,
                source,
                parsed.role(),
                classification.channel(),
                outcome,
                unknown ? "CLASSIFICATION" : "PARSING",
                parsed.reason(),
                "none",
                rendered);
    }

    private static LineResult result(
            int index,
            SourceLine source,
            String parserRole,
            String channel,
            String outcome,
            String stage,
            String reason,
            String mode,
            Component rendered) {
        return new LineResult(
                index,
                hash(source.component().getString()),
                source.intent(),
                source.reason(),
                parserRole,
                channel,
                outcome,
                stage,
                reason,
                mode,
                japanese(rendered.getString()),
                hash(rendered.getString()),
                nativeHash(rendered));
    }

    static String nativeHash(Component component) {
        var signature = new StringBuilder();
        for (var glyph : StyledParagraph.glyphs(component)) {
            Style s = glyph.style();
            signature
                    .append(glyph.text())
                    .append('\u0000')
                    .append(s.getColor() == null ? "none" : s.getColor().getValue())
                    .append(':')
                    .append(s.isBold())
                    .append(':')
                    .append(s.isItalic())
                    .append(':')
                    .append(s.isUnderlined())
                    .append(':')
                    .append(s.isStrikethrough())
                    .append(':')
                    .append(s.isObfuscated())
                    .append('\n');
        }
        return hash(signature.toString());
    }

    private static boolean sameNative(Component a, Component b) {
        return nativeHash(a).equals(nativeHash(b));
    }

    private static void verifyUnchanged(
            Component source, Component displayed, int line, List<String> problems) {
        if (!sameNative(source, displayed)) problems.add("UNTRANSLATED_SOURCE_CHANGED:" + line);
    }

    private static Component output(List<Component> output, int index, List<String> problems) {
        if (index < output.size()) return output.get(index);
        problems.add("MISSING_OUTPUT_LINE:" + index);
        return Component.empty();
    }

    static boolean japanese(String text) {
        return text.codePoints()
                .anyMatch(c -> c >= 0x3040 && c <= 0x30ff || c >= 0x3400 && c <= 0x9fff);
    }

    static String hash(String text) {
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String required(JsonObject row, String key) {
        if (!row.has(key) || row.get(key).isJsonNull())
            throw new IllegalArgumentException("Missing " + key);
        return row.get(key).getAsString();
    }

    private static String string(JsonObject row, String key) {
        return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsString() : "";
    }
}
