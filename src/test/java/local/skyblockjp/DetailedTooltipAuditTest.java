package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class DetailedTooltipAuditTest {
    @TempDir Path dir;

    private static Translator.DictionaryDecision decision(String encoded, String reason) {
        return new Translator.DictionaryDecision(
                encoded,
                reason,
                encoded == null ? "none" : "exact",
                "item",
                encoded == null ? List.of() : List.of("fixture:known"));
    }

    private TooltipAudit audit(Path path) {
        return new TooltipAudit(path, LoggerFactory.getLogger("detailed-audit-test"), "");
    }

    private static List<JsonObject> rows(Path path) throws Exception {
        return Files.readAllLines(path).stream()
                .map(line -> JsonParser.parseString(line).getAsJsonObject())
                .toList();
    }

    @Test
    void zeroHitEnglishTooltipRecordsItsOriginalAndPerBlockMissReason() throws Exception {
        Path path = dir.resolve("miss.jsonl");
        List<Component> source =
                List.of(
                        Component.literal("Unknown item"),
                        Component.literal("A newly added explanation."));
        var blocks = List.of(new LoreParser.Block(1, 2, "A newly added explanation."));
        try (var audit = audit(path)) {
            audit.recordDetailed(
                    "unknown",
                    source,
                    source,
                    List.of("A newly added explanation."),
                    260,
                    "GROQ",
                    "model",
                    "item",
                    "Storage",
                    blocks,
                    List.of(decision(null, "NO_EXACT_MATCH")),
                    "ORIGINAL",
                    true);
        }
        var row = rows(path).getFirst();
        assertEquals("ORIGINAL", row.get("provider").getAsString());
        assertEquals("PARSED", row.get("classification").getAsString());
        assertEquals(
                "A newly added explanation.",
                row.getAsJsonArray("originalLines").get(1).getAsString());
        assertEquals(row.get("originalLines"), row.get("displayLines"));
        var reason = row.getAsJsonArray("paragraphDecisions").get(0).getAsJsonObject();
        assertEquals("NO_EXACT_MATCH", reason.get("reason").getAsString());
        assertEquals("ORIGINAL", reason.get("supplier").getAsString());
        assertEquals(1, reason.get("start").getAsInt());
        assertEquals(2, reason.get("end").getAsInt());
    }

    @Test
    void completelyProtectedTooltipIsRecordedWithoutInventingAParagraph() throws Exception {
        Path path = dir.resolve("no-blocks.jsonl");
        List<Component> source =
                List.of(Component.literal("Sword"), Component.literal("Damage: +100"));
        try (var audit = audit(path)) {
            audit.recordDetailed(
                    "stats",
                    source,
                    source,
                    List.of(),
                    260,
                    "GROQ",
                    "model",
                    "item",
                    "Inventory",
                    List.of(),
                    List.of(),
                    "ORIGINAL",
                    true);
        }
        var row = rows(path).getFirst();
        assertEquals("NO_DESCRIPTION_BLOCKS", row.get("classification").getAsString());
        assertEquals(0, row.getAsJsonArray("paragraphDecisions").size());
        assertEquals("ORIGINAL", row.get("provider").getAsString());
    }

    @Test
    void mixedDictionaryDisplayRecordsRecoverableRgbAndUntranslatedReasons() throws Exception {
        Path path = dir.resolve("mixed.jsonl");
        Component colored =
                Component.literal("Restores ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(
                                Component.literal("50")
                                        .withStyle(
                                                style -> style.withColor(0x12AB34).withBold(true)))
                        .append(Component.literal(" Health.").withStyle(ChatFormatting.GRAY));
        List<Component> original =
                List.of(
                        Component.literal("Example"),
                        colored,
                        Component.empty(),
                        Component.literal("Unknown text."));
        List<Component> displayed =
                List.of(
                        original.getFirst(),
                        Component.literal("Healthを50回復する。"),
                        Component.empty(),
                        original.getLast());
        var blocks =
                List.of(
                        new LoreParser.Block(1, 2, "Restores 50 Health."),
                        new LoreParser.Block(3, 4, "Unknown text."));
        try (var audit = audit(path)) {
            audit.recordDetailed(
                    "mixed",
                    original,
                    displayed,
                    List.of("Healthを50回復する。", "Unknown text."),
                    260,
                    "GROQ",
                    "model",
                    "item",
                    "Backpack 1",
                    blocks,
                    List.of(
                            decision("encoded-known", "ACCEPTED"),
                            decision(null, "NUMBER_VARIANT_NOT_SUPPORTED")),
                    "DICTIONARY",
                    true);
        }
        var row = rows(path).getFirst();
        assertEquals("MIXED", row.get("provider").getAsString());
        assertEquals("GROQ", row.get("apiProvider").getAsString());
        var decisions = row.getAsJsonArray("paragraphDecisions");
        assertEquals(
                "DICTIONARY", decisions.get(0).getAsJsonObject().get("supplier").getAsString());
        assertEquals("ORIGINAL", decisions.get(1).getAsJsonObject().get("supplier").getAsString());
        assertEquals(
                "NUMBER_VARIANT_NOT_SUPPORTED",
                decisions.get(1).getAsJsonObject().get("reason").getAsString());
        var segments = row.getAsJsonArray("originalStyledLines").get(1).getAsJsonArray();
        StringBuilder rebuilt = new StringBuilder();
        boolean greenNumber = false;
        for (var value : segments) {
            var segment = value.getAsJsonObject();
            rebuilt.append(segment.get("text").getAsString());
            if (segment.get("text").getAsString().equals("50")) {
                greenNumber =
                        segment.get("color").getAsInt() == 0x12AB34
                                && segment.get("bold").getAsBoolean();
            }
        }
        assertEquals("Restores 50 Health.", rebuilt.toString());
        assertTrue(
                greenNumber,
                "the original numeric RGB and bold flag must be recoverable from JSON");
    }

    @Test
    void unchangedFramesDeduplicateButChangedReasonOrColorIsRecorded() throws Exception {
        Path path = dir.resolve("dedup.jsonl");
        List<Component> source =
                List.of(
                        Component.literal("Example"),
                        Component.literal("A lovely gift.").withStyle(ChatFormatting.GRAY));
        var blocks = List.of(new LoreParser.Block(1, 2, "A lovely gift."));
        List<Component> changedColor =
                List.of(
                        source.getFirst(),
                        Component.literal("A lovely gift.").withStyle(ChatFormatting.GOLD));
        try (var audit = audit(path)) {
            for (int i = 0; i < 3; i++)
                audit.recordDetailed(
                        "same",
                        source,
                        source,
                        List.of("A lovely gift."),
                        260,
                        "GROQ",
                        "model",
                        "item",
                        "Inventory",
                        blocks,
                        List.of(decision(null, "NO_EXACT_MATCH")),
                        "ORIGINAL",
                        true);
            audit.recordDetailed(
                    "same",
                    source,
                    source,
                    List.of("A lovely gift."),
                    260,
                    "GROQ",
                    "model",
                    "item",
                    "Inventory",
                    blocks,
                    List.of(decision(null, "STYLE_AMBIGUOUS")),
                    "ORIGINAL",
                    true);
            audit.recordDetailed(
                    "same",
                    changedColor,
                    changedColor,
                    List.of("A lovely gift."),
                    260,
                    "GROQ",
                    "model",
                    "item",
                    "Inventory",
                    blocks,
                    List.of(decision(null, "STYLE_AMBIGUOUS")),
                    "ORIGINAL",
                    true);
            audit.recordDetailed(
                    "same",
                    changedColor,
                    changedColor,
                    List.of("A lovely gift."),
                    260,
                    "GROQ",
                    "model",
                    "item",
                    "Inventory",
                    blocks,
                    List.of(decision(null, "STYLE_AMBIGUOUS")),
                    "ORIGINAL",
                    true);
        }
        var rows = rows(path);
        assertEquals(3, rows.size());
        assertNotEquals(rows.get(1).get("styleFingerprint"), rows.get(2).get("styleFingerprint"));
        assertEquals(rows.get(1).get("originalLines"), rows.get(2).get("originalLines"));
    }
}
