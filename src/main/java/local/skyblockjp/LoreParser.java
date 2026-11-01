package local.skyblockjp;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Classify rows before joining paragraphs; every retained row has a named reason. */
public final class LoreParser {
    public record Block(int start, int end, String text) {}

    public record Paragraph(Block block, String channel, String role, String reason) {}

    public record LineDecision(
            int index,
            String source,
            String role,
            String action,
            String reason,
            int paragraphIndex) {}

    public record Analysis(List<Paragraph> paragraphs, List<LineDecision> lines) {}

    private record Row(
            String role, String reason, boolean translate, String channel, boolean independent) {}

    private static final String ICONS = "[\\p{Co}\\p{So}⸕⸎✦✧❤❈☠⚔✎☣♣♠α❁⫽⚕✯☘⚡❂❥❣☯\\s]*";
    private static final String SCALAR =
            "[+−-]?(?:\\d[\\d,]*(?:\\.\\d+)?|\\.\\d+)(?:%|‰|x|ms|s|m|h|d|[kKmMbBtT])?" + ICONS;
    private static final Pattern METRIC_SEQUENCE =
            Pattern.compile(SCALAR + "(?:\\s*(?:/|:|[➜→›»])\\s*" + SCALAR + ")*");
    private static final String STAT_NAMES =
            "damage|strength|crit(?:ical)? (?:chance|damage)|health|defen[sc]e|true defense|intelligence|speed|(?:bonus )?attack speed|ferocity|vitality|mending|magic find|pet luck|sea creature chance|fishing speed|mining speed|mining fortune|farming fortune|foraging fortune|pristine|breaking power|swing range|sweep|mana|ability damage|gear score|quality|item score|(?:combat|farming|mining|fishing|foraging|alchemy|enchanting|taming) wisdom|rift (?:time|damage|health|mana|speed|intelligence|strength|defense)|health regen|health regeneration|damage reduction|effective health|effective hp|charm chance|treasure chance|flat|additive|multiplicative|stat cap|base extra strikes|chance for \\d+ more|(?:max )?fuel capacity";
    private static final Pattern STAT_VALUE =
            Pattern.compile(
                    "(?i)^" + ICONS + "(?:" + STAT_NAMES + ")\\s*:?" + ICONS + "([+−-]?\\d.*)$");
    private static final Pattern VALUE_STAT =
            Pattern.compile(
                    "(?i)^[‣•●]?\\s*[+−-]?\\d[\\d,.]*%?"
                            + ICONS
                            + "(?:"
                            + STAT_NAMES
                            + ")(?:\\s*\\([^)]*\\))*\\s*$");
    private static final Pattern GENERIC_VALUE_STAT =
            Pattern.compile(
                    "^[‣•●∙▪▸►▶]?\\s*[+−-]\\d[\\d,.]*(?:[%xX])?"
                            + ICONS
                            + "([A-Z][A-Za-z'’_-]*(?: (?:[A-Z][A-Za-z'’_-]*|of|the|and))*(?: per (?:second|minute|hour|day))?)(?:\\s*\\([^)]*\\))*\\s*$");
    private static final Pattern GENERIC_LABEL_STAT =
            Pattern.compile(
                    "^"
                            + ICONS
                            + "([A-Z][A-Za-z'’_-]*(?: (?:[A-Z][A-Za-z'’_-]*|of|the|and))*)\\s+"
                            + "[+−-]?\\d[\\d,.]*(?:%|x|ms|s|m|h|d|[kKmMbBtT])?"
                            + ICONS
                            + "(?:\\([^)]*\\)\\s*)*$");
    private static final Pattern BARE_STAT =
            Pattern.compile("(?i)^" + ICONS + "(?:" + STAT_NAMES + ")(?: Stat)?\\s*:?$");
    private static final Pattern TOTAL_ROW =
            Pattern.compile(
                    "(?i)^(?:Adds up to|Total(?: Bonus(?:es)?)?|Combined Total|Sum):\\s*([+−-]?\\d.*)$");
    private static final Pattern PURE_METRIC_VALUE =
            Pattern.compile(
                    "[+−-]?\\d[\\d,.]*(?:%|x|ms|s|m|h|d|[kKmMbBtT])?"
                            + ICONS
                            + "(?:\\([^)]*\\)\\s*)*");
    private static final Pattern PROSE_ACTION =
            Pattern.compile(
                    "(?i)\\b(?:grants?|gives?|deals?|increases?|decreases?|reduces?|heals?|restores?|shoots?|teleports?|summons?|consumes?|applies?|creates?|gain|take|receive|remove|convert|boost|buffs?|explodes?|costs?|allows?|becomes?|enables?|unlocks?|kill(?:s|ing)?|collect(?:s|ing)?|deliver|talk|find|complete|visit|reach|defeat|harvest|catch|craft|break|mine|earn|obtain|upgrade|win|use|eat|drink|activate|cast|open)\\b");
    private static final Pattern PROSE_LINK =
            Pattern.compile(
                    "(?i)\\b(?:when|while|for|per|every|you|your|nearby|with|to|from|but|can|will|is|are|of|and|or|in|on|near|under|over|against|after|before|during)\\b");
    private static final Pattern ABILITY =
            Pattern.compile(
                    "(?i)^(?:[\\p{Co}\\p{So}\\p{Sm}]\\s*)*(?:(?:pickaxe|drill|axe|sword|bow|wand|pet|weapon|item) ability|(?:passive|active)(?: ability)?|ability|full set bonus|tiered bonus|piece bonus|bonus):\\s*(.*)$");
    private static final Pattern CONTROL =
            Pattern.compile(
                    "(?i)^(?:(?:Shift |Ctrl\\+)?(?:Left|Right)[ -]click(?:\\s+to|:)?|(?:Ctrl|Control|Shift|Alt)\\s*\\+\\s*Click\\b|Click\\b|Shift-click to|Also accessible via).*");
    private static final Pattern UI_LABEL =
            Pattern.compile(
                    "(?i)^(?:Selected(?: pet| class| slot)?|Current(?:ly)?|Stored|Value|Price|Reward|Next(?: [A-Za-z]+)?):.*");
    private static final Pattern RARITY =
            Pattern.compile(
                    "^(?:a\\s+)?(?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL|SUPREME)(?: DUNGEON)?(?: [A-Z][A-Z -]*)?(?:\\s+a)?$");
    private static final Pattern MAX_PROGRESS =
            Pattern.compile(
                    "(?i)^(?:Max(?:imum)?(?: [A-Za-z]+){0,3} (?:level|tier|rank)(?: reached| achieved)?[.!]?|(?:level|tier|rank) cap reached[.!]?)$");
    private static final Pattern COST_HEADING =
            Pattern.compile(
                    "(?i)^(?:Cost|Costs|Price|Ingredients|Required (?:Items|Materials)|Materials|Requirements|Requires)\\s*:?$");
    private static final Pattern MODIFIED_COST_HEADING =
            Pattern.compile(
                    "^[A-Z][A-Za-z0-9'’_-]*(?: (?:[A-Z][A-Za-z0-9'’_-]*|of|the|and)){0,4} Costs?\\s*:?$");
    private static final Pattern MATERIAL_VALUE =
            Pattern.compile(
                    "^(?:\\d[\\d,]*x?\\s+)?[A-Z][A-Za-z0-9'’™_-]*(?: (?:[A-Z][A-Za-z0-9'’™_-]*|of|the|and)){0,9}(?: x\\d[\\d,.]*)?$");
    private static final Pattern JAPANESE =
            Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");
    private static final Pattern ENCHANT =
            Pattern.compile("(?:[A-Za-z'’ -]+ [IVXLCDM]+)(?:, ?[A-Za-z'’ -]+ [IVXLCDM]+)*,?");
    private static final Pattern MULTIPLIER_ACTION =
            Pattern.compile(
                    "(?i)\\b(?:doubles?|triples?|quadruples?|halves?|multipl(?:y|ies)|divides?)\\b");
    private static final Pattern EFFECT_LEAD =
            Pattern.compile(
                    "(?i)^(?:grants?|gives?|increases?|decreases?|reduces?|take|gain|earn|buffs?|while|killing|your|materializes|tarantula|doubles?|triples?|quadruples?|halves?|multipl(?:y|ies)|divides?|zaps?|regenerates?|passively grants?|only allows?|this gives?|you have)\\b.*");
    private static final Pattern PET_CATEGORY =
            Pattern.compile(
                    "(?i)^(?:(?:Combat|Mining|Farming|Fishing|Foraging|Alchemy|Enchanting|Taming|Hunting|Carpentry|All Skills) Pet|All Skills)$");
    private static final Pattern NUMERIC_EFFECT_LEAD =
            Pattern.compile(
                    "(?i)^(?:deals?|gain|take|receive|restore|heal|regenerates?|grants?)\\s+[+−-]?(?:\\d|\\.\\d).*");
    private static final Pattern VERIFIED_BODY_ACTION =
            Pattern.compile("(?i)\\b(?:drops?|appears?|regenerates?)\\b");

