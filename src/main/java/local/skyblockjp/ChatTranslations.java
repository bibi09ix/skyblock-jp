package local.skyblockjp;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;
import java.util.*;

/** Client-side message handles; button commands never contain the chat text. */
public final class ChatTranslations {
    private final SkyBlockJp mod;
    private final MessageHandles handles = new MessageHandles(500);
    private final Map<String, Previous> previous = new HashMap<>();
    private final LinkedHashMap<String, String> contextBySource = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> contextById = new LinkedHashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private Object connection;
    private boolean output;
    private record Previous(String text, long time) {}
    ChatTranslations(SkyBlockJp mod) { this.mod = mod; }
    void reset() { handles.clear(); previous.clear(); inFlight.clear(); contextBySource.clear(); contextById.clear(); }
    void refreshButtons() {
        var chat = Minecraft.getInstance().gui.hud.getChat();
        if (!(chat instanceof ChatAccess access)) return;
        var messages = access.skyblockjp$messages();
        for (int i = 0; i < messages.size(); i++) {
            var m = messages.get(i);
            Component original = MessageHandles.original(m.content());
            boolean translated = LoreParser.clean(original.getString()).startsWith("[JP]");
            Component updated = translated ? original : decorate(original);
            messages.set(i, new net.minecraft.client.multiplayer.chat.GuiMessage(m.addedTime(), updated, m.signature(), m.source(), m.tag()));
        }
        chat.rescaleChat();
    }
    private void connectionChanged() {
        Object current = Minecraft.getInstance().getConnection();
        if (connection != current) { reset(); mod.hud().clear(); connection = current; }
    }
    public Component decorate(Component original) {
        connectionChanged();
        if (output || !mod.active() || !mod.configuration().chatButtons || original.getString().isBlank()) return original;
        String id = handles.put(original);
        contextById.put(id, contextBySource.getOrDefault(original.getString(), ""));
        if (contextById.size() > 500) contextById.remove(contextById.keySet().iterator().next());
        return MessageHandles.decorate(original, id, mod.configuration().chatButtonFirst);
    }
    void npc(Component original) {
        connectionChanged();
        String plain = LoreParser.clean(original.getString());
        if (NpcTranslation.optionPrompt(plain)) {
            translate(NpcTranslation.whole(original), "選択肢", "", false, null);
            return;
        }
        int colon = plain.indexOf(':');
        if (colon < 0) return;
        String speaker = plain.substring(0, colon);
        long now = System.currentTimeMillis();
        Previous last = previous.get(speaker);
        String context = last != null && now - last.time() < 45000 ? last.text() : "";
        contextBySource.put(original.getString(), context);
        if (contextBySource.size() > 500) contextBySource.remove(contextBySource.keySet().iterator().next());
        if (previous.size() > 100) previous.clear();
        previous.put(speaker, new Previous(plain.substring(colon + 1).strip(), now));
        translate(NpcTranslation.paragraph(original), speaker, context, false, null);
    }
    public void request(String id) {
        connectionChanged();
        Component original = handles.get(id);
        if (original == null) { notifyLocal("このチャットは履歴から外れました。新しい行の「訳」を押してください。"); return; }
        if (!mod.active()) { notifyLocal("翻訳がOFF、または翻訳対象のサーバーではありません。"); return; }
        if (!inFlight.add(id)) { notifyLocal("この行は翻訳中です。"); return; }
        Component death = ChatText.death(original);
        if (death != null) {
            inFlight.remove(id); mod.service().recordLocalChat(original, death); display("チャット", death); return;
        }
        String plain = LoreParser.clean(original.getString());
        String speaker = plain.startsWith("[NPC]") && plain.contains(":") ? plain.substring(0, plain.indexOf(':')) : "チャット";
        String context = contextById.getOrDefault(id, "");
        mod.hud().status("翻訳中…");
        if (!mod.configuration().translationHud) notifyLocal("翻訳中…");
        translate(speaker.startsWith("[NPC]") ? NpcTranslation.paragraph(original) : NpcTranslation.whole(original), speaker, context, true, id);
    }
    private void translate(StyledParagraph paragraph, String speaker, String context, boolean manual, String id) {
        var mc = Minecraft.getInstance();
        var origin = mc.getConnection();
        var service = mod.service();
        var future = manual ? service.requestChat(paragraph, context) : service.requestNpc(paragraph, speaker, context);
        future.thenAccept(result -> mc.execute(() -> {
            if (id != null) inFlight.remove(id);
            if (mc.getConnection() != origin || service != mod.service() || !mod.active()) return;
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
