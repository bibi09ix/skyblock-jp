package local.skyblockjp;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NpcTranslationTest {
    @TempDir Path dir;
    @Test void allRecordedDialogueSurvivesStyleEncoding() throws Exception {
        try (var in = getClass().getResourceAsStream("/npc-review-fixtures.json")) {
            var records = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
            assertEquals(28, records.size());
            for (var entry : records) {
                var e = entry.getAsJsonObject(); String source = e.get("source").getAsString();
                var p = NpcTranslation.paragraph(Component.literal("[NPC] " + e.get("speaker").getAsString() + ": " + source));
                assertEquals(source.replaceAll("§.", "").strip(), p.decode(p.encoded()).getString());
                assertFalse(p.encoded().contains("§"));
            }
        }
    }
    @Test void rejectsObservedServiceRepliesButAcceptsDialogue() {
        for (String result : new String[]{"申し訳ありませんが、翻訳するツールチップのテキストが提供されていません。正しいテキストを入力してください。", "NO_JAPANESE: 訳文が提供されていません。", "I'm just a language model."})
            assertTrue(NpcTranslation.validate("Great!", result).startsWith("NPC_META_RESPONSE"));
        assertNull(NpcTranslation.validate("Great!", "よかった！"));
        assertNull(NpcTranslation.validate("How do you like your Faction?", "今の派閥はどう？"));
    }
    @Test void centuryAllowsOnlyTheSupportedNumber() {
        assertNull(NpcTranslation.validate("Not even a century ago teleportation was thought to be impossible!", "ほんの100年足らず前には、テレポートは不可能だと思われていたんだ！"));
        assertNotNull(NpcTranslation.validate("A century ago", "200年前だよ。"));
        assertNotNull(NpcTranslation.validate("Hello", "100年前だよ。"));
    }
    @Test void keepsNativeAndLegacyNpcColors() {
        var p = NpcTranslation.paragraph(Component.literal("[NPC] Kat: I upgraded ").withStyle(ChatFormatting.WHITE)
            .append(Component.literal("Blaze").withStyle(ChatFormatting.DARK_PURPLE)).append(Component.literal("!")));
        assertFalse(p.encoded().contains("[NPC]"));
        assertTrue(StyledParagraph.glyphs(p.decode(p.encoded())).stream().anyMatch(g -> g.text().equals("B") && g.style().getColor().getValue() == net.minecraft.network.chat.TextColor.DARK_PURPLE.getValue()));
        var legacy = NpcTranslation.paragraph(Component.literal("[NPC] Kat: §fYour pet §5Blaze§f is §6§lLEGENDARY§f!"));
        var decoded = legacy.decode(legacy.encoded());
        assertFalse(decoded.getString().contains("§"));
        assertTrue(StyledParagraph.glyphs(decoded).stream().anyMatch(g -> g.text().equals("L") && g.style().isBold() && g.style().getColor().getValue() == net.minecraft.network.chat.TextColor.GOLD.getValue()));
    }
    @Test void separatesNpcFromItemAndSpeakerCachesAndLogsReadableDialogue() throws Exception {
        var calls = new AtomicInteger();
        try (var t = new Translator(new Config(), dir.resolve("cache.json"), LoggerFactory.getLogger("npc-test"), text -> { calls.incrementAndGet(); return "よかった！"; })) {
            t.request("Great!").get(3, TimeUnit.SECONDS);
            var p = NpcTranslation.paragraph(Component.literal("[NPC] Elle: Great!"));
            t.requestNpc(p, "Elle").get(3, TimeUnit.SECONDS);
            t.requestNpc(p, "Elle").get(3, TimeUnit.SECONDS);
            t.requestNpc(p, "Kat").get(3, TimeUnit.SECONDS);
            assertEquals(3, calls.get());
            assertTrue(Files.readString(dir.resolve("npc-translations.jsonl")).contains("よかった"));
        }
    }
    @Test void showsOriginalInsteadOfServiceErrorAndLogsWithoutRetry() throws Exception {
        var calls = new AtomicInteger();
        try (var t = new Translator(new Config(), dir.resolve("cache.json"), LoggerFactory.getLogger("npc-test"), text -> calls.incrementAndGet() == 1 ? "NO_JAPANESE: 訳文が提供されていません。" : "今の派閥はどう？")) {
            assertEquals("How do you like your Faction?", t.requestNpc("How do you like your Faction?", "Elle").get(10, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertTrue(Files.readString(dir.resolve("translation-failures.jsonl")).contains("NPC_META_RESPONSE"));
        }
    }
}