    public static String clean(String s) {
        return s == null ? "" : s.replaceAll("(?i)§[0-9a-fk-or]", "").strip();
    }

    /** Numeric metric rows allow icons before the label, between the value and label, or after the value. */
    static boolean numericStat(String source) {
        String s = clean(source);
        if (totalStat(s)) return true;
        var stat = STAT_VALUE.matcher(s);
        if (stat.matches()) {
            String value = stat.group(1).replaceAll("\\([^)]*\\)", "");
            if (!PROSE_ACTION.matcher(value).find() && !PROSE_LINK.matcher(value).find())
                return true;
        }
        if (VALUE_STAT.matcher(s).matches()) return true;
        var generic = GENERIC_VALUE_STAT.matcher(s);
        if (generic.matches()) {
            String label = generic.group(1).replaceFirst(" per (?:second|minute|hour|day)$", "");
            if (!PROSE_ACTION.matcher(label).find()) return true;
        }
        var labelled = GENERIC_LABEL_STAT.matcher(s);
        if (labelled.matches() && !PROSE_ACTION.matcher(labelled.group(1)).find()) return true;
        int colon = s.indexOf(':');
        if (colon > 0 && colon <= 42) {
            String label = s.substring(0, colon), value = s.substring(colon + 1).strip();
            return metricLabel(label) && metricValue(value);
        }
        return false;
    }

