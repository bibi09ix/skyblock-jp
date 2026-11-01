package local.skyblockjp;

import com.google.gson.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Reviewed, source-bound semantic alignments. Color values always come from the live Component. */
public final class DictionaryAlignment {
    public static final String RESOURCE = "/assets/skyblock_jp/dictionary/alignment.json";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Set<String> CHANNELS = Set.of("item", "npc", "ui", "system", "common");

    public record Span(
            String id,
            String kind,
            int sourceStart,
            int sourceEnd,
            int targetStart,
            int targetEnd,
            String sourceText,
            String targetText,
            boolean confirmedOrdinary,
            boolean allowPlain) {}

    public record BodyAnchor(int sourceStart, int sourceEnd, String sourceText, String role) {}

    public record Plan(
            String id,
            String channel,
            String source,
            String target,
            String sourceSha256,
            String targetSha256,
            boolean reviewed,
            List<String> sourceIds,
            List<Span> spans,
            BodyAnchor bodyAnchor) {
        public Plan(
                String id,
                String channel,
                String source,
                String target,
                String sourceSha256,
                String targetSha256,
                boolean reviewed,
                List<String> sourceIds,
                List<Span> spans) {
            this(
                    id,
                    channel,
                    source,
                    target,
                    sourceSha256,
                    targetSha256,
                    reviewed,
                    sourceIds,
                    spans,
                    null);
        }

        public Plan {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
            spans = spans == null ? List.of() : List.copyOf(spans);
        }
    }

    private record Key(String channel, String source, String target) {}

    public record Stats(boolean loaded, int readPlans, int plans, int rejected, String error) {}

    private record Index(Map<Key, Plan> plans, Map<String, BodyAnchor> bodies, Stats stats) {}

    private static final class Holder {
        private static final Index INDEX =
                loadIndex(DictionaryAlignment.class.getResourceAsStream(RESOURCE));
    }

    private DictionaryAlignment() {}

    public static Plan lookup(
            String channel, String normalizedSource, String target, List<String> sourceIds) {
        Plan plan = Holder.INDEX.plans().get(new Key(channel, normalizedSource, target));
        if (plan == null
                || !plan.sourceIds().isEmpty() && Collections.disjoint(plan.sourceIds(), sourceIds))
            return null;
        return plan;
    }

    public static Stats stats() {
        return Holder.INDEX.stats();
    }

    static List<Plan> plans() {
        return List.copyOf(Holder.INDEX.plans().values());
    }

    /** Source-wide body roles are usable only when every reviewed plan agrees. */
    static BodyAnchor bodyForSource(String source) {
        return Holder.INDEX.bodies().get(BundledDictionary.normalize(source));
    }

    static Map<Key, Plan> load(InputStream input) {
        return loadIndex(input).plans();
    }

    private static Index failed(String reason) {
        return new Index(Map.of(), Map.of(), new Stats(false, 0, 0, 0, reason));
    }

