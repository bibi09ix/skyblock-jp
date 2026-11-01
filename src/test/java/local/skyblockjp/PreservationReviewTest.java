package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class PreservationReviewTest {
    @Test
    void reviewedOrdinaryDescriptionsAreTranslatedInsteadOfKeptAsNames() throws Exception {
        var dictionary = BundledDictionary.loadBundled();
        JsonArray rows;
        try (var input = getClass().getResourceAsStream("/preservation-review-fixtures.json")) {
            rows =
                    JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonArray();
        }
        for (var element : rows) {
            var row = element.getAsJsonObject();
            String source = row.get("source").getAsString(),
                    target = row.get("target").getAsString();
            for (String channel : List.of("item", "ui")) {
                var match = dictionary.inspect(channel, source, "").match().orElseThrow();
                assertFalse(match.keepOriginal(), source);
                assertEquals(target, match.text());
                var original =
                        new StyledParagraph(
                                List.of(Component.literal(source).withStyle(ChatFormatting.GRAY)));
                var encoded = original.dictionaryEncoding(match);
                assertNotNull(encoded.text(), source);
                assertEquals(target, original.decode(encoded.text()).getString());
            }
        }
    }
}