    private static boolean metricLabel(String label) {
        return !PROSE_ACTION.matcher(label).find()
                || label.matches("(?:[A-Z][A-Za-z' -]* )?(?:Cost|Costs)|Next Upgrade");
    }

    private static boolean metricValue(String value) {
        var suffixes = Pattern.compile("\\(([^)]*)\\)").matcher(value);
        while (suffixes.find())
            if (PROSE_ACTION.matcher(suffixes.group(1)).find()
                    || PROSE_LINK.matcher(suffixes.group(1)).find()) return false;
        return METRIC_SEQUENCE.matcher(value.replaceAll("\\([^)]*\\)", "").strip()).matches();
    }

    private static boolean totalStat(String source) {
        var total = TOTAL_ROW.matcher(source);
        if (!total.matches()) return false;
        String value = total.group(1);
        // The payload starts with a number, so it cannot recurse into another labelled total.
        return PURE_METRIC_VALUE.matcher(value).matches() || numericStat(value);
    }

    /** Compatibility API. A true value means metadata, not that a paragraph was rejected. */
    public static boolean protectedLine(String source) {
        return !classify(clean(source), false, false).translate();
    }

    public static List<Block> parse(List<String> lines) {
        return parse(lines, false);
    }

    public static List<Block> parse(List<String> lines, boolean ui) {
        var context =
                new TooltipContext.Decision(
                        ui ? TooltipContext.Kind.UI : TooltipContext.Kind.ITEM,
                        "LEGACY_PARSE_SCOPE",
                        Map.of());
        return analyze(lines, context).paragraphs().stream().map(Paragraph::block).toList();
    }

    public static Analysis analyze(List<String> source, TooltipContext.Decision context) {
        return analyze(source, context, label -> false);
    }

