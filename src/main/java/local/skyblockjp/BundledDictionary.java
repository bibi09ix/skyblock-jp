package local.skyblockjp;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Context-scoped literal matches plus a separately validated bounded matching sidecar. */
public final class BundledDictionary {
    public static final String RESOURCE = "/assets/skyblock_jp/dictionary/candidate.json";
    private static final Set<String> CHANNELS = Set.of("item", "npc", "ui", "system", "common");
    private static final Pattern FORMAT = Pattern.compile("§[0-9a-fk-or]", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?|\\.[0-9]+)(?:[kKmMbBtT](?![A-Za-z]))?(?:%|‰)?");
    private static final Pattern MULTIPLIER_WORD = Pattern.compile("(?i)(?<![A-Za-z0-9_-])(?:double|doubles|doubled|twice|triple|triples|tripled|thrice|quadruple|quadruples|quadrupled)(?![A-Za-z0-9_-])");
    private static final Pattern ENTITY = Pattern.compile("&(?:[A-Za-z][A-Za-z0-9]*|#[0-9]+|#x[0-9a-fA-F]+);");
    private static final Pattern SCRIPT = Pattern.compile("(?i)(?:javascript\\s*:|(?:document|window)\\s*\\.[A-Za-z_$]|"
            + "(?:document|window)\\s*\\.\\s*[A-Za-z_$][A-Za-z0-9_$]*\\s*[=(\\[]|function\\s*\\(|=>)");
    private static final Limits DEFAULT_LIMITS = new Limits(64 * 1024 * 1024, 150_000, 16_000, 150_000, 512);

