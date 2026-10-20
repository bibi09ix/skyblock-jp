package local.skyblockjp;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.*;
import org.slf4j.LoggerFactory;

/** Public GitHub release assets only. No credentials, API keys, chat or player data are sent. */
final class AutoUpdater {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("skyblock_jp");
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile String status = "配布先は未設定です";
    private volatile boolean staged;
    private boolean installerLaunched;
    private long generation;
    String status() { return status; }
    boolean staged() { return staged; }
    static String bundledRepository() {
        try (var input = AutoUpdater.class.getResourceAsStream("/skyblock-jp-update.properties")) {
            if (input == null) return "";
            var p = new Properties(); p.load(input); return p.getProperty("repository", "").strip();
        } catch (IOException e) { return ""; }
    }
    static String repository(String configured) {
        String repo = configured == null || configured.isBlank() ? bundledRepository() : configured.strip();
        if (!repo.isEmpty() && !repo.matches("[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("GitHubの owner/repository を入力してください");
        return repo;
    }
    static URI manifestUri(String repo) {
        return URI.create("https://github.com/" + repo + "/releases/latest/download/skyblock-jp-26.2.json");
    }
    record Release(String version, String minecraft, String url, String sha256) {}
    static boolean newer(String remote, String current) {
        if (!remote.matches("\\d+\\.\\d+\\.\\d+") || !current.matches("\\d+\\.\\d+\\.\\d+")) return false;
        var a = remote.split("\\."); var b = current.split("\\.");
        for (int n = 0; n < 3; n++) {
            int c = new java.math.BigInteger(a[n]).compareTo(new java.math.BigInteger(b[n]));
            if (c != 0) return c > 0;
        }
        return false;
    }
    static Release parse(String json, String repo, String minecraft) {
        var release = new Gson().fromJson(json, Release.class);
        if (release == null || !minecraft.equals(release.minecraft()) || !"26.2".equals(minecraft))
            throw new IllegalArgumentException("Minecraftバージョンが一致しません");
        URI uri = URI.create(release.url());
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getUserInfo() != null
            || uri.getPort() != -1 || !uri.getPath().startsWith("/" + repo + "/releases/download/")
            || !uri.getPath().endsWith(".jar") || uri.getQuery() != null || uri.getFragment() != null
            || release.sha256() == null || !release.sha256().matches("[a-f0-9]{64}")
            || !release.version().matches("\\d+\\.\\d+\\.\\d+"))
            throw new IllegalArgumentException("更新ファイルの情報が不正です");
        return release;
    }
    static void verifyJar(Path jar, Release release) throws Exception {
        if (!UpdateInstaller.hash(jar).equals(release.sha256())) throw new IOException("SHA-256 mismatch");
        try (var zip = new JarFile(jar.toFile())) {
            var entry = zip.getJarEntry("fabric.mod.json");
            if (entry == null || entry.getSize() > 65536) throw new IOException("Invalid mod metadata");
            JsonObject meta;
            try (var stream = zip.getInputStream(entry)) {
                byte[] bytes = stream.readNBytes(65537);
                if (bytes.length > 65536) throw new IOException("Oversized mod metadata");
                meta = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            }
            if (!meta.get("id").getAsString().equals("skyblock_jp") || !meta.get("version").getAsString().equals(release.version())
                || !Set.of("26.2", "~26.2").contains(meta.getAsJsonObject("depends").get("minecraft").getAsString()))
                throw new IOException("Wrong mod or Minecraft version");
        }
    }
    private static byte[] fetch(HttpClient http, URI uri, int limit) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).header("User-Agent", "SkyBlock-JP-Updater").GET().build();
        var response = http.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), limit))
            .orTimeout(60, java.util.concurrent.TimeUnit.SECONDS).join();
            if (response.statusCode() != 200 || !"https".equals(response.uri().getScheme())) throw new IOException("Update HTTP " + response.statusCode());
            return response.body();
    }
    synchronized void cancelPending() throws IOException {
        generation++;
        Path plan = FabricLoader.getInstance().getConfigDir().resolve("skyblock_jp/updates/pending.properties");
        Files.deleteIfExists(plan); staged = false; status = "自動更新はOFFです";
    }
    synchronized void check(Config config, boolean manual) {
        if (!manual && !config.autoUpdate) { status = "自動更新はOFFです"; return; }
        final String repo;
        try { repo = repository(config.updateRepository); }
        catch (IllegalArgumentException e) { status = e.getMessage(); return; }
        if (repo.isEmpty()) { status = "配布先は未設定です"; return; }
        if (installerLaunched && !staged) { status = "更新を停止しました。次回起動時に再確認します"; return; }
        if (staged || !checking.compareAndSet(false, true)) return;
        long startedGeneration = generation;
        status = "最新版を確認中…";
        Thread.ofVirtual().name("SkyBlock-JP-Update").start(() -> {
            try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
                var loader = FabricLoader.getInstance(); var mod = loader.getModContainer("skyblock_jp").orElseThrow();
                String current = mod.getMetadata().getVersion().getFriendlyString();
                String minecraft = loader.getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                var release = parse(new String(fetch(http, manifestUri(repo), 65536), java.nio.charset.StandardCharsets.UTF_8), repo, minecraft);
                if (!newer(release.version(), current)) { status = "最新版です（" + current + "）"; return; }
                var paths = mod.getOrigin().getPaths();
                Path mods = loader.getGameDir().resolve("mods").toRealPath();
                if (paths.size() != 1) throw new IOException("Unsupported mod layout");
                Path target = paths.getFirst().toRealPath();
                if (!target.getParent().equals(mods) || !target.toString().endsWith(".jar")) throw new IOException("Mod is not a regular JAR in mods");
                Path base = loader.getConfigDir().resolve("skyblock_jp/updates"); Files.createDirectories(base);
                Path candidate = base.resolve("candidate.download");
                Files.write(candidate, fetch(http, URI.create(release.url()), 32 * 1024 * 1024));
                verifyJar(candidate, release);
                if (!config.autoUpdate && !manual) { status = "自動更新はOFFです"; return; }
                synchronized (this) {
                if (generation != startedGeneration) return;
                Path helper = base.resolve("installer.jar");
                try (var output = new JarOutputStream(Files.newOutputStream(helper)); var input = UpdateInstaller.class.getResourceAsStream("UpdateInstaller.class")) {
                    if (input == null) throw new IOException("Installer missing");
                    output.putNextEntry(new JarEntry("local/skyblockjp/UpdateInstaller.class")); input.transferTo(output); output.closeEntry();
                }
                var p = new Properties();
                p.setProperty("mods", mods.toString()); p.setProperty("target", target.toString());
                p.setProperty("staged", candidate.toRealPath().toString()); p.setProperty("sha256", release.sha256());
                p.setProperty("oldSha256", UpdateInstaller.hash(target)); p.setProperty("version", release.version());
                Path plan = base.resolve("pending.properties");
                try (var output = Files.newOutputStream(plan)) { p.store(output, "SkyBlock JP verified update"); }
                String exe = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
                Path java = Path.of(System.getProperty("java.home"), "bin", exe);
                new ProcessBuilder(java.toString(), "-cp", helper.toAbsolutePath().toString(), "local.skyblockjp.UpdateInstaller", plan.toAbsolutePath().toString(), Long.toString(ProcessHandle.current().pid()))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(base.resolve("installer.log").toFile())).start();
                installerLaunched = true;
                staged = true; status = "新版 " + release.version() + " を準備済み。終了後に更新します";
                LOG.info("SkyBlock JP update verified and staged: {}", release.version());
                }
            } catch (Exception e) {
                status = "更新を確認できませんでした（今の版はそのまま使えます）";
                LOG.warn("SkyBlock JP updater failed ({})", e.getClass().getSimpleName());
            } finally { checking.set(false); }
        });
    }
}