    /** The supplied predicate must identify an approved literal item/label, not arbitrary title-case text. */
    public static Analysis analyze(
            List<String> source,
            TooltipContext.Decision context,
            Predicate<String> knownItemLabel) {
        if (source == null || source.isEmpty()) return new Analysis(List.of(), List.of());
        List<String> text = source.stream().map(LoreParser::clean).toList();
        boolean ui = context != null && context.kind() == TooltipContext.Kind.UI;
        boolean pet =
                text.stream().anyMatch(s -> PET_CATEGORY.matcher(s).matches())
                        || text.getFirst().matches("^\\[Lvl \\d+\\] .+");
        var rows = new ArrayList<Row>();
        boolean costSection = false;
        for (int i = 0; i < text.size(); i++) {
            String s = text.get(i);
            var metadata = TooltipMetadata.classify(text, i);
            Row row;
            if (s.isBlank()) {
                costSection = false;
                row = keep("SEPARATOR", "BLANK_LINE");
            } else if (debug(s)) {
                costSection = false;
                row = keep("DEBUG", "DEBUG_FOOTER");
            } else if (i == 0 && !ui) row = keep("ITEM_NAME", "ITEM_NAME_FIRST_LINE");
            else if (costHeading(text, i)) {
                costSection = true;
                row =
                        keep(
                                "SECTION_LABEL",
                                COST_HEADING.matcher(s).matches()
                                        ? "COST_SECTION_START"
                                        : "MODIFIED_COST_SECTION_START");
            } else if (costSection && costValue(s))
                row = keep("COST_VALUE", "COST_SECTION_CONTENT");
            else if (i > 0
                    && rows.get(i - 1).role().equals("STAT")
                    && s.matches("(?i)^and (?:\\d[\\d,]* )?more(?:\\.{1,3}|…|!)?$"))
                row = keep("STAT_TRAILER", "STAT_LIST_TRAILER");
            else if (metadata != null) {
                costSection = false;
                row = keep(metadata.role(), metadata.reason());
            } else {
                costSection = false;
                boolean usageEffect = usageEffect(text, i);
                row = classify(s, ui, usageEffect);
                // Roman numerals on a menu title are a level label, not an item's enchantment list.
                // Numeric stats, existing Japanese, debug metadata and other strong keeps still
                // win.
                if (i == 0 && ui && (row.translate() || row.role().equals("ENCHANTMENT_NAME")))
                    row =
                            context.reason().equals("MINION_LOCKED_SLOT_INSTRUCTION")
                                    ? translate(
                                            "DESCRIPTION", "VERIFIED_MENU_INSTRUCTION", "ui", true)
                                    : translate("UI_TITLE", "UI_TITLE_FIRST_LINE", "ui", true);
                else if (row.translate()
                        && !usageEffect
                        && namedAbilityHeading(text, i, pet, knownItemLabel))
                    row = keep("ABILITY_NAME", "STRUCTURED_ABILITY_HEADING");
            }
            rows.add(row);
        }
        // A wrapped numeric tail belongs to a preceding description, never to a cost or stat block.
        for (int i = 1; i < rows.size(); i++) {
            if (text.get(i).matches("#?[+−-]?[\\d,.]+(?:%|s)?[.!]?")
                    && rows.get(i - 1).translate()
                    && !rows.get(i - 1).independent()
                    && !text.get(i - 1).matches(".*[.!?:]$")
                    && !text.get(i - 1).matches("(?i)^Level\\b.*"))
                rows.set(
                        i,
                        translate(
                                rows.get(i - 1).role(),
                                "WRAPPED_NUMERIC_CONTINUATION",
                                rows.get(i - 1).channel(),
                                false));
        }
        var paragraphs = new ArrayList<Paragraph>();
        LineDecision[] decisions = new LineDecision[text.size()];
        for (int i = 0; i < rows.size(); ) {
            Row row = rows.get(i);
            if (!row.translate()) {
                decisions[i] = line(i, text, row, "KEEP", -1);
                i++;
                continue;
            }
            int end = i + 1;
            while (end < rows.size()) {
                Row next = rows.get(end);
                boolean wrappedLabel =
                        next.role().equals("DESCRIPTION_LABEL")
                                && row.role().equals("DESCRIPTION")
                                && text.get(end - 1)
                                        .matches(
                                                "(?i).*\\b(?:a|an|the|of|to|in|on|for|from|with|within)\\s*$");
                if (!next.translate()
                        || (next.independent() && !wrappedLabel)
                        || row.role().equals("UI_TITLE")
                        || row.role().equals("DESCRIPTION_LABEL")) break;
                if (row.role().equals("UI_CONTROL") && text.get(end - 1).matches(".*[.!?]$")) break;
                // Physical wrapping of a control prompt may continue with ordinary prose.
                if (!next.channel().equals(row.channel()) && !row.role().equals("UI_CONTROL"))
                    break;
                if (text.get(end).matches("^(?:[‣•●◼➜➤▸►▶]\\s*|-\\s+).*")
                        || text.get(end).matches("^\\d+ Combo \\(lasts [\\d.]+s\\)$")
                        || text.get(i).matches("^\\d+ Combo \\(lasts [\\d.]+s\\)$")) break;
                // These are two complete effects, not a physical wrap of one sentence.
                // A following bare value/name or an ordinary continuation still joins.
                if (NUMERIC_EFFECT_LEAD.matcher(text.get(end)).matches()
                        && NUMERIC_EFFECT_LEAD.matcher(text.get(end - 1)).matches()
                        && !text.get(end - 1)
                                .matches(
                                        "(?i).*\\b(?:and|or|to|for|per|of|from|against|by|with)\\s*$"))
                    break;
                end++;
            }
            String joined = String.join(" ", text.subList(i, end)).replaceAll("\\s+", " ");
            boolean english = joined.replaceAll("[^A-Za-z]", "").length() >= 3;
            if (english) {
                int paragraphIndex = paragraphs.size();
                paragraphs.add(
                        new Paragraph(
                                new Block(i, end, joined),
                                row.channel(),
                                row.role(),
                                row.reason()));
                for (int n = i; n < end; n++)
                    decisions[n] =
                            line(
                                    n,
                                    text,
                                    rows.get(n),
                                    n == i ? "TRANSLATE" : "JOIN",
                                    paragraphIndex);
            } else
                for (int n = i; n < end; n++)
                    decisions[n] =
                            line(
                                    n,
                                    text,
                                    keep("NON_TARGET", "INSUFFICIENT_ENGLISH_TEXT"),
                                    "KEEP",
                                    -1);
            i = end;
        }
        return new Analysis(List.copyOf(paragraphs), List.of(decisions));
    }