    private final Map<Key, Match> entries;
    private final Set<Key> ambiguous;
    private final Set<Key> normalizedConflicts;
    private final Stats stats;
    private final DictionaryMatching matching;
    private final Map<String,String> reviewEvidence;
    private final ReviewedDictionary reviewed;
    private final NpcDictionaryTemplates npcTemplates;
    private final DictionaryGlyphFallback glyphFallback;
    private record LookupKey(Key key,Map<String,Object> context) {}
    private final Map<LookupKey,Lookup> lookupCache=Collections.synchronizedMap(new LinkedHashMap<>(256,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<LookupKey,Lookup> eldest){return size()>2048;}
    });

    private BundledDictionary(Map<Key, Match> entries, Set<Key> ambiguous, Stats stats) {
        this(entries,ambiguous,stats,DictionaryMatching.empty("not_loaded"));
    }
    private BundledDictionary(Map<Key, Match> entries, Set<Key> ambiguous, Stats stats,DictionaryMatching matching) {
        this(entries,ambiguous,stats,matching,Map.of(),ReviewedDictionary.empty("not_loaded"));
    }
    private BundledDictionary(Map<Key, Match> entries, Set<Key> ambiguous, Stats stats,DictionaryMatching matching,
            Map<String,String> reviewEvidence,ReviewedDictionary reviewed) {
        this.entries = Map.copyOf(entries);
        this.ambiguous = Set.copyOf(ambiguous);
        this.normalizedConflicts = ambiguous.stream().map(key -> new Key(key.channel, key.speaker,
                DictionaryMatching.aliasKey(key.source))).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.stats = stats;
        this.matching=matching;
        this.reviewEvidence=Map.copyOf(reviewEvidence);
        this.reviewed=reviewed;
        var seeds=new ArrayList<DictionaryMatching.Seed>();
        entries.forEach((key,value)->seeds.add(new DictionaryMatching.Seed(key.channel,key.speaker,key.source,value)));
        ambiguous.forEach(key->seeds.add(new DictionaryMatching.Seed(key.channel,key.speaker,key.source,null)));
        this.npcTemplates=NpcDictionaryTemplates.create(seeds);
        this.glyphFallback=DictionaryGlyphFallback.create(seeds);
    }

    /** UTF-16 offsets in the normalized live source and completed target, never template offsets. */
    public record Binding(String name, String type, int sourceStart, int sourceEnd,
                          int targetStart, int targetEnd, String sourceText, String targetText) { }
    public record Match(String text, boolean keepOriginal, List<String> sourceIds, List<Binding> bindings, DictionaryAlignment.Plan alignment) {
        public Match(String text, boolean keepOriginal, List<String> sourceIds) { this(text, keepOriginal, sourceIds, List.of(), null); }
        public Match(String text, boolean keepOriginal, List<String> sourceIds,List<Binding> bindings) { this(text,keepOriginal,sourceIds,bindings,null); }
        public Match { sourceIds = List.copyOf(sourceIds); bindings = List.copyOf(bindings); }
    }
    public record Lookup(Optional<Match> match,String reason,String mode) {}
    public record MatchingStats(boolean loaded,int normalizedKeys,int ambiguousNormalizedKeys,int explicitAliases,int templates,String error) {}

    /** Reasons are bounded category names, never fragments of resource text. */
    public record Stats(boolean loaded, String buildId, int readEntries, int acceptedEntries,
                        int rejectedEntries, int conflictingKeys, int availableKeys,
                        Map<String, Integer> rejectionReasons, String error) {
        public Stats { rejectionReasons = Map.copyOf(rejectionReasons); }
    }

    // Smaller limits let tests exercise the same streaming boundaries without huge fixtures.
    record Limits(int resourceBytes, int entries, int textChars, int sourceIds, int sourceIdChars) {
        Limits {
            if (resourceBytes < 1 || entries < 1 || textChars < 1 || sourceIds < 1 || sourceIdChars < 1)
                throw new IllegalArgumentException("Dictionary limits must be positive");
        }
    }

    private record Key(String channel, String speaker, String source) { }
    private record Entry(Key key, String target, boolean keepOriginal, List<String> sourceIds) { }

    public static BundledDictionary empty() { return failed("", "not_loaded", 0); }

    /** Missing or invalid resources return an empty dictionary; they never prevent startup. */
    public static BundledDictionary loadBundled() {
        return load(BundledDictionary.class.getResourceAsStream(RESOURCE))
            .withMatching(BundledDictionary.class.getResourceAsStream(DictionaryMatching.RESOURCE))
            .withReviewed(BundledDictionary.class.getResourceAsStream(ReviewedDictionary.RESOURCE));
    }
    /** Sidecar failure keeps the strict literal dictionary available. */
    public BundledDictionary withMatching(InputStream input) {
        List<DictionaryMatching.Seed> seeds=new ArrayList<>();
        entries.forEach((key,value)->seeds.add(new DictionaryMatching.Seed(key.channel,key.speaker,key.source,value)));
        ambiguous.forEach(key->seeds.add(new DictionaryMatching.Seed(key.channel,key.speaker,key.source,null)));
        return new BundledDictionary(entries,ambiguous,stats,DictionaryMatching.load(input,stats.buildId(),seeds),reviewEvidence,reviewed);
    }
    public BundledDictionary withReviewed(InputStream input) {
        return new BundledDictionary(entries,ambiguous,stats,matching,reviewEvidence,ReviewedDictionary.load(input,stats.buildId(),reviewEvidence));
    }
    public record ReviewedStats(boolean loaded,int rules,int rejectedRules,String error) {}
    public ReviewedStats reviewedStats() {var result=reviewed.stats();return new ReviewedStats(result.loaded(),result.rules(),result.rejectedRules(),result.error());}
    public MatchingStats matchingStats(){var s=matching.stats();return new MatchingStats(s.loaded(),s.normalizedKeys(),s.ambiguousNormalizedKeys(),s.explicitAliases(),s.templates(),s.error());}

    /** Consumes and closes the stream. No partial dictionary is exposed on document errors. */
    public static BundledDictionary load(InputStream input) { return load(input, DEFAULT_LIMITS); }

    static BundledDictionary load(InputStream input, Limits limits) {
        if (input == null) return failed("", "resource_missing", 0);
        String buildId = "";
        int read = 0, accepted = 0, rejected = 0;
        Map<String, Integer> reasons = new TreeMap<>();
        Map<Key, Accumulator> pending = new HashMap<>();
        Set<Key> conflicts = new HashSet<>();
        Map<String,String> reviewEvidence = new HashMap<>();
        try (InputStream bounded = new LimitedInputStream(input, limits.resourceBytes());
             JsonReader json = new JsonReader(new InputStreamReader(bounded, StandardCharsets.UTF_8.newDecoder()
                     .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            json.setStrictness(Strictness.STRICT);
            require(json.peek() == JsonToken.BEGIN_OBJECT, "root_object_required");
            json.beginObject();
            Set<String> fields = new HashSet<>();
            int schema = -1;
            String tier = "";
            while (json.hasNext()) {
                String field = json.nextName();
                require(fields.add(field), "duplicate_root_field");
                switch (field) {
                    case "schema_version" -> {
                        require(json.peek() == JsonToken.NUMBER, "invalid_schema");
                        String value = json.nextString();
                        require(value.equals("1"), "unsupported_schema");
                        schema = 1;
                    }
                    case "build_id" -> {
                        buildId = string(json, 128);
                        require(!buildId.isBlank() && safeIdentifier(buildId), "invalid_build_id");
                    }
                    case "tier" -> tier = string(json, 32);
                    case "entries" -> {
                        require(json.peek() == JsonToken.BEGIN_ARRAY, "entries_array_required");
                        json.beginArray();
                        while (json.hasNext()) {
                            require(++read <= limits.entries(), "entry_limit");
                            Entry entry;
                            try {
                                entry = readEntry(json, limits);
                            } catch (InvalidEntry invalid) {
                                rejected++;
                                reasons.merge(invalid.reason, 1, Integer::sum);
                                continue;
                            }
                            accepted++;
                            Key key = entry.key();
                            if (conflicts.contains(key)) continue;
                            Accumulator existing = pending.get(key);
                            if (existing == null) pending.put(key, new Accumulator(entry));
                            else if (existing.target.equals(entry.target()) && existing.keepOriginal == entry.keepOriginal())
                                existing.sourceIds.addAll(entry.sourceIds());
                            else {
                                pending.remove(key);
                                conflicts.add(key);
                            }
                        }
                        json.endArray();
                    }
                    case "blocked_keys" -> {
                        require(json.peek() == JsonToken.BEGIN_ARRAY, "blocked_keys_array_required");
                        json.beginArray();
                        int blocked = 0;
                        while (json.hasNext()) {
                            require(++blocked <= limits.entries(), "blocked_key_limit");
                            Key key = readBlockedKey(json, limits);
                            conflicts.add(key);
                            pending.remove(key);
                        }
                        json.endArray();
                    }
                    case "review_evidence" -> {
                        require(json.peek()==JsonToken.BEGIN_ARRAY,"review_evidence_array_required");json.beginArray();int count=0;
                        while(json.hasNext()) {
                            require(++count<=limits.entries(),"review_evidence_limit");
                            var value=readReviewEvidence(json,limits);
                            require(reviewEvidence.putIfAbsent(value.getKey(),value.getValue())==null,"duplicate_review_evidence");
                        }
                        json.endArray();
                    }
                    default -> throw new InvalidDocument("unknown_root_field");
                }
            }
            json.endObject();
            require(json.peek() == JsonToken.END_DOCUMENT, "trailing_content");
            require(fields.containsAll(Set.of("schema_version", "build_id", "tier", "entries")) && schema == 1,
                    "missing_root_field");
            require(tier.equals("candidate"), "unsupported_tier");
            Map<Key, Match> complete = new HashMap<>();
            pending.forEach((key, value) -> complete.put(key,
                    new Match(value.target, value.keepOriginal, List.copyOf(value.sourceIds))));
            return new BundledDictionary(complete, conflicts,
                    new Stats(true, buildId, read, accepted, rejected, conflicts.size(), complete.size(), reasons, ""),
                    DictionaryMatching.empty("not_loaded"),reviewEvidence,ReviewedDictionary.empty("not_loaded"));
        } catch (InvalidDocument invalid) {
            return failed(buildId, invalid.reason, read);
        } catch (ResourceLimitException invalid) {
            return failed(buildId, "resource_byte_limit", read);
        } catch (IOException | RuntimeException invalid) {
            // Avoid including arbitrary dictionary content or unbounded parser messages in logs/UI.
            return failed(buildId, "resource_invalid_" + invalid.getClass().getSimpleName(), read);
        }
    }

    /**
     * Item/UI/system queries may also use common entries, provided both contexts agree.
     * NPC queries require the exact speaker and never consult common or another NPC.
     * Player/chat and unknown channels always miss.
     */
    public Optional<Match> lookup(String channel, String source, String speaker) {
        return inspect(channel,source,speaker).match();
    }
    public Lookup inspect(String channel,String source,String speaker) {
        return inspect(channel,source,speaker,Map.of());
    }
    public Lookup inspect(String channel,String source,String speaker,Map<String,Object> context) {
        if (channel == null || !CHANNELS.contains(channel) || source == null
                || source.length() > DEFAULT_LIMITS.textChars() * 4) return miss("invalid_scope","none");
        String normalized = normalize(source);
        if (normalized.isEmpty() || normalized.length() > DEFAULT_LIMITS.textChars()) return miss("invalid_text","none");
        String scopedSpeaker = channel.equals("npc") ? normalizeSpeaker(speaker) : "";
        if (channel.equals("npc") && scopedSpeaker.isEmpty()) return miss("missing_speaker","none");
        Key key = new Key(channel, scopedSpeaker, normalized);
        Map<String,Object> observed=NpcDictionaryTemplates.context(ReviewedDictionary.context(context),context);
        var cacheKey=new LookupKey(key,observed);
        var cached=lookupCache.get(cacheKey);if(cached!=null)return cached;
        Lookup result=reviewed.inspect(channel,scopedSpeaker,normalized,observed);
        if(result==null)result=inspectNormalized(key);
        // Only a semantic lookup miss may use these extensions. A found exact/reviewed
        // translation, a blocked key, and later style restoration failures stay authoritative.
        if(result.match().isEmpty()&&result.reason().equals("not_found")) {
            var npc=channel.equals("npc")?npcTemplates.inspect(normalized,scopedSpeaker,observed):miss("not_found","npc_template");
            var glyph=glyphFallback.inspect(channel,normalized,scopedSpeaker);
            if(!npc.reason().equals("not_found")&&npc.match().isEmpty()) result=npc;
            else if(!glyph.reason().equals("not_found")&&glyph.match().isEmpty()) result=glyph;
            else if(npc.match().isPresent()||glyph.match().isPresent())
                result=combine(npc.match().orElse(null),glyph.match().orElse(null),npc.match().isPresent()?"npc_template":"glyph_fallback");
        }
        if(result.match().isPresent()) {
            Match match=result.match().get();
            var alignment=DictionaryAlignment.lookup(channel,normalized,match.text(),match.sourceIds());
            if(alignment!=null)result=new Lookup(Optional.of(new Match(match.text(),match.keepOriginal(),match.sourceIds(),match.bindings(),alignment)),result.reason(),result.mode());
        }
        lookupCache.put(cacheKey,result);return result;
    }
    private Lookup inspectNormalized(Key key) {
        String channel=key.channel,normalized=key.source,scopedSpeaker=key.speaker;
        if (ambiguous.contains(key)) return miss("ambiguous","exact");
        boolean fallback=!channel.equals("npc")&&!channel.equals("common");
        Key commonKey = new Key("common", "", normalized);
        if (fallback&&ambiguous.contains(commonKey)) return miss("ambiguous","exact");
        // A quarantined literal remains unavailable through case/period/glyph
        // aliases, common fallback and templates, including when the sidecar fails.
        String alias = DictionaryMatching.aliasKey(normalized);
        if (normalizedConflicts.contains(new Key(channel, scopedSpeaker, alias))
                || fallback && normalizedConflicts.contains(new Key("common", "", alias)))
            return miss("ambiguous", "normalized");
        Match direct = entries.get(key);
        Match common = fallback?entries.get(commonKey):null;
        if(direct!=null||common!=null)return combine(direct,common,"exact");
        for(String mode:List.of("normalized","template")) {
            Lookup a=mode.equals("normalized")?matching.normalized(channel,normalized,scopedSpeaker):matching.template(channel,normalized,scopedSpeaker);
            Lookup b=!fallback?miss("not_found",mode):mode.equals("normalized")?matching.normalized("common",normalized,""):matching.template("common",normalized,"");
            if(a.reason.equals("ambiguous")||b.reason.equals("ambiguous"))return miss("ambiguous",mode);
            if(a.match.isPresent()||b.match.isPresent())return combine(a.match.orElse(null),b.match.orElse(null),mode);
        }
        return miss("not_found","none");
    }
    private static Lookup miss(String reason,String mode){return new Lookup(Optional.empty(),reason,mode);}
    private static Lookup combine(Match direct,Match common,String mode) {
        if(direct==null)return new Lookup(Optional.of(common),"matched",mode);
        if(common==null)return new Lookup(Optional.of(direct),"matched",mode);
        if (!direct.text().equals(common.text()) || direct.keepOriginal() != common.keepOriginal()) return miss("conflict",mode);
        Set<String> ids = new LinkedHashSet<>(direct.sourceIds());
        ids.addAll(common.sourceIds());
        if (!direct.bindings().equals(common.bindings())) return miss("conflict", mode);
        return new Lookup(Optional.of(new Match(direct.text(), direct.keepOriginal(), List.copyOf(ids), direct.bindings())),"matched",mode);
    }

    public Stats stats() { return stats; }

    /** Case and numbers are deliberately retained. Only visual wrapping and Minecraft colors fold. */
    public static String normalize(String value) {
        if (value == null || value.isEmpty()) return "";
        String plain = FORMAT.matcher(value).replaceAll("");
        StringBuilder result = new StringBuilder(plain.length());
        boolean space = false;
        for (int offset = 0; offset < plain.length();) {
            int cp = plain.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xA0) {
                space = result.length() > 0;
            } else {
                if (space) result.append(' ');
                result.appendCodePoint(cp);
                space = false;
            }
        }
        return result.toString();
    }

    private static String normalizeSpeaker(String speaker) {
        String normalized = normalize(speaker);
        if (normalized.startsWith("[NPC]")) normalized = normalize(normalized.substring(5));
        else if (normalized.startsWith("[BOSS]")) normalized = normalize(normalized.substring(6));
        return normalized;
    }

    private static Entry readEntry(JsonReader json, Limits limits) throws IOException, InvalidDocument, InvalidEntry {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue();
            throw new InvalidEntry("entry_object_required");
        }
        json.beginObject();
        Set<String> fields = new HashSet<>();
        Map<String, String> values = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        Boolean keep = null;
        String problem = null;
        while (json.hasNext()) {
            String field = json.nextName();
            if (!fields.add(field)) problem = first(problem, "duplicate_entry_field");
            switch (field) {
                case "channel", "speaker", "source", "target" -> {
                    if (json.peek() != JsonToken.STRING) {
                        json.skipValue();
                        problem = first(problem, "invalid_string_field");
                    } else {
                        String value = json.nextString();
                        if (value.length() > limits.textChars()) problem = first(problem, "text_limit");
                        else values.put(field, value);
                    }
                }
                case "keep_original" -> {
                    if (json.peek() != JsonToken.BOOLEAN) {
                        json.skipValue();
                        problem = first(problem, "invalid_keep_original");
                    } else keep = json.nextBoolean();
                }
                case "source_ids" -> {
                    if (json.peek() != JsonToken.BEGIN_ARRAY) {
                        json.skipValue();
                        problem = first(problem, "invalid_source_ids");
                    } else {
                        json.beginArray();
                        int count = 0;
                        while (json.hasNext()) {
                            // A field can be oversized even when the enclosing resource is small.
                            require(++count <= limits.sourceIds(), "source_id_limit");
                            if (json.peek() != JsonToken.STRING) {
                                json.skipValue();
                                problem = first(problem, "invalid_source_id");
                            } else {
                                String id = json.nextString();
                                if (id.isBlank() || id.length() > limits.sourceIdChars() || !safeIdentifier(id))
                                    problem = first(problem, "invalid_source_id");
                                else ids.add(id);
                            }
                        }
                        json.endArray();
                    }
                }
                default -> {
                    json.skipValue();
                    problem = first(problem, "unknown_entry_field");
                }
            }
        }
        json.endObject();
        if (!fields.equals(Set.of("channel", "speaker", "source", "target", "keep_original", "source_ids")))
            problem = first(problem, "missing_entry_field");
        if (problem != null) throw new InvalidEntry(problem);
        String channel = values.get("channel");
        if (!CHANNELS.contains(channel)) throw new InvalidEntry("unsupported_channel");
        if (!safeText(values.get("speaker"))) throw new InvalidEntry("invalid_speaker_scope");
        String speaker = normalizeSpeaker(values.get("speaker"));
        if (channel.equals("npc") ? speaker.isEmpty() : !speaker.isEmpty()) throw new InvalidEntry("invalid_speaker_scope");
        String sourceRaw = values.get("source"), targetRaw = values.get("target");
        if (!safeText(sourceRaw) || !safeText(targetRaw)) throw new InvalidEntry("unsafe_text");
        String source = normalize(sourceRaw), target = normalize(targetRaw);
        if (source.isEmpty() || target.isEmpty()) throw new InvalidEntry("empty_text");
        if (ids.isEmpty()) throw new InvalidEntry("missing_source_ids");
        if (Boolean.TRUE.equals(keep) && !source.equals(target)) throw new InvalidEntry("invalid_preservation");
        if (!HexColorTokens.preserved(source, target)) throw new InvalidEntry("rgb_color_mismatch");
        if (!quantitiesPreserved(source,target)) throw new InvalidEntry("number_mismatch");
        if (!symbols(source).equals(symbols(target))) throw new InvalidEntry("symbol_mismatch");
        return new Entry(new Key(channel, speaker, source), target, Boolean.TRUE.equals(keep), ids);
    }

    static boolean safeText(String value) {
        if (value == null || ENTITY.matcher(value).find() || SCRIPT.matcher(value).find()) return false;
        String plain = CommandTokens.withoutNumberedLabels(CommandTokens.withoutLiteralCommandPlaceholders(HexColorTokens.masked(FORMAT.matcher(value).replaceAll(""))));
        for (int offset = 0; offset < plain.length();) {
            int cp = plain.codePointAt(offset);
            offset += Character.charCount(cp);
            int type = Character.getType(cp);
            if (cp == '§' || cp == '<' || cp == '>' || cp == '{' || cp == '}' || cp == '#'
                    || type == Character.FORMAT || type == Character.SURROGATE
                    || (type == Character.CONTROL && cp != '\t' && cp != '\n' && cp != '\r')) return false;
        }
        return true;
    }

    private static boolean safeIdentifier(String value) {
        return value.codePoints().noneMatch(cp -> Character.isISOControl(cp)
                || Character.getType(cp) == Character.FORMAT || Character.getType(cp) == Character.SURROGATE);
    }

    static Map<String, Integer> numbers(String value) {
        Map<String, Integer> result = new HashMap<>();
        NUMBER.matcher(value).results().forEach(match -> result.merge(match.group(), 1, Integer::sum));
        return result;
    }

    /** No literal is dropped, changed, re-signed or rescaled by a quantity-word allowance. */
    static boolean quantitiesPreserved(String source,String target) {
        if (!HexColorTokens.preserved(source, target)) return false;
        // Exclude hexadecimal digits from numeric quantities only after full RGB
        // atom equality was verified. The generic numbers() API remains intact.
        Map<String,Integer> required=numbers(HexColorTokens.masked(source)),actual=numbers(HexColorTokens.masked(target)),allowance=new HashMap<>();
        for(var value:required.entrySet())if(actual.getOrDefault(value.getKey(),0)<value.getValue())return false;
        MULTIPLIER_WORD.matcher(source).results().forEach(m->{
            String word=m.group().toLowerCase(java.util.Locale.ROOT);
            String number=Set.of("double","doubles","doubled","twice").contains(word)?"2":Set.of("triple","triples","tripled","thrice").contains(word)?"3":"4";
            allowance.merge(number,1,Integer::sum);
        });
        for(var value:actual.entrySet())if(value.getValue()-required.getOrDefault(value.getKey(),0)>allowance.getOrDefault(value.getKey(),0))return false;
        return true;
    }

    static Map<Integer, Integer> symbols(String value) {
        Map<Integer, Integer> result = new HashMap<>();
        value.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type == Character.MATH_SYMBOL || type == Character.CURRENCY_SYMBOL || type == Character.MODIFIER_SYMBOL
                    || type == Character.OTHER_SYMBOL || type == Character.PRIVATE_USE)
                result.merge(cp, 1, Integer::sum);
        });
        return result;
    }

    private static String string(JsonReader reader, int limit) throws IOException, InvalidDocument {
        require(reader.peek() == JsonToken.STRING, "invalid_root_string");
        String value = reader.nextString();
        require(value.length() <= limit, "root_string_limit");
        return value;
    }

    /** Tombstones are safety metadata: malformed records invalidate the whole document. */
    private static Key readBlockedKey(JsonReader reader, Limits limits) throws IOException, InvalidDocument {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "blocked_key_object_required");
        reader.beginObject();
        Map<String, String> fields = new HashMap<>();
        while (reader.hasNext()) {
            String field = reader.nextName();
            require(Set.of("channel", "speaker", "source").contains(field), "unknown_blocked_key_field");
            require(!fields.containsKey(field), "duplicate_blocked_key_field");
            fields.put(field, string(reader, limits.textChars()));
        }
        reader.endObject();
        require(fields.keySet().equals(Set.of("channel", "speaker", "source")), "missing_blocked_key_field");
        String channel = fields.get("channel"), rawSpeaker = fields.get("speaker"), rawSource = fields.get("source");
        require(CHANNELS.contains(channel), "invalid_blocked_key_channel");
        require(safeText(rawSpeaker) && safeText(rawSource), "unsafe_blocked_key");
        String speaker = normalizeSpeaker(rawSpeaker), source = normalize(rawSource);
        require(channel.equals("npc") ? !speaker.isEmpty() : speaker.isEmpty(), "invalid_blocked_key_speaker");
        require(!source.isEmpty(), "empty_blocked_key_source");
        return new Key(channel, speaker, source);
    }
    private static Map.Entry<String,String> readReviewEvidence(JsonReader reader,Limits limits)throws IOException,InvalidDocument {
        require(reader.peek()==JsonToken.BEGIN_OBJECT,"review_evidence_object_required");reader.beginObject();
        var values=new HashMap<String,String>();
        while(reader.hasNext()) {
            String field=reader.nextName();require(Set.of("channel","speaker","source","variants_sha256").contains(field),"unknown_review_evidence_field");
            require(!values.containsKey(field),"duplicate_review_evidence_field");values.put(field,string(reader,limits.textChars()));
        }
        reader.endObject();require(values.keySet().equals(Set.of("channel","speaker","source","variants_sha256")),"missing_review_evidence_field");
        String channel=values.get("channel"),speaker=values.get("speaker"),source=values.get("source"),hash=values.get("variants_sha256");
        require(CHANNELS.contains(channel)&&(channel.equals("npc")?!speaker.isBlank():speaker.isEmpty()),"invalid_review_evidence_scope");
        require(safeText(speaker)&&safeText(source)&&!source.isBlank()&&hash.matches("[a-f0-9]{64}"),"invalid_review_evidence");
        return Map.entry(ReviewedDictionary.key(channel,normalizeSpeaker(speaker),source),hash);
    }

    private static String first(String old, String problem) { return old == null ? problem : old; }
    private static void require(boolean condition, String reason) throws InvalidDocument {
        if (!condition) throw new InvalidDocument(reason);
    }

    private static BundledDictionary failed(String buildId, String error, int readEntries) {
        return new BundledDictionary(Map.of(), Set.of(),
                new Stats(false, buildId, readEntries, 0, 0, 0, 0, Map.of(), error));
    }

    private static final class Accumulator {
        final String target;
        final boolean keepOriginal;
        final Set<String> sourceIds = new LinkedHashSet<>();
        Accumulator(Entry entry) {
            target = entry.target();
            keepOriginal = entry.keepOriginal();
            sourceIds.addAll(entry.sourceIds());
        }
    }

    private static final class InvalidDocument extends Exception {
        final String reason;
        InvalidDocument(String reason) { super(reason); this.reason = reason; }
    }

    private static final class InvalidEntry extends Exception {
        final String reason;
        InvalidEntry(String reason) { super(reason); this.reason = reason; }
    }

    private static final class ResourceLimitException extends IOException { }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long limit) { super(input); remaining = limit; }
        @Override public int read() throws IOException {
            int result = super.read();
            if (result >= 0 && --remaining < 0) throw new ResourceLimitException();
            return result;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int requested = (int) Math.min(length, remaining + 1);
            int result = in.read(bytes, offset, requested);
            if (result > 0 && (remaining -= result) < 0) throw new ResourceLimitException();
            return result;
        }
    }
}
