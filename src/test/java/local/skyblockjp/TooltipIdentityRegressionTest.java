package local.skyblockjp;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TooltipIdentityRegressionTest {
    private static final TooltipContext.Decision ITEM = new TooltipContext.Decision(TooltipContext.Kind.ITEM, "fixture", Map.of("skyblockItemId", "TEST"));
    private static String key(List<Component> lines) {
        var analysis = LoreParser.analyze(lines.stream().map(Component::getString).toList(), ITEM);
        return TooltipTranslationKey.of(ITEM, lines.getFirst().getString(), analysis.paragraphs().stream().map(LoreParser.Paragraph::channel).toList(),
            analysis.paragraphs().stream().map(p -> new StyledParagraph(lines.subList(p.block().start(),p.block().end()))).toList());
    }
    @Test void dynamicMetadataDoesNotOrphanAnAlreadyCompletedManualTranslation() {
        List<Component> before = List.of(Component.literal("Fixture Item"), Component.literal("Charges: 20/20"),Component.literal(""),Component.literal("Creates a harmless sparkle."));
        List<Component> after = List.of(Component.literal("Fixture Item"), Component.literal("Charges: 19/20"),Component.literal(""),Component.literal("Creates a harmless sparkle."));
        assertEquals(key(before), key(after));
        var blocks = LoreParser.parse(after.stream().map(Component::getString).toList());
        var paragraphs = blocks.stream().map(b -> new StyledParagraph(after.subList(b.start(),b.end()))).toList();
        var config = new Config(); config.candidateDictionary = false;
        var result = ItemTooltipTranslation.prepare(after,blocks,paragraphs,config,Set.of(key(before)).contains(key(after)),p->null,p->"無害な輝きを生み出す。",List::of);
        assertEquals(ItemTooltipTranslation.Mode.FULL,result.mode());
        assertEquals("Charges: 19/20",result.lines().get(1).getString());
        assertEquals("無害な輝きを生み出す。",result.lines().getLast().getString());
    }
    @Test void changedDescriptionOrItemNameDoesNotInheritTheManualRequest() {
        List<Component> a=List.of(Component.literal("Tool A"),Component.literal("Creates a harmless sparkle."));
        assertNotEquals(key(a),key(List.of(Component.literal("Tool B"),a.getLast())));
        assertNotEquals(key(a),key(List.of(a.getFirst(),Component.literal("Consumes a rare crystal."))));
    }
    @Test void expiredOrEvictedResultsDoNotPretendWorkIsStillPending() {
        List<Component> lines=List.of(Component.literal("Item"),Component.literal("Creates a harmless sparkle."));
        var blocks=LoreParser.parse(lines.stream().map(Component::getString).toList());
        var paragraphs=List.of(new StyledParagraph(lines.subList(1,2)));
        var config=new Config(); config.candidateDictionary=false;
        assertEquals(ItemTooltipTranslation.Mode.ORIGINAL, ItemTooltipTranslation.prepare(lines,blocks,paragraphs,config,true,p->null,p->null,List::of,p->false).mode());
        assertEquals(ItemTooltipTranslation.Mode.PENDING, ItemTooltipTranslation.prepare(lines,blocks,paragraphs,config,true,p->null,p->null,List::of,p->true).mode());
    }
    @Test void fullStyledCopiesAndAppendedFootersResolveButSharedTitlesDoNotConfuseItems() {
        var tracked=new DisplayedTooltips<String>(); var screen=new Object(); var title=Component.literal("Sword");
        List<Component> first=List.of(title,Component.literal("Damage: +5"),Component.literal("First ability."));
        List<Component> second=List.of(title,Component.literal("Damage: +8"),Component.literal("Second ability."));
        tracked.put(screen,first,"first","item-a"); tracked.put(screen,second,"second","item-b");
        assertEquals("first",tracked.displayed(screen,first));
        var copy=new ArrayList<Component>(first.stream().map(Component::copy).toList()); copy.add(Component.literal("Other mod footer"));
        assertEquals("first",tracked.displayed(screen,copy));
        assertEquals("UNIQUE_STYLED_CONTENT",tracked.reason());
        copy.set(1,Component.literal("Damage: +5").withStyle(ChatFormatting.RED));
        assertNull(tracked.displayed(screen,copy));
        assertNull(tracked.displayed(new Object(),first));
    }
    @Test void ambiguousProvenanceNeverGuessesAndClearedSourcesCannotReturn() {
        var tracked=new DisplayedTooltips<String>(); var screen=new Object();
        List<Component> a=List.of(Component.literal("Same"),Component.literal("Same body."));
        List<Component> b=a.stream().map(c->(Component)c.copy()).toList();
        tracked.put(screen,a,"first","item");tracked.put(screen,b,"second","ui");
        assertNull(tracked.displayed(screen,a.stream().map(c->(Component)c.copy()).toList()));
        assertEquals("AMBIGUOUS_TOOLTIP_SOURCE",tracked.reason());
        tracked.clear();assertNull(tracked.displayed(screen,a));
    }
    @Test void laterMutationsCannotRewriteTheRecordedSourceOrDeferredObservation() {
        var tracked = new DisplayedTooltips<String>(); var screen = new Object();
        var child = Component.literal("Deals 5 damage.").withStyle(ChatFormatting.RED);
        List<Component> lines = List.of(Component.literal("Item"), Component.empty().append(child));
        tracked.put(screen, lines, "old source", "item");
        var selected = TooltipObservation.snapshot(lines);
        child.append(" Also heals 10 health.");
        assertEquals("Deals 5 damage.", selected.getLast().getString());
        assertNull(tracked.displayed(screen, lines));
        assertNull(tracked.displayed(screen, lines.stream().map(c -> (Component)c.copy()).toList()));
        assertEquals("old source", tracked.displayed(screen, selected));
    }
    @Test void snapshotsFreezeTranslationArgumentsAndMatchEquivalentTextTrees() {
        var argument = Component.literal("Original");
        List<Component> source = List.of(Component.literal("Item"), Component.translatableWithFallback("test.unknown", "Effect: %s", argument));
        var snapshot = TooltipObservation.snapshot(source);
        argument.append(" changed");
        assertEquals("Effect: Original", snapshot.getLast().getString());
        assertEquals(snapshot, TooltipObservation.snapshot(List.of(Component.literal("Item"), Component.literal("Effect: ").append("Original"))));
    }
    @Test void frozenSingleLineObservationsRetainSourceIdentityWithoutBypassingContentChecks() {
        var tracked = new DisplayedTooltips<String>(); var screen = new Object();
        List<Component> source = List.of(Component.literal("Single title"));
        tracked.put(screen, source, "source", "item");
        assertEquals("source", tracked.displayed(screen, TooltipObservation.snapshot(source), source));
        assertNull(tracked.displayed(screen, List.of(Component.literal("Changed title")), source));
        assertNull(tracked.displayed(screen, TooltipObservation.snapshot(source), List.of(Component.literal("Single title"))));
    }
    @Test void onlyTheDeferredTooltipActuallyDrawnPromotesAnObservation() {
        var seen=new ArrayList<String>();var draws=new AtomicInteger();
        Runnable first=TooltipObservation.selected(null,draws::incrementAndGet,()->seen.add("first"));
        assertSame(first,TooltipObservation.selected(first,first,()->seen.add("rejected")));
        Runnable replacement=TooltipObservation.selected(first,draws::incrementAndGet,()->seen.add("replacement"));
        replacement.run();assertEquals(List.of("replacement"),seen);assertEquals(1,draws.get());
    }
    @Test void cursorMayMoveWithinAnObservedSlotButCannotLeaveItOrUseAStaleFrame() {
        var input=new TooltipKeyInput();var screen=new Object();
        input.observe(screen,"item",40,50,100,new TooltipKeyInput.Bounds(32,48,48,64));
        assertEquals(TooltipKeyInput.Press.FIRST,input.press(screen,"item",47,63,101,true));
        input.release();assertEquals(TooltipKeyInput.Press.NONE,input.press(screen,"item",48,63,102,true));
        assertEquals("CURSOR_LEFT_SOURCE_BOUNDS",input.reason());
        input.release();assertEquals(TooltipKeyInput.Press.NONE,input.press(screen,"item",40,50,100+TooltipKeyInput.MAX_HOVER_AGE_NANOS+1,true));
        input.release();input.clearHover();assertEquals(TooltipKeyInput.Press.NONE,input.press(screen,"item",40,50,103,true));
    }
}
