package local.skyblockjp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NpcDictionaryDecisionAuditTest {
    @TempDir Path dir;
    private Path decisions() { return dir.resolve("npc-dictionary-decisions.jsonl"); }
    private static Config config() {
        var config = new Config();
        config.candidateDictionary = true;
        config.dictionaryDiagnostics = true;
        config.apiTranslation = false;
        return config;
    }
    private static StyledParagraph paragraph(String source) {
        return new StyledParagraph(List.of(Component.literal(source).withStyle(ChatFormatting.GRAY)));
    }
    private static BundledDictionary dictionary(String source, String target) {
        var entries = source == null ? List.of() : List.of(Map.of("channel", "npc", "speaker", "Bob", "source", source,
            "target", target, "keep_original", false, "source_ids", List.of("fixture/npc")));
        String json = new Gson().toJson(Map.of("schema_version", 1, "build_id", "npc-audit-fixture", "tier", "candidate", "entries", entries));
        var result = BundledDictionary.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(result.stats().loaded(), result.stats().error());
        return result;
    }
    private Translator translator(Config config, Translator.Transport transport, BundledDictionary dictionary) {
        return new Translator(config, dir.resolve("cache.json"), LoggerFactory.getLogger("npc-dictionary-audit-test"), transport, dictionary);
    }
    private static ThreadPoolExecutor worker(Translator translator) throws Exception {
        var field = Translator.class.getDeclaredField("diagnosticWorker"); field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(translator);
    }
    // A FIFO barrier makes audit assertions deterministic without sleeps or any API call.
    private static void flush(Translator translator) throws Exception {
        worker(translator).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }
    private List<JsonObject> rows(Path file) throws Exception {
        if (!Files.exists(file)) return List.of();
        return Files.readAllLines(file).stream().filter(s -> !s.isBlank()).map(s -> JsonParser.parseString(s).getAsJsonObject())
            .filter(row->!file.getFileName().toString().equals("npc-dictionary-decisions.jsonl")
                ||row.has("event")&&row.get("event").getAsString().equals("NPC_DICTIONARY_DECISION")).toList();
    }
    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void diagnosticsOffDoesNotCreateAMissLogOrMakeAnApiRequest() throws Exception {
        var config = config(); config.dictionaryDiagnostics = false;
        var calls = new AtomicInteger();
        try (var translator = translator(config, text -> { calls.incrementAndGet(); return "こんにちは。"; }, dictionary(null, null))) {
            assertNull(translator.requestNpc(paragraph("A peculiar greeting."), "Bob", "Previous line").get());
            flush(translator);
            assertFalse(Files.exists(decisions()));
            assertEquals(0, calls.get());
        }
    }

    @Test void apiOffMissRecordsExactBodyContextHashesStyleAndPolicyStop() throws Exception {
        var config = config();
        String source = "✆ If left unchecked, it is possible that the powers of this creature will grow.";
        var paragraph = new StyledParagraph(List.of(Component.empty()
            .append(Component.literal("✆ ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(source.substring(2)).withStyle(ChatFormatting.WHITE))));
        String previous = "Earlier dialogue from Dean.";
        try (var translator = translator(config, text -> { fail("API OFF must not invoke transport"); return null; }, dictionary(null, null))) {
            assertNull(translator.requestNpc(paragraph, "Dean", previous).get());
            flush(translator);
            var row = rows(decisions()).getFirst();
            assertEquals(1, rows(decisions()).size());
            assertEquals("NPC_DICTIONARY_DECISION", row.get("event").getAsString());
            assertEquals(ModVersion.CURRENT, row.get("version").getAsString());
            assertEquals("Dean", row.get("speaker").getAsString());
            assertEquals(source, row.get("source").getAsString());
            assertEquals(paragraph.encoded(), row.get("encodedSource").getAsString());
            assertEquals(previous, row.get("previousDialogue").getAsString());
            assertEquals(previous, row.getAsJsonObject("dictionaryContext").get("previousDialogue").getAsString());
            assertEquals(hash(source), row.get("sourceSha256").getAsString());
            assertEquals(hash(previous), row.get("previousDialogueSha256").getAsString());
            assertEquals(hash(paragraph.dictionaryFingerprint()), row.get("styleFingerprintSha256").getAsString());
            assertEquals("npc-audit-fixture", row.get("dictionaryBuildId").getAsString());
            assertEquals("not_found", row.get("reason").getAsString());
            assertEquals("DICTIONARY_LOOKUP", row.get("stage").getAsString());
            assertEquals("API_OFF_STOP", row.get("fallbackAction").getAsString());
            assertEquals("POLICY", row.get("stopStage").getAsString());
            assertFalse(row.get("apiTranslationEnabled").getAsBoolean());
            assertTrue(row.get("sourceComplete").getAsBoolean());
            assertTrue(row.get("sourceStyledComplete").getAsBoolean());
            assertEquals("NATIVE_JOINED_SOURCE", row.get("sourceStyledOrigin").getAsString());
            var runs = row.getAsJsonArray("sourceStyledRuns");
            assertEquals("✆ ", runs.get(0).getAsJsonObject().get("text").getAsString());
            assertEquals(0xAAAAAA, runs.get(0).getAsJsonObject().get("color").getAsInt());
            assertEquals(0xFFFFFF, runs.get(1).getAsJsonObject().get("color").getAsInt());
            var loggedText = new StringBuilder();
            runs.forEach(run -> loggedText.append(run.getAsJsonObject().get("text").getAsString()));
            assertEquals(source, loggedText.toString());
            assertEquals(source.codePointCount(0, source.length()), row.get("sourceStyledGlyphCount").getAsInt());
            assertEquals(source.substring(2), runs.get(1).getAsJsonObject().get("text").getAsString());
        }
    }

    @Test void duplicatesCoalesceButSourceContextStyleAndPolicyChangesRemainVisible() throws Exception {
        var config = config(); var original = paragraph("A peculiar greeting.");
        try (var translator = translator(config, text -> { fail("API OFF"); return null; }, dictionary(null, null))) {
            translator.requestNpc(original, "Bob", "First branch");
            translator.requestNpc(original, "Bob", "First branch");
            translator.requestNpc(original, "Bob", "Second branch");
            translator.requestNpc(paragraph("A different greeting."), "Bob", "First branch");
            translator.requestNpc(new StyledParagraph(List.of(Component.literal(original.sourceText()).withStyle(ChatFormatting.YELLOW))), "Bob", "First branch");
            config.dictionaryPlainFallback = !config.dictionaryPlainFallback;
            translator.requestNpc(original, "Bob", "First branch");
            config.candidateDictionary = false;
            translator.requestNpc(original, "Bob", "First branch");
            flush(translator);
            var rows = rows(decisions());
            assertEquals(6, rows.size());
            assertEquals(6, rows.stream().map(r -> r.get("decisionId").getAsString()).distinct().count());
            assertEquals("DICTIONARY_DISABLED", rows.getLast().get("reason").getAsString());
        }
    }

    @Test void deduplicationMemoryIsBoundedAndEvictedSourcesCanBeLoggedAgain() throws Exception {
        try (var translator = translator(config(), text -> { fail("API OFF"); return null; }, dictionary(null, null))) {
            for (int i = 0; i < 513; i++) {
                translator.requestNpc(paragraph("Unique audit sentence " + i + "."), "Bob", "");
                if (i % 32 == 31) flush(translator);
            }
            flush(translator);
            assertEquals(513, rows(decisions()).size());
            translator.requestNpc(paragraph("Unique audit sentence 0."), "Bob", "");
            flush(translator);
            assertEquals(514, rows(decisions()).size());
        }
    }

    @Test void protectedValueRejectionKeepsCandidateAndFragmentDiagnostics() throws Exception {
        var dictionary = dictionary("Gain 10 Health.", "体力が10増加する。");
        assertTrue(dictionary.lookup("npc", "Gain 10 Health.", "Bob").isPresent());
        var paragraph = new StyledParagraph(List.of(Component.empty()
            .append(Component.literal("Gain ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("10 ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal("Health").withStyle(ChatFormatting.RED))
            .append(Component.literal(".").withStyle(ChatFormatting.GRAY))));
        try (var translator = translator(config(), text -> { fail("API OFF"); return null; }, dictionary)) {
            var decision = translator.inspectDictionary(paragraph, "npc", "Bob");
            assertFalse(decision.accepted());
            assertNull(translator.requestNpc(paragraph, "Bob", "").get());
            flush(translator);
            var row = rows(decisions()).getFirst();
            assertEquals("SEMANTIC_VALIDATION", row.get("stage").getAsString());
            assertTrue(decision.reason().startsWith("PROTECTED_"));
            assertEquals(decision.reason(), row.get("reason").getAsString());
            assertEquals("exact", row.get("matchMode").getAsString());
            assertEquals("fixture/npc", row.getAsJsonArray("sourceIds").get(0).getAsString());
            var details = row.getAsJsonObject("dictionaryDetails");
            assertEquals("体力が10増加する。", details.get("targetCandidate").getAsString());
            assertEquals(hash("体力が10増加する。"), details.get("targetSha256").getAsString());
            assertTrue(details.has("validationStage"));
            assertTrue(details.has("sourceLiteral") || details.has("styleLiteral"));
        }
    }
    @Test void anUnknownOrdinaryAccentRemainsAStyleRejection()throws Exception {
        String source="An unusual word glows.",target="珍しい言葉が光っている。";
        var paragraph=new StyledParagraph(List.of(Component.empty()
            .append(Component.literal("An unusual ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("word").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" glows.").withStyle(ChatFormatting.GRAY))));
        try(var translator=translator(config(),s->{fail("API OFF");return null;},dictionary(source,target))) {
            var decision=translator.inspectDictionary(paragraph,"npc","Bob");assertFalse(decision.accepted());
            assertEquals("STYLE_ALIGNMENT_MISSING",decision.reason());
            assertNull(translator.requestNpc(paragraph,"Bob","").get());flush(translator);
            var row=rows(decisions()).getFirst();assertEquals("STYLE_RESTORE",row.get("stage").getAsString());
            assertEquals("word",row.getAsJsonObject("dictionaryDetails").get("originalStyledFragment").getAsString());
        }
    }

    @Test void acceptedDictionaryResultUsesExistingSuccessLogWithoutAMissOrApiCall() throws Exception {
        var calls = new AtomicInteger(); var config = config(); config.apiTranslation = true;
        var paragraph = paragraph("Welcome back.");
        try (var translator = translator(config, text -> { calls.incrementAndGet(); return "外部の訳。"; }, dictionary("Welcome back.", "おかえり。"))) {
            String encoded = translator.requestNpc(paragraph, "Bob", "").get();
            assertEquals("おかえり。", paragraph.decode(encoded).getString());
            flush(translator);
            assertFalse(Files.exists(decisions()));
            var successes = rows(dir.resolve("dictionary-translations.jsonl"));
            assertEquals(1, successes.size());
            assertEquals("bundled-dictionary", successes.getFirst().get("mode").getAsString());
            assertEquals(0, calls.get());
        }
    }

    @Test void enabledApiFallbackStillRunsExactlyOnceAfterTheLoggedMiss() throws Exception {
        var config = config(); config.apiTranslation = true;
        var calls = new AtomicInteger(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var paragraph = paragraph("Hello there.");
        try (var translator = translator(config, text -> {
            calls.incrementAndGet(); entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "こんにちは。";
        }, dictionary(null, null))) {
            try {
                var first = translator.requestNpc(paragraph, "Bob", "");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertSame(first, translator.requestNpc(paragraph, "Bob", ""));
                release.countDown();
                assertEquals("こんにちは。", first.get(5, TimeUnit.SECONDS));
                flush(translator);
                assertEquals(1, calls.get());
                assertEquals(1, rows(decisions()).size());
                assertEquals("API_FALLBACK_ALLOWED", rows(decisions()).getFirst().get("fallbackAction").getAsString());
            } finally { release.countDown(); }
        }
    }

    @Test void closeDiscardsQueuedAuditAndLaterRequestsCannotWrite() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var translator = translator(config(), text -> { fail("API OFF"); return null; }, dictionary(null, null));
        var executor = worker(translator);
        try {
            executor.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertNull(translator.requestNpc(paragraph("Queued missing dialogue."), "Bob", "").get());
            translator.close();
            release.countDown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(translator.requestNpc(paragraph("After close."), "Bob", "").get());
            assertFalse(Files.exists(decisions()));
        } finally { release.countDown(); translator.close(); }
    }
}
