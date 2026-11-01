package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoUpdateTest {
    @TempDir Path dir;

    private Path jar(Path file, String id, String version, String minecraft) throws Exception {
        Files.createDirectories(file.getParent());
        try (var out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write(
                    ("{\"id\":\""
                                    + id
                                    + "\",\"version\":\""
                                    + version
                                    + "\",\"depends\":{\"minecraft\":\""
                                    + minecraft
                                    + "\"}}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }

    private Path plan(Path mods, Path target, Path staged) throws Exception {
        var p = new Properties();
        p.setProperty("mods", mods.toAbsolutePath().toString());
        p.setProperty("target", target.toAbsolutePath().toString());
        p.setProperty("staged", staged.toAbsolutePath().toString());
        p.setProperty("sha256", UpdateInstaller.hash(staged));
        p.setProperty("oldSha256", UpdateInstaller.hash(target));
        Path plan = staged.getParent().resolve("pending.properties");
        try (var out = Files.newOutputStream(plan)) {
            p.store(out, "test");
        }
        return plan;
    }

    @Test
    void versionsNeverDowngradeOrInstallPrereleases() {
        assertTrue(AutoUpdater.newer("0.6.10", "0.6.9"));
        assertFalse(AutoUpdater.newer("0.6.4", "0.6.4"));
        assertFalse(AutoUpdater.newer("0.6.3", "0.6.4"));
        assertFalse(AutoUpdater.newer("0.7.0-beta", "0.6.4"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoUpdater.repository("https://github.com/owner/repo"));
        assertThrows(
                IllegalArgumentException.class, () -> AutoUpdater.repository("owner/../../other"));
    }

    @Test
    void manifestRejectsDifferentMinecraftHostAndRepository() throws Exception {
        String good =
                "{\"version\":\"0.6.5\",\"minecraft\":\"26.2\",\"url\":\"https://github.com/owner/repo/releases/download/v0.6.5/mod.jar\",\"sha256\":\""
                        + "a".repeat(64)
                        + "\"}";
        assertEquals("0.6.5", AutoUpdater.parse(good, "owner/repo", "26.2").version());
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoUpdater.parse(good, "owner/repo", "26.3"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoUpdater.parse(good.replace("https:", "http:"), "owner/repo", "26.2"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AutoUpdater.parse(
                                good.replace("owner/repo/releases", "stranger/repo/releases"),
                                "owner/repo",
                                "26.2"));
    }

    @Test
    void downloadedJarMustMatchHashModIdentityVersionAndMinecraft() throws Exception {
        Path file = jar(dir.resolve("candidate.download"), "skyblock_jp", "0.6.5", "~26.2");
        var release = new AutoUpdater.Release("0.6.5", "26.2", "", UpdateInstaller.hash(file));
        AutoUpdater.verifyJar(file, release);
        assertThrows(
                Exception.class,
                () ->
                        AutoUpdater.verifyJar(
                                file,
                                new AutoUpdater.Release("0.6.5", "26.2", "", "0".repeat(64))));
        jar(file, "other_mod", "0.6.5", "~26.2");
        var wrongId = new AutoUpdater.Release("0.6.5", "26.2", "", UpdateInstaller.hash(file));
        assertThrows(Exception.class, () -> AutoUpdater.verifyJar(file, wrongId));
        jar(file, "skyblock_jp", "0.6.5", "~26.3");
        var wrongMc = new AutoUpdater.Release("0.6.5", "26.2", "", UpdateInstaller.hash(file));
        assertThrows(Exception.class, () -> AutoUpdater.verifyJar(file, wrongMc));
    }

    @Test
    void installerPreservesOldJarAndConfigAndSkipsManualReplacement() throws Exception {
        Path mods = dir.resolve("mods");
        Path base = dir.resolve("config/skyblock_jp/updates");
        Path old = jar(mods.resolve("skyblock-jp.jar"), "skyblock_jp", "0.6.4", "~26.2");
        Path staged = jar(base.resolve("candidate.download"), "skyblock_jp", "0.6.5", "~26.2");
        String oldHash = UpdateInstaller.hash(old), newHash = UpdateInstaller.hash(staged);
        Path config = base.getParent().resolve("config.json");
        Files.writeString(config, "{\"groqApiKey\":\"test-only-placeholder\"}");
        String savedConfig = Files.readString(config);
        UpdateInstaller.apply(plan(mods, old, staged));
        assertEquals(newHash, UpdateInstaller.hash(old));
        assertEquals(savedConfig, Files.readString(config));
        try (var files = Files.list(base.resolve("backups"))) {
            assertEquals(oldHash, UpdateInstaller.hash(files.findFirst().orElseThrow()));
        }
        staged = jar(base.resolve("candidate.download"), "skyblock_jp", "0.6.6", "~26.2");
        Path next = plan(mods, old, staged);
        jar(old, "skyblock_jp", "0.6.7", "~26.2");
        String manual = UpdateInstaller.hash(old);
        UpdateInstaller.apply(next);
        assertEquals(manual, UpdateInstaller.hash(old));
        assertTrue(Files.exists(base.resolve("skipped-changed-install.properties")));
    }

    @Test
    void installerRejectsTamperingAndPathsOutsideMods() throws Exception {
        Path mods = dir.resolve("mods");
        Path old = jar(mods.resolve("skyblock-jp.jar"), "skyblock_jp", "0.6.4", "~26.2");
        Path staged =
                jar(dir.resolve("updates/candidate.download"), "skyblock_jp", "0.6.5", "~26.2");
        Path plan = plan(mods, old, staged);
        String hash = UpdateInstaller.hash(old);
        Files.writeString(staged, "tampered");
        assertThrows(Exception.class, () -> UpdateInstaller.apply(plan));
        assertEquals(hash, UpdateInstaller.hash(old));
        staged = jar(staged, "skyblock_jp", "0.6.5", "~26.2");
        Path elsewhere = jar(dir.resolve("outside.jar"), "skyblock_jp", "0.6.4", "~26.2");
        Path invalid = plan(mods, elsewhere, staged);
        assertThrows(Exception.class, () -> UpdateInstaller.apply(invalid));
    }

    @Test
    void standaloneHelperWaitsForParentWithoutLockingTargetJar() throws Exception {
        Path old = jar(dir.resolve("mods/skyblock-jp.jar"), "skyblock_jp", "0.6.4", "~26.2");
        Path staged =
                jar(dir.resolve("updates/candidate.download"), "skyblock_jp", "0.6.5", "~26.2");
        Path plan = plan(old.getParent(), old, staged);
        String before = UpdateInstaller.hash(old), after = UpdateInstaller.hash(staged);
        Path helper = dir.resolve("installer.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(helper));
                var input = UpdateInstaller.class.getResourceAsStream("UpdateInstaller.class")) {
            out.putNextEntry(new JarEntry("local/skyblockjp/UpdateInstaller.class"));
            input.transferTo(out);
            out.closeEntry();
            try (var parentClass =
                    UpdateTestParent.class.getResourceAsStream("UpdateTestParent.class")) {
                out.putNextEntry(new JarEntry("local/skyblockjp/UpdateTestParent.class"));
                parentClass.transferTo(out);
                out.closeEntry();
            }
        }
        String exe =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "java.exe"
                                        : "java")
                        .toString();
        // A child JVM reads stdin until closed; the installer must wait for that exact process.
        var parent =
                new ProcessBuilder(
                                exe, "-cp", helper.toString(), "local.skyblockjp.UpdateTestParent")
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
        var apply =
                new ProcessBuilder(
                                exe,
                                "-cp",
                                helper.toString(),
                                "local.skyblockjp.UpdateInstaller",
                                plan.toString(),
                                Long.toString(parent.pid()))
                        .redirectErrorStream(true)
                        .redirectOutput(dir.resolve("helper.log").toFile())
                        .start();
        try {
            assertFalse(apply.waitFor(300, TimeUnit.MILLISECONDS));
            assertEquals(before, UpdateInstaller.hash(old));
            parent.getOutputStream().close();
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS));
            assertTrue(apply.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, apply.exitValue());
            assertEquals(after, UpdateInstaller.hash(old));
        } finally {
            parent.destroyForcibly();
            apply.destroyForcibly();
        }
    }
}
