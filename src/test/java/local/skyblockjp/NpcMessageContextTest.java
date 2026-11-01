package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class NpcMessageContextTest {
    private static Component message(String speaker, String body) {
        return Component.literal("[NPC] " + speaker + ": " + body);
    }

    @Test
    void identicalReceivedRepliesRetainTheirOwnBranchesForLaterManualRequests() {
        var c = new NpcMessageContexts(500);
        c.capture(message("Kuudra Loremaster", "I know so many things about Kuudra."), 0, true);
        var one = message("Kuudra Loremaster", "They are quite interesting.");
        var first = c.capture(one, 1, true);
        c.capture(
                message("Kuudra Loremaster", "I've been studying the ancient texts of Kuudra."),
                2,
                true);
        var two = message("Kuudra Loremaster", "They are quite interesting.");
        var second = c.capture(two, 3, true);
        assertNotEquals(first.occurrence(), second.occurrence());
        assertEquals("I know so many things about Kuudra.", c.get(one).previous());
        assertEquals("I've been studying the ancient texts of Kuudra.", c.get(two).previous());
        assertNull(c.get(one.copy()), "Unknown copies must not borrow context by text equality");
    }

    @Test
    void automaticAndDisplayHooksCaptureOnceInEitherOrder() {
        for (boolean firstAutomatic : List.of(true, false)) {
            var c = new NpcMessageContexts(20);
            var earlier = message("A", "Earlier");
            c.capture(earlier, 0, firstAutomatic);
            c.capture(earlier, 0, !firstAutomatic);
            var source = message("A", "Reply");
            var one = c.capture(source, 1, firstAutomatic);
            assertEquals(one, c.capture(source, 2, !firstAutomatic));
            assertEquals("Earlier", c.get(source).previous());
        }
    }

    @Test
    void reusedComponentAtASecondInsertionCreatesANewOccurrence() {
        var c = new NpcMessageContexts(20);
        var source = message("A", "Reply");
        var one = c.capture(source, 0, false);
        var two = c.capture(source, 1, false);
        assertNotEquals(one.occurrence(), two.occurrence());
    }

    @Test
    void unavailableOldOrOtherSpeakerContextIsNotInvented() {
        var c = new NpcMessageContexts(20);
        c.capture(message("A", "Earlier"), 0, true);
        assertEquals("", c.capture(message("B", "Yes!"), 1, true).previous());
        assertEquals("", c.capture(message("A", "Yes!"), 45000, true).previous());
        assertEquals("", c.capture(message("A", "Clock went backwards"), 4, true).previous());
        c.clear();
        assertEquals("", c.capture(message("A", "New connection"), 5, true).previous());
    }

    @Test
    void explicitHistoryRebuildTransfersContextWithoutAdvancingDialogue() {
        var c = new NpcMessageContexts(20);
        c.capture(message("A", "Earlier"), 0, false);
        var source = message("A", "Reply");
        var entry = c.capture(source, 1, false);
        var rebuilt = source.copy();
        c.transfer(source, rebuilt);
        assertEquals(entry, c.get(rebuilt));
        assertEquals("Reply", c.capture(message("A", "Next"), 2, false).previous());
    }

    @Test
    void copiesBetweenReceiveEventAndGuiInsertionDoNotMakeAReplyItsOwnPreviousLine() {
        var c = new NpcMessageContexts(20);
        for (String branch : List.of("First branch", "Second branch")) {
            var before = message("A", branch);
            c.capture(before, 0, true);
            c.capture(before.copy(), 0, false);
            var reply = message("A", "Same reply");
            c.capture(reply, 1, true);
            var displayed = reply.copy();
            assertEquals(branch, c.capture(displayed, 1, false).previous());
            assertEquals(branch, c.get(displayed).previous());
        }
    }

    @Test
    void receiveContextWinsForTheSameOccurrenceEvenWhenGuiMissedAHiddenLine() {
        var c = new NpcMessageContexts(20);
        var visible = message("A", "Visible");
        c.capture(visible, 0, true);
        c.capture(visible, 0, false);
        c.capture(message("A", "Hidden receive event"), 1, true);
        var reply = message("A", "Reply");
        var shown = c.capture(reply, 2, false);
        assertEquals("Visible", shown.previous());
        c.capture(reply, 2, true);
        assertEquals("Hidden receive event", c.get(reply).previous());
        assertEquals(shown.occurrence(), c.get(reply).occurrence());
    }

    @Test
    void boundedEvictionNeverReplacesOldOccurrenceWithEqualText() {
        var c = new NpcMessageContexts(1);
        var one = message("A", "Reply");
        var two = one.copy();
        c.capture(one, 0, false);
        c.capture(two, 1, false);
        assertNull(c.get(one));
        assertNotNull(c.get(two));
    }

    @Test
    void changedMessageCannotUseOrTransferItsFormerContext() {
        var c = new NpcMessageContexts(20);
        var source = Component.literal("[NPC] A: Reply");
        c.capture(source, 0, false);
        source.append(" changed");
        assertNull(c.get(source));
        var copy = source.copy();
        c.transfer(source, copy);
        assertNull(c.get(copy));
    }

    @Test
    void hoverReturnsActualParentComponentIncludingIdenticalMessagesAndLegacyWrapper() {
        var one = message("A", "Reply");
        var two = one.copy();
        var text =
                net.minecraft.util.FormattedCharSequence.forward(
                        "Reply", net.minecraft.network.chat.Style.EMPTY);
        var parent = new GuiMessage(0, two, null, null, null);
        var line = new GuiMessage.Line(parent, text, true);
        assertSame(two, ChatHover.source(List.of(line), text));
        var decorated = MessageHandles.decorate(one, "id", true);
        assertSame(decorated.getSiblings().getLast(), MessageHandles.originalReference(decorated));
    }
}
