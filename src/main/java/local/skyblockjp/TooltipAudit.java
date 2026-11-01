package local.skyblockjp;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/** Writes each changed tooltip once, off the render thread. */
final class TooltipAudit implements AutoCloseable {
    private final String session = UUID.randomUUID().toString();
    private final Map<String, String> seen = new LinkedHashMap<>();
    private final ThreadPoolExecutor writer =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(128),
                    r -> {
                        Thread t = new Thread(r, "SkyBlock-JP-Audit");
                        t.setDaemon(true);
                        return t;
                    });
    private final Path file;
    private final Logger logger;
    private final String secret;
    private final Set<CompletableFuture<Void>> snapshotBarriers = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong writeFailures =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean closed;

    TooltipAudit(Path file, Logger logger, String secret) {
        this.file = file;
        this.logger = logger;
        this.secret = secret;
    }

    private long lastQueueWarning;
    private long inputSequence;
    private long droppedRecords;

    private static final class ObservationState {
        final String signature;
        final Map<String, Object> first;
        long count = 1;
        String last;

        ObservationState(String signature, Map<String, Object> first) {
            this.signature = signature;
            this.first = Map.copyOf(first);
            last = String.valueOf(first.get("observedAt"));
        }
    }

    private final LinkedHashMap<String, ObservationState> observations =
            new LinkedHashMap<>(64, 0.75f, true);

    void forget(String key) {
        seen.remove("display:" + key);
    }

    /** Called by the UI owner: flush cumulative observations, then enqueue a FIFO disk boundary. */
    CompletableFuture<Void> snapshotBarrier() {
        if (closed)
            return CompletableFuture.failedFuture(
                    new CancellationException("Tooltip audit closed"));
        long failedBefore = writeFailures.get();
        long droppedBefore = droppedRecords;
        observations.forEach(this::flushObservation);
        if (droppedRecords != droppedBefore)
            return CompletableFuture.failedFuture(
                    new RejectedExecutionException("Tooltip snapshot queue full"));
        var complete = new CompletableFuture<Void>();
        snapshotBarriers.add(complete);
        try {
            writer.execute(
                    () -> {
                        if (writeFailures.get() != failedBefore)
                            complete.completeExceptionally(
                                    new java.io.IOException("Tooltip snapshot write failed"));
                        else complete.complete(null);
                        snapshotBarriers.remove(complete);
                    });
        } catch (RejectedExecutionException stopped) {
            complete.completeExceptionally(stopped);
            snapshotBarriers.remove(complete);
        }
        return complete;
    }

    static String tooltipId(String key) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void record(
            String key,
            List<Component> original,
            List<Component> rendered,
            List<String> translations,
            int width,
            String provider,
            String model) {
        recordEntry(key, original, rendered, translations, width, provider, model, Map.of());
    }

    /** Includes misses and zero-block tooltips, so unchanged English is diagnosable too. */
    void recordDetailed(
            String key,
            List<Component> original,
            List<Component> rendered,
            List<String> translations,
            int width,
            String apiProvider,
            String model,
            String channel,
            String screenTitle,
            List<LoreParser.Block> blocks,
            List<Translator.DictionaryDecision> decisions,
            String mode,
            boolean dictionaryAllowed) {
        recordDetailed(
                key,
                original,
                rendered,
                translations,
                width,
                apiProvider,
                model,
                channel,
                screenTitle,
                blocks,
                decisions,
                mode,
                dictionaryAllowed,
                null,
                null,
                List.of(),
                false,
                dictionaryAllowed);
    }

    void recordDetailed(
            String key,
            List<Component> original,
            List<Component> rendered,
            List<String> translations,
            int width,
            String apiProvider,
            String model,
            String channel,
            String screenTitle,
            List<LoreParser.Block> blocks,
            List<Translator.DictionaryDecision> decisions,
            String mode,
            boolean dictionaryAllowed,
            TooltipContext.Decision context,
            LoreParser.Analysis analysis,
            List<Translator.CacheInspection> cacheStates,
            boolean requested,
            boolean autoDisplay) {
        var paragraphs = new ArrayList<Map<String, Object>>();
        Set<String> suppliers = new LinkedHashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.get(i);
            var decision = decisions.get(i);
            var cache = i < cacheStates.size() ? cacheStates.get(i) : null;
            String supplier =
                    dictionaryAllowed && decision.accepted() && !mode.equals("ORIGINAL")
                            ? "DICTIONARY"
                            : mode.equals("FULL")
                                    ? cache == null
                                            ? "API_CACHE"
                                            : switch (cache.status()) {
                                                case "API_CACHE", "API_PROVISIONAL" ->
                                                        cache.status();
                                                default -> "ORIGINAL";
                                            }
                                    : "ORIGINAL";
            suppliers.add(supplier);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("start", block.start());
            row.put("end", block.end());
            row.put("source", block.text());
            row.put("paragraphIndex", i);
            row.put("paragraphId", tooltipId(key) + ":" + i);
            row.put("lineStart", block.start() + 1);
            row.put("lineEnd", block.end());
            row.put("channel", decision.channel());
            row.put("reason", decision.reason());
            row.put("matchMode", decision.mode());
            row.put("sourceIds", decision.sourceIds().stream().limit(16).toList());
            row.put("sourceIdCount", decision.sourceIds().size());
            row.put("supplier", supplier);
            row.put("encodedDictionaryTranslation", decision.encoded());
            row.put("dictionaryAccepted", decision.accepted());
            row.put("dictionaryDetails", decision.details());
            String stage =
                    String.valueOf(
                            decision.details()
                                    .getOrDefault(
                                            "stage",
                                            decision.accepted()
                                                    ? "ACCEPTED"
                                                    : "DICTIONARY_LOOKUP"));
            row.put("dictionaryStage", stage);
            String stopStage =
                    !supplier.equals("ORIGINAL")
                            ? "NONE"
                            : cache != null
                                            && cache.status().equals("API_DISABLED")
                                            && requested
                                            && !(decision.accepted() && !autoDisplay)
                                    ? "POLICY"
                                    : cache != null && cache.status().equals("API_FAILED_ORIGINAL")
                                            ? "API_REQUEST"
                                            : mode.equals("FULL")
                                                            && cache != null
                                                            && cache.status()
                                                                    .equals("ORIGINAL_FALLBACK")
                                                    ? "API_RESULT"
                                                    : mode.equals("PENDING")
                                                            ? "BATCH_WAIT"
                                                            : decision.accepted()
                                                                    ? "DISPLAY_POLICY"
                                                                    : stage;
            row.put("stopStage", stopStage);
            row.put(
                    "displayReason",
                    !supplier.equals("ORIGINAL")
                            ? decision.reason().equals("KEEP_ORIGINAL")
                                    ? "KEEP_ORIGINAL"
                                    : "TRANSLATION_APPLIED"
                            : cache != null
                                            && cache.status().equals("API_DISABLED")
                                            && requested
                                            && !(decision.accepted() && !autoDisplay)
                                    ? "API_TRANSLATION_DISABLED"
                                    : cache != null && cache.status().equals("API_FAILED_ORIGINAL")
                                            ? "API_FAILED_ORIGINAL"
                                            : mode.equals("FULL")
                                                            && cache != null
                                                            && cache.status()
                                                                    .equals("ORIGINAL_FALLBACK")
                                                    ? "ORIGINAL_FALLBACK"
                                                    : mode.equals("PENDING")
                                                            ? "WAITING_FOR_COMPLETE_BATCH"
                                                            : decision.accepted()
                                                                            && !requested
                                                                            && !autoDisplay
                                                                    ? "DICTIONARY_AUTO_DISPLAY_OFF"
                                                                    : decision.reason());
            if (cache != null) {
                row.put("apiState", cache.status());
                row.put("apiError", cache.error());
            }
            if (cache != null)
                row.put("apiTranslationEnabled", !cache.status().equals("API_DISABLED"));
            if (i < translations.size()) row.put("displayedText", translations.get(i));
            if (analysis != null && i < analysis.paragraphs().size()) {
                var planned = analysis.paragraphs().get(i);
                row.put("role", planned.role());
                row.put("parseReason", planned.reason());
            }
            paragraphs.add(row);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("channel", channel);
        details.put("screenTitle", screenTitle);
        details.put("displayMode", mode);
        details.put("dictionaryDisplayAllowed", dictionaryAllowed);
        details.put("paragraphDecisions", paragraphs);
        details.put("manualRequested", requested);
        details.put("dictionaryAutoDisplay", autoDisplay);
        if (!cacheStates.isEmpty())
            details.put(
                    "apiTranslationEnabled",
                    cacheStates.stream().noneMatch(s -> s.status().equals("API_DISABLED")));
        details.put("classification", blocks.isEmpty() ? "NO_DESCRIPTION_BLOCKS" : "PARSED");
        if (context != null)
            details.put(
                    "tooltipClassification",
                    Map.of(
                            "kind",
                            context.kind().name(),
                            "reason",
                            context.reason(),
                            "evidence",
                            context.evidence()));
        if (analysis != null) {
            details.put(
                    "lineDecisions",
                    analysis.lines().stream()
                            .limit(256)
                            .map(
                                    line ->
                                            Map.<String, Object>of(
                                                    "index",
                                                    line.index(),
                                                    "lineNumber",
                                                    line.index() + 1,
                                                    "source",
                                                    line.source(),
                                                    "role",
                                                    line.role(),
                                                    "action",
                                                    line.action(),
                                                    "reason",
                                                    line.reason(),
                                                    "paragraphIndex",
                                                    line.paragraphIndex()))
                            .toList());
            Map<String, Long> counts = new LinkedHashMap<>();
            for (var line : analysis.lines())
                counts.merge(line.action() + ":" + line.reason(), 1L, Long::sum);
            details.put("lineDecisionCounts", counts);
        }
        // Component equality retains color/style. Defer the more expensive segment snapshot until
        // this changes.
        details.put("styleFingerprint", original.hashCode());
        String provider =
                suppliers.isEmpty()
                        ? "ORIGINAL"
                        : suppliers.size() > 1 ? "MIXED" : suppliers.iterator().next();
        details.put("apiProvider", apiProvider);
        recordEntry(key, original, rendered, translations, width, provider, model, details);
    }

    /** Early gates/input events are separate from actual displayed translation records. */
    void recordPipeline(
            String key,
            List<Component> original,
            String stage,
            String reason,
            Map<String, Object> details) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("schemaVersion", 3);
        entry.put("event", "TOOLTIP_PIPELINE");
        entry.put("session", session);
        entry.put("version", ModVersion.CURRENT);
        entry.put("tooltipId", details.getOrDefault("tooltipId", tooltipId(key)));
        entry.put("stage", stage);
        entry.put("reason", reason);
        entry.put("traceId", entry.get("tooltipId"));
        entry.put("item", original.isEmpty() ? "" : original.getFirst().getString());
        entry.put("sourceStyleFingerprint", original.hashCode());
        boolean matched =
                stage.equals("OBSERVATION")
                        && Set.of("SOURCE_LIST_IDENTITY", "UNIQUE_STYLED_CONTENT").contains(reason);
        boolean unmatchedDisplay = stage.equals("OBSERVATION") && !matched;
        entry.put(
                "sourceSnapshotKind",
                unmatchedDisplay
                        ? "UNMATCHED_DISPLAY"
                        : matched ? "MATCHED_ORIGINAL" : "CALLER_SOURCE");
        entry.put(
                unmatchedDisplay ? "observedDisplayLines" : "originalLines",
                original.stream().limit(256).map(Component::getString).toList());
        entry.put("details", Map.copyOf(details));
        if (stage.equals("MANUAL_INPUT")
                || stage.equals("RETRANSLATION")
                || stage.startsWith("REQUEST_")) entry.put("eventSequence", ++inputSequence);
        var semantic = new TreeMap<String, Object>(entry);
        var semanticDetails = new TreeMap<String, Object>(details);
        semanticDetails.remove("displayX");
        semanticDetails.remove("displayY");
        semantic.put("details", semanticDetails);
        String signature = semantic.toString();
        entry.put("observedAt", java.time.Instant.now().toString());
        if (stage.equals("OBSERVATION")) {
            var previous = observations.get(key);
            if (previous != null && previous.signature.equals(signature)) {
                previous.count++;
                previous.last = String.valueOf(entry.get("observedAt"));
                return;
            }
            if (previous != null) flushObservation(key, previous);
            if (observations.size() >= 256 && !observations.containsKey(key)) {
                var oldest = observations.entrySet().iterator().next();
                flushObservation(oldest.getKey(), oldest.getValue());
                observations.remove(oldest.getKey());
            }
            entry.put("recordKind", "STATE_CHANGE");
            entry.put("observationCount", 1);
            entry.put("firstObservedAt", entry.get("observedAt"));
            entry.put("lastObservedAt", entry.get("observedAt"));
            observations.put(key, new ObservationState(signature, entry));
        }
        enqueue(
                file.resolveSibling("translation-pipeline.jsonl"),
                "pipeline:" + stage + ":" + key,
                signature,
                entry);
    }

    private void flushObservation(String key, ObservationState state) {
        if (state.count <= 1) return;
        var summary = new LinkedHashMap<String, Object>(state.first);
        summary.put("recordKind", "STATE_SUMMARY");
        summary.put("observationCount", state.count);
        summary.put("lastObservedAt", state.last);
        summary.put("observedAt", state.last);
        enqueue(
                file.resolveSibling("translation-pipeline.jsonl"),
                "observation-summary:" + key,
                state.signature + "|" + state.count,
                summary);
    }

    private void recordEntry(
            String key,
            List<Component> original,
            List<Component> rendered,
            List<String> translations,
            int width,
            String provider,
            String model,
            Map<String, Object> details) {
        if (original.isEmpty()) return;
        List<String> before = original.stream().map(Component::getString).toList();
        List<String> after = rendered.stream().map(Component::getString).toList();
        String signature = width + "|" + after + "|" + translations + "|" + details;
        if (signature.equals(seen.get("display:" + key))) return;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("schemaVersion", 3);
        entry.put("event", "TOOLTIP_DISPLAY");
        entry.put("tooltipId", tooltipId(key));
        entry.put("traceId", tooltipId(key));
        entry.put("session", session);
        entry.put("version", ModVersion.CURRENT);
        entry.put("item", before.getFirst());
        entry.put("observedAt", java.time.Instant.now().toString());
        entry.put("provider", provider);
        entry.put("model", model);
        entry.put("wrapWidthPixels", width);
        entry.put("originalLines", before);
        entry.put("translatedParagraphs", translations);
        entry.put("displayLines", after);
        entry.put("displayText", String.join("\n", after));
        entry.put("stage", "mod tooltip lines before Minecraft/other-mod layout");
        entry.putAll(details);
        if (!details.isEmpty()) {
            var snapshot = styledLines(original);
            entry.put("originalStyledLines", snapshot);
            long captured =
                    snapshot.stream()
                            .flatMap(List::stream)
                            .mapToLong(
                                    s -> {
                                        String t = (String) s.get("text");
                                        return t.codePointCount(0, t.length());
                                    })
                            .sum();
            long expected = before.stream().mapToLong(t -> t.codePointCount(0, t.length())).sum();
            entry.put(
                    "originalStyledLinesComplete",
                    snapshot.size() == original.size() && captured == expected);
            entry.put("originalStyledGlyphCount", captured);
            var displayed = styledLines(rendered, false);
            entry.put("displayStyledLines", displayed);
            long displayedCaptured =
                    displayed.stream()
                            .flatMap(List::stream)
                            .mapToLong(
                                    s ->
                                            ((String) s.get("text"))
                                                    .codePointCount(
                                                            0, ((String) s.get("text")).length()))
                            .sum();
            long displayedExpected =
                    after.stream().mapToLong(t -> t.codePointCount(0, t.length())).sum();
            entry.put(
                    "displayStyledLinesComplete",
                    displayed.size() == after.size() && displayedCaptured == displayedExpected);
            entry.put("displayStyledGlyphCount", displayedCaptured);
            entry.put("normalizationVersion", "ascii_case_single_period_stat_glyphs_v1");
        }
        enqueue(file, "display:" + key, signature, entry);
    }

    private void enqueue(
            Path destination, String key, String signature, Map<String, Object> entry) {
        if (signature.equals(seen.get(key))) return;
        entry.put("skippedDiagnosticRecords", droppedRecords);
        try {
            writer.execute(
                    () -> {
                        try {
                            TranslationDiagnostics.append(destination, entry, secret);
                        } catch (Exception e) {
                            writeFailures.incrementAndGet();
                            logger.warn(
                                    "SkyBlock JP: tooltip audit could not save ({})",
                                    e.getClass().getSimpleName());
                        }
                    });
            if (seen.size() >= 1000) seen.remove(seen.keySet().iterator().next());
            seen.put(key, signature);
        } catch (RejectedExecutionException e) {
            droppedRecords++;
            long now = System.currentTimeMillis();
            if (now - lastQueueWarning >= 30_000) {
                lastQueueWarning = now;
                logger.warn(
                        "SkyBlock JP: tooltip audit queue full/closed; diagnostic record skipped (warnings limited to once per 30s)");
            }
        }
    }

    private static List<List<Map<String, Object>>> styledLines(List<Component> lines) {
        return styledLines(lines, true);
    }

    private static List<List<Map<String, Object>>> styledLines(
            List<Component> lines, boolean nativeMetadata) {
        var result = new ArrayList<List<Map<String, Object>>>();
        int remaining = 64_000;
        for (var line : lines.stream().limit(256).toList()) {
            var segments = new ArrayList<Map<String, Object>>();
            var glyphs = StyledParagraph.glyphs(line);
            for (int i = 0; i < glyphs.size() && remaining > 0; ) {
                var style = glyphs.get(i).style();
                var text = new StringBuilder();
                do {
                    text.append(glyphs.get(i++).text());
                    remaining--;
                } while (i < glyphs.size() && remaining > 0 && glyphs.get(i).style().equals(style));
                Map<String, Object> segment = new LinkedHashMap<>();
                segment.put("text", text.toString());
                if (style.getColor() != null) segment.put("color", style.getColor().getValue());
                if (nativeMetadata) segment.put("nativeStyle", style.toString());
                segment.put("bold", style.isBold());
                segment.put("italic", style.isItalic());
                segment.put("underlined", style.isUnderlined());
                segment.put("strikethrough", style.isStrikethrough());
                segment.put("obfuscated", style.isObfuscated());
                segments.add(segment);
            }
            result.add(segments);
            if (remaining <= 0) break;
        }
        return result;
    }

    public void close() {
        if (closed) return;
        observations.forEach(this::flushObservation);
        observations.clear();
        closed = true;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            snapshotBarriers.forEach(
                    barrier ->
                            barrier.completeExceptionally(
                                    new CancellationException("Tooltip audit closed")));
            snapshotBarriers.clear();
        }
    }
}
