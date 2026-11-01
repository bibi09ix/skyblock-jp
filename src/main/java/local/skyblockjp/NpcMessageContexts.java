package local.skyblockjp;

import java.util.*;
import net.minecraft.network.chat.Component;

/** Bounded identity-based capture: identical text in two received messages is never one occurrence. */
final class NpcMessageContexts {
    record Context(long occurrence, String speaker, String previous) {}
    private record Previous(String body, long time) {}
    private static final class Entry {
        final Component source; Context context; final String text; final long receivedAt;
        boolean automatic, displayed;
        Entry(Component source, Context context,long receivedAt) { this.source=source; this.context=context;this.text=source.getString();this.receivedAt=receivedAt; }
    }
    private final int capacity;
    private final IdentityHashMap<Component, Entry> identities = new IdentityHashMap<>();
    private final Deque<Entry> order = new ArrayDeque<>();
    private final LinkedHashMap<String, Previous> automaticPrevious = new LinkedHashMap<>(16,.75f,true);
    private final LinkedHashMap<String, Previous> displayedPrevious = new LinkedHashMap<>(16,.75f,true);
    private long sequence;
    NpcMessageContexts(int capacity) { if(capacity<1)throw new IllegalArgumentException();this.capacity=capacity; }
    Context capture(Component source, long now, boolean automatic) {
        String text=LoreParser.clean(source.getString());
        String speaker="", body="";
        int colon=text.indexOf(':');
        if((text.startsWith("[NPC]")||text.startsWith("[BOSS]"))&&colon>0) {
            speaker=text.substring(0,colon);body=text.substring(colon+1).strip();
        }
        var previous=automatic?automaticPrevious:displayedPrevious;
        var last=speaker.isEmpty()?null:previous.get(speaker);
        String before=last!=null&&now>=last.time()&&now-last.time()<45000?last.body():"";
        var existing=identities.get(source);
        // Receive-event and vanilla insertion can observe the same message in either order.
        // A second observation of the same phase is a distinct insertion, even if a mod reused its Component.
        if(existing!=null && existing.text.equals(source.getString()) && now>=existing.receivedAt && now-existing.receivedAt<5000 && !(automatic?existing.automatic:existing.displayed)) {
            // An actual receive event is authoritative when hidden/filtered messages
            // make the GUI's previous line different; the identity proves this occurrence.
            if(automatic)existing.context=new Context(existing.context.occurrence(),speaker,before);
            if(automatic)existing.automatic=true;else existing.displayed=true;
            advance(previous,speaker,body,now);
            return existing.context;
        }
        var entry=new Entry(source,new Context(++sequence,speaker,before),now);
        entry.automatic=automatic;entry.displayed=!automatic;
        identities.put(source,entry);order.addLast(entry);
        advance(previous,speaker,body,now);
        trim();return entry.context;
    }
    private static void advance(LinkedHashMap<String,Previous> previous,String speaker,String body,long now) {
        if(speaker.isEmpty())return;
        previous.put(speaker,new Previous(body,now));
        if(previous.size()>100)previous.remove(previous.keySet().iterator().next());
    }
    Context get(Component source) { var e=identities.get(source);return e==null||!e.text.equals(source.getString())?null:e.context; }
    void transfer(Component before, Component after) {
        var old=identities.get(before);if(old==null||before==after||!old.text.equals(after.getString()))return;
        var next=new Entry(after,old.context,old.receivedAt);next.automatic=old.automatic;next.displayed=old.displayed;
        identities.put(after,next);order.addLast(next);trim();
    }
    private void trim() { while(order.size()>capacity){var e=order.removeFirst();if(identities.get(e.source)==e)identities.remove(e.source);} }
    void clear() { identities.clear();order.clear();automaticPrevious.clear();displayedPrevious.clear(); }
}
