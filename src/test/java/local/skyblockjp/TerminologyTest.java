package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TerminologyTest {
    private JsonObject document() throws Exception {
        try (var input = getClass().getResourceAsStream("/assets/skyblock_jp/terminology.json")) {
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    @Test
    void aliasesAndStatContextRetainTheirPreviousMeaning() {
        assertEquals(
                java.util.List.of("Guardians", "Guardian"),
                ProtectedTerms.dictionaryEnglishAliases("Guardians"));
        assertEquals(
                java.util.List.of("Forge Timers", "Forgeの残り時間"),
                ProtectedTerms.dictionaryEnglishAliases("Forge Timers"));
        assertFalse(ProtectedTerms.forText("the speed of this machine").matcher("speed").find());
        assertTrue(ProtectedTerms.forText("your speed is increased").matcher("speed").find());
        assertTrue(ProtectedTerms.forText("Chocolate Factory").matcher("Chocolate Factory").find());
        assertThrows(
                UnsupportedOperationException.class,
                () -> Terminology.entityAliases().put("Unknown", "Other"));
    }

    @Test
    void staleOrMalformedTerminologyCannotSilentlyRemoveProtection() throws Exception {
        var missing = document();
        missing.getAsJsonArray("protected_names").remove(new JsonPrimitive("Health"));
        assertThrows(IllegalArgumentException.class, () -> Terminology.parse(missing.toString()));
        var duplicate = document();
        duplicate.getAsJsonArray("protected_names").add("Health");
        assertThrows(IllegalArgumentException.class, () -> Terminology.parse(duplicate.toString()));
        var controls = document();
        controls.getAsJsonObject("entity_aliases").addProperty("Injected", "Bad\nTerm");
        assertThrows(IllegalArgumentException.class, () -> Terminology.parse(controls.toString()));
        var typed = document();
        typed.getAsJsonObject("suffix_aliases").addProperty("pets", 5);
        assertThrows(IllegalArgumentException.class, () -> Terminology.parse(typed.toString()));
    }
}
