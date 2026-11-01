package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class LiteralCommandPlaceholderTest {
    @Test
    void loaderRetainsLiteralMarkersInKeysAndTargets() {
        String source = "Play with friends using /coopadd <name>!";
        String target = "/coopadd <name>でフレンドと一緒に遊ぼう！";
        var entries =
                List.of(
                        java.util.Map.of(
                                "channel",
                                "ui",
                                "speaker",
                                "",
                                "source",
                                source,
                                "target",
                                target,
                                "keep_original",
                                false,
                                "source_ids",
                                List.of("observed/ui/profile_coop")),
                        java.util.Map.of(
                                "channel",
                                "ui",
                                "speaker",
                                "",
                                "source",
                                "Rabbit #5",
                                "target",
                                "Rabbitの#5",
                                "keep_original",
                                false,
                                "source_ids",
                                List.of("observed/ui/numbered_label")));
        String json =
                new com.google.gson.Gson()
                        .toJson(
                                java.util.Map.of(
                                        "schema_version",
                                        1,
                                        "build_id",
                                        "literal-test",
                                        "tier",
                                        "candidate",
                                        "entries",
                                        entries));
        var dictionary =
                BundledDictionary.load(
                        new java.io.ByteArrayInputStream(
                                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(dictionary.stats().loaded());
        assertEquals(
                0,
                dictionary.stats().rejectedEntries(),
                dictionary.stats().rejectionReasons().toString());
        assertEquals(target, dictionary.lookup("ui", source, "").orElseThrow().text());
        assertEquals("Rabbitの#5", dictionary.lookup("ui", "Rabbit #5", "").orElseThrow().text());
        assertTrue(dictionary.lookup("ui", source.replace("<name>", "name"), "").isEmpty());
        assertTrue(dictionary.lookup("ui", "Rabbit 5", "").isEmpty());
    }

    @Test
    void nativeArgumentIsLiteralAndProtectedWithItsCommand() {
        String source = "Play with friends using /coopadd <name>!";
        assertTrue(BundledDictionary.safeText(source));
        assertEquals(source, BundledDictionary.normalize(source));
        assertNotEquals(
                BundledDictionary.normalize(source.replace("<name>", "name")),
                BundledDictionary.normalize(source));
        assertEquals("Rabbit #5", BundledDictionary.normalize("Rabbit #5"));
        assertNotEquals(
                BundledDictionary.normalize("Rabbit 5"), BundledDictionary.normalize("Rabbit #5"));
        assertEquals(
                List.of("/coopadd <name>"),
                CommandTokens.parse(source).stream().map(CommandTokens.Token::text).toList());
        assertEquals(
                List.of("/coopadd <name>"),
                CommandTokens.parse("/coopadd <name>でフレンドと一緒に遊ぼう！").stream()
                        .map(CommandTokens.Token::text)
                        .toList());
        var paragraph =
                new StyledParagraph(
                        List.of(
                                Component.literal("Play with friends using ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(
                                                Component.literal("/coopadd <name>")
                                                        .withStyle(ChatFormatting.AQUA))
                                        .append(
                                                Component.literal("!")
                                                        .withStyle(ChatFormatting.GRAY))));
        var good =
                paragraph.dictionaryEncoding(
                        new BundledDictionary.Match(
                                "/coopadd <name>でフレンドと一緒に遊ぼう！",
                                false,
                                List.of("observed/ui/profile_coop")));
        assertTrue(good.accepted(), good.reason());
        var decoded = paragraph.decode(good.text());
        assertTrue(decoded.getString().contains("/coopadd <name>"));
        for (var glyph : StyledParagraph.glyphs(decoded).subList(0, "/coopadd <name>".length()))
            assertEquals(
                    Component.literal("x").withStyle(ChatFormatting.AQUA).getStyle().getColor(),
                    glyph.style().getColor());
        var bad =
                paragraph.dictionaryEncoding(
                        new BundledDictionary.Match(
                                "/coopadd Bobでフレンドと一緒に遊ぼう！", false, List.of("bad")));
        assertFalse(bad.accepted());
    }

    @Test
    void arbitraryTagsAndPlaceholdersRemainRejected() {
        for (String text :
                List.of(
                        "Hello <name>!",
                        "Use /coopadd <script>!",
                        "Use <b>/coopadd</b>!",
                        "Use /coopadd <name onclick=run>!",
                        "Use /coopadd <name> and <img>!",
                        "Use /coopadd {name}!"))
            assertFalse(BundledDictionary.safeText(text), text);
        assertFalse(BundledDictionary.safeText("Use /coopadd &lt;name&gt;!"));
    }

    @Test
    void nativeOptionalCommandsAndPlaceholderSubcommandsRetainOnlyTheirInvocation() {
        for (String command : List.of("/calendar", "/levels", "/loadouts")) {
            assertEquals(
                    List.of(command),
                    CommandTokens.parse("Also accessible via " + command).stream()
                            .map(CommandTokens.Token::text)
                            .toList());
            assertEquals(
                    List.of(command),
                    CommandTokens.parse(command + " からも開ける。").stream()
                            .map(CommandTokens.Token::text)
                            .toList());
        }
        for (String[] pair :
                List.of(
                        new String[] {
                            "Use /coopadd <name> to play with friends!", "/coopadd <name>"
                        },
                        new String[] {
                            "Use /ignore add <username> to prevent a specific player from joining.",
                            "/ignore add <username>"
                        },
                        new String[] {
                            "You can also specify an extra argument when using /wiki <id> to search via an item ID.",
                            "/wiki <id>"
                        },
                        new String[] {
                            "Visit the Wiki using /wiki and browse the many pages and utilities.",
                            "/wiki"
                        },
                        new String[] {
                            "This item can be reclaimed! Use /reclaim to take back what's rightfully yours!",
                            "/reclaim"
                        })) {
            assertTrue(BundledDictionary.safeText(pair[0]), pair[0]);
            assertEquals(
                    List.of(pair[1]),
                    CommandTokens.parse(pair[0]).stream().map(CommandTokens.Token::text).toList(),
                    pair[0]);
        }
        // Unknown arguments still form an immutable invocation, rather than
        // being dropped because this command also has a no-argument menu form.
        assertEquals("/wiki STONE", CommandTokens.parse("Use /wiki STONE!").getFirst().text());
        assertEquals(
                "/ignore add Bob", CommandTokens.parse("Use /ignore add Bob!").getFirst().text());
        assertTrue(BundledDictionary.safeText("33,333 Chocobit Rabbits, labeled #1 to #33333!"));
        for (String text :
                List.of(
                        "Rabbit #name",
                        "Rabbit #1name",
                        "Armor #7FFFD4G",
                        "#",
                        "Use /wiki <other>")) assertFalse(BundledDictionary.safeText(text), text);
    }
}
