package local.skyblockjp;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;
import java.util.*;

/** Client-side translation requests; original chat components remain untouched. */
public final class ChatTranslations {
    private final SkyBlockJp mod;
    private final MessageHandles handles = new MessageHandles(500);
    private final NpcMessageContexts contexts = new NpcMessageContexts(1000);
    private final LinkedHashMap<String, String> contextById = new LinkedHashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final IdentityHashMap<Component,String> idsBySource = new IdentityHashMap<>();
    private Object connection;
    private boolean output;
    ChatTranslations(SkyBlockJp mod) { this.mod = mod; }
    void reset() { handles.clear(); contexts.clear(); inFlight.clear(); contextById.clear(); idsBySource.clear(); }
    void refreshButtons() {
        var chat = Minecraft.getInstance().gui.hud.getChat();
        if (!(chat instanceof ChatAccess access)) return;
        var messages = access.skyblockjp$messages();
        for (int i = 0; i < messages.size(); i++) {
            var m = messages.get(i);
            Component original = MessageHandles.original(m.content());
            Component updated = original;
            contexts.transfer(MessageHandles.originalReference(m.content()), updated);
            messages.set(i, new net.minecraft.client.multiplayer.chat.GuiMessage(m.addedTime(), updated, m.signature(), m.source(), m.tag()));
        }
        chat.rescaleChat();
    }
    void syncConnection() { syncConnection(Minecraft.getInstance().getConnection()); }
    /** Tick/connect and every receive path share the same idempotent transition. */
    void syncConnection(Object current) {
        if (connection != current) { reset(); mod.hud().clear(); connection = current; }
    }
    public Component decorate(Component original) {
        syncConnection();
        if(!output && !LoreParser.clean(original.getString()).startsWith("[JP]")) contexts.capture(MessageHandles.originalReference(original),System.currentTimeMillis(),false);
        return original;
    }
    public void request(Component original) {
        syncConnection();
        String pending=idsBySource.get(original);
        if(pending!=null&&inFlight.contains(pending)){request(pending);return;}
        String id = handles.put(original);
        idsBySource.put(original,id);
        var occurrence=contexts.get(original);
        contextById.put(id,occurrence==null?"":occurrence.previous());
        if (contextById.size() > 500) {
            String oldest=contextById.keySet().iterator().next();contextById.remove(oldest);
            idsBySource.values().removeIf(oldest::equals);
        }
        request(id);
    }
    void npc(Component original) {
        syncConnection();
        String plain = LoreParser.clean(original.getString());
        var occurrence=contexts.capture(MessageHandles.originalReference(original),System.currentTimeMillis(),true);
        if (NpcTranslation.optionPrompt(plain)) {
            translate(NpcTranslation.whole(original), "選択肢", "", false, null);
            return;
        }
        int colon = plain.indexOf(':');
        if (colon < 0) return;
        String speaker = plain.substring(0, colon);
        String context = occurrence.previous();
        translate(NpcTranslation.paragraph(original), speaker, context, false, null);
    }
    /** Only registered fixed notifications are automatic; unknown/player text is never sent. */
    void system(Component original) {
        syncConnection();
        var paragraph = NpcTranslation.whole(original);
        var decision = mod.service().inspectDictionary(paragraph, "system", "");
        if (!decision.accepted() || decision.reason().equals("KEEP_ORIGINAL")) return;
        mod.service().recordDictionary(paragraph, "", decision.encoded(), "scope=system; notification", decision);
        display("システム", paragraph.decode(decision.encoded()));
    }
    public void request(String id) {
        syncConnection();
        Component original = handles.get(id);
        if (original == null) { notifyLocal("このチャットは履歴から外れました。翻訳したいテキストにカーソルを合わせて翻訳キーを押してください。"); return; }
        if (!mod.active()) { notifyLocal("翻訳がOFF、または翻訳対象のサーバーではありません。"); return; }
        if (!inFlight.add(id)) { notifyLocal("この行は翻訳中です。"); return; }
        Component death = ChatText.death(original);
        if (death != null) {
            inFlight.remove(id); mod.service().recordLocalChat(original, death); display("チャット", death); return;
        }
        String plain = LoreParser.clean(original.getString());
        boolean npcHeader = (plain.startsWith("[NPC]") || plain.startsWith("[BOSS]")) && plain.contains(":");
        String speaker = npcHeader ? plain.substring(0, plain.indexOf(':')) : "チャット";
        String context = contextById.getOrDefault(id, "");
        String status = mod.configuration().apiTranslation ? "翻訳中…" : "辞書を確認中…";
        mod.hud().status(status);
        if (!mod.configuration().translationHud) notifyLocal(status);
        boolean npc = npcHeader || NpcTranslation.optionPrompt(plain);
        if (NpcTranslation.optionPrompt(plain)) speaker = "選択肢";
        translate(npcHeader ? NpcTranslation.paragraph(original)
            : npc ? NpcTranslation.whole(original) : NpcTranslation.wholeChat(original), speaker, context, !npc, id);
    }
    private void translate(StyledParagraph paragraph, String speaker, String context, boolean manual, String id) {
        var mc = Minecraft.getInstance();
        var origin = mc.getConnection();
        var service = mod.service();
        var npcEvidence=Map.<String,Object>of("localPlayerName",mc.getUser().getName());
        var future = manual ? service.requestChat(paragraph, context)
            : id != null ? service.requestNpcManually(paragraph, speaker, context,npcEvidence) : service.requestNpc(paragraph, speaker, context,npcEvidence);
        future.thenAccept(result -> mc.execute(() -> {
            if (id != null) inFlight.remove(id);
            if (mc.getConnection() != origin || service != mod.service() || !mod.active()) return;
            if (result == null) {
                if (id != null && !mod.configuration().apiTranslation) {
                    mod.hud().status("API翻訳OFF: この文章の辞書訳はありません");
                    if (!mod.configuration().translationHud) notifyLocal("API翻訳OFF: この文章の辞書訳はありません。");
                }
                return;
            }
            display(speaker, paragraph.decodeForDisplay(result).copy().withStyle(ChatFormatting.WHITE));
        }));
    }
    private void display(String speaker, Component translated) {
        var mc = Minecraft.getInstance();
        if (mod.configuration().translationHud) mod.hud().add(speaker, translated);
        else {
            output = true;
            try { mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[JP] " + speaker + ": ").withStyle(ChatFormatting.AQUA).append(translated)); }
            finally { output = false; }
        }
    }
    private void notifyLocal(String text) {
        output = true;
        try { Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[JP] " + text).withStyle(ChatFormatting.GRAY)); }
        finally { output = false; }
    }
}
