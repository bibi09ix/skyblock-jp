package local.skyblockjp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Local, bounded, opt-in diagnostic export. This class never opens configuration or chat logs. */
final class SharedLogExporter {
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    static final int MAX_LINE_BYTES = 1024 * 1024;
    static final int MAX_LINES_PER_FILE = 20_000;
    private static final List<String> LOG_NAMES = List.of("translation-display.jsonl", "translation-pipeline.jsonl",
        "npc-dictionary-decisions.jsonl", "dictionary-translations.jsonl");
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Deliberately contains no provider model, endpoint, API key, repository URL, or local path. */
    record Metadata(String modVersion, String minecraftVersion, String fabricLoaderVersion, String javaVersion,
                    String dictionaryBuildId, boolean apiTranslation, boolean dictionaryEnabled,
                    boolean dictionaryAutoDisplay, boolean dictionaryDiagnostics, boolean dictionaryPlainFallback) {}
    record ExportResult(Path zipPath, int exportedRecords, int excludedRecords, int malformedRecords,
                        int truncatedFiles, int omittedFields) {
        boolean hasWarnings() { return excludedRecords > 0 || malformedRecords > 0 || truncatedFiles > 0 || omittedFields > 0; }
    }
    private SharedLogExporter() {}

    static ExportResult export(Path logs, Path out, Metadata info, Collection<String> secrets) throws IOException {
        if (logs == null || out == null || info == null) throw new IOException("Invalid export request");
        checkInterrupted();
        var sanitizer = new SharedLogSanitizer(secrets);
        prepareDirectories(logs, out);
        String unique = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
            + "-" + UUID.randomUUID();
        Path target = out.resolve("skyblock-jp-shared-logs-" + unique + ".zip");
        Path temporary = out.resolve("skyblock-jp-shared-logs-" + unique + ".zip.part");
        var totals = new Totals(); var files = new JsonArray();
        boolean completed = false;
        try {
            try (var raw = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 var zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
                for (String name : LOG_NAMES) {
                    snapshot(logs, name + ".1", sanitizer, totals, files, zip);
                    snapshot(logs, name, sanitizer, totals, files, zip);
                }
                JsonObject manifest = manifest(info, sanitizer, totals, files);
                writeEntry(zip, "manifest.json", JSON.toJson(manifest) + "\n");
                writeEntry(zip, "README.txt", README);
            }
            // Never replace an earlier export. A random basename is generated independently of metadata.
            checkInterrupted();
            // ATOMIC_MOVE has implementation-defined replacement behavior if target exists.
            // The default move publishes the finished file with an explicit no-replace contract.
            Files.move(temporary, target);
            completed = true;
            return new ExportResult(target, totals.exported, totals.excluded, totals.malformed, totals.truncated, totals.omitted);
        } catch (IOException | RuntimeException e) {
            // No source snippets, provider failures, secrets or private filesystem paths in outward errors.
            throw new IOException("Shared log export could not be completed");
        } finally {
            if (!completed) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Only our own unique temp file. */ }
        }
    }

    private static void snapshot(Path logs, String name, SharedLogSanitizer sanitizer, Totals totals,
                                 JsonArray files, ZipOutputStream zip) throws IOException {
        Path source = logs.resolve(name);
        checkInterrupted();
        var report = new JsonObject(); report.addProperty("file", name);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            report.addProperty("status", Files.exists(source, LinkOption.NOFOLLOW_LINKS) ? "not_regular_file" : "missing");
            files.add(report); return;
        }
        int beforeExported = totals.exported, beforeExcluded = totals.excluded, beforeMalformed = totals.malformed,
            beforeOmitted = totals.omitted;
        boolean limited = false, entryStarted = false;
        long consumed = 0, snapshotSize = 0;
        try (SeekableByteChannel channel = Files.newByteChannel(source, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            snapshotSize = channel.size();
            long budget = Math.min(snapshotSize, Math.min(MAX_FILE_BYTES, MAX_TOTAL_BYTES - totals.bytes));
            if (budget < snapshotSize) limited = true;
            if (budget <= 0 && snapshotSize > 0) {
                report.addProperty("status", "byte_limit");
            } else {
                try { zip.putNextEntry(new ZipEntry("logs/" + name)); entryStarted = true; }
                catch (IOException e) { throw new ArchiveWriteException(); }
                // Buffered stream may prefetch, but only bytes within the opening size/budget are parsed.
                try (var input = new BufferedInputStream(Channels.newInputStream(channel), 16_384)) {
                    var line = new ByteArrayOutputStream(1024); boolean tooLong = false; int lines = 0;
                    while (consumed < budget && lines < MAX_LINES_PER_FILE) {
                        if ((consumed & 0x3fff) == 0) checkInterrupted();
                        int value = input.read();
                        if (value < 0) { limited = consumed < budget; break; }
                        consumed++;
                        if (value == '\n') {
                            lines++;
                            if (tooLong) totals.malformed++;
                            else process(line.toByteArray(), name, lines, sanitizer, totals, zip);
                            line.reset(); tooLong = false;
                        } else if (!tooLong) {
                            if (line.size() >= MAX_LINE_BYTES) { tooLong = true; line.reset(); }
                            else line.write(value);
                        }
                    }
                    if (lines >= MAX_LINES_PER_FILE && consumed < budget) limited = true;
                    if (line.size() > 0 || tooLong) {
                        // An incomplete last line is not a snapshot/replay record. A complete final JSON
                        // at real EOF is allowed even if the logger has not added its trailing newline.
                        if (tooLong) totals.malformed++;
                        else if (consumed == snapshotSize && !limited)
                            process(line.toByteArray(), name, lines + 1, sanitizer, totals, zip);
                        else { totals.malformed++; limited = true; }
                    }
                    report.addProperty("linesScanned", lines);
                }
                report.addProperty("status", limited ? "bounded_snapshot" : "snapshot");
            }
        } catch (InterruptedIOException | ArchiveWriteException e) { throw e;
        } catch (IOException e) {
            // A rotation or a concurrent truncate can make one file unavailable; later files remain useful.
            limited = true; report.addProperty("status", "unavailable_during_snapshot");
        } finally {
            if (entryStarted) zip.closeEntry();
        }
        totals.bytes += consumed;
        if (limited) totals.truncated++;
        report.addProperty("snapshotBytes", snapshotSize); report.addProperty("bytesScanned", consumed);
        report.addProperty("exportedRecords", totals.exported - beforeExported);
        report.addProperty("excludedRecords", totals.excluded - beforeExcluded);
        report.addProperty("malformedRecords", totals.malformed - beforeMalformed);
        report.addProperty("omittedFields", totals.omitted - beforeOmitted);
        report.addProperty("truncated", limited); files.add(report);
    }

    private static void process(byte[] bytes, String file, int line, SharedLogSanitizer sanitizer,
                                Totals totals, ZipOutputStream zip) throws IOException {
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { totals.malformed++; return; }
        if (text.isBlank()) return;
        checkInterrupted();
        JsonObject record;
        try { record = SharedLogSanitizer.parse(text); }
        catch (IllegalArgumentException e) { totals.malformed++; return; }
        var safe = sanitizer.sanitize(file, record);
        if (safe == null) { totals.excluded++; return; }
        safe.record().addProperty("exportOriginalLine", line);
        byte[] output = (JSON.toJson(safe.record()) + "\n").getBytes(StandardCharsets.UTF_8);
        try { zip.write(output); } catch (IOException e) { throw new ArchiveWriteException(); }
        totals.exported++; totals.omitted += safe.omittedFields();
    }

    private static JsonObject manifest(Metadata metadata, SharedLogSanitizer sanitizer, Totals totals, JsonArray files) {
        var root = new JsonObject(); root.addProperty("format", "skyblock-jp-shared-diagnostics"); root.addProperty("schemaVersion", 1);
        root.addProperty("createdAt", Instant.now().toString()); root.addProperty("automaticUpload", false);
        var runtime = new JsonObject();
        runtime.addProperty("modVersion", safeMetadata(metadata.modVersion(), false, sanitizer));
        runtime.addProperty("minecraftVersion", safeMetadata(metadata.minecraftVersion(), false, sanitizer));
        runtime.addProperty("fabricLoaderVersion", safeMetadata(metadata.fabricLoaderVersion(), false, sanitizer));
        runtime.addProperty("javaVersion", safeMetadata(metadata.javaVersion(), false, sanitizer));
        runtime.addProperty("dictionaryBuildId", safeMetadata(metadata.dictionaryBuildId(), true, sanitizer));
        runtime.addProperty("apiTranslation", metadata.apiTranslation()); runtime.addProperty("dictionaryEnabled", metadata.dictionaryEnabled());
        runtime.addProperty("dictionaryAutoDisplay", metadata.dictionaryAutoDisplay()); runtime.addProperty("dictionaryDiagnostics", metadata.dictionaryDiagnostics());
        runtime.addProperty("dictionaryPlainFallback", metadata.dictionaryPlainFallback()); root.add("runtime", runtime);
        var bounds = new JsonObject(); bounds.addProperty("maxBytesPerFile", MAX_FILE_BYTES); bounds.addProperty("maxTotalBytes", MAX_TOTAL_BYTES);
        bounds.addProperty("maxBytesPerLine", MAX_LINE_BYTES); bounds.addProperty("maxLinesPerFile", MAX_LINES_PER_FILE);
        bounds.addProperty("maxJsonDepth", 24); bounds.addProperty("maxJsonNodes", 50_000); root.add("limits", bounds);
        root.addProperty("exportedRecords", totals.exported); root.addProperty("excludedRecords", totals.excluded);
        root.addProperty("malformedRecords", totals.malformed); root.addProperty("truncatedFiles", totals.truncated);
        root.addProperty("omittedFields", totals.omitted); root.addProperty("bytesScanned", totals.bytes); root.add("files", files);
        root.addProperty("sourceTextPolicy", "Retained text is unchanged; unsafe records are excluded, never redacted. No source-hash recomputation.");
        root.addProperty("replayPolicy", "Observed display is not original source. Require original completeness and verify hashes before replay. Native interaction metadata is omitted; original style fingerprints may cover omitted metadata.");
        root.addProperty("privacy", "Player-chat channels and contexts are excluded. NPC/item text can contain player names; this archive is not fully anonymous. Review before sharing.");
        return root;
    }
    private static String safeMetadata(String value, boolean allowEmpty, SharedLogSanitizer sanitizer) {
        if (value != null && (allowEmpty && value.isEmpty() || value.matches("[A-Za-z0-9][A-Za-z0-9._+:-]{0,127}"))
            && !sanitizer.unsafeText(value)) return value;
        return "unknown";
    }
    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    private static final class Totals { int exported, excluded, malformed, truncated, omitted; long bytes; }
    private static final class ArchiveWriteException extends IOException {
        ArchiveWriteException() { super("Archive output is unavailable"); }
    }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Shared log export was interrupted");
    }
    private static void prepareDirectories(Path logs, Path out) throws IOException {
        try {
            // Check ancestors too: a junction/reparse point is not necessarily isSymbolicLink().
            if (Files.exists(logs, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS) || !ordinaryDirectory(logs)))
                throw new IOException("Export directory is unavailable");
            if (Files.isSymbolicLink(out)) throw new IOException("Export directory is unavailable");
            Files.createDirectories(out);
            if (!Files.isDirectory(out, LinkOption.NOFOLLOW_LINKS) || !ordinaryDirectory(out))
                throw new IOException("Export directory is unavailable");
        } catch (IOException | RuntimeException e) {
            // Files.createDirectories failure messages normally contain the private absolute path.
            throw new IOException("Export directory is unavailable");
        }
    }
    private static boolean ordinaryDirectory(Path path) throws IOException {
        return !Files.isSymbolicLink(path) && path.toAbsolutePath().normalize().equals(path.toRealPath());
    }

    private static final String README = """
        SkyBlock JP 共有用ログ / Shared diagnostic logs

        This ZIP was created locally at the user's request. Nothing was uploaded automatically.
        Only saved translation-display, translation-pipeline, npc-dictionary-decisions and
        dictionary-translations JSONL files, plus their .1 rotations, were considered.
        Configuration, API keys, translation caches, chat/HUD/API logs and latest.log are not copied.
        Each supported record is checked for chat origin and credentials, then projected onto
        an explicit field allowlist. Unsafe/unknown-schema records and malformed lines are skipped.
        Click/hover/insertion/nativeStyle payloads and raw API error messages are omitted.

        NPC/item text may contain player names. This is NOT a fully anonymous archive.
        Review its contents before sharing it. It contains only already saved records, which
        may span multiple versions and sessions. Missing files or bounded/incomplete snapshots
        are described with counts in manifest.json; original log files were not modified.

        Source/translation strings, RGB/style flags, line indexes and hash/completeness fields
        of retained records are not rewritten. exportOriginalLine is the physical input line.
        Native interaction metadata is removed, so original style fingerprints may include
        data absent from this archive. Verify source hashes and completeness before replay.
        UNMATCHED_DISPLAY/observedDisplayLines is an output observation, not original source.
        Schema 3 MATCHED_ORIGINAL/originalLines is a positively identified source snapshot.
        STATE_SUMMARY contains the cumulative observationCount of one STATE_CHANGE; do not
        add both counts. Repeated coordinates are coalesced; first/last observation times remain.
        NPC_TRANSLATION_STATUS contains only linked trace/outcome metadata, never API bodies.
        Records already marked as redacted in the saved log are excluded; no missing data is
        reconstructed and no hashes are recomputed to disguise an altered source.
        """;
}
