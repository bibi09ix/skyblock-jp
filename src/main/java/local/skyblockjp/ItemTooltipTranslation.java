package local.skyblockjp;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;

/** Synchronous first-frame dictionary display with explicit manual API requests. */
final class ItemTooltipTranslation {
    enum Mode { ORIGINAL, DICTIONARY, PENDING, FULL }
    record Result(List<Component> lines, List<String> paragraphs, Mode mode, int complete, int total, int dictionaryMatches) {}

    static Result prepare(List<Component> original, List<LoreParser.Block> blocks, List<StyledParagraph> paragraphs,
            Config config, boolean requested, Function<StyledParagraph, String> dictionary,
            Function<StyledParagraph, String> completed,
            Function<Component, List<Component>> wrap) {
        return prepare(original, blocks, paragraphs, config, requested, dictionary, completed, wrap, p -> true);
    }
    static Result prepare(List<Component> original, List<LoreParser.Block> blocks, List<StyledParagraph> paragraphs,
            Config config, boolean requested, Function<StyledParagraph, String> dictionary,
            Function<StyledParagraph, String> completed, Function<Component, List<Component>> wrap,
            Predicate<StyledParagraph> pending) {
        boolean localOnly = !config.apiTranslation;
        boolean immediate = config.candidateDictionary && (config.dictionaryAutoDisplay || localOnly && requested);
        // Read each lookup once per display pass. A newly arriving API result cannot make
        // the readiness test disagree with the replacements or the corresponding audit.
        Map<StyledParagraph, String> local = new IdentityHashMap<>();
        if (immediate) for (var paragraph : paragraphs) local.put(paragraph, dictionary.apply(paragraph));
        var base = immediate ? ItemTranslation.prepareDictionary(original, blocks, paragraphs, local::get, wrap)
            : new ItemTranslation.Result(new ArrayList<>(original), 0, paragraphs.size(), null);
        // A dictionary miss cannot become ready later while API translation is disabled.
        // Manual G may still reveal known local paragraphs when automatic display is off.
        if (localOnly) return new Result(base.lines(), displayTexts(paragraphs, local, true),
            base.complete() > 0 ? Mode.DICTIONARY : Mode.ORIGINAL, base.complete(), base.total(), base.complete());
        if (!requested && base.ready()) return new Result(base.lines(), displayTexts(paragraphs, local, true),
            Mode.DICTIONARY, base.complete(), base.total(), base.complete());

        if (requested) {
            Map<StyledParagraph, String> values = new IdentityHashMap<>();
            for (var paragraph : paragraphs) values.put(paragraph, completed.apply(paragraph));
            var full = ItemTranslation.prepareParagraphs(original, blocks, paragraphs, values::get, wrap);
            if (full.ready()) return new Result(full.lines(), displayTexts(paragraphs, values, false), Mode.FULL,
                full.complete(), full.total(), base.complete());
            // Only already-known dictionary text may appear before a manual/API batch finishes.
            Mode mode = paragraphs.stream().anyMatch(pending) ? Mode.PENDING : base.complete() > 0 ? Mode.DICTIONARY : Mode.ORIGINAL;
            return new Result(base.lines(), displayTexts(paragraphs, local, true), mode,
                full.complete(), full.total(), base.complete());
        }
        return new Result(base.lines(), displayTexts(paragraphs, local, true),
            base.complete() > 0 ? Mode.DICTIONARY : Mode.ORIGINAL, base.complete(), base.total(), base.complete());
    }

    private static List<String> displayTexts(List<StyledParagraph> paragraphs, Map<StyledParagraph, String> values, boolean strict) {
        return paragraphs.stream().map(p -> {
            String text = values.get(p);
            try {
                if (text == null) return p.sourceText();
                if (strict && !StyledParagraph.validNumbers(p.encoded(), text)) return p.sourceText();
                return (strict ? p.decode(text) : p.decodeForDisplay(text)).getString();
            }
            catch (IllegalArgumentException e) { return p.sourceText(); }
        }).toList();
    }
}
