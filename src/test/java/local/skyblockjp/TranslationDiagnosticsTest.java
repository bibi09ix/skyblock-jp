package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranslationDiagnosticsTest {
    @TempDir Path dir;

    @Test
    void classifiesFailures() {
        assertTrue(
                TranslationDiagnostics.validate("Gain <n0/> Health", "体力が増加")
                        .contains("missing={<n0/>=1}"));
        assertTrue(
                TranslationDiagnostics.validate("Gain 5 Health", "体力が50増加")
                        .startsWith("NUMBER_MISMATCH"));
        assertTrue(TranslationDiagnostics.validate("Hello", "Hello").startsWith("NO_JAPANESE"));
        assertTrue(TranslationDiagnostics.validate("Hello", "").startsWith("EMPTY_RESPONSE"));
        assertTrue(
                TranslationDiagnostics.validate("<s0>Hello</s0>", "</s0>こんにちは<s0>")
                        .startsWith("MARKER_NESTING"));
        assertNull(TranslationDiagnostics.validate("Gain <n0/> Health", "体力が<n0/>増加"));
    }

    @Test
    void writesContextAndResponseWithoutSecret() throws Exception {
        Path log = dir.resolve("failures.jsonl");
        TranslationDiagnostics.append(
                log,
                Map.of(
                        "context",
                        "item=Sword; lines=3-4",
                        "response",
                        "bad-secret",
                        "source",
                        "Gain Health",
                        "reason",
                        "bad"),
                "bad-secret");
        String saved = Files.readString(log);
        assertFalse(saved.contains("bad-secret"));
        assertTrue(saved.contains("Sword"));
        assertTrue(saved.contains("REDACTED"));
        assertEquals("Bearer [REDACTED]", TranslationDiagnostics.redact("Bearer abc-123", ""));
    }
}
