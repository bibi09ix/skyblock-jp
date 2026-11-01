package local.skyblockjp;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import java.util.*;
import java.util.regex.*;

/** Numbers never enter the translator. Style tags follow translated phrases. */
public final class StyledParagraph {
    static final Pattern NUMBER = Pattern.compile("[+−-]?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?|\\.\\d+)(?:[kKmMbBtT](?![A-Za-z]))?(?:%|‰)?");
    // Symbols are display atoms, not translatable words. In particular ⸕ and ⸎
    // are Unicode punctuation rather than symbols, so include those explicitly.
    private static final String SYMBOL = "(?!×)[\\p{Co}\\p{Sm}\\p{Sc}\\p{Sk}\\p{So}α⫽᠅‣•●➤■⸕⸎]";
    static final Pattern MARKER = Pattern.compile("<t\\d+>[^<>]*</t\\d+>|<n\\d+/>|</?s\\d+>");
    private final List<Style> styles = new ArrayList<>();
    private final List<Component> numbers = new ArrayList<>();
    private final List<Component> terms = new ArrayList<>();
    private final List<ProtectedPiece> protectedPieces = new ArrayList<>();
    private final List<StylePiece> stylePieces = new ArrayList<>();
    private final Style base;
    private final String encoded;
    private final String sourceText;
    private final Component nativeSource;
    private final Component nativeIdentityDisplay;
    private final Pattern sourceTokens;
    private final String dictionaryFingerprint;
    public String encoded() { return encoded; }
    /** Joined source text, before local display changes such as duration m -> 分. */
    public String sourceText() { return sourceText; }
    /** Includes native styles as well as text: differently colored values must not share a result. */
    public String dictionaryFingerprint() { return dictionaryFingerprint; }
    public record DictionaryEncoding(String text, String reason, Map<String, Object> details) {
        public DictionaryEncoding(String text, String reason) { this(text, reason, Map.of()); }
        public DictionaryEncoding { details = details == null ? Map.of() : Map.copyOf(details); }
        public boolean accepted() { return text != null; }
    }
    private record ProtectedPiece(String literal, String marker, Component component, int end, boolean numeric) {}
    private record StylePiece(String literal, int id, int start) {}
    private record Replacement(int start, int end, String encoded, ProtectedPiece piece) {}
    public record Glyph(String text, Style style) {}
    /** Visible joined native source, with known legacy codes materialized and exact whitespace styles. */
    public List<Glyph> sourceGlyphs() { return List.copyOf(glyphs(nativeSource)); }
    public static List<Glyph> glyphs(Component component) {
        List<Glyph> result = new ArrayList<>();
        component.visit((style, text) -> {
            text.codePoints().forEach(cp -> result.add(new Glyph(new String(Character.toChars(cp)), style)));
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }
    /** Materialize only recognized legacy codes carried by other mods' literal text.
     * Native text runs are independent formatting scopes, as in Minecraft's
     * StringDecomposer. Unknown/truncated section signs remain source text.
     */
    private static List<Glyph> formattedSourceGlyphs(Component component) {
        List<Glyph> result = new ArrayList<>();
        component.visit((nativeStyle, text) -> {
            Style current = nativeStyle;
            for (int at = 0; at < text.length();) {
                char value = text.charAt(at);
                if (value == '§' && at + 1 < text.length()) {
                    char code = text.charAt(at + 1);
                    // getByCode also folds non-ASCII characters such as Kelvin
                    // sign to k. Accept the same ASCII grammar as dictionary
                    // normalization instead of interpreting unknown source data.
                    var format = "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(code) >= 0 ? ChatFormatting.getByCode(code) : null;
                    if (format != null) {
                        current = format == ChatFormatting.RESET ? nativeStyle : current.applyLegacyFormat(format);
                        at += 2;
                        continue;
                    }
                }
                int cp = text.codePointAt(at);
                result.add(new Glyph(new String(Character.toChars(cp)), current));
                at += Character.charCount(cp);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }
    public StyledParagraph(List<Component> lines) { this(lines, false); }
    /** Only actual player/manual-chat text may contain an immutable username header. */
    public StyledParagraph(List<Component> lines, boolean playerChat) {
        List<Glyph> glyphs = new ArrayList<>();
        for (Component line : lines) {
            List<Glyph> part = new ArrayList<>(formattedSourceGlyphs(line));
            while (!part.isEmpty() && part.getFirst().text().isBlank()) part.removeFirst();
            while (!part.isEmpty() && part.getLast().text().isBlank()) part.removeLast();
            if (!part.isEmpty()) {
                if (!glyphs.isEmpty()) glyphs.add(new Glyph(" ", glyphs.getLast().style()));
                glyphs.addAll(part);
            }
        }
        StringBuilder plain = new StringBuilder();
        List<Style> charStyles = new ArrayList<>();
        for (Glyph glyph : glyphs) {
            plain.append(glyph.text());
            for (int i = 0; i < glyph.text().length(); i++) charStyles.add(glyph.style());
        }
        String text = plain.toString();
        sourceText = text;
        nativeSource = component(text, charStyles, 0, text.length());
        Pattern termsForText = ProtectedTerms.forText(text, playerChat);
        sourceTokens = Pattern.compile(termsForText.pattern() + "|" + NUMBER.pattern() + "|" + SYMBOL);
        Map<Style, Integer> weights = new LinkedHashMap<>();
        glyphs.forEach(g -> weights.merge(g.style(), g.text().length(), Integer::sum));
        Style reviewedBody = reviewedBodyStyle(text, charStyles);
        base = reviewedBody != null ? reviewedBody : proseBase(text, charStyles, sourceTokens, weights);
        Matcher matcher = sourceTokens.matcher(text);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            encodeText(out, text, charStyles, cursor, matcher.start());
            if (termsForText.matcher(matcher.group()).matches()) {
                int id = terms.size(); terms.add(component(text, charStyles, matcher.start(), matcher.end()));
                // Immutable payloads may contain channel separators (Party >),
                // URLs or literal angle brackets. Their native Component remains
                // local; escaped payloads keep the marker grammar unambiguous.
                String payload = matcher.group().replace("<", "&lt;").replace(">", "&gt;");
                String marker = "<t" + id + ">" + payload + "</t" + id + ">";
                out.append(marker);
                protectedPieces.add(new ProtectedPiece(matcher.group(), marker, terms.get(id), matcher.end(), false));
            } else {
                // Lowercase m after an explicit duration cue means minutes, not million.
                // Keep the numeric value/color locally while rendering the unit in Japanese.
                if (matcher.group().matches("\\d+(?:\\.\\d+)?m")
                    && (text.substring(0, matcher.start()).matches("(?is).*\\b(?:lasts|duration:)\\s*")
                        || text.substring(0, matcher.start()).matches("(?is)^Consume to boost .*\\bfor\\s*"))) {
                    numbers.add(component(text, charStyles, matcher.start(), matcher.end() - 1).copy()
                        .append(Component.literal("分").setStyle(charStyles.get(matcher.end() - 1))));
                } else numbers.add(component(text, charStyles, matcher.start(), matcher.end()));
                String marker = "<n" + (numbers.size() - 1) + "/>";
                out.append(marker);
                protectedPieces.add(new ProtectedPiece(matcher.group(), marker, numbers.getLast(), matcher.end(), NUMBER.matcher(matcher.group()).matches()));
            }
            cursor = matcher.end();
        }
        encodeText(out, text, charStyles, cursor, text.length());
        encoded = out.toString();
        nativeIdentityDisplay = nativeDisplaySnapshot(text, charStyles);
        StringBuilder signature = new StringBuilder(sourceText).append('\u0000').append(encoded);
        Style previousStyle = null;
        for (int i = 0; i < charStyles.size(); i++) {
            Style style = charStyles.get(i);
            if (!style.equals(previousStyle)) signature.append('\u0000').append(i).append(':').append(style);
            previousStyle = style;
        }
        dictionaryFingerprint = signature.toString();
    }

    private Style reviewedBodyStyle(String text, List<Style> charStyles) {
        var anchor = DictionaryAlignment.bodyForSource(text);
        if (anchor == null) return null;
        int start = rawOffset(anchor.sourceStart()), end = rawOffset(anchor.sourceEnd());
        if (start < 0 || end <= start || !BundledDictionary.normalize(text.substring(start, end)).equals(anchor.sourceText())) return null;
        Style style = null;
        for (int at = start; at < end; at++) {
            if (lookupSpace(text.charAt(at))) continue;
            if (style == null) style = charStyles.get(at);
            else if (!style.equals(charStyles.get(at))) return null;
        }
        return style;
    }
    private int rawOffset(int normalized) {
        int left = 0, right = sourceText.length();
        while (left < right) {
            int middle = (left + right) >>> 1;
            if (normalizedOffset(middle) < normalized) left = middle + 1; else right = middle;
        }
        return normalizedOffset(left) == normalized ? left : -1;
    }
    /** Preserve native whitespace styles while retaining intentional local unit display. */
    private Component nativeDisplaySnapshot(String text, List<Style> charStyles) {
        var changed = protectedPieces.stream().filter(p -> !p.literal().equals(p.component().getString())).toList();
        if (changed.isEmpty()) return nativeSource;
        var display = Component.empty(); int cursor = 0;
        for (var piece : changed) {
            int start = piece.end() - piece.literal().length();
            display.append(component(text, charStyles, cursor, start)).append(piece.component().copy()); cursor = piece.end();
        }
        return display.append(component(text, charStyles, cursor, text.length()));
    }

    /** Native values/names and separator punctuation cannot elect a prose color. */
    private static Style proseBase(String text, List<Style> charStyles, Pattern tokens, Map<Style, Integer> allWeights) {
        var protectedOffsets = new BitSet(text.length());
        var matches = tokens.matcher(text);
        while (matches.find()) protectedOffsets.set(matches.start(), matches.end());
        Map<Style, Integer> proseWeights = new LinkedHashMap<>();
        for (int at = 0; at < text.length();) {
            int codePoint = text.codePointAt(at);
            if (Character.isLetter(codePoint) && !protectedOffsets.get(at))
                proseWeights.merge(charStyles.get(at), 1, Integer::sum);
            at += Character.charCount(codePoint);
        }
        if (!proseWeights.isEmpty()) {
            // Formatting emphasis remains a separate alignment obligation when
            // ordinary un-emphasized prose exists; it must not become the base.
            var regular = proseWeights.entrySet().stream().filter(e -> !emphasized(e.getKey())).toList();
            // White skill/reward bodies and gray item lore both occur natively.
            // A neutral style qualifies only through actual unprotected letters,
            // never through a gray arrow, white period or protected stat name.
            var neutral = regular.stream().filter(e -> neutralProseColor(e.getKey())).toList();
            // Retain the established gray-lore convention when gray actually
            // carries prose. A long genuine white emphasis must stay an accent.
            var gray = neutral.stream().filter(e -> e.getKey().getColor() != null && e.getKey().getColor().getValue() == 0xAAAAAA)
                .max(Map.Entry.comparingByValue());
            if (gray.isPresent()) return gray.get().getKey();
            if (!neutral.isEmpty()) return neutral.stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            // Gray italic flavor prose still outranks a regular colored name.
            // Do not give the same exception to a white bold/italic accent over
            // an otherwise regular colored body: that accent must stay mapped.
            var grayProse = proseWeights.entrySet().stream()
                .filter(e -> e.getKey().getColor() != null && e.getKey().getColor().getValue() == 0xAAAAAA)
                .max(Map.Entry.comparingByValue());
            if (grayProse.isPresent()) return grayProse.get().getKey();
            if (!regular.isEmpty()) return regular.stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            var allNeutral = proseWeights.entrySet().stream().filter(e -> neutralProseColor(e.getKey())).toList();
            return (allNeutral.isEmpty() ? proseWeights.entrySet().stream() : allNeutral.stream())
                .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        }
        // Value/name-only text retains the previous deterministic behavior.
        return allWeights.keySet().stream().filter(s -> s.getColor() != null && s.getColor().getValue() == 0xAAAAAA)
            .findFirst().orElseGet(() -> allWeights.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Style.EMPTY));
    }

    private static boolean emphasized(Style style) {
        return style.isBold() || style.isItalic() || style.isUnderlined() || style.isStrikethrough() || style.isObfuscated();
    }

    private static boolean neutralProseColor(Style style) {
        return style.getColor() == null || style.getColor().getValue() == 0xAAAAAA || style.getColor().getValue() == 0xFFFFFF;
    }
    private void encodeText(StringBuilder out, String text, List<Style> charStyles, int start, int end) {
        for (int i = start; i < end;) {
            int j = i + 1;
            Style style = charStyles.get(i);
            while (j < end && charStyles.get(j).equals(style)) j++;
            String piece = text.substring(i, j);
            if (style.equals(base) || piece.isBlank()) out.append(piece);
            else {
                int id = styles.size(); styles.add(style);
                stylePieces.add(new StylePiece(piece, id, i));
                out.append("<s").append(id).append(">").append(piece).append("</s").append(id).append(">");
            }
            i = j;
        }
    }
    /**
     * Convert a literal dictionary value to the same protected format as an API response.
     * Uncertain style alignment must fall back to the API rather than lose a source color.
     */
    public String encodeDictionaryTranslation(String target) {
        return dictionaryEncoding(target).text();
    }
    public DictionaryEncoding dictionaryEncoding(String target) {
        return dictionaryEncoding(target, List.of(), null, false);
    }
    /** Template variable identities survive lookup instead of being guessed from equal values. */
    public DictionaryEncoding dictionaryEncoding(BundledDictionary.Match match) {
        return dictionaryEncoding(match, false);
    }
    public DictionaryEncoding dictionaryEncoding(BundledDictionary.Match match, boolean allowPlain) {
        return dictionaryEncoding(match.text(), match.bindings(), match.alignment(), allowPlain);
    }
    private DictionaryEncoding dictionaryEncoding(String target, List<BundledDictionary.Binding> suppliedBindings,
            DictionaryAlignment.Plan alignment, boolean allowPlain) {
        String literalTarget = target == null ? null : CommandTokens.withoutLiteralCommandPlaceholders(target);
        if (target == null || target.isBlank() || literalTarget.indexOf('<') >= 0 || literalTarget.indexOf('>') >= 0)
            return rejected("INVALID_TARGET", Map.of("validationStage", "TARGET_INPUT", "cause",
                target == null ? "null" : target.isBlank() ? "blank" : "unexpected_markup"));
        if (!HexColorTokens.preserved(sourceText, target))
            return rejected("PROTECTED_RGB_COLOR_CHANGED", Map.of("validationStage", "RGB_LITERAL_FIDELITY",
                "sourceColors", HexColorTokens.signature(sourceText), "targetColors", HexColorTokens.signature(target)));
        var typedCheck = TypedDictionaryTokens.inspect(sourceText, target);
        if (!typedCheck.reason().isEmpty()) return rejected(typedCheck.reason(), typedCheck.details());
        if (alignment != null) {
            String failure = DictionaryAlignment.validate(alignment, sourceText, target);
            if (!failure.isEmpty()) return rejected(failure, Map.of("validationStage", "AUTHORED_ALIGNMENT", "alignmentId", alignment.id() == null ? "" : alignment.id()));
        }
        var combinedBindings = new LinkedHashSet<>(suppliedBindings);
        combinedBindings.addAll(TypedDictionaryTokens.bindings(sourceText, target));
        if (alignment != null) combinedBindings.addAll(DictionaryAlignment.bindings(alignment));
        var semanticBindings = QuantityBindings.combine(sourceText, target, combinedBindings);
        if (!semanticBindings.reason().isEmpty()) return rejected(semanticBindings.reason(), semanticBindings.details());
        List<BundledDictionary.Binding> bindings = semanticBindings.bindings();
        List<Replacement> replacements = new ArrayList<>();
        var bindingFailure = applyBindings(target, bindings, replacements);
        if (bindingFailure != null) return bindingFailure;
        if (target.equals(sourceText)) return new DictionaryEncoding(encoded, "ACCEPTED", Map.of("renderPolicy", "NATIVE_SOURCE"));
        Map<String, List<ProtectedPiece>> groups = new LinkedHashMap<>();
        for (ProtectedPiece piece : protectedPieces) {
            if (replacements.stream().anyMatch(r -> r.piece() == piece)) continue;
            String key = piece.marker().startsWith("<t") ? "term:" + piece.literal().toLowerCase(Locale.ROOT) : "value:" + piece.literal();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(piece);
        }
        var ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.<List<ProtectedPiece>>comparingInt(g -> g.getFirst().marker().startsWith("<t") ? 0 : 1)
            .thenComparing(Comparator.comparingInt((List<ProtectedPiece> g) -> g.getFirst().literal().length()).reversed()));
        List<List<ProtectedPiece>> ambiguous = new ArrayList<>();
        for (var group : ordered) {
            var first = group.getFirst();
            var candidates = protectedOccurrences(target, first, replacements);
            if (candidates.size() < group.size()) return rejected("PROTECTED_VALUE_MISSING",
                protectedDetails(first, group.size(), candidates, "PROTECTED_GROUP_COUNT"));
            if (candidates.size() > group.size()) return rejected("PROTECTED_VALUE_EXTRA",
                protectedDetails(first, group.size(), candidates, "PROTECTED_GROUP_COUNT"));
            boolean sameStyle = group.stream().allMatch(p -> sameComponentStyle(first, p));
            if (!sameStyle) {
                if (!first.numeric() && first.marker().startsWith("<t")) return rejected("AMBIGUOUS_VALUE_STYLE",
                    ambiguousDetails(first, candidates.size(), "NON_NUMERIC_STYLE_CONFLICT", null));
                ambiguous.add(group); continue;
            }
            for (int i = 0; i < group.size(); i++) {
                var p = group.get(i); var at = candidates.get(i);
                replacements.add(new Replacement(at.start(), at.end(), p.marker(), p));
            }
        }
        // Equal native values use a retained variable binding, a unique semantic
        // unit, or a distinct adjacent stat. Order alone never assigns differing colors.
        for (var group : ambiguous) {
            var candidates = protectedOccurrences(target, group.getFirst(), replacements);
            for (var p : group) {
                String role = p.numeric() ? QuantityRoles.source(sourceText, p.end() - p.literal().length(), p.end()) : "";
                var roleMatches = role.isEmpty() ? List.<Replacement>of() : candidates.stream()
                    .filter(c -> QuantityRoles.target(target, c.start(), c.end()).equals(role))
                    .filter(c -> !overlaps(c.start(), c.end(), replacements)).toList();
                if (roleMatches.size() == 1) {
                    var at = roleMatches.getFirst(); replacements.add(new Replacement(at.start(), at.end(), p.marker(), p)); continue;
                }
                ProtectedPiece anchor = adjacentSourceTerm(p);
                if (anchor == null) return rejected("AMBIGUOUS_VALUE_STYLE",
                    ambiguousDetails(p, 0, "SOURCE_ADJACENT_TERM_MISSING", null));
                var anchors = replacements.stream().filter(r -> r.piece() == anchor).toList();
                if (anchors.size() != 1) return rejected("AMBIGUOUS_VALUE_STYLE",
                    ambiguousDetails(p, anchors.size(), "TARGET_TERM_NOT_UNIQUE", anchor));
                var matches = candidates.stream().filter(c -> adjacentTargetValue(target, c, anchors.getFirst()))
                    .filter(c -> !overlaps(c.start(), c.end(), replacements)).toList();
                if (matches.size() != 1) return rejected("AMBIGUOUS_VALUE_STYLE",
                    ambiguousDetails(p, matches.size(), "TARGET_ADJACENT_VALUE_NOT_UNIQUE", anchor));
                var at = matches.getFirst(); replacements.add(new Replacement(at.start(), at.end(), p.marker(), p));
            }
        }
        // Do not allow an otherwise unknown extra stat, number or icon to enter the
        // output just because all required source values have already been found.
        boolean proseNumbersValid = BundledDictionary.quantitiesPreserved(sourceText, target);
        Matcher targetTokens = sourceTokens.matcher(target);
        while (targetTokens.find()) {
            int start = targetTokens.start(), end = targetTokens.end();
            if (replacements.stream().noneMatch(r -> start >= r.start() && end <= r.end())
                && !ProtectedTerms.PATTERN.matcher(targetTokens.group()).matches()
                && !(NUMBER.matcher(targetTokens.group()).matches() && proseNumbersValid))
                return rejected("PROTECTED_VALUE_EXTRA",
                    extraDetails(targetTokens.group(), start, 0, 1, "UNMATCHED_TARGET_TOKEN"));
        }
        // forText deliberately leaves ordinary lowercase homonyms translatable. An
        // extra lowercase stat in the target must still be rejected: only actual,
        // unprotected occurrences in this source provide a quota for ordinary words.
        Map<String, Integer> ordinaryTerms = new HashMap<>();
        Matcher sourceTerms = ProtectedTerms.PATTERN.matcher(sourceText);
        while (sourceTerms.find()) {
            int start = sourceTerms.start(), end = sourceTerms.end();
            if (protectedPieces.stream().noneMatch(p -> start >= p.end() - p.literal().length() && end <= p.end()))
                ordinaryTerms.merge(sourceTerms.group().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        Matcher targetTerms = ProtectedTerms.PATTERN.matcher(target);
        while (targetTerms.find()) {
            int start = targetTerms.start(), end = targetTerms.end();
            if (replacements.stream().anyMatch(r -> start >= r.start() && end <= r.end())) continue;
            String key = targetTerms.group().toLowerCase(Locale.ROOT);
            int allowed = ordinaryTerms.getOrDefault(key, 0);
            if (allowed == 0) return rejected("PROTECTED_VALUE_EXTRA",
                extraDetails(targetTerms.group(), start, 0, 1, "ORDINARY_TERM_QUOTA_EXHAUSTED"));
            ordinaryTerms.put(key, allowed - 1);
        }
        for (var piece : protectedPieces) {
            if (!piece.marker().startsWith("<t")) continue;
            String[] names = piece.literal().split(",\\s*(?:and\\s+)?|\\s+and\\s+");
            if (names.length < 2) continue;
            for (String name : names) {
                if (name.isBlank()) continue;
                if (englishOccurrences(target, name.strip()) > englishOccurrences(sourceText, name.strip())) {
                    var details = new LinkedHashMap<>(extraDetails(name.strip(), -1,
                        englishOccurrences(sourceText, name.strip()), englishOccurrences(target, name.strip()), "NAMED_LIST_MEMBER_COUNT"));
                    details.put("sourceLiteral", diagnosticFragment(piece.literal()));
                    details.put("marker", diagnosticFragment(piece.marker()));
                    return rejected("PROTECTED_VALUE_EXTRA", details);
                }
            }
        }
        Set<Integer> alignedStyles = new HashSet<>();
        parentheticalStyleProjection(target, replacements, alignedStyles);
        var plainStyleIds = new ArrayList<String>();
        StringBuilder plainMarkers = new StringBuilder();
        for (StylePiece piece : stylePieces) {
            if (alignedStyles.contains(piece.id())) continue;
            String literal = piece.literal().strip();
            int trimStart = piece.start() + piece.literal().indexOf(literal);
            var authoredSpans = DictionaryAlignment.forStyle(alignment, normalizedOffset(trimStart), normalizedOffset(trimStart + literal.length()));
            if (!authoredSpans.isEmpty()) {
                var authored = authoredSpans.getFirst();
                if (authored.targetStart() >= 0) {
                    int start = authoredSpans.stream().mapToInt(DictionaryAlignment.Span::targetStart).min().orElseThrow();
                    int end = authoredSpans.stream().mapToInt(DictionaryAlignment.Span::targetEnd).max().orElseThrow();
                    if (overlaps(start, end, replacements)) return rejected("ALIGNMENT_TARGET_OCCUPIED",
                        Map.of("validationStage", "AUTHORED_ALIGNMENT", "alignmentId", alignment.id(), "spanId", authored.id()));
                    replacements.add(new Replacement(start, end, "<s" + piece.id() + ">"
                        + target.substring(start, end) + "</s" + piece.id() + ">", null));
                    alignedStyles.add(piece.id()); continue;
                }
                if (allowPlain && authored.kind().equals("ordinary") && authored.confirmedOrdinary() && authored.allowPlain()) {
                    // Keep the marker inventory balanced without assigning this accent to an unrelated phrase.
                    // Only the exact reviewed source/target/span may degrade; immutable values were validated above.
                    plainMarkers.append("<s").append(piece.id()).append("></s").append(piece.id()).append('>');
                    plainStyleIds.add(authored.id()); alignedStyles.add(piece.id()); continue;
                }
                return rejected("PLAIN_ALIGNMENT_DISABLED", Map.of("validationStage", "AUTHORED_ALIGNMENT", "alignmentId", alignment.id(), "spanId", authored.id()));
            }
            List<Replacement> matches = new ArrayList<>();
            matches.addAll(pairedPunctuation(target, piece, replacements));
            matches.addAll(boundStyleOccurrences(target, piece, bindings, replacements));
            // A rate unit moves before its value in Japanese. Match its typed
            // value/glyph/cap role rather than guessing from the nearest number.
            for (var span : DictionaryRateTokens.styleSpans(sourceText, target, piece.start(), piece.start() + piece.literal().length())) {
                if (!overlaps(span.start(), span.end(), replacements)) matches.add(new Replacement(span.start(), span.end(),
                    "<s" + piece.id() + ">" + target.substring(span.start(), span.end()) + "</s" + piece.id() + ">", null));
            }
            String unit = translatedUnit(literal);
            ProtectedPiece quantity = precedingQuantity(piece);
            // Unit meaning takes precedence over surface spelling. Otherwise two
            // English 'seconds' candidates bypassed their known numeric anchors.
            for (String alias : ProtectedTerms.dictionaryEnglishAliases(literal))
                matches.addAll(styleOccurrences(target, alias, piece.id(), replacements, unit == null ? null : quantity));
            if (unit != null && quantity != null) matches.addAll(styleOccurrences(target, unit, piece.id(), replacements, quantity));
            if (literal.equalsIgnoreCase("x") || literal.equals("×")) {
                if (quantity == null) quantity = followingQuantity(piece);
                if (quantity != null) for (String alias : List.of("x", "×", "倍", "個"))
                    matches.addAll(styleOccurrences(target, alias, piece.id(), replacements, quantity));
            }
            if (matches.isEmpty() && literal.matches("(?i)wolves|mobs|monsters|enemies|spiders|creatures")) {
                if (quantity != null) for (String alias : List.of("体", "匹"))
                    matches.addAll(styleOccurrences(target, alias, piece.id(), replacements, quantity));
            }
            // Consider all known representations together. Choosing an English
            // occurrence first would hide ambiguity when a second Japanese
            // equivalent exists elsewhere in the same target.
            for (String alias : dictionaryPhraseAliases(literal))
                matches.addAll(styleOccurrences(target, alias, piece.id(), replacements, null));
            // The native Crit icon establishes the role; this is not a general
            // plural or proper-name singularization rule.
            if (literal.equals("Crits") && sourceText.substring(0, trimStart).stripTrailing().endsWith("\uE007"))
                matches.addAll(styleOccurrences(target, "Crit", piece.id(), replacements, null));
            matches = distinctLongest(matches);
            int spanStart = normalizedOffset(piece.start()), spanEnd = normalizedOffset(piece.start() + piece.literal().length());
            var spanBindings = bindings.stream().filter(b -> spanStart < b.sourceEnd() && spanEnd > b.sourceStart()).toList();
            if (!spanBindings.isEmpty()) matches = matches.stream().filter(r -> spanBindings.stream()
                .allMatch(b -> r.start() < b.targetEnd() && r.end() > b.targetStart())).toList();
            if (matches.isEmpty()) return rejected("STYLE_ALIGNMENT_MISSING", styleDetails(piece, matches));
            if (matches.size() != 1) {
                // Repeated equivalent accents are safe only when every possible
                // assignment produces the same native style and occurrence count.
                var equivalent = stylePieces.stream().filter(p -> !alignedStyles.contains(p.id())
                    && p.literal().strip().equalsIgnoreCase(literal) && styles.get(p.id()).equals(styles.get(piece.id()))).toList();
                if (equivalent.size() != matches.size()) return rejected("STYLE_ALIGNMENT_AMBIGUOUS", styleDetails(piece, matches));
                for (int i = 0; i < equivalent.size(); i++) {
                    var p = equivalent.get(i); var at = matches.get(i);
                    replacements.add(new Replacement(at.start(), at.end(), "<s" + p.id() + ">" + target.substring(at.start(), at.end()) + "</s" + p.id() + ">", null));
                    alignedStyles.add(p.id());
                }
                continue;
            }
            replacements.add(matches.getFirst());
            alignedStyles.add(piece.id());
        }
        replacements.sort(Comparator.comparingInt(Replacement::start));
        StringBuilder result = new StringBuilder(plainMarkers);
        int cursor = 0;
        for (Replacement replacement : replacements) {
            result.append(target, cursor, replacement.start()).append(replacement.encoded());
            cursor = replacement.end();
        }
        result.append(target, cursor, target.length());
        String value = result.toString();
        return validNumbers(encoded, value) ? new DictionaryEncoding(value, "ACCEPTED", alignment == null ? Map.of()
            : Map.of("alignmentId", alignment.id(), "alignmentMode", plainStyleIds.isEmpty() ? "AUTHORED" : "CONFIRMED_ORDINARY_PLAIN",
                "plainStyleIds", List.copyOf(plainStyleIds))) : rejected("INVALID_MARKERS",
            Map.of("validationStage", "ENCODED_OUTPUT_VALIDATION", "markersValid", validMarkers(encoded, value),
                "quantitiesPreserved", QuantityValidation.preserved(encoded, value)));
    }
    private DictionaryEncoding applyBindings(String target, List<BundledDictionary.Binding> bindings, List<Replacement> replacements) {
        String source = BundledDictionary.normalize(sourceText);
        for (var binding : bindings) {
            if (!validSlice(source, binding.sourceStart(), binding.sourceEnd(), binding.sourceText())
                || !validSlice(target, binding.targetStart(), binding.targetEnd(), binding.targetText())
                || !binding.sourceText().equals(binding.targetText()))
                return rejected("INVALID_BINDING", Map.of("validationStage", "TEMPLATE_BINDING", "variable", binding.name()));
            // Source offsets belong to the normalized lookup text. Map each native
            // atom through the same normalization, retaining its original component.
            for (var piece : protectedPieces) {
                int rawStart = piece.end() - piece.literal().length();
                int start = normalizedOffset(rawStart), end = normalizedOffset(piece.end());
                if (start >= binding.sourceEnd() || end <= binding.sourceStart()) continue;
                boolean contained = start >= binding.sourceStart() && end <= binding.sourceEnd();
                int expectedStart = binding.targetStart() + start - binding.sourceStart();
                int expectedEnd = binding.targetStart() + end - binding.sourceStart();
                var candidates = protectedOccurrences(target, piece, List.of()).stream()
                    .filter(r -> r.start() < binding.targetEnd() && r.end() > binding.targetStart())
                    .filter(r -> !contained || r.start() == expectedStart && r.end() == expectedEnd).toList();
                if (candidates.size() != 1) return rejected("INVALID_BINDING",
                    Map.of("validationStage", "TEMPLATE_BINDING", "variable", binding.name(), "candidateCount", candidates.size()));
                var candidate = candidates.getFirst();
                var existing = replacements.stream().filter(r -> r.piece() == piece).findFirst();
                if (existing.isPresent()) {
                    if (existing.get().start() != candidate.start() || existing.get().end() != candidate.end())
                        return rejected("INVALID_BINDING", Map.of("validationStage", "TEMPLATE_BINDING", "variable", binding.name(), "cause", "conflicting_target"));
                    continue;
                }
                if (overlaps(candidate.start(), candidate.end(), replacements)) return rejected("INVALID_BINDING",
                    Map.of("validationStage", "TEMPLATE_BINDING", "variable", binding.name(), "cause", "overlapping_target"));
                replacements.add(candidate);
            }
        }
        return null;
    }
    private List<Replacement> boundStyleOccurrences(String target, StylePiece piece,
            List<BundledDictionary.Binding> bindings, List<Replacement> replacements) {
        String literal = piece.literal().strip();
        if (literal.isEmpty()) return List.of();
        int rawStart = piece.start() + piece.literal().indexOf(literal);
        int start = normalizedOffset(rawStart), end = normalizedOffset(rawStart + literal.length());
        var result = new ArrayList<Replacement>();
        for (var binding : bindings) {
            if (!binding.sourceText().equals(binding.targetText()) || start < binding.sourceStart() || end > binding.sourceEnd()) continue;
            int at = binding.targetStart() + start - binding.sourceStart(), stop = binding.targetStart() + end - binding.sourceStart();
            if (at < 0 || stop > target.length() || at >= stop || overlaps(at, stop, replacements)
                || !BundledDictionary.normalize(literal).equals(target.substring(at, stop))) continue;
            result.add(new Replacement(at, stop, "<s" + piece.id() + ">" + target.substring(at, stop) + "</s" + piece.id() + ">", null));
        }
        return result;
    }
    private int normalizedOffset(int rawOffset) {
        // A trailing space is trimmed by normalize; count it only when the next
        // character is a non-space, so atom starts after a separator stay aligned.
        String prefix = sourceText.substring(0, rawOffset);
        int length = BundledDictionary.normalize(prefix).length();
        if (length > 0 && rawOffset > 0 && rawOffset < sourceText.length() && lookupSpace(sourceText.codePointBefore(rawOffset))
            && !lookupSpace(sourceText.codePointAt(rawOffset))) length++;
        return length;
    }
    private static boolean lookupSpace(int cp) { return Character.isWhitespace(cp) || Character.isSpaceChar(cp); }
    private static boolean validSlice(String text, int start, int end, String expected) {
        return start >= 0 && end > start && end <= text.length() && text.substring(start, end).equals(expected);
    }
    private static DictionaryEncoding rejected(String reason, Map<String, Object> details) { return new DictionaryEncoding(null, reason, details); }
    private static String diagnosticFragment(String text) {
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }
    private static Map<String, Object> protectedDetails(ProtectedPiece piece, int expected,
            List<Replacement> candidates, String step) {
        var details = new LinkedHashMap<String, Object>();
        details.put("validationStage", step);
        details.put("sourceLiteral", diagnosticFragment(piece.literal()));
        details.put("marker", diagnosticFragment(piece.marker()));
        details.put("sourceOffset", piece.end() - piece.literal().length());
        details.put("offsetUnit", "UTF16_JOINED_SOURCE");
        details.put("expectedCount", expected);
        details.put("candidateCount", candidates.size());
        details.put("candidateOffsets", candidates.stream().limit(8).map(r -> List.of(r.start(), r.end())).toList());
        return details;
    }
    private static Map<String, Object> ambiguousDetails(ProtectedPiece piece, int count, String step, ProtectedPiece anchor) {
        var details = new LinkedHashMap<String, Object>();
        details.put("validationStage", "REPEATED_VALUE_STYLE");
        details.put("anchorStage", step);
        details.put("sourceLiteral", diagnosticFragment(piece.literal()));
        details.put("marker", diagnosticFragment(piece.marker()));
        details.put("sourceOffset", piece.end() - piece.literal().length());
        details.put("offsetUnit", "UTF16_JOINED_SOURCE");
        details.put("candidateCount", count);
        details.put("expectedCount", 1);
        if (anchor != null) details.put("anchorLiteral", diagnosticFragment(anchor.literal()));
        return details;
    }
    private static Map<String, Object> extraDetails(String literal, int offset, long expected, long count, String step) {
        return Map.of("validationStage", step, "sourceLiteral", "", "marker", "", "targetLiteral", diagnosticFragment(literal),
            "targetOffset", offset, "expectedCount", expected, "candidateCount", count);
    }
    private Map<String, Object> styleDetails(StylePiece piece, List<Replacement> candidates) {
        Style style = styles.get(piece.id());
        var details = new LinkedHashMap<String, Object>();
        details.put("validationStage", "STYLE_FRAGMENT_ALIGNMENT");
        details.put("originalStyledFragment", diagnosticFragment(piece.literal()));
        details.put("styleId", piece.id());
        details.put("sourceOffset", piece.start());
        details.put("offsetUnit", "UTF16_JOINED_SOURCE");
        details.put("candidateCount", candidates.size());
        details.put("candidateOffsets", candidates.stream().limit(8).map(r -> List.of(r.start(), r.end())).toList());
        details.put("style", Map.of("colorRGB", style.getColor() == null ? "default" : String.format(Locale.ROOT, "#%06X", style.getColor().getValue()),
            "bold", style.isBold(), "italic", style.isItalic(), "underlined", style.isUnderlined(),
            "strikethrough", style.isStrikethrough(), "obfuscated", style.isObfuscated()));
        return details;
    }
    private static boolean overlaps(int start, int end, List<Replacement> replacements) {
        return replacements.stream().anyMatch(r -> start < r.end() && end > r.start());
    }
    private static boolean sameComponentStyle(ProtectedPiece a, ProtectedPiece b) {
        var first = glyphs(a.component()); var second = glyphs(b.component());
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (!first.get(i).text().equalsIgnoreCase(second.get(i).text()) || !first.get(i).style().equals(second.get(i).style())) return false;
        }
        return true;
    }
    private static List<Replacement> protectedOccurrences(String target, ProtectedPiece piece, List<Replacement> replacements) {
        var result = new ArrayList<Replacement>();
        if (piece.numeric()) {
            var m = NUMBER.matcher(target);
            while (m.find()) if (m.group().equals(piece.literal()) && !overlaps(m.start(), m.end(), replacements))
                result.add(new Replacement(m.start(), m.end(), piece.marker(), piece));
        } else if (piece.marker().startsWith("<t")) {
            for (String alias : ProtectedTerms.dictionaryEnglishAliases(piece.literal())) {
                var m = Pattern.compile(Pattern.quote(alias), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(target);
                while (m.find()) if (wordBoundaries(target, alias, m.start(), m.end()) && !overlaps(m.start(), m.end(), replacements))
                    result.add(new Replacement(m.start(), m.end(), piece.marker(), piece));
            }
            // A named list remains one immutable API term, but Japanese punctuation
            // may separate the same complete, ordered list in a literal dictionary.
            String[] names = piece.literal().split(",\\s*(?:and\\s+)?|\\s+and\\s+");
            if (names.length > 1 && Arrays.stream(names).noneMatch(String::isBlank)) {
                String join = "\\s*(?:、|，|,\\s*(?:and\\s+)?|(?:and|と|や|及び|および)\\s*)\\s*";
                var m = Pattern.compile(String.join(join, Arrays.stream(names).map(String::strip).map(Pattern::quote).toList()),
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(target);
                while (m.find()) if (wordBoundaries(target, piece.literal(), m.start(), m.end()) && !overlaps(m.start(), m.end(), replacements))
                    result.add(new Replacement(m.start(), m.end(), piece.marker(), piece));
                // English can share a final equipment noun across a complete list:
                // "Spider, Tarantula, Primordial and Spirit Boots". A reviewed
                // dictionary may repeat Boots for clarity. Match every member in
                // its original order, then restore the same immutable source term.
                var expanded = expandedEquipmentList(names);
                if (!expanded.isEmpty()) {
                    var expandedMatch = Pattern.compile(String.join(join, expanded.stream().map(Pattern::quote).toList()),
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(target);
                    while (expandedMatch.find()) if (wordBoundaries(target, piece.literal(), expandedMatch.start(), expandedMatch.end())
                        && !continuesNamedList(target, expandedMatch.start(), expandedMatch.end())
                        && !overlaps(expandedMatch.start(), expandedMatch.end(), replacements))
                        result.add(new Replacement(expandedMatch.start(), expandedMatch.end(), piece.marker(), piece));
                }
            }
        } else {
            for (int at = target.indexOf(piece.literal()); at >= 0; at = target.indexOf(piece.literal(), at + piece.literal().length()))
                if (!overlaps(at, at + piece.literal().length(), replacements)) result.add(new Replacement(at, at + piece.literal().length(), piece.marker(), piece));
        }
        return distinctLongest(result);
    }
    private static final String EQUIPMENT_NOUN = "Boots|Helmet|Chestplate|Leggings|Sword|Bow|Pickaxe|Drill|Rod|Wand|Axe|Belt|Necklace|Cloak|Gloves";
    private static List<String> expandedEquipmentList(String[] names) {
        if (names.length < 3 || names.length > 4) return List.of();
        var last = Pattern.compile("^(.+) (" + EQUIPMENT_NOUN + ")$").matcher(names[names.length - 1].strip());
        if (!last.matches()) return List.of();
        String suffix = last.group(2);
        var expanded = new ArrayList<String>();
        for (int i = 0; i < names.length - 1; i++) {
            String member = names[i].strip();
            // Mixed explicit categories are ambiguous; do not infer or repair them.
            if (member.matches(".*\\b(?:" + EQUIPMENT_NOUN + ")$")) return List.of();
            expanded.add(member + " " + suffix);
        }
        expanded.add(names[names.length - 1].strip());
        return expanded.stream().map(s -> s.toLowerCase(Locale.ROOT)).distinct().count() == names.length ? expanded : List.of();
    }
    private static boolean continuesNamedList(String target, int start, int end) {
        return target.substring(0, start).matches("(?s).*(?:[,、，]|\\band|と|や|及び|および)\\s*$")
            || target.substring(end).matches("(?s)^\\s*(?:[,、，]|and\\b|と|や|及び|および).*" );
    }
    private ProtectedPiece adjacentSourceTerm(ProtectedPiece number) {
        var matches = protectedPieces.stream().filter(p -> p.marker().startsWith("<t"))
            .filter(p -> protectedPieces.stream().filter(other -> other.marker().startsWith("<t") && other.literal().equalsIgnoreCase(p.literal())).count() == 1)
            .filter(p -> {
                int pStart = p.end() - p.literal().length(), nStart = number.end() - number.literal().length();
                String gap = pStart >= number.end() ? sourceText.substring(number.end(), pStart)
                    : p.end() <= nStart ? sourceText.substring(p.end(), nStart) : "not-adjacent";
                return gap.matches("(?:[\\s:：]|" + SYMBOL + ")*");
            }).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }
    private static boolean adjacentTargetValue(String target, Replacement number, Replacement anchor) {
        String gap = number.start() >= anchor.end() ? target.substring(anchor.end(), number.start())
            : anchor.start() >= number.end() ? target.substring(number.end(), anchor.start()) : "not-adjacent";
        return gap.matches("(?:[\\s:：]|" + SYMBOL + "|が|は|を|の|に)*");
    }
    private static List<Replacement> distinctLongest(List<Replacement> matches) {
        var ordered = new ArrayList<>(matches);
        ordered.sort(Comparator.comparingInt((Replacement r) -> r.end() - r.start()).reversed());
        var result = new ArrayList<Replacement>();
        for (var r : ordered) if (result.stream().noneMatch(old -> r.start() >= old.start() && r.end() <= old.end())) result.add(r);
        result.sort(Comparator.comparingInt(Replacement::start));
        return result;
    }
    private static boolean wordBoundaries(String target, String literal, int start, int end) {
        return !(asciiWord(literal.charAt(0)) && start > 0 && asciiWord(target.charAt(start - 1)))
            && !(asciiWord(literal.charAt(literal.length() - 1)) && end < target.length() && asciiWord(target.charAt(end)));
    }
    private static long englishOccurrences(String text, String literal) {
        var matches = Pattern.compile(Pattern.quote(literal), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        return matches.results().filter(m -> wordBoundaries(text, literal, m.start(), m.end())).count();
    }
    private List<Replacement> styleOccurrences(String target, String literal, int id,
                                                      List<Replacement> replacements, ProtectedPiece quantity) {
        List<Replacement> matches = new ArrayList<>();
        if (literal.isEmpty()) return matches;
        var occurrence = Pattern.compile(Pattern.quote(literal), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(target);
        while (occurrence.find()) {
            int start = occurrence.start();
            int occurrenceStart = start;
            int end = start + literal.length();
            if (replacements.stream().anyMatch(r -> occurrenceStart < r.end() && end > r.start())) continue;
            var sourcePiece = stylePieces.stream().filter(p -> p.id() == id).findFirst().orElse(null);
            boolean dimension = sourcePiece != null && (literal.equalsIgnoreCase("x") || literal.equals("×"))
                && QuantityBindings.dimensionOperator(sourceText, sourcePiece.start(), sourcePiece.start() + sourcePiece.literal().length(), target, start, end);
            if ((literal.equalsIgnoreCase("x") || literal.equals("×")) && QuantityBindings.insideDimension(target, start, end) && !dimension) continue;
            boolean quantityPrefix = quantity != null && (literal.equalsIgnoreCase("x") || translatedUnit(literal) != null)
                && replacements.stream().anyMatch(r -> r.piece() == quantity && r.end() == start)
                && !(asciiWord(literal.charAt(literal.length() - 1)) && end < target.length() && asciiWord(target.charAt(end)));
            boolean quantitySuffix = quantity != null && literal.equalsIgnoreCase("x")
                && replacements.stream().anyMatch(r -> r.piece() == quantity && r.start() == end)
                && !(start > 0 && asciiWord(target.charAt(start - 1)));
            if (!wordBoundaries(target, literal, start, end) && !quantityPrefix && !quantitySuffix && !dimension) continue;
            if (quantity != null && replacements.stream().noneMatch(r -> r.piece() == quantity
                && (r.end() <= occurrenceStart && target.substring(r.end(), occurrenceStart).isBlank()
                    || (literal.equalsIgnoreCase("x") || literal.equals("×")) && r.start() >= end && target.substring(end, r.start()).isBlank()))) continue;
            matches.add(new Replacement(start, end, "<s" + id + ">" + target.substring(start, end) + "</s" + id + ">", null));
        }
        return matches;
    }
    // Bounded structural projection; unknown or mixed ordinary styles remain unresolved.
    private record ParentheticalPair(int open,int close,int depth) {}
    private static List<ParentheticalPair> parentheticalPairs(String text) {
        var stack=new ArrayDeque<int[]>(); var result=new ArrayList<ParentheticalPair>();
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='('||c=='（') stack.push(new int[]{i,stack.size(),c});
            else if(c==')'||c=='）') {
                if(stack.isEmpty()) return List.of();
                var p=stack.pop();
                if((p[2]=='(' && c!=')')||(p[2]=='（' && c!='）')) return List.of();
                result.add(new ParentheticalPair(p[0],i,p[1]));
            }
        }
        return stack.isEmpty()?result:List.of();
    }
    private List<String> parentheticalSignature(String text,ParentheticalPair pair) {
        return sourceTokens.matcher(text.substring(pair.open()+1,pair.close())).results()
            .map(m->{
                String value=m.group().toLowerCase(Locale.ROOT);
                if(NUMBER.matcher(m.group()).matches()) {
                    int a=pair.open()+1+m.start(),z=pair.open()+1+m.end();
                    String unit=QuantityRoles.target(text,a,z);
                    String before=text.substring(pair.open()+1,a);
                    boolean cap=before.matches("(?is).*(?:\\bmax(?:imum)?|最大|上限)\\s*$");
                    value+="|"+(cap?"cap:":"")+unit;
                }
                return value;
            }).sorted().toList();
    }
    private String parentheticalClauseKind(String text,ParentheticalPair pair) {
        String inner=text.substring(pair.open()+1,pair.close());
        if(inner.matches("(?is).*(?:\\bmax(?:imum)?\\b|最大|上限).*"))return "cap";
        if(inner.matches("(?is).*(?:cooldown|クールダウン).*"))return "cooldown";
        if(inner.matches("(?is).*(?:resets?|リセット).*"))return "reset";
        String ordinary=sourceTokens.matcher(inner).replaceAll("");
        return ordinary.codePoints().anyMatch(Character::isLetter)?"unknown":"literal-only";
    }
    private List<Replacement> pairedPunctuation(String target,StylePiece piece,List<Replacement> replacements) {
        String literal=piece.literal().strip();
        if(!literal.equals("(")&&!literal.equals(")")) return List.of();
        int offset=piece.start()+piece.literal().indexOf(literal);
        var original=parentheticalPairs(sourceText).stream().filter(p->literal.equals("(")?p.open()==offset:p.close()==offset).toList();
        if(original.size()!=1) return List.of();
        var pair=original.getFirst(); var signature=parentheticalSignature(sourceText,pair);
        if(signature.isEmpty()) return List.of();
        var candidates=parentheticalPairs(target).stream().filter(p->p.depth()==pair.depth() && parentheticalSignature(target,p).equals(signature)).toList();
        if(candidates.size()!=1) return List.of();
        int at=literal.equals("(")?candidates.getFirst().open():candidates.getFirst().close();
        if(overlaps(at,at+1,replacements)) return List.of();
        return List.of(new Replacement(at,at+1,"<s"+piece.id()+">"+target.substring(at,at+1)+"</s"+piece.id()+">",null));
    }
    private void parentheticalStyleProjection(String target,List<Replacement> replacements,Set<Integer> aligned) {
        for(var pair:parentheticalPairs(sourceText)) {
            var signature=parentheticalSignature(sourceText,pair);
            if(signature.isEmpty())continue;
            String clauseKind=parentheticalClauseKind(sourceText,pair);
            if(clauseKind.equals("unknown"))continue;
            var targetPairs=parentheticalPairs(target).stream().filter(p->p.depth()==pair.depth()&&parentheticalSignature(target,p).equals(signature)&&parentheticalClauseKind(target,p).equals(clauseKind)).toList();
            if(targetPairs.size()!=1)continue;
            var pieces=stylePieces.stream().filter(p->p.start()<pair.close()+1&&p.start()+p.literal().length()>pair.open()).toList();
            if(pieces.isEmpty()||pieces.stream().anyMatch(p->aligned.contains(p.id())))continue;
            var style=styles.get(pieces.getFirst().id());
            if(pieces.stream().anyMatch(p->!styles.get(p.id()).equals(style)))continue;
            boolean safe=true; int trailing=pair.close()+1;
            for(var p:pieces) {
                int a=p.start(),z=a+p.literal().length();
                if(a<pair.open()&&!sourceText.substring(a,pair.open()).isBlank())safe=false;
                if(z>pair.close()+1&&!sourceText.substring(pair.close()+1,z).matches("[.!?。！？\\s]*"))safe=false;
                trailing=Math.max(trailing,z);
            }
            if(!safe)continue;
            var protectedOffsets=new BitSet(sourceText.length());
            sourceTokens.matcher(sourceText).results().forEach(m->protectedOffsets.set(m.start(),m.end()));
            int offset=0;
            for(var glyph:sourceGlyphs()) {
                int end=offset+glyph.text().length();
                if(offset>=pair.open()&&end<=pair.close()+1&&!protectedOffsets.get(offset)&&!glyph.text().isBlank()&&!glyph.style().equals(style))safe=false;
                offset=end;
            }
            if(!safe)continue;
            var tp=targetPairs.getFirst(); int a=tp.open(),z=tp.close()+1;
            if(trailing>pair.close()+1&&z<target.length()&&".!?。！？".indexOf(target.charAt(z))>=0)z++;
            final int targetStart=a,targetEnd=z;
            var contained=replacements.stream().filter(r->r.start()>=targetStart&&r.end()<=targetEnd).sorted(Comparator.comparingInt(Replacement::start)).toList();
            if(replacements.stream().anyMatch(r->r.start()<targetEnd&&r.end()>targetStart&&!(r.start()>=targetStart&&r.end()<=targetEnd)))continue;
            var encodedPart=new StringBuilder();int cursor=a;
            for(var replacement:contained){encodedPart.append(target,cursor,replacement.start()).append(replacement.encoded());cursor=replacement.end();}
            encodedPart.append(target,cursor,z);
            var opening=new StringBuilder();var closing=new StringBuilder();
            for(var piece:pieces){int id=piece.id();opening.append("<s").append(id).append('>');closing.insert(0,"</s"+id+">");}
            String value=opening.toString()+encodedPart+closing;
            replacements.removeAll(contained);replacements.add(new Replacement(a,z,value,null));
            pieces.forEach(p->aligned.add(p.id()));
        }
    }

    private static boolean asciiWord(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z' || value >= '0' && value <= '9' || value == '_';
    }
    private ProtectedPiece precedingQuantity(StylePiece piece) {
        return protectedPieces.stream().filter(p -> p.numeric() && p.end() <= piece.start()
            && sourceText.substring(p.end(), piece.start()).isBlank()).findFirst().orElse(null);
    }
    private ProtectedPiece followingQuantity(StylePiece piece) {
        int end = piece.start() + piece.literal().length();
        var matches = protectedPieces.stream().filter(p -> p.numeric() && p.end() - p.literal().length() >= end
            && sourceText.substring(end, p.end() - p.literal().length()).isBlank()).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }
    /**
     * Reviewed semantic color anchors, not a translator or a fuzzy search. A source
     * accent is usable only when exactly one of its Japanese equivalents occurs in
     * this dictionary target; unknown/ambiguous phrases still fail closed.
     */
    private static List<String> dictionaryPhraseAliases(String literal) {
        return switch (literal.toLowerCase(Locale.ROOT)) {
            case "nearby enemies" -> List.of("周囲の敵", "近くの敵");
            case "nearby players" -> List.of("周囲のプレイヤー", "近くのプレイヤー");
            case "nearby" -> List.of("周囲", "近く");
            case "enemies", "enemy" -> List.of("敵");
            case "mobs", "monsters" -> List.of("モンスター", "モブ");
            case "players", "player" -> List.of("プレイヤー");
            case "pets", "pet" -> List.of("ペット");
            case "rewards", "reward" -> List.of("報酬");
            case "reward chests" -> List.of("報酬チェスト");
            case "chests", "chest" -> List.of("チェスト");
            case "safe place" -> List.of("安全な場所");
            case "small fee" -> List.of("少しの料金", "少額の料金");
            case "xp", "exp", "experience" -> List.of("経験値");
            case "furniture recipe" -> List.of("家具レシピ");
            case "recipe", "recipes" -> List.of("レシピ");
            case "combo kills" -> List.of("連続で敵を倒す", "連続キル");
            case "successful defusing" -> List.of("解除に成功", "解除の成功");
            case "holding an axe" -> List.of("Axeを持っている間", "斧を持っている間");
            case "deep sea" -> List.of("深海");
            case "damage" -> List.of("ダメージ");
            case "chance" -> List.of("確率");
            case "bonus", "bonuses" -> List.of("ボーナス");
            case "speed" -> List.of("速度", "速さ", "速く");
            case "strength" -> List.of("強さ", "威力", "効果");
            case "vitality" -> List.of("生命力");
            case "health" -> List.of("体力", "健康");
            case "defense" -> List.of("防御", "守り");
            case "abilities", "ability" -> List.of("能力", "アビリティ");
            case "items", "item" -> List.of("アイテム");
            case "special items", "special item" -> List.of("特別なアイテム", "特別な品");
            case "evolve", "evolves", "evolved", "evolving" -> List.of("進化");
            case "double", "doubles", "doubled", "twice" -> List.of("2倍", "2");
            case "triple", "triples", "tripled", "thrice" -> List.of("3倍", "3");
            case "quadruple", "quadruples", "quadrupled" -> List.of("4倍", "4");
            case "inventory" -> List.of("インベントリ");
            case "skill", "skills" -> List.of("スキル");
            case "level", "levels" -> List.of("レベル");
            case "progress" -> List.of("進捗");
            case "max", "maximum" -> List.of("最大", "上限");
            case "cost" -> List.of("費用", "消費量", "消費");
            case "grants", "grant", "gives", "give" -> List.of("付与する", "与える", "付与", "増加する");
            case "increases", "increase" -> List.of("増加する", "増える", "増やす", "増加");
            case "reduces", "reduce", "decreases", "decrease" -> List.of("減少する", "軽減する", "減らす", "減少", "軽減");
            case "unlocks", "unlock" -> List.of("解放する", "解放できる", "解放");
            case "craft", "crafting" -> List.of("クラフト", "作成");
            case "collect" -> List.of("集める", "収集", "回収");
            case "permanent" -> List.of("常に", "永続");
            case "!" -> List.of("！");
            case "?" -> List.of("？");
            case ":" -> List.of("：");
            default -> List.of();
        };
    }
    private static String translatedUnit(String literal) {
        return switch (literal.toLowerCase(Locale.ROOT)) {
            case "second", "seconds", "sec", "secs", "s" -> "秒";
            case "minute", "minutes", "min", "mins" -> "分";
            case "hour", "hours" -> "時間";
            case "day", "days" -> "日";
            case "block", "blocks" -> "ブロック";
            default -> null;
        };
    }
    private static Component component(String text, List<Style> styles, int start, int end) {
        MutableComponent out = Component.empty();
        for (int i = start; i < end;) {
            int j = i + 1;
            while (j < end && styles.get(j).equals(styles.get(i))) j++;
            out.append(Component.literal(text.substring(i, j)).setStyle(styles.get(i))); i = j;
        }
        return out;
    }
    public static boolean validMarkers(String original, String translated) {
        List<String> a = MARKER.matcher(original).results().map(MatchResult::group).sorted().toList();
        List<String> b = MARKER.matcher(translated).results().map(MatchResult::group).sorted().toList();
        if (!a.equals(b)) return false;
        Deque<String> stack = new ArrayDeque<>();
        Matcher matcher = MARKER.matcher(translated);
        while (matcher.find()) {
            String tag = matcher.group();
            if (tag.startsWith("<s")) stack.push(tag.substring(2, tag.length() - 1));
            else if (tag.startsWith("</s") && (stack.isEmpty() || !stack.pop().equals(tag.substring(3, tag.length() - 1)))) return false;
        }
        return stack.isEmpty();
    }
    public static boolean validNumbers(String original, String translated) {
        return validMarkers(original, translated) && QuantityValidation.preserved(original, translated);
    }
    public Component decode(String translated) {
        if (!validNumbers(encoded, translated)) throw new IllegalArgumentException("Invalid protected markers");
        if (encoded.equals(translated)) return copyNativeSnapshot(nativeIdentityDisplay);
        MutableComponent out = Component.empty();
        Deque<Style> stack = new ArrayDeque<>(); stack.push(base);
        Matcher matcher = MARKER.matcher(translated);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) out.append(Component.literal(translated.substring(cursor, matcher.start())).setStyle(stack.peek()));
            String tag = matcher.group();
            if (tag.startsWith("<t")) out.append(terms.get(Integer.parseInt(tag.substring(2, tag.indexOf('>')))).copy());
            else if (tag.startsWith("<n")) out.append(numbers.get(Integer.parseInt(tag.substring(2, tag.length() - 2))).copy());
            else if (tag.startsWith("</s")) stack.pop();
            else stack.push(styles.get(Integer.parseInt(tag.substring(2, tag.length() - 1))));
            cursor = matcher.end();
        }
        if (cursor < translated.length()) out.append(Component.literal(translated.substring(cursor)).setStyle(stack.peek()));
        return out;
    }
    private static Component copyNativeSnapshot(Component snapshot) {
        var copy = Component.empty();
        snapshot.visit((style, text) -> {
            if (!text.isEmpty()) copy.append(Component.literal(text).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return copy;
    }
    private static int displayId(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException e) { return Integer.MAX_VALUE; } }
    /** Provisional display: tolerate malformed markup without leaking tags or throwing. */
    public Component decodeForDisplay(String translated) {
        if (translated == null || translated.isBlank()) return decode(encoded);
        try { return decode(translated); } catch (IllegalArgumentException ignored) {}
        var out = Component.empty();
        var stack = new ArrayDeque<Style>(); stack.push(base);
        var seenNumbers = new HashSet<Integer>(); var seenTerms = new HashSet<Integer>();
        var tags = Pattern.compile("<t(\\d+)>([^<>]*)</t\\1>|<n(\\d+)/>|</?([st])(\\d+)>").matcher(translated);
        int cursor = 0;
        while (tags.find()) {
            if (tags.start() > cursor) out.append(Component.literal(translated.substring(cursor, tags.start())).setStyle(stack.peek()));
            if (tags.group(1) != null) {
                int id = displayId(tags.group(1));
                if (id < terms.size()) { out.append(terms.get(id).copy()); seenTerms.add(id); }
                else out.append(Component.literal(tags.group(2)).setStyle(stack.peek()));
            } else if (tags.group(3) != null) {
                int id = displayId(tags.group(3));
                if (id < numbers.size()) { out.append(numbers.get(id).copy()); seenNumbers.add(id); }
            } else if (tags.group(4).equals("s")) {
                int id = displayId(tags.group(5));
                if (tags.group().startsWith("</")) { if (stack.size() > 1) stack.pop(); }
                else if (id < styles.size() && translated.indexOf("</s" + id + ">", tags.end()) >= 0) stack.push(styles.get(id));
            }
            cursor = tags.end();
        }
        if (cursor < translated.length()) out.append(Component.literal(translated.substring(cursor)).setStyle(stack.peek()));
        if (seenNumbers.size() < numbers.size() || seenTerms.size() < terms.size())
            out.append(Component.literal("\n原文: ").setStyle(base)).append(decode(encoded));
        return out;
    }
}
