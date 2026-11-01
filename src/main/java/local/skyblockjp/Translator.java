package local.skyblockjp;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

/** Bounded FIFO, deduplication, retries and provider-scoped paragraph cache. */
public final class Translator implements AutoCloseable {
    private final Config config;
    private final Logger log;
    private final BundledDictionary dictionary;
    private final DictionaryTranslationResolver dictionaryResolver;
    private final DictionaryAuditTrail dictionaryAudit;
    // Explicit retranslation must keep displaying its API result, even if a dictionary entry
    // exists.
    private final Set<String> externalOverrides = ConcurrentHashMap.newKeySet();
    private final Set<String> paragraphOverrides = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> overridesByRequest = new ConcurrentHashMap<>();

    private static final class CandidateDictionary {
        private static final BundledDictionary VALUE = BundledDictionary.loadBundled();
    }

    private final TranslationProvider provider;
    private final TranslationAuditLog audit;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(256),
                    r -> {
                        Thread t = new Thread(r, "SkyBlock-JP");
                        t.setDaemon(true);
                        return t;
                    });
    static final int CACHE_LIMIT = 10000;
    private final TranslationCacheStore cache;
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    private enum TemporaryKind {
        PROVISIONAL,
        ORIGINAL,
        FAILED
    }

    private record TemporaryResult(
            String text, String error, TemporaryKind kind, long retryAfter) {}

    // A failure's displayed text, reason and retry time have one lifetime and one eviction policy.
    private final Map<String, TemporaryResult> temporaryResults = boundedLru(2000);
    private final LongSupplier retryClock;
    private long nextRequestAt;
    private volatile String lastError = "";
    private volatile boolean closed;
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private volatile Thread closingThread;

    private boolean apiAvailable() {
        return !closed && config.apiTranslation;
    }

    public String lastError() {
        return apiAvailable() ? lastError : "";
    }

    public String error(String text) {
        if (!apiAvailable()) return null;
        var result = temporaryResults.get(key(text));
        return result == null || result.error().isEmpty() ? null : result.error();
    }

    public boolean pending(String text) {
        return apiAvailable() && pending.containsKey(key(text));
    }

    public boolean pending(String text, String channel) {
        return apiAvailable() && pending.containsKey(scopeKey(text, channel));
    }

    public boolean pending(String text, String channel, Map<String, Object> context) {
        return apiAvailable() && pending.containsKey(scopeKey(text, channel, context));
    }

    public synchronized void retry(String text) {
        if (apiAvailable()) temporaryResults.remove(key(text));
    }

    public synchronized void retry(String text, String channel) {
        if (apiAvailable()) temporaryResults.remove(scopeKey(text, channel));
    }

    public synchronized void retry(String text, String channel, Map<String, Object> context) {
        if (apiAvailable()) temporaryResults.remove(scopeKey(text, channel, context));
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

    /** Refresh the original paragraph, coalescing clicks while a request is running. */
    public synchronized CompletableFuture<String> retranslate(String text, String context) {
        return retranslateInternal(text, context, null);
    }

    public synchronized CompletableFuture<String> retranslate(
            StyledParagraph paragraph, String context) {
        return retranslateInternal(paragraph.encoded(), context, paragraph.sourceText());
    }

    public synchronized CompletableFuture<String> retranslate(
            StyledParagraph paragraph, String context, Map<String, Object> dictionaryContext) {
        return retranslateInternal(
                paragraph.encoded(), context, paragraph.sourceText(), dictionaryContext);
    }

    private CompletableFuture<String> retranslateInternal(
            String text, String context, String source) {
        return retranslateInternal(text, context, source, Map.of());
    }

    private CompletableFuture<String> retranslateInternal(
            String text, String context, String source, Map<String, Object> dictionaryContext) {
        // Disabling API translation is read-only with respect to persisted responses and
        // manual override/cooldown state, so re-enabling it can resume the same cache.
        if (!apiAvailable()) return CompletableFuture.completedFuture(null);
        String channel = contextChannel(context);
        String k = scopeKey(text, channel, dictionaryContext);
        var current = pending.get(k);
        if (current != null && !current.isDone()) return current;
        if (current != null) pending.remove(k, current);
        if (source == null) externalOverrides.add(k);
        else {
            if (paragraphOverrides.size() >= 2000) {
                paragraphOverrides.clear();
                overridesByRequest.clear();
            }
            String token = overrideKey(channel, source, text, dictionaryContext);
            paragraphOverrides.add(token);
            overridesByRequest
                    .computeIfAbsent(k, ignored -> ConcurrentHashMap.newKeySet())
                    .add(token);
        }
        cache.remove(k);
        retry(text, channel, dictionaryContext);
        return requestInternal(text, context, null, null, "", false, dictionaryContext);
    }

    public synchronized CompletableFuture<String> connectionTest() {
        if (!apiAvailable()) return CompletableFuture.completedFuture(null);
        String probe = "This item restores 50 Health.";
        cache.remove(key(probe));
        retry(probe);
        return request(probe).thenApply(result -> error(probe) != null ? null : result);
    }

    @FunctionalInterface
    interface Transport {
        String translate(String text) throws Exception;
    }

    private final Transport transport;

    public Translator(Config config, Path cachePath, Logger log) {
        this(config, cachePath, log, null);
    }

    Translator(Config config, Path cachePath, Logger log, Transport transport) {
        this(
                config,
                cachePath,
                log,
                transport,
                config.candidateDictionary ? CandidateDictionary.VALUE : BundledDictionary.empty());
    }

    Translator(
            Config config,
            Path cachePath,
            Logger log,
            Transport transport,
            BundledDictionary dictionary) {
        this(config, cachePath, log, transport, dictionary, System::currentTimeMillis);
    }

    Translator(
            Config config,
            Path cachePath,
            Logger log,
            Transport transport,
            BundledDictionary dictionary,
            LongSupplier retryClock) {
        this(
                config,
                cachePath,
                log,
                transport,
                dictionary,
                retryClock,
                TranslationDiagnostics::append);
    }

    Translator(
            Config config,
            Path cachePath,
            Logger log,
            Transport transport,
            BundledDictionary dictionary,
            LongSupplier retryClock,
            TranslationAuditLog.Writer auditWriter) {
        this.config = config;
        this.log = log;
        this.dictionary = Objects.requireNonNull(dictionary);
        this.dictionaryResolver = new DictionaryTranslationResolver(config, dictionary);
        this.cache = new TranslationCacheStore(cachePath, CACHE_LIMIT, log);
        this.retryClock = Objects.requireNonNull(retryClock);
        this.transport = transport;
        this.provider = new TranslationProvider(config);
        this.audit = new TranslationAuditLog(cachePath, log, auditWriter);
        this.dictionaryAudit =
                new DictionaryAuditTrail(config, dictionary, audit, log, () -> !closed);
        if (config.candidateDictionary) {
            var stats = dictionary.stats();
            log.info(
                    "SkyBlock JP candidate dictionary: loaded={}, build={}, keys={}, rejected={}, conflicts={}, error={}",
                    stats.loaded(),
                    stats.buildId(),
                    stats.availableKeys(),
                    stats.rejectedEntries(),
                    stats.conflictingKeys(),
                    stats.error());
            log.info(
                    "SkyBlock JP reviewed dictionary: {}; alignments: {}; ordinary token roles: {}",
                    dictionary.reviewedStats(),
                    DictionaryAlignment.stats(),
                    OrdinaryTokenRoles.stats());
        }
    }

    private String key(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            ("v14|ja|"
                                                            + config.provider
                                                            + "|"
                                                            + config.groqModel
                                                            + "|"
                                                            + text)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String displayValue(String key) {
        String hit = cache.get(key);
        if (hit != null) return hit;
        var temporary = temporaryResults.get(key);
        return temporary == null ? null : temporary.text();
    }

    public String cached(String text) {
        return apiAvailable() ? displayValue(key(text)) : null;
    }

    public String cached(String text, String channel) {
        return apiAvailable() ? displayValue(scopeKey(text, channel)) : null;
    }

    public record CacheInspection(String status, String error) {}

    /** Read the existing request state only; never performs dictionary lookup or schedules work. */
    public CacheInspection cacheInspection(String text, String channel) {
        return cacheInspection(text, channel, Map.of());
    }

    public CacheInspection cacheInspection(
            String text, String channel, Map<String, Object> dictionaryContext) {
        if (!apiAvailable()) return new CacheInspection("API_DISABLED", "");
        String k = scopeKey(text, channel, dictionaryContext);
        if (cache.containsKey(k)) return new CacheInspection("API_CACHE", "");
        var temporary = temporaryResults.get(k);
        if (temporary != null) {
            if (temporary.kind() == TemporaryKind.FAILED)
                return new CacheInspection("API_FAILED_ORIGINAL", temporary.error());
            return new CacheInspection(
                    Objects.equals(temporary.text(), text)
                            ? "ORIGINAL_FALLBACK"
                            : "API_PROVISIONAL",
                    "");
        }
        // Follow result availability in the same way as tooltip display, including
        // the interval between publishing a result and completing/removing its future.
        var request = pending.get(k);
        return new CacheInspection(request != null && !request.isDone() ? "PENDING" : "EMPTY", "");
    }

    public String cached(StyledParagraph paragraph) {
        return cached(paragraph, "item");
    }

    public String cached(StyledParagraph paragraph, String channel) {
        return cached(paragraph, channel, Map.of());
    }

    public String cached(
            StyledParagraph paragraph, String channel, Map<String, Object> dictionaryContext) {
        if (closed) return null;
        String local = inspectDictionary(paragraph, channel, "", true, dictionaryContext).encoded();
        return local == null
                ? apiAvailable()
                        ? displayValue(scopeKey(paragraph.encoded(), channel, dictionaryContext))
                        : null
                : local;
    }

    /** Local-only, synchronous lookup. Never queues work, reads API cache, or contacts a provider. */
    public String dictionaryTranslation(StyledParagraph paragraph) {
        return localTranslation(paragraph, null);
    }

    public String dictionaryTranslation(StyledParagraph paragraph, String channel) {
        return inspectDictionary(paragraph, channel, "").encoded();
    }

    /** A real item-label decision can disambiguate verb-like ability names without guessing prose. */
    public boolean knownItemLabel(String title) {
        if (closed || !config.candidateDictionary || !dictionary.stats().loaded()) return false;
        return dictionary
                .inspect("item", title, "")
                .match()
                .filter(BundledDictionary.Match::keepOriginal)
                .filter(
                        match ->
                                match.sourceIds().stream()
                                        .anyMatch(id -> id.startsWith("item/label/")))
                .isPresent();
    }

    public record DictionaryDecision(
            String encoded,
            String reason,
            String mode,
            String channel,
            List<String> sourceIds,
            Map<String, Object> details) {
        public DictionaryDecision(
                String encoded,
                String reason,
                String mode,
                String channel,
                List<String> sourceIds) {
            this(encoded, reason, mode, channel, sourceIds, Map.of());
        }

        public DictionaryDecision {
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public boolean accepted() {
            return encoded != null;
        }
    }

    /** A bounded-category explanation for every local decision; never queues API work. */
    public DictionaryDecision inspectDictionary(
            StyledParagraph paragraph, String channel, String speaker) {
        return inspectDictionary(paragraph, channel, speaker, true);
    }

    /** Automatic display must not inherit another tooltip's manual API preference. */
    public DictionaryDecision inspectDictionary(
            StyledParagraph paragraph, String channel, String speaker, boolean manualRequested) {
        return inspectDictionary(paragraph, channel, speaker, manualRequested, Map.of());
    }

    public DictionaryDecision inspectDictionary(
            StyledParagraph paragraph,
            String channel,
            String speaker,
            boolean manualRequested,
            Map<String, Object> dictionaryContext) {
        if (closed)
            return new DictionaryDecision(
                    null,
                    "SERVICE_CLOSED",
                    "none",
                    channel,
                    List.of(),
                    dictionaryDetails("POLICY", Map.of()));
        if (!config.candidateDictionary)
            return new DictionaryDecision(
                    null,
                    "DICTIONARY_DISABLED",
                    "none",
                    channel,
                    List.of(),
                    dictionaryDetails("POLICY", Map.of()));
        if (!dictionary.stats().loaded())
            return new DictionaryDecision(
                    null,
                    "DICTIONARY_UNAVAILABLE",
                    "none",
                    channel,
                    List.of(),
                    dictionaryDetails("POLICY", Map.of()));
        if (apiAvailable()
                && manualRequested
                && !channel.equals("npc")
                && !channel.equals("system")
                && (externalOverrides.contains(
                                scopeKey(paragraph.encoded(), channel, dictionaryContext))
                        || paragraphOverrides.contains(
                                overrideKey(
                                        channel,
                                        paragraph.sourceText(),
                                        paragraph.encoded(),
                                        dictionaryContext))))
            return new DictionaryDecision(
                    null,
                    "API_RETRANSLATION_PRIORITY",
                    "none",
                    channel,
                    List.of(),
                    dictionaryDetails("POLICY", Map.of()));
        return dictionaryResolver.inspect(paragraph, channel, speaker, dictionaryContext);
    }

    boolean exactJapanese(String channel, String source) {
        if (!config.candidateDictionary || closed || !source.endsWith(" Recipe")) return false;
        var lookup = dictionary.inspect(channel, source, "");
        String expected = source.substring(0, source.length() - " Recipe".length()) + "のレシピ";
        return lookup.mode().equals("exact")
                && lookup.match()
                        .filter(m -> !m.keepOriginal() && m.text().equals(expected))
                        .isPresent();
    }

    private static String contextFingerprint(Map<String, Object> context) {
        return TranslationFingerprint.context(context);
    }

    private static Map<String, Object> dictionaryDetails(
            String stage, Map<String, Object> details) {
        var result = new LinkedHashMap<String, Object>(details);
        result.put("stage", stage);
        return result;
    }

    private String overrideKey(String channel, String source, String encoded) {
        return key(channel + "|" + source + "|" + encoded);
    }

    private String overrideKey(
            String channel, String source, String encoded, Map<String, Object> context) {
        return context == null || context.isEmpty()
                ? overrideKey(channel, source, encoded)
                : key(channel + "|" + contextFingerprint(context) + "|" + source + "|" + encoded);
    }

    private String scopeKey(String text, String channel) {
        return key(channel.equals("item") ? text : channel + "-v1|" + text);
    }

    private String scopeKey(String text, String channel, Map<String, Object> context) {
        return context == null || context.isEmpty()
                ? scopeKey(text, channel)
                : key(channel + "-context-v1|" + contextFingerprint(context) + "|" + text);
    }

    String requestIdentity(StyledParagraph paragraph, String channel, Map<String, Object> context) {
        return scopeKey(paragraph.encoded(), channel, context);
    }

    static String contextChannel(String context) {
        return context.startsWith("scope=ui; ") ? "ui" : "item";
    }

    public BundledDictionary.Stats dictionaryStats() {
        return dictionary.stats();
    }

    public int queueSize() {
        return pending.size();
    }

    public CompletableFuture<String> request(String text) {
        return request(text, "unspecified");
    }

    public synchronized CompletableFuture<String> request(String text, String context) {
        return requestInternal(text, context, null, null, "", false);
    }

    public synchronized CompletableFuture<String> request(
            StyledParagraph paragraph, String context) {
        return requestInternal(paragraph.encoded(), context, null, paragraph, "", false);
    }

    public synchronized CompletableFuture<String> request(
            StyledParagraph paragraph, String context, Map<String, Object> dictionaryContext) {
        return requestInternal(
                paragraph.encoded(), context, null, paragraph, "", false, dictionaryContext);
    }

    public synchronized CompletableFuture<String> requestNpc(String text, String speaker) {
        return requestInternal(text, "NPC=" + speaker, speaker, null, "", false);
    }

    public synchronized CompletableFuture<String> requestNpc(
            StyledParagraph paragraph, String speaker) {
        return requestNpc(paragraph, speaker, "");
    }

    public synchronized CompletableFuture<String> requestNpc(
            StyledParagraph paragraph, String speaker, String previous) {
        return requestNpc(paragraph, speaker, previous, Map.of());
    }

    public synchronized CompletableFuture<String> requestNpc(
            StyledParagraph paragraph,
            String speaker,
            String previous,
            Map<String, Object> evidence) {
        var context = new LinkedHashMap<String, Object>(evidence == null ? Map.of() : evidence);
        context.put("previousDialogue", previous == null ? "" : previous);
        return requestInternal(
                paragraph.encoded(),
                "NPC=" + speaker,
                speaker,
                paragraph,
                previous == null ? "" : previous,
                false,
                Map.copyOf(context));
    }

    /** A deliberate hover request may retry a failed/provisional NPC reply before its automatic cooldown. */
    public synchronized CompletableFuture<String> requestNpcManually(
            StyledParagraph paragraph, String speaker, String previous) {
        return requestNpcManually(paragraph, speaker, previous, Map.of());
    }

    public synchronized CompletableFuture<String> requestNpcManually(
            StyledParagraph paragraph,
            String speaker,
            String previous,
            Map<String, Object> evidence) {
        if (apiAvailable())
            temporaryResults.remove(dialogueKey(paragraph.encoded(), speaker, previous, false));
        return requestNpc(paragraph, speaker, previous, evidence);
    }

    private String dialogueKey(String text, String speaker, String previous, boolean chat) {
        return key((chat ? "chat-v2|" : "npc-v2|") + speaker + "|" + previous + "|" + text);
    }

    public synchronized CompletableFuture<String> requestChat(
            StyledParagraph paragraph, String previous) {
        if (!apiAvailable()) return CompletableFuture.completedFuture(null);
        String k = dialogueKey(paragraph.encoded(), "CHAT", previous, true);
        temporaryResults.remove(k);
        return requestInternal(
                paragraph.encoded(), "CHAT=manual", "CHAT", paragraph, previous, true);
    }

    void recordLocalChat(
            net.minecraft.network.chat.Component original,
            net.minecraft.network.chat.Component translated) {
        if (closed) return;
        var p = NpcTranslation.wholeChat(original);
        audit.submit(
                "chat-translations.jsonl",
                Map.of(
                        "speaker",
                        "CHAT",
                        "version",
                        ModVersion.CURRENT,
                        "mode",
                        "local-death-template",
                        "previousDialogue",
                        "",
                        "source",
                        p.decode(p.encoded()).getString(),
                        "translation",
                        translated.getString()),
                config.groqApiKey,
                () -> !closed,
                () -> {});
    }

    private CompletableFuture<String> requestInternal(
            String text,
            String context,
            String speaker,
            StyledParagraph paragraph,
            String previous,
            boolean chat) {
        return requestInternal(
                text,
                context,
                speaker,
                paragraph,
                previous,
                chat,
                speaker != null && !chat ? Map.of("previousDialogue", previous) : Map.of());
    }

    private CompletableFuture<String> requestInternal(
            String text,
            String context,
            String speaker,
            StyledParagraph paragraph,
            String previous,
            boolean chat,
            Map<String, Object> dictionaryContext) {
        if (closed) return CompletableFuture.completedFuture(null);
        // Plain String requests lack the original styled text. Only paragraph-aware calls can
        // safely restore literal values, names and colors from the bundled plain-text corpus.
        String channel = speaker == null ? contextChannel(context) : "npc";
        var decision =
                chat || paragraph == null
                        ? null
                        : inspectDictionary(paragraph, channel, speaker, true, dictionaryContext);
        String local = decision == null ? null : decision.encoded();
        if (local != null) {
            recordDictionary(paragraph, speaker, local, context, decision);
            return CompletableFuture.completedFuture(local);
        }
        if (speaker != null && !chat && paragraph != null && decision != null)
            dictionaryAudit.miss(paragraph, speaker, previous, dictionaryContext, decision);
        String trace =
                !chat && speaker != null && paragraph != null
                        ? DictionaryAuditTrail.traceId(paragraph, speaker, previous)
                        : "";
        // The dictionary remains available while API output (including old cached or
        // provisional output) is disabled. Do not enqueue unknown text in this mode.
        if (!apiAvailable()) {
            dictionaryAudit.status(trace, "POLICY", "API_DISABLED", 0);
            return CompletableFuture.completedFuture(null);
        }
        String k =
                speaker == null
                        ? scopeKey(text, channel, dictionaryContext)
                        : dialogueKey(text, speaker, previous, chat);
        var temporary = temporaryResults.get(k);
        if (temporary != null
                && temporary.kind() == TemporaryKind.FAILED
                && temporary.retryAfter() <= retryClock.getAsLong()) {
            temporaryResults.remove(k);
        }
        String hit = displayValue(k);
        if (hit != null) {
            // Apply safe NPC corrections to old saved responses too, without paying for a
            // full cache invalidation or changing unrelated item/player translations.
            if (speaker != null && !chat) {
                String repaired = NpcTranslationRepair.repair(text, hit, previous);
                if (!Objects.equals(hit, repaired)
                        && StyledParagraph.validNumbers(text, repaired)
                        && NpcTranslation.validate(text, repaired) == null) {
                    if (cache.containsKey(k)) cache.put(k, repaired);
                    else {
                        var priorResult = temporaryResults.get(k);
                        if (priorResult != null)
                            temporaryResults.put(
                                    k,
                                    new TemporaryResult(
                                            repaired,
                                            priorResult.error(),
                                            priorResult.kind(),
                                            priorResult.retryAfter()));
                    }
                    hit = repaired;
                }
            }
            var state = temporaryResults.get(k);
            String cacheOutcome =
                    cache.containsKey(k)
                            ? "CACHE_HIT"
                            : state == null
                                    ? "CACHE_ORIGINAL"
                                    : switch (state.kind()) {
                                        case PROVISIONAL -> "CACHE_PROVISIONAL";
                                        case FAILED -> "CACHE_FAILED_ORIGINAL";
                                        case ORIGINAL -> "CACHE_ORIGINAL";
                                    };
            dictionaryAudit.status(trace, "CACHE", cacheOutcome, 0);
            return CompletableFuture.completedFuture(hit);
        }
        if (text.length() > 4000) {
            dictionaryAudit.status(trace, "POLICY", "INPUT_TOO_LONG", 0);
            diagnostic(k, context, text, null, 0, "INPUT_TOO_LONG: original displayed");
            restoreDictionaryAfterFailure(k);
            temporaryResults.put(k, new TemporaryResult(text, "", TemporaryKind.ORIGINAL, 0));
            return CompletableFuture.completedFuture(text);
        }
        // A completion callback can request again before the worker reaches finally.
        var current = pending.get(k);
        if (current != null && !current.isDone()) return current;
        if (current != null) pending.remove(k, current);
        var future = new CompletableFuture<String>();
        var prior = pending.putIfAbsent(k, future);
        if (prior != null) return prior;
        dictionaryAudit.status(trace, "REQUEST", "QUEUED", 0);
        try {
            worker.execute(
                    () -> {
                        boolean saveAfterRequest = false;
                        try {
                            if (!apiAvailable()) {
                                dictionaryAudit.status(trace, "POLICY", "API_DISABLED", 0);
                                future.complete(null);
                                return;
                            }
                            String retryHint = "";
                            for (int attempt = 0; attempt < 3; attempt++) {
                                String result = null;
                                String responseDetails = "";
                                try {
                                    if (!apiAvailable()) {
                                        dictionaryAudit.status(trace, "POLICY", "API_DISABLED", 0);
                                        future.complete(null);
                                        return;
                                    }
                                    long delay = nextRequestAt - System.currentTimeMillis();
                                    if (delay > 0) Thread.sleep(delay);
                                    if (!apiAvailable()) {
                                        dictionaryAudit.status(trace, "POLICY", "API_DISABLED", 0);
                                        future.complete(null);
                                        return;
                                    }
                                    nextRequestAt =
                                            System.currentTimeMillis()
                                                    + (config.provider.equals("GROQ") ? 2200 : 350);
                                    dictionaryAudit.status(
                                            trace, "REQUEST", "RUNNING", attempt + 1);
                                    String apiText = chat ? ChatText.body(text) : text;
                                    boolean symbolic = text.matches("#[A-Z0-9_]+[!]?");
                                    if (symbolic || chat && ChatText.coordinatesOnly(text))
                                        result = text;
                                    else if (transport != null)
                                        result = transport.translate(apiText);
                                    else {
                                        var response =
                                                provider.translate(
                                                        apiText,
                                                        new TranslationProvider.Context(
                                                                speaker, previous, chat, retryHint),
                                                        this::apiAvailable);
                                        result = response.text();
                                        responseDetails = response.details();
                                    }
                                    // close() shares this publication boundary. A transport that
                                    // ignores
                                    // interruption must not publish or save its response after
                                    // replacement.
                                    synchronized (Translator.this) {
                                        if (!apiAvailable()) {
                                            future.complete(null);
                                            return;
                                        }
                                        result = ChatText.unwrap(result);
                                        if (chat) result = ChatText.rejoin(text, result);
                                        String rawResult = result;
                                        String repairCandidate =
                                                TranslationRepair.candidate(text, result);
                                        if (speaker != null && !chat)
                                            repairCandidate =
                                                    TranslationRepair.candidate(
                                                            text,
                                                            NpcTranslationRepair.candidate(
                                                                    text,
                                                                    repairCandidate,
                                                                    previous));
                                        String candidateError =
                                                validateResult(
                                                        text, repairCandidate, speaker, chat);
                                        boolean rejectedRepair =
                                                !Objects.equals(rawResult, repairCandidate)
                                                        && (TranslationRepair.worsensStructure(
                                                                        text,
                                                                        rawResult,
                                                                        repairCandidate)
                                                                || validateResult(
                                                                                        text,
                                                                                        rawResult,
                                                                                        speaker,
                                                                                        chat)
                                                                                == null
                                                                        && candidateError != null);
                                        result = rejectedRepair ? rawResult : repairCandidate;
                                        if (!Objects.equals(rawResult, repairCandidate)) {
                                            audit.submit(
                                                    "translation-repairs.jsonl",
                                                    Map.of(
                                                            "version",
                                                            ModVersion.CURRENT,
                                                            "requestId",
                                                            k.substring(0, 12),
                                                            "context",
                                                            context,
                                                            "source",
                                                            text,
                                                            "rawResponse",
                                                            rawResult == null ? "" : rawResult,
                                                            "candidateResponse",
                                                            repairCandidate == null
                                                                    ? ""
                                                                    : repairCandidate,
                                                            "repairedResponse",
                                                            result == null ? "" : result,
                                                            "decision",
                                                            rejectedRepair
                                                                    ? "REJECTED_REPAIR_KEPT_ORIGINAL"
                                                                    : "REPAIR_APPLIED",
                                                            "candidateValidation",
                                                            candidateError == null
                                                                    ? ""
                                                                    : candidateError),
                                                    config.groqApiKey,
                                                    () -> !closed,
                                                    () -> {});
                                        }
                                        String validationError =
                                                validateResult(text, result, speaker, chat);
                                        if ((symbolic || chat && ChatText.coordinatesOnly(text))
                                                && text.equals(result)) validationError = null;
                                        if (validationError != null) {
                                            diagnostic(
                                                    k,
                                                    context,
                                                    text,
                                                    result,
                                                    attempt + 1,
                                                    "DISPLAY_ANYWAY: " + validationError,
                                                    responseDetails);
                                            if (result == null
                                                    || result.isBlank()
                                                    || validationError.startsWith(
                                                            "NPC_META_RESPONSE")
                                                    || validationError.startsWith(
                                                            "CHAT_JSON_WRAPPER")) {
                                                result = text;
                                                restoreDictionaryAfterFailure(k);
                                            }
                                        }
                                        if (validationError == null) {
                                            cache.put(k, result.strip());
                                            temporaryResults.remove(k);
                                        } else {
                                            cache.remove(k);
                                            temporaryResults.put(
                                                    k,
                                                    new TemporaryResult(
                                                            result.strip(),
                                                            "",
                                                            TemporaryKind.PROVISIONAL,
                                                            0));
                                        }
                                        lastError = "";
                                        if (speaker != null) {
                                            audit.submit(
                                                    chat
                                                            ? "chat-translations.jsonl"
                                                            : "npc-translations.jsonl",
                                                    Map.of(
                                                            "speaker",
                                                            speaker,
                                                            "version",
                                                            ModVersion.CURRENT,
                                                            "previousDialogue",
                                                            previous,
                                                            "source",
                                                            paragraph == null
                                                                    ? text
                                                                    : paragraph
                                                                            .decode(text)
                                                                            .getString(),
                                                            "translation",
                                                            paragraph == null
                                                                    ? result
                                                                    : paragraph
                                                                            .decodeForDisplay(
                                                                                    result)
                                                                            .getString(),
                                                            "encodedSource",
                                                            text,
                                                            "encodedTranslation",
                                                            result,
                                                            "provisional",
                                                            validationError != null,
                                                            "validationReason",
                                                            validationError == null
                                                                    ? ""
                                                                    : validationError),
                                                    config.groqApiKey,
                                                    () -> !closed,
                                                    () -> {});
                                        }
                                        dictionaryAudit.status(
                                                trace,
                                                "RESULT",
                                                validationError != null
                                                        ? "PROVISIONAL"
                                                        : text.equals(result)
                                                                ? "UNCHANGED"
                                                                : "TRANSLATED",
                                                attempt + 1);
                                        future.complete(result.strip());
                                        saveAfterRequest = true;
                                        return;
                                    }
                                } catch (TranslationProvider.Failure e) {
                                    if (!apiAvailable()) {
                                        future.complete(null);
                                        return;
                                    }
                                    diagnostic(
                                            k,
                                            context,
                                            text,
                                            result,
                                            attempt + 1,
                                            e.getMessage(),
                                            e.details);
                                    retryHint =
                                            chat
                                                    ? " Your last translation was invalid. Translate all ordinary chat text including u=you, lf=looking for, p me=invite me. Never copy English u after the addressee. Return only Japanese translation with all immutable markers intact, without JSON fields."
                                                    : speaker == null
                                                            ? " Previous attempt failed validation: "
                                                                    + e.getMessage()
                                                                    + ". Correct this failure; include every required marker."
                                                            : " Translate the dialogue itself. Your previous response was invalid. Return only Japanese dialogue and preserve all markers, with no service messages or diagnostic codes.";
                                    if (!e.retryable || attempt == 2) throw e;
                                    Thread.sleep(
                                            e.getMessage().equals("HTTP 429")
                                                    ? 15000L * (attempt + 1)
                                                    : 1500L * (attempt + 1));
                                } catch (Exception e) {
                                    if (!apiAvailable()) {
                                        future.complete(null);
                                        return;
                                    }
                                    diagnostic(
                                            k,
                                            context,
                                            text,
                                            result,
                                            attempt + 1,
                                            e.getClass().getSimpleName(),
                                            responseDetails);
                                    throw e;
                                }
                            }
                        } catch (Exception e) {
                            synchronized (Translator.this) {
                                if (!apiAvailable()) {
                                    future.complete(null);
                                    return;
                                }
                                // A failed refresh must not hide an otherwise usable local
                                // translation.
                                restoreDictionaryAfterFailure(k);
                                lastError = describeError(e);
                                temporaryResults.put(
                                        k,
                                        new TemporaryResult(
                                                text,
                                                lastError,
                                                TemporaryKind.FAILED,
                                                retryClock.getAsLong() + 60000));
                                dictionaryAudit.status(trace, "RESULT", "FAILED", 0);
                                future.complete(text);
                                if (e instanceof InterruptedException)
                                    Thread.currentThread().interrupt();
                            }
                        } finally {
                            pending.remove(k, future);
                            if (saveAfterRequest && apiAvailable()) cache.saveIfDue();
                        }
                    });
        } catch (RejectedExecutionException e) {
            dictionaryAudit.status(trace, "REQUEST", "QUEUE_FULL", 0);
            pending.remove(k, future);
            restoreDictionaryAfterFailure(k);
            temporaryResults.put(
                    k,
                    new TemporaryResult(
                            text,
                            "翻訳待ちが満杯です。少し待って再試行してください",
                            TemporaryKind.FAILED,
                            retryClock.getAsLong() + 3000));
            diagnostic(k, context, text, null, 0, "QUEUE_FULL: original displayed");
            future.complete(text);
        }
        return future;
    }

    private static String validateResult(String text, String result, String speaker, boolean chat) {
        return speaker == null
                ? TranslationDiagnostics.validate(text, result)
                : chat ? ChatText.validate(text, result) : NpcTranslation.validate(text, result);
    }

    private void restoreDictionaryAfterFailure(String key) {
        externalOverrides.remove(key);
        var tokens = overridesByRequest.remove(key);
        if (tokens != null) paragraphOverrides.removeAll(tokens);
    }

    private String localTranslation(StyledParagraph paragraph, String speaker) {
        return inspectDictionary(paragraph, speaker == null ? "item" : "npc", speaker).encoded();
    }

    void recordDictionary(
            StyledParagraph paragraph,
            String speaker,
            String encoded,
            String context,
            DictionaryDecision decision) {
        dictionaryAudit.record(paragraph, speaker, encoded, context, decision);
    }

    private void diagnostic(
            String id, String context, String source, String response, int attempt, String reason) {
        diagnostic(id, context, source, response, attempt, reason, "");
    }

    private void diagnostic(
            String id,
            String context,
            String source,
            String response,
            int attempt,
            String reason,
            String responseDetails) {
        if (!apiAvailable()) return;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("requestId", id.substring(0, 12));
        record.put("attempt", attempt);
        record.put("provider", config.provider);
        record.put("model", config.groqModel);
        record.put("context", context);
        record.put("reason", reason);
        record.put("source", source);
        record.put("response", response);
        record.put("apiDetails", responseDetails);
        audit.submit(
                "translation-failures.jsonl", record, config.groqApiKey, () -> !closed, () -> {});
    }

    /** FIFO barrier for diagnostics; translation completion deliberately does not imply durable logs. */
    CompletableFuture<Void> awaitDiagnostics() {
        return audit.barrier();
    }

    ThreadPoolExecutor diagnosticExecutor() {
        return audit.executor();
    }

    public void close() {
        boolean owner;
        List<CompletableFuture<String>> cancelled;
        synchronized (this) {
            owner = !closed;
            if (owner) {
                closed = true;
                closingThread = Thread.currentThread();
                worker.shutdownNow();
                cancelled = List.copyOf(pending.values());
                pending.clear();
            } else cancelled = List.of();
        }
        if (!owner) {
            if (Thread.currentThread() != closingThread) termination.join();
            return;
        }
        try {
            cancelled.forEach(future -> future.complete(null));
            provider.close();
            audit.close();
            cache.close();
        } finally {
            termination.complete(null);
        }
    }

    private String describeError(Exception e) {
        if (!(e instanceof TranslationProvider.Failure)) return "通信または応答の処理に失敗しました";
        if (e.getMessage().startsWith("MARKER_")) return "色・数値のマーカーが壊れました。詳細ログに記録済み";
        if (e.getMessage().startsWith("NUMBER_")) return "数値が変化しました。詳細ログに記録済み";
        if (e.getMessage().startsWith("NO_JAPANESE")) return "日本語に翻訳されませんでした。詳細ログに記録済み";
        if (e.getMessage().startsWith("UNTRANSLATED_PROSE")
                || e.getMessage().startsWith("NPC_META_RESPONSE"))
            return "訳文の内容が不正でした。原文を保持しました。詳細ログに記録済み";
        if (e.getMessage().startsWith("CONTENT_MISSING")) return "説明の条件・操作が訳文から抜けています。詳細ログに記録済み";
        if (e.getMessage().startsWith("EMPTY_RESPONSE")) return "APIの訳文が空でした。詳細ログに記録済み";
        return switch (e.getMessage()) {
            case "HTTP 404" -> "モデルが見つかりません。設定でモデルを変更してください";
            case "HTTP 401", "HTTP 403", "missing Groq key" -> "APIキーまたは利用権限を確認してください";
            case "HTTP 429" -> "APIの利用上限です。時間を空けて再試行してください";
            case "invalid translation" -> "数値または日本語の検証に失敗したため原文を保持しました";
            default -> "翻訳に失敗しました: " + e.getMessage();
        };
    }
}