    /**
     * An additive retry after the existing whole paragraph returned not_found.
     * The caller must keep an accepted whole paragraph and must inspect every
     * child with its native styles before replacing it. The predicate licenses
     * only a strict literal Recipe translation preserving the entire item name;
     * it must not be a fuzzy, normalized, or substring dictionary search.
     */
    public static List<Paragraph> independentRecipeRows(
            List<String> source,
            TooltipContext.Decision context,
            Paragraph whole,
            Predicate<String> exactRecipeTranslation) {
        if (source == null
                || context == null
                || whole == null
                || exactRecipeTranslation == null
                || !whole.role().equals("DESCRIPTION")
                || !Set.of("item", "ui").contains(whole.channel())) return List.of();
        var evidence = context.evidence();
        if (!"CONTAINER".equals(evidence.get("location"))
                || !Boolean.TRUE.equals(evidence.get("slotMatches"))) return List.of();
        Object rawTitle = evidence.get("containerTitle");
        if (!(rawTitle instanceof String title)
                || !clean(title)
                        .replaceFirst("^\\(\\d+/\\d+\\)\\s*", "")
                        .matches("(?i).+ Collections?$")) return List.of();
        Block block = whole.block();
        if (block.start() < 1 || block.end() > source.size() || block.end() - block.start() < 2)
            return List.of();
        List<String> text = source.stream().map(LoreParser::clean).toList();
        int previous = block.start() - 1;
        while (previous >= 0 && text.get(previous).isBlank()) previous--;
        if (previous < 0 || !text.get(previous).matches("(?:.+ )?Rewards:")) return List.of();
        String joined =
                String.join(" ", text.subList(block.start(), block.end())).replaceAll("\\s+", " ");
        if (!joined.equals(block.text())) return List.of();
        var result = new ArrayList<Paragraph>();
        for (int i = block.start(); i < block.end(); i++) {
            String line = text.get(i);
            if (!line.matches(".+ Recipe") || !exactRecipeTranslation.test(line)) return List.of();
            result.add(
                    new Paragraph(
                            new Block(i, i + 1, line),
                            whole.channel(),
                            "RECIPE_LIST_ENTRY",
                            "VERIFIED_INDEPENDENT_RECIPE_ROW"));
        }
        return List.copyOf(result);
    }

    /** Update diagnostic paragraph indexes after an already-validated additive partition. */
    public static Analysis replaceParagraphs(
            Analysis source, Map<Integer, List<Paragraph>> replacements) {
        if (source == null || replacements == null || replacements.isEmpty()) return source;
        for (var key : replacements.keySet())
            if (key == null || key < 0 || key >= source.paragraphs().size())
                throw new IllegalArgumentException("Unknown replacement paragraph");
        var paragraphs = new ArrayList<Paragraph>();
        var byOldIndex = new HashMap<Integer, List<Integer>>();
        for (int i = 0; i < source.paragraphs().size(); i++) {
            Paragraph original = source.paragraphs().get(i);
            List<Paragraph> children = replacements.getOrDefault(i, List.of(original));
            if (children.isEmpty())
                throw new IllegalArgumentException("A partition cannot drop source text");
            int cursor = original.block().start();
            var indexes = new ArrayList<Integer>();
            for (Paragraph child : children) {
                if (child == null
                        || child.block().start() != cursor
                        || child.block().end() <= cursor
                        || child.block().end() > original.block().end()
                        || !child.channel().equals(original.channel()))
                    throw new IllegalArgumentException(
                            "Partition has a gap, overlap, or changed scope");
                indexes.add(paragraphs.size());
                paragraphs.add(child);
                cursor = child.block().end();
            }
            if (cursor != original.block().end())
                throw new IllegalArgumentException("Partition does not cover complete source");
            byOldIndex.put(i, List.copyOf(indexes));
        }
        var lines = new ArrayList<LineDecision>();
        for (LineDecision line : source.lines()) {
            if (line.paragraphIndex() < 0) {
                lines.add(line);
                continue;
            }
            int index =
                    byOldIndex.get(line.paragraphIndex()).stream()
                            .filter(
                                    i -> {
                                        Block b = paragraphs.get(i).block();
                                        return line.index() >= b.start() && line.index() < b.end();
                                    })
                            .findFirst()
                            .orElseThrow(
                                    () -> new IllegalArgumentException("Line outside partition"));
            Paragraph p = paragraphs.get(index);
            boolean replaced = replacements.containsKey(line.paragraphIndex());
            lines.add(
                    new LineDecision(
                            line.index(),
                            line.source(),
                            replaced ? p.role() : line.role(),
                            replaced
                                    ? (line.index() == p.block().start() ? "TRANSLATE" : "JOIN")
                                    : line.action(),
                            replaced ? p.reason() : line.reason(),
                            index));
        }
        return new Analysis(List.copyOf(paragraphs), List.copyOf(lines));
    }

    private static LineDecision line(
            int index, List<String> text, Row row, String action, int paragraph) {
        return new LineDecision(
                index, text.get(index), row.role(), action, row.reason(), paragraph);
    }

    private static Row keep(String role, String reason) {
        return new Row(role, reason, false, "", false);
    }

    private static Row translate(String role, String reason, String channel, boolean independent) {
        return new Row(role, reason, true, channel, independent);
    }

    private static boolean costValue(String source) {
        return !CONTROL.matcher(source).matches()
                && !PROSE_ACTION.matcher(source).find()
                && (MATERIAL_VALUE.matcher(source).matches()
                        || metricValue(source)
                        || source.matches(
                                "(?i)^[\\d,.]+(?:[kmb])? (?:Coins?|Motes|Bits|Copper|Essence|Gems|Tokens)$"));
    }

