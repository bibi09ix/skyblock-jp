package local.skyblockjp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import static org.junit.jupiter.api.Assertions.*;

class TooltipPresentationTest {
    @TempDir Path dir;
    @Test void termsAreImmutableAndRetainColor() {
        var p = new StyledParagraph(List.of(Component.literal("Gain ").withStyle(ChatFormatting.GRAY).append(Component.literal("Mining Fortune").withStyle(ChatFormatting.GOLD))));
        assertEquals("Gain <t0>Mining Fortune</t0>", p.encoded());
        assertNotNull(TranslationDiagnostics.validate(p.encoded(), "<t0>採掘運</t0>が増える。"));
        var decoded = p.decode("<t0>Mining Fortune</t0>が増える。");
        assertEquals(net.minecraft.network.chat.TextColor.GOLD.getValue(), StyledParagraph.glyphs(decoded).getFirst().style().getColor().getValue());
        assertNull(TranslationDiagnostics.validate("<t0>Titanium Ore</t0>.", "<t0>Titanium Ore</t0>"));
    }
    @Test void headingAndBracketsAreChecked() {
        assertTrue(LoreParser.protectedLine("Pickaxe Ability: Maniac Miner"));
        assertEquals(1, LoreParser.parse(List.of("Maniac Miner", "Pickaxe Ability: Maniac Miner", "Grants more damage.")).size());
        assertTrue(TranslationDiagnostics.validate("Maximum <n0/>.", "上限は<n0/>)です。").startsWith("PUNCTUATION_"));
        assertNull(TranslationDiagnostics.validate("Maximum <n0/>.", "上限は<n0/>です。"));
        assertTrue(TranslationDiagnostics.balanced("（上限500）です。"));
        assertFalse(TranslationDiagnostics.balanced("（上限500)です。"));
    }
    @Test void wrapsWordsTermsUnitsAndPunctuationWithoutDroppingText() {
        for (String text : List.of("これはMining Fortuneが増加する。", "効果は30秒間続く。", "説明（最大値）です。", "abc\n\nxyz", "Supercalifragilisticexpialidocious")) {
            var lines = TooltipWrap.wrap(Component.literal(text).withStyle(ChatFormatting.GREEN), 16, c -> c.getString().codePointCount(0,c.getString().length()));
            assertEquals(text.replace("\n", ""), String.join("", lines.stream().map(Component::getString).toList()));
            assertTrue(lines.stream().noneMatch(c -> c.getString().matches("^[。、）].*")));
            if (text.contains("Mining Fortune")) assertTrue(lines.stream().anyMatch(c -> c.getString().contains("Mining Fortune")));
            if (text.contains("30秒間")) assertTrue(lines.stream().anyMatch(c -> c.getString().contains("30秒間")));
            assertEquals(net.minecraft.network.chat.TextColor.GREEN.getValue(), StyledParagraph.glyphs(lines.getFirst()).getFirst().style().getColor().getValue());
        }
    }
    @Test void auditPreservesLineBreaksDeduplicatesAndLogsNewWidth() throws Exception {
        Path path = dir.resolve("display.jsonl");
        List<Component> original = List.of(Component.literal("Sword"), Component.literal("SecretSource"), Component.empty());
        List<Component> display = List.of(Component.literal("Sword"), Component.literal("説明"), Component.literal("続き"));
        try (var audit = new TooltipAudit(path, LoggerFactory.getLogger("test"), "SecretSource")) {
            audit.record("key", original, display, List.of("説明続き"), 260, "GROQ", "test");
            audit.record("key", original, display, List.of("説明続き"), 260, "GROQ", "test");
            audit.record("key", original, display, List.of("説明続き"), 200, "GROQ", "test");
            audit.forget("key");
            audit.record("key", original, display, List.of("説明続き"), 200, "GROQ", "test");
        }
        var rows = Files.readAllLines(path);
        assertEquals(3, rows.size());
        assertFalse(Files.readString(path).contains("SecretSource"));
        var json = JsonParser.parseString(rows.getFirst()).getAsJsonObject();
        assertEquals("Sword\n説明\n続き", json.get("displayText").getAsString());
        assertEquals("", json.getAsJsonArray("originalLines").get(2).getAsString());
    }
}
