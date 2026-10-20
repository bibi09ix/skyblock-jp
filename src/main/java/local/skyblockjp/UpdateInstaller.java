package local.skyblockjp;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Standalone JDK-only helper, extracted into its own JAR so it never locks the mod being replaced. */
public final class UpdateInstaller {
    static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) return;
        Path plan = Path.of(args[0]).toAbsolutePath().normalize();
        ProcessHandle.of(Long.parseLong(args[1])).ifPresent(parent -> parent.onExit().join());
        if (!Files.exists(plan)) return; // Cancelled from settings before the game exited.
        for (int n = 0; n < 30; n++) {
            try { apply(plan); return; }
            catch (IOException locked) { if (n == 29) throw locked; Thread.sleep(500); }
        }
    }
    static void apply(Path plan) throws Exception {
        var p = new Properties();
        try (var input = Files.newInputStream(plan)) { p.load(input); }
        Path base = plan.toRealPath().getParent();
        Path mods = Path.of(p.getProperty("mods")).toRealPath();
        Path target = Path.of(p.getProperty("target")).toRealPath();
        Path staged = Path.of(p.getProperty("staged")).toRealPath();
        if (!target.getParent().equals(mods) || !target.getFileName().toString().endsWith(".jar")
            || !staged.getParent().equals(base)) throw new IOException("Invalid update paths");
        if (!hash(target).equals(p.getProperty("oldSha256"))) {
            Files.move(plan, base.resolve("skipped-changed-install.properties"), StandardCopyOption.REPLACE_EXISTING);
            return; // A manual replacement takes priority.
        }
        if (!hash(staged).equals(p.getProperty("sha256"))) throw new IOException("Staged update checksum mismatch");
        Path backups = base.resolve("backups"); Files.createDirectories(backups);
        Path backup = backups.resolve("skyblock-jp-" + System.currentTimeMillis() + ".jar.disabled");
        Files.copy(target, backup);
        try {
            try { Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException failed) {
            if (!Files.exists(target)) Files.copy(backup, target);
            throw failed;
        }
        Files.move(plan, base.resolve("last-installed.properties"), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("SkyBlock JP update installed; backup: " + backup.getFileName());
    }
}