    private static boolean costHeading(List<String> lines, int index) {
        String source = lines.get(index);
        if (COST_HEADING.matcher(source).matches()) return true;
        if (!MODIFIED_COST_HEADING.matcher(source).matches() || index + 1 >= lines.size())
            return false;
        String value = lines.get(index + 1);
        // A modified heading needs an immediately following resource value, not another effect
        // sentence.
        return MATERIAL_VALUE.matcher(value).matches() && !PROSE_ACTION.matcher(value).find()
                || value.matches(
                        "(?i)^[\\d,.]+(?:[kmb])? (?:Coins?|Motes|Bits|Copper|Essence|Gems|Tokens)$");
    }

    private static Row classify(String s, boolean ui, boolean usageEffect) {
        if (s.isBlank()) return keep("SEPARATOR", "BLANK_LINE");
        if (debug(s)) return keep("DEBUG", "DEBUG_FOOTER");
        if (JAPANESE.matcher(s).find()) return keep("ALREADY_TRANSLATED", "JAPANESE_TEXT_PRESENT");
        if (s.matches(
                "(?i)^(?:NPC (?:Sell Price|Buy Price|Price)|SELL PRICE|BUY PRICE|BAZAAR(?: (?:Buy Price|Sell Price|Buy Order|Sell Offer|Price))?|AH(?: Price| Sales)?|LOWEST BIN(?: PRICE)?|MUSEUM(?:\\s*\\([^)]*\\)| VALUE)?):.*"))
            return keep("MARKET_METADATA", "MARKET_OR_MUSEUM_METADATA");
        if (s.equals("Sell Price"))
            return translate("DESCRIPTION_LABEL", "MARKET_LABEL_ONLY", ui ? "ui" : "item", true);
        if (COST_HEADING.matcher(s).matches()) return keep("SECTION_LABEL", "COST_SECTION_START");
        if (s.matches(
                        "(?i)^(?:Cost|Price|Ingredients|Required (?:Items|Materials)|Materials|Requirements):.*")
                && !effectClause(s.substring(s.indexOf(':') + 1)))
            return keep("COST_VALUE", "COST_LABEL_VALUE");
        if (s.matches(
                "(?i)^[\\d,.]+(?:[kmb])? (?:Coins?|Motes|Bits|Copper|Essence|Gems|Tokens)(?:\\b.*)?$"))
            return keep("COST_VALUE", "CURRENCY_VALUE");
        if (s.matches(
                "(?i)^(?:Mana Cost|Soulflow Cost|Cooldown|Duration|Cast Time|Charges):\\s*[+−-]?[\\d,.]+(?:[:/]\\d+)*(?:[a-z%\\p{Co} ]+)?[.!]?$"))
            return keep("DURATION_OR_COST", "NUMERIC_DURATION_OR_ABILITY_COST");
        if (s.matches(
                "(?i)^(?:Level|Tier|Rank)\\s+(?:\\d+|[IVXLCDM]+)\\s*[➜→›»]\\s*(?:\\d+|[IVXLCDM]+)[.!]?$"))
            return keep("PROGRESS", "LEVEL_TRANSITION_VALUE");
        if (MAX_PROGRESS.matcher(s).matches()
                || s.matches(
                        "(?i)^(?:MAX LEVEL(?:.*)?|Progress to Level (?:\\d+|[IVXLCDM]+):.*|Your SkyBlock Level:.*|Next Milestone:.*|Total Chocolate Production Boosts)$")
                || s.matches(
                        "^[▸▶►•\\s]*[\\d,.]+(?:[kKmMbB])?(?:/[\\d,.]+(?:[kKmMbB])?)?\\s*(?:XP|EXP)?$")
                || s.matches("^\\d+ Score:.*")) return keep("PROGRESS", "PROGRESS_OR_XP_ROW");
        if (totalStat(s)) return keep("STAT", "STAT_TOTAL_ROW");
        if (numericStat(s)) return keep("STAT", "NUMERIC_STAT_ROW");
        if (BARE_STAT.matcher(s).matches())
            return keep(
                    "STAT_LABEL",
                    s.matches("(?i).* Stat\\s*:?$") ? "STAT_TYPE_LABEL" : "BARE_STAT_LABEL");
        if (RARITY.matcher(s).matches()) return keep("RARITY", "RARITY_LINE");
        if (s.matches("(?i)^[*\\s]*(?:CO-OP )?SOULBOUND[*\\s]*$"))
            return keep("BINDING", "SOULBOUND_MARKER");
        if (PET_CATEGORY.matcher(s).matches()
                || s.matches(
                        "(?:Minion Fuel|Minion Upgrade|Brewing Ingredient|Collection Item|Precursor Relic|Reforge Stone|Hunting Tool|Fishing Tool|Farming Tool|Mining Tool|Basic Brew|Consumable|Weapon|Lava Rod)"))
            return keep("ITEM_TYPE", "ITEM_CATEGORY_LABEL");
        if (s.matches("^Selected class:\\s*(?:Berserk|Healer|Mage|Tank|Archer|None)$"))
            return keep("ITEM_METADATA", "SELECTED_CLASS_VALUE");
        if (s.matches("^[\\p{Co}\\s]+[A-Z][A-Za-z]+$") && !PROSE_ACTION.matcher(s).find())
            return keep("TYPE_LABEL", "ICON_TYPE_LABEL");
        if (s.matches(
                "(?i)^[A-Za-z' -]+ \\((?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL)\\):$"))
            return keep("STAT_LABEL", "REFORGE_RARITY_LABEL");
        if (s.matches(
                        "(?i)^(?:Gemstones|Fuel|Current Bonus|Applicable on|Apply Cost|Held Item|Fuel Tank|Drill Engine|Upgrade Module|Secured):.*")
                || s.matches("(?i)^Breaking Power [0-9]+$")
                || s.matches("(?i)^[A-Za-z' -]+ Slayer: (?:LVL|Level) \\d+$")
                || s.matches("^(?:Berserk|Healer|Mage|Tank|Archer):.*"))
            return keep("ITEM_METADATA", "ITEM_STATE_OR_COMPONENT");
        if (s.matches("(?i)^(?:Reforge(?: Stats| Bonus| Cost)?|Enchantments?|Upgrades?):.*")
                && !PROSE_ACTION.matcher(s.substring(s.indexOf(':') + 1)).find())
            return keep("ITEM_METADATA", "REFORGE_OR_UPGRADE_METADATA");
        if (s.matches("^At [\\d,.]+ (?:Accessory|Magical) Power:$")
                || s.matches("^(?:Unique )?Power Bonus:$")
                || s.matches("^(?:Fig|Mangrove|Helix) Tree:.*"))
            return keep("STAT_LABEL", "STAT_SECTION_LABEL");
        if (namedBullet(s)) return keep("NAME_LIST", "NAMED_BULLET_ENTRY");
        var ability = ABILITY.matcher(s);
        if (ability.matches() && !inlineEffect(ability.group(1)))
            return keep("ABILITY_NAME", "EXPLICIT_ABILITY_HEADING");
        if (ENCHANT.matcher(s).matches() && !PROSE_ACTION.matcher(s).find())
            return keep("ENCHANTMENT_NAME", "ENCHANTMENT_LIST");
        if (s.matches("^[A-Z][A-Za-z' -]*(?:Fuel Tank|Drill Engine|Goblin Egg Part)$"))
            return keep("COMPONENT_NAME", "INSTALLED_COMPONENT_NAME");
        if (s.matches("(?i)^Requires (?:[A-Za-z]+ )?(?:Level |Tier )?[IVXLCDM0-9]+[.!]?$"))
            return keep("REQUIREMENT", "LEVEL_REQUIREMENT");
        var metadata = TooltipMetadata.classify(s);
        if (metadata != null) return keep(metadata.role(), metadata.reason());
        if (usageEffect)
            return translate("DESCRIPTION", "USAGE_EFFECT_PROSE", ui ? "ui" : "item", false);
        if (s.equals("Combinable in Anvil"))
            return translate(
                    "DESCRIPTION_LABEL", "ANVIL_OPERATION_LABEL", ui ? "ui" : "item", true);
        if (CONTROL.matcher(s).matches())
            return translate("UI_CONTROL", "CONTROL_PROMPT", "ui", true);
        if (UI_LABEL.matcher(s).matches())
            return translate("UI_LABEL", "UI_LABEL_VALUE", "ui", true);
        int colon = s.indexOf(':');
        if (colon > 0
                && colon <= 60
                && s.substring(0, colon).matches("[A-Z][A-Za-z'’ -]*")
                && (!PROSE_ACTION.matcher(s.substring(0, colon)).find()
                        || s.substring(0, colon)
                                .matches("[A-Z][A-Za-z'’ -]+ (?:Buff|Bonus|Upgrade|Cost)"))) {
            String value = s.substring(colon + 1).strip();
            if (!value.isEmpty()
                    && MATERIAL_VALUE.matcher(value).matches()
                    && !PROSE_ACTION.matcher(value).find()
                    && !PROSE_LINK.matcher(value).find())
                return keep("ITEM_METADATA", "NAMED_LABEL_VALUE");
            if (value.isEmpty() && !effectClause(s.substring(0, colon)))
                return translate(
                        "DESCRIPTION_LABEL", "ISOLATED_UNKNOWN_LABEL", ui ? "ui" : "item", true);
        }
        if (!s.matches(".*[A-Za-z].*")) return keep("NON_TARGET", "NO_ENGLISH_TEXT");
        return translate("DESCRIPTION", "PROSE_CANDIDATE", ui ? "ui" : "item", false);
    }