    private static Index loadIndex(InputStream input) {
        if (input == null) return failed("RESOURCE_MISSING");
        try (input) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) return failed("RESOURCE_TOO_LARGE");
            var root =
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (root.get("schema_version").getAsInt() != 1
                    || !root.get("normalization").getAsString().equals("bundled-source-v1"))
                return failed("SCHEMA_MISMATCH");
            var values = root.getAsJsonArray("plans");
            if (values.size() > 4000) return failed("PLAN_LIMIT");
            var result = new HashMap<Key, Plan>();
            var conflicts = new HashSet<Key>();
            var rejectedBodySources = new HashSet<String>();
            var gson = new Gson();
            for (var value : values) {
                Plan plan = gson.fromJson(value, Plan.class);
                if (!validate(plan, plan.source(), plan.target()).isEmpty()) {
                    if (plan.source() != null)
                        rejectedBodySources.add(BundledDictionary.normalize(plan.source()));
                    continue;
                }
                Key key = new Key(plan.channel(), plan.source(), plan.target());
                if (result.putIfAbsent(key, plan) != null) conflicts.add(key);
            }
            conflicts.forEach(
                    key -> {
                        result.remove(key);
                        rejectedBodySources.add(key.source());
                    });
            var bodies = new HashMap<String, BodyAnchor>();
            var grouped = new HashMap<String, List<Plan>>();
            result.values()
                    .forEach(
                            plan ->
                                    grouped.computeIfAbsent(
                                                    plan.source(), ignored -> new ArrayList<>())
                                            .add(plan));
            grouped.forEach(
                    (source, plans) -> {
                        if (rejectedBodySources.contains(source)) return;
                        var anchor = agreedBody(source, plans);
                        if (anchor != null) bodies.put(source, anchor);
                    });
            return new Index(
                    Map.copyOf(result),
                    Map.copyOf(bodies),
                    new Stats(
                            true, values.size(), result.size(), values.size() - result.size(), ""));
        } catch (Exception ignored) {
            return failed("MALFORMED_RESOURCE");
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String validate(Plan plan, String liveSource, String target) {
        if (plan == null
                || plan.id() == null
                || plan.id().isBlank()
                || plan.id().length() > 160
                || !plan.reviewed()
                || plan.channel() == null
                || !CHANNELS.contains(plan.channel())
                || plan.source() == null
                || plan.target() == null
                || plan.source().length() > 16000
                || plan.target().length() > 16000
                || plan.spans().isEmpty() && plan.bodyAnchor() == null
                || plan.spans().size() > 128) return "INVALID_ALIGNMENT_PLAN";
        if (!sha256(plan.source()).equals(plan.sourceSha256())
                || !sha256(plan.target()).equals(plan.targetSha256()))
            return "ALIGNMENT_HASH_MISMATCH";
        if (!BundledDictionary.normalize(liveSource).equals(plan.source())
                || !target.equals(plan.target())) return "STALE_ALIGNMENT_PLAN";
        if (plan.bodyAnchor() != null && !validBody(plan.source(), plan.bodyAnchor()))
            return "INVALID_BODY_ALIGNMENT";
        var ids = new HashSet<String>();
        var sourceRanges = new ArrayList<Span>();
        var targetRanges = new ArrayList<Span>();
        for (Span span : plan.spans()) {
            if (span == null
                    || span.id() == null
                    || span.id().isBlank()
                    || !ids.add(span.id())
                    || span.kind() == null
                    || !Set.of("ordinary", "name", "value").contains(span.kind())
                    || !slice(
                            plan.source(), span.sourceStart(), span.sourceEnd(), span.sourceText()))
                return "INVALID_ALIGNMENT_SPAN";
            if (sourceRanges.stream()
                    .anyMatch(
                            s ->
                                    overlap(
                                            s.sourceStart(),
                                            s.sourceEnd(),
                                            span.sourceStart(),
                                            span.sourceEnd())))
                return "OVERLAPPING_ALIGNMENT_SOURCE";
            sourceRanges.add(span);
            boolean plainOnly =
                    span.targetStart() == -1
                            && span.targetEnd() == -1
                            && "".equals(span.targetText());
            if (!plainOnly
                    && !slice(target, span.targetStart(), span.targetEnd(), span.targetText()))
                return "INVALID_ALIGNMENT_TARGET";
            if (!plainOnly
                    && targetRanges.stream()
                            .anyMatch(
                                    s ->
                                            overlap(
                                                    s.targetStart(),
                                                    s.targetEnd(),
                                                    span.targetStart(),
                                                    span.targetEnd())))
                return "OVERLAPPING_ALIGNMENT_TARGET";
            if (!plainOnly) targetRanges.add(span);
            if (span.kind().equals("ordinary")) {
                if (!span.confirmedOrdinary()
                        || (plainOnly && !span.allowPlain())
                        || !ordinary(span.sourceText())) return "UNSAFE_ORDINARY_ALIGNMENT";
                if (!plainOnly
                        && (StyledParagraph.NUMBER.matcher(span.targetText()).find()
                                || Pattern.compile("[A-Za-z\\p{Co}]")
                                        .matcher(span.targetText())
                                        .find())) return "UNSAFE_ORDINARY_TARGET";
            } else {
                if (span.confirmedOrdinary() || span.allowPlain() || plainOnly)
                    return "UNSAFE_PROTECTED_ALIGNMENT";
                if (span.kind().equals("value") && !span.sourceText().equals(span.targetText()))
                    return "ALIGNMENT_VALUE_CHANGED";
                if (span.kind().equals("name")
                        && !ProtectedTerms.dictionaryEnglishAliases(span.sourceText())
                                .contains(span.targetText())
                        && !(span.sourceText().matches("[A-Z][A-Za-z' -]+[.!]")
                                && ProtectedTerms.dictionaryEnglishAliases(
                                                span.sourceText()
                                                        .substring(
                                                                0, span.sourceText().length() - 1))
                                        .contains(span.targetText())))
                    return "ALIGNMENT_NAME_CHANGED";
            }
        }
        return "";
    }

    static BodyAnchor agreedBody(String source, List<Plan> plans) {
        if (plans.isEmpty()) return null;
        BodyAnchor agreed = null;
        for (var plan : plans) {
            if (plan == null
                    || !validate(plan, source, plan.target()).isEmpty()
                    || plan.bodyAnchor() == null) return null;
            if (agreed == null) agreed = plan.bodyAnchor();
            else if (!agreed.equals(plan.bodyAnchor())) return null;
        }
        return agreed;
    }

    private static boolean validBody(String source, BodyAnchor body) {
        if (!"prose".equals(body.role())
                || !slice(source, body.sourceStart(), body.sourceEnd(), body.sourceText())
                || !body.sourceText().equals(body.sourceText().strip())) return false;
        String text = body.sourceText();
        var starter =
                Pattern.compile("^(?:For|And|You|Your|Here|This|That|It|We|I|A|An)(?= )")
                        .matcher(text);
        if (starter.find())
            text = text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
        return text.codePoints().filter(Character::isLetter).count() >= 3 && ordinary(text);
    }

    /** Unknown title-cased names and display atoms never become an automatic plain-text escape hatch. */
    private static boolean ordinary(String text) {
        if (text == null
                || text.isBlank()
                || StyledParagraph.NUMBER.matcher(text).find()
                || ProtectedTerms.forText(text).matcher(text).find()
                || Pattern.compile("[\\p{Co}\\p{Sm}\\p{Sc}\\p{Sk}\\p{So}α⫽᠅‣•●➤■⸕⸎]")
                        .matcher(text)
                        .find()
                || unreviewedCapital(text)) return false;
        return true;
    }

    private static boolean unreviewedCapital(String text) {
        var capitals = Pattern.compile("[A-Z][A-Za-z'-]*").matcher(text);
        while (capitals.find()) {
            String word = capitals.group();
            if (Set.of("Max", "Maximum").contains(word) && text.equals(word)) continue;
            // An exact reviewed whole-sentence span may begin with the article
            // The. It does not authorize capitalized names later in the span.
            // The surrounding plan still binds source, target, hashes and IDs.
            if (capitals.start() == 0 && word.equals("The") && text.startsWith("The ")) continue;
            return true;
        }
        return false;
    }

    private static boolean slice(String text, int start, int end, String expected) {
        return expected != null
                && start >= 0
                && end > start
                && end <= text.length()
                && !Character.isLowSurrogate(text.charAt(start))
                && (end == text.length() || !Character.isLowSurrogate(text.charAt(end)))
                && text.substring(start, end).equals(expected);
    }

    private static boolean overlap(int a, int b, int c, int d) {
        return a < d && b > c;
    }

    static List<BundledDictionary.Binding> bindings(Plan plan) {
        return plan.spans().stream()
                .filter(s -> !s.kind().equals("ordinary") && s.sourceText().equals(s.targetText()))
                .map(
                        s ->
                                new BundledDictionary.Binding(
                                        s.id(),
                                        s.kind(),
                                        s.sourceStart(),
                                        s.sourceEnd(),
                                        s.targetStart(),
                                        s.targetEnd(),
                                        s.sourceText(),
                                        s.targetText()))
                .toList();
    }

    static List<Span> forStyle(Plan plan, int start, int end) {
        if (plan == null) return List.of();
        var spans =
                plan.spans().stream()
                        .filter(s -> s.sourceStart() >= start && s.sourceEnd() <= end)
                        .sorted(Comparator.comparingInt(Span::sourceStart))
                        .toList();
        if (spans.isEmpty()
                || spans.getFirst().sourceStart() != start
                || spans.getLast().sourceEnd() != end) return List.of();
        for (int i = 1; i < spans.size(); i++)
            if (!plan.source()
                    .substring(spans.get(i - 1).sourceEnd(), spans.get(i).sourceStart())
                    .isBlank()) return List.of();
        if (spans.size() > 1) {
            if (spans.stream().anyMatch(s -> s.targetStart() < 0)) return List.of();
            var target = spans.stream().sorted(Comparator.comparingInt(Span::targetStart)).toList();
            for (int i = 1; i < target.size(); i++)
                if (!plan.target()
                        .substring(target.get(i - 1).targetEnd(), target.get(i).targetStart())
                        .isBlank()) return List.of();
        }
        return spans;
    }
}
