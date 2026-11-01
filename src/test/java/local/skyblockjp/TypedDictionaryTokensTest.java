package local.skyblockjp;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TypedDictionaryTokensTest {
    private static Component t(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }
    private static StyledParagraph paragraph(String source) {
        return new StyledParagraph(List.of(t(source, ChatFormatting.GRAY)));
    }
    private static Component translate(Component source, String target) {
        var p = new StyledParagraph(List.of(source)); var encoded = p.dictionaryEncoding(target);
        assertTrue(encoded.accepted(), () -> p.sourceText() + " => " + target + " / " + encoded.reason() + encoded.details());
        return p.decode(encoded.text());
    }
    private static void reject(String source, String target, String reason) {
        var actual = paragraph(source).dictionaryEncoding(target);
        assertFalse(actual.accepted(), () -> source + " => " + target);
        assertEquals(reason, actual.reason(), () -> actual.details().toString());
    }
    private static void color(Component component, String needle, ChatFormatting expected) {
        int from = component.getString().indexOf(needle); assertTrue(from >= 0, component.getString());
        int offset = 0; var expectedStyle = t("x", expected).getStyle();
        for (var glyph : StyledParagraph.glyphs(component)) {
            if (offset >= from && offset < from + needle.length())
                assertEquals(expectedStyle.getColor(), glyph.style().getColor(), needle);
            offset += glyph.text().length();
        }
    }

    @Test void knownCommandSignaturesPreserveEveryUsernameIncludingPreviousGaps() {
        for (String prefix : List.of("/invite", "/visit", "/trade", "/duel", "/report", "/sbkick", "/coopadd",
            "/p", "/p invite", "/p kick", "/party promote", "/party transfer", "/f add", "/friend remove",
            "/ignore add", "/g invite", "/guild kick")) {
            String command = prefix + " Steve_123";
            var source = t("Use " + prefix + " ", ChatFormatting.GRAY).copy()
                .append(t("Steve_123", ChatFormatting.GOLD)).append(t(".", ChatFormatting.GRAY));
            var result = translate(source, command + "を使って。");
            assertEquals(command + "を使って。", result.getString());
            color(result, "Steve_123", ChatFormatting.GOLD);
            reject(source.getString(), prefix + " Alex_456を使って。", "COMMAND_ARGUMENT_MISMATCH");
            reject(source.getString(), prefix + "を使って。", "COMMAND_ARGUMENT_MISMATCH");
        }
    }

    @Test void unknownCommandAndSubcommandArgumentsRemainEntireNativeValues() {
        for (String command : List.of("/custom Steve --count=2 --label=\"gold ore\"", "/party mystery Steve Alex",
            "/f mystery Steve", "/msg Steve hello there", "/coop Steve Alex", "/reply yes please",
            "/custom Steve.file --path=/some/path", "/custom /nested Steve")) {
            var source = t(command, ChatFormatting.AQUA);
            assertEquals(command, translate(source, command).getString());
            var parsed = CommandTokens.parse(command);
            assertEquals(1, parsed.size()); assertEquals(command, parsed.getFirst().text());
            if (command.contains("Steve")) reject(command, command.replace("Steve", "Alex2"), "COMMAND_ARGUMENT_MISMATCH");
            else reject(command, command.replace("please", "never"), "COMMAND_ARGUMENT_MISMATCH");
        }
        reject("/custom Steve --count=2", "/custom Steve --count=3", "COMMAND_ARGUMENT_MISMATCH");
        reject("/custom Steve --count=2", "/custom Steve", "COMMAND_ARGUMENT_MISMATCH");
        reject("/invite Steve", "/invite Steve Alex", "COMMAND_ARGUMENT_MISMATCH");
        reject("/p invite Steve", "/p invite Steve Alex", "COMMAND_ARGUMENT_MISMATCH");
        reject("/p list", "/p list Steve", "COMMAND_ARGUMENT_MISMATCH");
        reject("/pets", "/pets Steve", "COMMAND_ARGUMENT_MISMATCH");
        reject("/invite Steve --unknown=Alex", "/invite Steve --unknown=Bob", "COMMAND_ARGUMENT_MISMATCH");
        reject("/custom Steve.file", "/custom Steve.other", "COMMAND_ARGUMENT_MISMATCH");
        assertEquals("/custom こんにちは", translate(t("/custom こんにちは", ChatFormatting.GRAY), "/custom こんにちは").getString());
        reject("/custom こんにちは", "/custom さようなら", "COMMAND_ARGUMENT_MISMATCH");
        assertNotNull(translate(t("Use /custom.", ChatFormatting.GRAY), "/customの後に入力する。"));
        assertNotNull(translate(t("Use /coop Steve Alex.", ChatFormatting.GRAY), "/coop Steve Alexで招待する。"));
    }

    @Test void reviewedCommandUsageProseDoesNotBecomeAUsername() {
        var cases = Map.of(
            "Allow anyone to /visit your island. If disabled, you may still /invite them.", "誰でも/visitで島を訪問できる。無効でも/inviteで招待できる。",
            "Learn all about how to /visit players across the SkyBlock universe!", "SkyBlock各地のプレイヤーを/visitで訪ねる方法を学ぼう！",
            "Alternatively, use the /sbkick & /sbkickall commands or clicking on them.", "/sbkickや/sbkickallコマンドで操作できる。",
            "Use /coop followed by the name of all the friends you want to invite!", "/coopの後に、招待したいフレンド全員の名前を入力してね！",
            "Use /ignore add Steve to ban them from visiting your island!", "/ignore add Steveで、そのプレイヤーの島への訪問を禁止できる！");
        cases.forEach((source, target) -> assertNotNull(translate(t(source, ChatFormatting.GRAY), target)));
        // These bare words can be real usernames. Only the reviewed prose above gets an exception.
        for (String user : List.of("them", "your", "players", "command", "me")) {
            reject("Use /invite " + user + ".", "/inviteを使う。", "COMMAND_ARGUMENT_MISMATCH");
            reject("Use /visit " + user + ".", "/visitを使う。", "COMMAND_ARGUMENT_MISMATCH");
        }
        reject("Use /report command.", "/reportを使う。", "COMMAND_ARGUMENT_MISMATCH");
        reject("Use /visit your island.", "/visitで島を訪問する。", "COMMAND_ARGUMENT_MISMATCH");
        assertTrue(paragraph("lowballing 3b /visit me 100m+").encoded().contains(">/visit me</t"));
    }

    @Test void commandPrefixesUrlsAndLiteralFormattingCannotChangeIdentities() {
        var source = t("Use ", ChatFormatting.GRAY).copy().append(t("/sbkick", ChatFormatting.RED))
            .append(t(" & ", ChatFormatting.GRAY)).append(t("/sbkickall", ChatFormatting.GOLD)).append(t(".", ChatFormatting.GRAY));
        var result = translate(source, "/sbkickや/sbkickallを使う。");
        color(result, "/sbkick", ChatFormatting.RED); color(result, "/sbkickall", ChatFormatting.GOLD);
        reject(source.getString(), "/sbkickallを使う。", "COMMAND_ARGUMENT_MISMATCH");
        assertTrue(CommandTokens.parse("Visit https://example.com/foo and store.hypixel.net/bar.").isEmpty());
        assertEquals(List.of("/invite Steve"), CommandTokens.parse("(Use /invite Steve.)").stream().map(CommandTokens.Token::text).toList());
        var p = new StyledParagraph(List.of(t("Party > [MVP+] Steve: Use /invite Alex.", ChatFormatting.GRAY)), true);
        assertFalse(p.dictionaryEncoding("Party > [MVP+] Bob: /invite Alexを使って。").accepted());
    }

    @Test void levelNameLabelAndValueKeepSeparateNativeColors() {
        var source = t("Reach ", ChatFormatting.GRAY).copy().append(t("SkyBlock", ChatFormatting.AQUA))
            .append(t(" Level ", ChatFormatting.YELLOW)).append(t("120", ChatFormatting.GREEN));
        var result = translate(source, "SkyBlockのレベルを120にする");
        color(result, "SkyBlock", ChatFormatting.AQUA); color(result, "レベル", ChatFormatting.YELLOW); color(result, "120", ChatFormatting.GREEN);
        for (String target : List.of("SkyBlockのレベルを121にする", "Combatのレベルを120にする", "SkyBlockを120にする"))
            reject(source.getString(), target, "TYPED_LEVEL_ROLE_MISMATCH");
        assertNotNull(translate(t("Requires Combat Skill 3!", ChatFormatting.GRAY), "Combat SkillのLevel 3が必要。"));
    }

    @Test void equalRomanLevelsUseSkillRolesInsteadOfOccurrenceOrder() {
        var source = t("Requires Combat Level ", ChatFormatting.GRAY).copy().append(t("XV", ChatFormatting.AQUA))
            .append(t(" and Mining Skill ", ChatFormatting.GRAY)).append(t("XV", ChatFormatting.GOLD)).append(t(".", ChatFormatting.GRAY));
        var result = translate(source, "MiningのSkillレベル XVとCombatレベル XVが必要。");
        var values = StyledParagraph.glyphs(result).stream().filter(g -> g.text().equals("X")).toList();
        assertEquals(t("x", ChatFormatting.GOLD).getStyle().getColor(), values.getFirst().style().getColor());
        assertEquals(t("x", ChatFormatting.AQUA).getStyle().getColor(), values.getLast().style().getColor());
        reject(source.getString(), "MiningのSkillレベル XIVとCombatレベル XVが必要。", "TYPED_LEVEL_ROLE_MISMATCH");
        var repeated = t("Combat Level ", ChatFormatting.GRAY).copy().append(t("XV", ChatFormatting.AQUA))
            .append(t(" and Combat Level ", ChatFormatting.GRAY)).append(t("XV", ChatFormatting.GOLD));
        assertTrue(TypedDictionaryTokens.bindings(repeated.getString(), "Combatレベル XVとCombatレベル XV").isEmpty());
        assertFalse(new StyledParagraph(List.of(repeated)).dictionaryEncoding("Combatレベル XVとCombatレベル XV").accepted());
    }

    @Test void bindingOffsetsNormalizeSourceButKeepLiteralTargetWhitespace() {
        String source = "Requires\u00a0Combat\u2009Level XV!", target = "条件：  Combat　レベル　XVが必要！";
        var bindings = TypedDictionaryTokens.bindings(source, target);
        assertEquals(2, bindings.size());
        for (var b : bindings) {
            assertEquals(b.sourceText(), BundledDictionary.normalize(source).substring(b.sourceStart(), b.sourceEnd()));
            assertEquals(b.targetText(), target.substring(b.targetStart(), b.targetEnd()));
        }
        assertNotNull(translate(t(source, ChatFormatting.GRAY), target));
        reject(source, target.replace("XV", "XIV"), "TYPED_LEVEL_ROLE_MISMATCH");
    }

    @Test void namedListsPreserveEveryNativeMemberAcrossReviewedSingularAliases() {
        var source = t("Increases damage dealt to ", ChatFormatting.GRAY).copy().append(t("Spiders", ChatFormatting.RED))
            .append(t(", ", ChatFormatting.GRAY)).append(t("Cave Spiders", ChatFormatting.GOLD))
            .append(t(", and ", ChatFormatting.GRAY)).append(t("Silverfish", ChatFormatting.BLUE))
            .append(t(" by ", ChatFormatting.GRAY)).append(t("40%", ChatFormatting.GREEN)).append(t(".", ChatFormatting.GRAY));
        String target = "Spider、Cave Spider、Silverfishに与えるダメージが40%増加する。";
        var result = translate(source, target);
        for (var e : Map.of("Spiders", ChatFormatting.RED, "Cave Spiders", ChatFormatting.GOLD,
            "Silverfish", ChatFormatting.BLUE, "40%", ChatFormatting.GREEN).entrySet()) color(result, e.getKey(), e.getValue());
        reject(source.getString(), target.replace("Cave Spider、", ""), "TYPED_LIST_MEMBER_MISMATCH");
        reject(source.getString(), target.replace("Silverfish", "Silverfish、Creeper"), "TYPED_LIST_MEMBER_MISMATCH");
        reject(source.getString(), target.replace("Silverfish", "Silverfish、Zombie"), "TYPED_LIST_UNKNOWN_TARGET_WORD");
        reject("Avoid Zombie. " + source.getString(), "Zombieを避ける。" + target.replace("Silverfish", "Silverfish、Zombie"), "TYPED_LIST_TARGET_WORD_COUNT");
        assertFalse(paragraph(source.getString()).dictionaryEncoding(target.replace("40%", "41%")).accepted());
        assertFalse(paragraph("Gain +10 Mining Speed.").dictionaryEncoding("Mining Fortuneが+10増加する。").accepted());
    }

    @Test void namedListVerbsAndOtherReviewedInflectionsStayTranslatable() {
        String source = "Increases Mithril Powder, Gemstone Powder, and Glacite Powder gain by +10%.";
        assertNotNull(translate(t(source, ChatFormatting.GRAY), "Mithril Powder、Gemstone Powder、Glacite Powderの獲得量が+10%増加する。"));
        reject(source, "Mithril Powder、Mithril Powder、Glacite Powderの獲得量が+10%増加する。", "TYPED_LIST_MEMBER_MISMATCH");
        assertNotNull(translate(t("Sweet Benevolence Earn +30% more Candy, Gifts and Chocolate from duplicate Rabbits during their respective events.", ChatFormatting.GRAY),
            "Sweet Benevolence それぞれのイベントで、Candy、Gift、重複Rabbitから得るChocolateの獲得量が+30%増加する。"));
        for (var alias : Map.of("quests", "Quest", "Abiphones", "Abiphone", "Potions", "Potion", "Logs", "Log").entrySet())
            assertTrue(ProtectedTerms.dictionaryEnglishAliases(alias.getKey()).contains(alias.getValue()));
    }

    @Test void ordinaryCaseHomonymsAreNotForcedIntoEnglishNames() {
        assertNotNull(translate(t("A bubbling soup filled with poisonous mushrooms.", ChatFormatting.GRAY), "毒キノコでいっぱいの、ぐつぐつ煮立ったスープ。"));
        assertNotNull(translate(t("Defenders of the Catacombs.", ChatFormatting.GRAY), "Catacombsの守護者たち。"));
        assertFalse(paragraph("Access an anvil from anywhere in SkyBlock!").dictionaryEncoding("SkyBlockのどこからでもAnvilを利用できる！").accepted());
    }
}