    private static boolean debug(String s) {
        return s.matches("(?i)^(?:minecraft:|skyblock:|JP:).*")
                || s.matches("(?i)^(?:Minecraft|Hypixel_skyblock)$")
                || s.matches("(?i)^\\d+\\s*(?:個のコンポーネント|components?)$");
    }

    private static boolean namedBullet(String s) {
        var bullet = Pattern.compile("^[‣•●∙▪▸►▶-]\\s+(.+)$").matcher(s);
        if (!bullet.matches()) return false;
        String value = bullet.group(1);
        return value.matches(
                        "[A-Z][A-Za-z0-9'’_-]*(?: (?:[A-Z][A-Za-z0-9'’_-]*|of|the|and|a|an|in)){0,7}")
                && !PROSE_ACTION.matcher(value).find();
    }

    private static boolean usageEffect(List<String> lines, int index) {
        String s = lines.get(index);
        if (!CONTROL.matcher(s).matches()) return false;
        // A consume/use clause joined to an effect is item prose, including a wrapped "and gain
        // ...".
        if (s.matches("(?i).*(?:consume|use|activate|eat|drink)\\b.*\\band\\b.*")) return true;
        return s.matches("(?i).*(?:consume|use|activate|eat|drink)\\b.*")
                && index + 1 < lines.size()
                && lines.get(index + 1)
                        .matches(
                                "(?i)^(?:and\\s+)?(?:gain|grants?|receive|restore|boost|increase|reduce|become)\\b.*");
    }

