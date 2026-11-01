package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class ProvisionalDisplayTest {
    @TempDir Path dir;

    private static Config apiConfig() {
        var c = new Config();
        c.candidateDictionary = false;
        return c;
    }

    @Test
    void missingNumberDisplaysTranslationAndColoredOriginal() {
        var p =
                new StyledParagraph(
                        List.of(
                                Component.literal("Deal ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(
                                                Component.literal("50")
                                                        .withStyle(ChatFormatting.GREEN))
                                        .append(Component.literal(" damage."))));
        var output = p.decodeForDisplay("ダメージを与える。");
        assertTrue(output.getString().contains("ダメージを与える。\n原文: Deal 50 damage."));
        assertTrue(
                StyledParagraph.glyphs(output).stream()
                        .anyMatch(
                                g ->
                                        g.text().equals("5")
                                                && g.style().getColor().getValue()
                                                        == net.minecraft.network.chat.TextColor
                                                                .GREEN
                                                                .getValue()));
        assertEquals(2, TooltipWrap.wrap(output, 500, c -> c.getString().length()).size());
    }

    @Test
    void brokenStyleDoesNotHideTranslationOrNumber() {
        var p = new StyledParagraph(List.of(Component.literal("Deal 50 damage.")));
        assertEquals("50ダメージ。", p.decodeForDisplay("<s99><n0/>ダメージ。</s98>").getString());
    }

    @Test
    void durationMultiplierWithColoredXIsAccepted() {
        String source =
                "Your pet fuses its power with placed <t0>Deployables</t0> to give them <n0/><s0>x </s0>duration.";
        assertNull(
                TranslationContentChecks.missing(
                        source, "ペットが配置した<t0>Deployables</t0>と力を融合し、持続時間を<n0/><s0>x </s0>にする。"));
    }

    @Test
    void changedTermRestoresOriginalName() {
        var p = new StyledParagraph(List.of(Component.literal("Gain Health.")));
        assertEquals("Healthが増加する。", p.decodeForDisplay("<t0>体力</t0>が増加する。").getString());
    }

    @Test
    void tradeMillionIsNotMinutes() {
        var p = new StyledParagraph(List.of(Component.literal("ill do it for 100m LOL")));
        assertTrue(p.decode(p.encoded()).getString().contains("100m"));
        assertFalse(p.decode(p.encoded()).getString().contains("分"));
    }

    @Test
    void communicationFailureFinishesWithOriginalAndAllowsRefresh() throws Exception {
        var calls = new AtomicInteger();
        try (var t =
                new Translator(
                        apiConfig(),
                        dir.resolve("cache.json"),
                        LoggerFactory.getLogger("test"),
                        text -> {
                            if (calls.incrementAndGet() == 1)
                                throw new java.io.IOException("test failure");
                            return "こんにちは！";
                        })) {
            assertEquals("Hello!", t.request("Hello!").get(3, TimeUnit.SECONDS));
            assertEquals("Hello!", t.cached("Hello!"));
            assertEquals(
                    "こんにちは！", t.retranslate("Hello!", "manual retry").get(3, TimeUnit.SECONDS));
            t.awaitDiagnostics().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(
                    Files.readString(dir.resolve("translation-failures.jsonl"))
                            .contains("IOException"));
        }
    }

    @Test
    void provisionalResponseIsNotPersistedAndDoesNotRetryAutomatically() throws Exception {
        var calls = new AtomicInteger();
        Path file = dir.resolve("cache.json");
        try (var t =
                new Translator(
                        apiConfig(),
                        file,
                        LoggerFactory.getLogger("test"),
                        text -> {
                            calls.incrementAndGet();
                            return "Hello!";
                        })) {
            assertEquals("Hello!", t.request("Hello!").get(3, TimeUnit.SECONDS));
            assertEquals("Hello!", t.request("Hello!").get(3, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        }
        try (var t =
                new Translator(
                        apiConfig(), file, LoggerFactory.getLogger("test"), text -> "こんにちは！")) {
            assertEquals("こんにちは！", t.request("Hello!").get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void inputLimitCompletesInsteadOfWaitingForever() throws Exception {
        String source = "a".repeat(4001);
        try (var t =
                new Translator(
                        apiConfig(),
                        dir.resolve("cache.json"),
                        LoggerFactory.getLogger("test"),
                        text -> {
                            throw new AssertionError();
                        })) {
            assertEquals(source, t.request(source).get());
            assertEquals(source, t.cached(source));
        }
    }

    @Test
    void chatHeaderStaysLocal() throws Exception {
        String source = "<t0>[VIP] Player: </t0>Hello!";
        assertEquals("Hello!", ChatText.body(source));
        assertEquals("<t0>[VIP] Player: </t0>こんにちは！", ChatText.rejoin(source, "こんにちは！"));
    }

    @Test
    void creatureTypeAndSlayerLevelStayOriginal() {
        assertTrue(LoreParser.protectedLine("\uE000 Arthropod"));
        assertTrue(LoreParser.protectedLine("Enderman Slayer: LVL 8"));
        assertFalse(LoreParser.protectedLine("Grants more damage against Arthropods."));
    }
}
