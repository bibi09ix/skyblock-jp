package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class NpcDictionaryRoutingTest {
    @Test
    void bossNpcAndChoicesAreRecognizedWithoutTreatingPlayersAsNpcs() {
        assertTrue(NpcTranslation.automaticMessage("[BOSS] Arachne: I will be back!"));
        assertTrue(NpcTranslation.automaticMessage("[NPC] Banker: Welcome!"));
        assertTrue(NpcTranslation.automaticMessage("Select an option: [Yes] [No]"));
        assertFalse(NpcTranslation.automaticMessage("[MVP+] Banker: Welcome!"));
        assertFalse(NpcTranslation.automaticMessage("[NPC] Banker:"));
    }

    @Test
    void onlyTheActualChatPathProtectsThePlayerHeader() {
        var ui = NpcTranslation.whole(Component.literal("Rewards: Earn more Coins!"));
        assertFalse(ui.encoded().contains("<t0>Rewards: "));
        var player = NpcTranslation.wholeChat(Component.literal("Builder42: hello there!"));
        assertTrue(player.encoded().contains("Builder42: "));
        assertTrue(player.encoded().startsWith("<t"));
    }
}