    private static boolean namedAbilityHeading(
            List<String> lines, int index, boolean pet, Predicate<String> knownItemLabel) {
        if (index == 0 || index + 1 >= lines.size()) return false;
        String s = lines.get(index), next = lines.get(index + 1);
        // Avoid dictionary/template lookup for ordinary wrapped prose on every
        // tooltip row. Only a bounded, title-shaped section candidate needs it.
        if (s.length() > 100
                || !s.matches(
                        "(?:[^A-Za-z0-9\\s]+\\s+)?[A-Z][A-Za-z'-]*(?: (?:[A-Z][A-Za-z'-]*|of|the|and)){0,5}"))
            return false;
        boolean boundary = index == 1 || lines.get(index - 1).isBlank();
        boolean petTransition = false;
        if (!boundary && pet) {
            Row previous = classify(lines.get(index - 1), false, false);
            petTransition = !previous.translate() || lines.get(index - 1).matches(".*[.!?]$");
        }
        if ((!boundary && !petTransition) || next.isBlank()) return false;
        boolean verified = knownItemLabel != null && knownItemLabel.test(s);
        if (!boundary && !verified) return false;
        Row following = classify(next, false, false);
        if (!following.translate() || following.role().equals("UI_CONTROL")) return false;
        StringBuilder body = new StringBuilder();
        for (int at = index + 1; at < lines.size() && at <= index + 8; at++) {
            if (lines.get(at).isBlank() || !classify(lines.get(at), false, false).translate())
                break;
            if (!body.isEmpty()) body.append(' ');
            body.append(lines.get(at));
        }
        // Exact approved labels supply stronger evidence than capitalization.
        // Wrapped clauses may put their action in the second or later row.
        if (verified
                && (PROSE_ACTION.matcher(body).find()
                        || PROSE_LINK.matcher(body).find()
                        || VERIFIED_BODY_ACTION.matcher(body).find())) return true;
        if (s.matches("(?i)^(?:A|An|The|This|Your|You)\\b.*")) return false;
        // Action words can also occur in names (for example Teleport Savvy).
        // Only an approved dictionary label can override that ambiguity. A
        // generic title-case clause such as Gain Health must remain prose.
        if ((PROSE_ACTION.matcher(s).find() || MULTIPLIER_ACTION.matcher(s).find()) && !verified)
            return false;
        // Title case alone is insufficient: a following explicit effect supplies the structural
        // evidence.
        return (pet && PROSE_ACTION.matcher(body).find()) || EFFECT_LEAD.matcher(next).matches();
    }

    private static boolean inlineEffect(String suffix) {
        String withoutClick = suffix.replaceAll("(?i)\\b(?:RIGHT|LEFT) CLICK\\b", " ").strip();
        var action =
                Pattern.compile(PROSE_ACTION.pattern() + "|(?i)\\bdamage\\b").matcher(withoutClick);
        if (!action.find()) return false;
        String tail = withoutClick.substring(action.end()).strip();
        return !tail.isEmpty()
                && (tail.matches(".*\\d.*")
                        || tail.matches(".*[.!?].*")
                        || tail.matches("^[a-z].*"));
    }

    private static boolean effectClause(String value) {
        return PROSE_ACTION.matcher(value).find()
                || value.matches("(?i).*\\b(?:when|while|you|your|is|are|will|can)\\b.*");
    }

    public static boolean numbersPreserved(String original, String translated) {
        return numbers(original).equals(numbers(translated));
    }

    private static List<String> numbers(String text) {
        var values = new ArrayList<String>();
        var matcher = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?").matcher(text);
        while (matcher.find()) values.add(matcher.group().replace(",", ""));
        Collections.sort(values);
        return values;
    }
}
