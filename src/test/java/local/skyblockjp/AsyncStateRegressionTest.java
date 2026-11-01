package local.skyblockjp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class AsyncStateRegressionTest {
    @TempDir Path dir;
    private static final String COLORED_SOURCE = "When an enemy is hit by a <t0>Snowball</t0>, they are <s0>stunned</s0> for <n0/> seconds and deal <n1/> less damage.";
    private static final String COLORED_RESPONSE = "<t0>Snowball</t0>が命中した敵は<n0/>秒間<s0>スタン</s0>し、与えるダメージが<n1/>減少する。";
    private static Config config() { var c = new Config(); c.provider = "GOOGLE"; return c; }
    private static StyledParagraph paragraph(String text) { return new StyledParagraph(List.of(Component.literal(text))); }
    private Translator translator(Config c, String name, Translator.Transport transport, AtomicLong clock) {
        return new Translator(c, dir.resolve(name + ".json"), LoggerFactory.getLogger("async-state-regression"),
            transport, BundledDictionary.empty(), clock::get);
    }

    @Test void semanticRepairCannotDestroyAlreadyValidColorBindings() {
        assertNull(TranslationDiagnostics.validate(COLORED_SOURCE, COLORED_RESPONSE));
        assertEquals(COLORED_RESPONSE, TranslationRepair.repair(COLORED_SOURCE, COLORED_RESPONSE));
        assertNull(TranslationDiagnostics.validate(COLORED_SOURCE, TranslationRepair.repair(COLORED_SOURCE, COLORED_RESPONSE)));
        String npcSource = "I'm the town <s0>Blacksmith</s0>! I can reforge items for you, for a price.";
        String npcResponse = "町の<s0>鍛冶屋</s0>だよ！料金を払えばアイテムをリフォージしてあげるよ。";
        assertEquals(npcResponse, NpcTranslationRepair.repair(npcSource, npcResponse, ""));
    }

    @Test void rejectedRepairIsLoggedAndOriginalValidResponseIsPersisted() throws Exception {
        var c = config();
        try (var t = translator(c, "repair", text -> COLORED_RESPONSE, new AtomicLong(1))) {
            assertEquals(COLORED_RESPONSE, t.request(COLORED_SOURCE).get(3, TimeUnit.SECONDS));
            assertEquals("API_CACHE", t.cacheInspection(COLORED_SOURCE, "item").status());
            var record = JsonParser.parseString(Files.readAllLines(dir.resolve("translation-repairs.jsonl")).getFirst()).getAsJsonObject();
            assertEquals("REJECTED_REPAIR_KEPT_ORIGINAL", record.get("decision").getAsString());
            assertEquals(COLORED_RESPONSE, record.get("rawResponse").getAsString());
            assertEquals(COLORED_RESPONSE, record.get("repairedResponse").getAsString());
            assertTrue(record.get("candidateValidation").getAsString().startsWith("MARKER_MISMATCH"));
            assertFalse(Files.exists(dir.resolve("translation-failures.jsonl")));
        }
        try (var t = translator(c, "repair", text -> { throw new AssertionError("persisted hit must not send"); }, new AtomicLong(1))) {
            assertEquals(COLORED_RESPONSE, t.request(COLORED_SOURCE).join());
        }
    }

    @Test void npcFailureRetriesAfterExpiryButNotBefore() throws Exception {
        var clock = new AtomicLong(1000); var calls = new AtomicInteger();
        var p = paragraph("Come back later.");
        try (var t = translator(config(), "expiry", text -> {
            if (calls.incrementAndGet() == 1) throw new IOException("temporary fixture failure");
            return "また後で来てね。";
        }, clock)) {
            assertEquals(p.encoded(), t.requestNpc(p, "Test", "Earlier sentence.").get(3, TimeUnit.SECONDS));
            clock.set(60999);
            assertEquals(p.encoded(), t.requestNpc(p, "Test", "Earlier sentence.").join());
            assertEquals(1, calls.get());
            clock.set(61000);
            assertEquals("また後で来てね。", t.requestNpc(p, "Test", "Earlier sentence.").get(3, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        }
    }

    @Test void manualNpcRetryBypassesFailureCooldownAndCoalescesRepeatedClicks() throws Exception {
        var calls = new AtomicInteger(); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var p = paragraph("Come back later.");
        try (var t = translator(config(), "manual", text -> {
            if (calls.incrementAndGet() == 1) throw new IOException("temporary fixture failure");
            started.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return "また後で来てね。";
        }, new AtomicLong(1000))) {
            assertEquals(p.encoded(), t.requestNpc(p, "Test", "Earlier sentence.").get(3, TimeUnit.SECONDS));
            var retry = t.requestNpcManually(p, "Test", "Earlier sentence.");
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
                assertSame(retry, t.requestNpcManually(p, "Test", "Earlier sentence."));
            } finally { release.countDown(); }
            assertEquals("また後で来てね。", retry.get(3, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            assertEquals("また後で来てね。", t.requestNpcManually(p, "Test", "Earlier sentence.").join());
            assertEquals(2, calls.get(), "manual retry must retain successful cached replies");
        }
    }

    @Test void disabledManualNpcRetryDoesNotDiscardFailureState() throws Exception {
        var c = config(); var calls = new AtomicInteger(); var p = paragraph("Come back later.");
        try (var t = translator(c, "disabled-manual", text -> { calls.incrementAndGet(); throw new IOException("fixture"); }, new AtomicLong(1000))) {
            assertEquals(p.encoded(), t.requestNpc(p, "Test").get(3, TimeUnit.SECONDS));
            c.apiTranslation = false;
            assertNull(t.requestNpcManually(p, "Test", "").join());
            c.apiTranslation = true;
            assertEquals(p.encoded(), t.requestNpc(p, "Test").join());
            assertEquals(1, calls.get());
        }
    }

    @Test void provisionalResponseIsNotConfusedWithExpiredCommunicationFailure() throws Exception {
        var clock = new AtomicLong(1000); var calls = new AtomicInteger();
        try (var t = translator(config(), "provisional", text -> { calls.incrementAndGet(); return "Hello!"; }, clock)) {
            assertEquals("Hello!", t.requestNpc("Hello!", "Test").get(3, TimeUnit.SECONDS));
            clock.set(1_000_000);
            assertEquals("Hello!", t.requestNpc("Hello!", "Test").join());
            assertEquals(1, calls.get(), "display-anyway provisional results must not start automatic repeat requests");
        }
    }

    @Test void oneNpcFailureDoesNotBlockAnotherSpeakerOrDialogueContext() throws Exception {
        var calls = new AtomicInteger(); var p = paragraph("Come back later.");
        try (var t = translator(config(), "scope", text -> {
            if (calls.incrementAndGet() == 1) throw new IOException("fixture");
            return "また後で来てね。";
        }, new AtomicLong(1000))) {
            assertEquals(p.encoded(), t.requestNpc(p, "First", "A").get(3, TimeUnit.SECONDS));
            assertEquals("また後で来てね。", t.requestNpc(p, "Second", "A").get(3, TimeUnit.SECONDS));
            assertEquals("また後で来てね。", t.requestNpc(p, "First", "B").get(3, TimeUnit.SECONDS));
            assertEquals(p.encoded(), t.requestNpc(p, "First", "A").join());
            assertEquals(3, calls.get());
        }
    }

    @Test void cacheCapacityEvictsOnlyLeastRecentlyUsedEntryAndPersistsTheRest() throws Exception {
        var c = config(); var clock = new AtomicLong(1); Path file = dir.resolve("capacity.json");
        try (var t = translator(c, "capacity", text -> "保存された訳。", clock)) {
            t.request("A recent description.").get(3, TimeUnit.SECONDS);
            t.request("An older description.").get(3, TimeUnit.SECONDS);
        }
        JsonObject seed = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        for (int i = seed.size(); i < Translator.CACHE_LIMIT; i++) seed.addProperty("fixture-" + i, "既存の訳。");
        Files.writeString(file, seed.toString());
        try (var t = translator(c, "capacity", text -> "新しい訳。", clock)) {
            assertEquals("保存された訳。", t.cached("A recent description."));
            assertEquals("新しい訳。", t.request("A brand new description.").get(3, TimeUnit.SECONDS));
            assertEquals("保存された訳。", t.cached("A recent description."));
            assertNull(t.cached("An older description."), "only the least recently used entry should be evicted");
        }
        var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(Translator.CACHE_LIMIT, saved.size());
        assertTrue(saved.has("fixture-2"));
        try (var t = translator(c, "capacity", text -> { throw new AssertionError("persistent hit must not send"); }, clock)) {
            assertEquals("保存された訳。", t.request("A recent description.").join());
            assertEquals("新しい訳。", t.request("A brand new description.").join());
        }
    }
}
