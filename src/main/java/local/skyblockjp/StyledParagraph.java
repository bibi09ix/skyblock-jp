package local.skyblockjp;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import java.util.*;
import java.util.regex.*;

/** Numbers never enter the translator. Style tags follow translated phrases. */
public final class StyledParagraph {
    static final Pattern NUMBER = Pattern.compile("[+−-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?:%|[kKmMbB](?![A-Za-z]))?");
    private static final Pattern PROTECTED = Pattern.compile(ProtectedTerms.PATTERN.pattern() + "|" + NUMBER.pattern() + "|[\\p{Co}✦✧❤❈☠⚔✎☣♣♠α❁⫽⚕✯☘⚡❂❥❣☯᠅‣•●➤■]");
    static final Pattern MARKER = Pattern.compile("<t\\d+>[^<>]*</t\\d+>|<n\\d+/>|</?s\\d+>");
    private final List<Style> styles = new ArrayList<>();
    private final List<Component> numbers = new ArrayList<>();
    private final List<Component> terms = new ArrayList<>();
    private final Style base;
    private final String encoded;
    public String encoded() { return encoded; }
    public record Glyph(String text, Style style) {}
    public static List<Glyph> glyphs(Component component) {
        List<Glyph> result = new ArrayList<>();
        component.visit((style, text) -> {
            text.codePoints().forEach(cp -> result.add(new Glyph(new String(Character.toChars(cp)), style)));
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }
    public StyledParagraph(List<Component> lines) {
        List<Glyph> glyphs = new ArrayList<>();
        for (Component line : lines) {
            List<Glyph> part = new ArrayList<>(glyphs(line));
            while (!part.isEmpty() && part.getFirst().text().isBlank()) part.removeFirst();
            while (!part.isEmpty() && part.getLast().text().isBlank()) part.removeLast();
            if (!part.isEmpty()) {
                if (!glyphs.isEmpty()) glyphs.add(new Glyph(" ", glyphs.getLast().style()));
                glyphs.addAll(part);
            }
        }
        Map<Style, Integer> weights = new LinkedHashMap<>();
        glyphs.forEach(g -> weights.merge(g.style(), g.text().length(), Integer::sum));
        // Lore prose is normally gray; colored stat names can dominate character counts.
        // Picking their color as the base used to tag every connector (and/while/in).
        base = weights.keySet().stream().filter(style -> style.getColor() != null && style.getColor().getValue() == 0xAAAAAA)
            .findFirst().orElseGet(() -> weights.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Style.EMPTY));
        StringBuilder plain = new StringBuilder();
        List<Style> charStyles = new ArrayList<>();
        for (Glyph glyph : glyphs) {
            plain.append(glyph.text());
            for (int i = 0; i < glyph.text().length(); i++) charStyles.add(glyph.style());
        }
        String text = plain.toString();
        Pattern termsForText = ProtectedTerms.forText(text);
        Matcher matcher = Pattern.compile(termsForText.pattern() + "|" + NUMBER.pattern() + "|[\\p{Co}✦✧❤❈☠⚔✎☣♣♠α❁⫽⚕✯☘⚡❂❥❣☯᠅‣•●➤■]").matcher(text);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            encodeText(out, text, charStyles, cursor, matcher.start());
            if (termsForText.matcher(matcher.group()).matches()) {
                int id = terms.size(); terms.add(component(text, charStyles, matcher.start(), matcher.end()));
                out.append("<t").append(id).append(">").append(matcher.group()).append("</t").append(id).append(">");
            } else {
                // Lowercase m after an explicit duration cue means minutes, not million.
                // Keep the numeric value/color locally while rendering the unit in Japanese.
                if (matcher.group().matches("\\d+(?:\\.\\d+)?m")
                    && (text.substring(0, matcher.start()).matches("(?is).*\\b(?:lasts|duration:)\\s*")
                        || text.substring(0, matcher.start()).matches("(?is)^Consume to boost .*\\bfor\\s*"))) {
                    numbers.add(component(text, charStyles, matcher.start(), matcher.end() - 1).copy()
                        .append(Component.literal("分").setStyle(charStyles.get(matcher.end() - 1))));
                } else numbers.add(component(text, charStyles, matcher.start(), matcher.end()));
                out.append("<n").append(numbers.size() - 1).append("/>");
            }
            cursor = matcher.end();
        }
        encodeText(out, text, charStyles, cursor, text.length());
        encoded = out.toString();
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
                out.append("<s").append(id).append(">").append(piece).append("</s").append(id).append(">");
            }
            i = j;
        }
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
