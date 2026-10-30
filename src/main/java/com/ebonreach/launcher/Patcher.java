package com.ebonreach.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Patcher {

    /** How many files to download at once. */
    private static final int WORKERS = 16;

    /** Cache of verified hashes, written into the install directory. */
    private static final String STATE_FILE = ".ebonreach-state.json";

    /**
     * One file from the manifest. {@code algo} is "SHA-256" when the manifest
     * carries a sha256 field, otherwise "MD5" (manifests from before Oct 2026).
     */
    public record FileEntry(String path, long size, String algo, String hash) {}

    /** What we last verified about a local file: its size, mtime and hash. */
    private record StateEntry(long size, long mtime, String algo, String hash) {}

    public interface Progress {
        void onFile(String path, int index, int total);
        void onBytes(long done, long total);
    }

    private final String baseUrl;
    private final Path installDir;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, StateEntry> state = new ConcurrentHashMap<>();

    public Patcher(String baseUrl, Path installDir) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.installDir = installDir;
        loadState();
    }

    public List<FileEntry> fetchManifest() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "manifest.json"))
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200)
            throw new IllegalStateException("manifest fetch failed: HTTP " + res.statusCode());

        JsonNode root = new ObjectMapper().readTree(res.body());
        List<FileEntry> out = new ArrayList<>();
        for (JsonNode n : root.get("files")) {
            String path = n.get("path").asText();
            checkSafePath(path);
            // Prefer SHA-256. MD5 only proves a download wasn't damaged; it
            // can't prove a file wasn't deliberately swapped.
            if (n.hasNonNull("sha256"))
                out.add(new FileEntry(path, n.get("size").asLong(), "SHA-256", n.get("sha256").asText()));
            else
                out.add(new FileEntry(path, n.get("size").asLong(), "MD5", n.get("md5").asText()));
        }
        return out;
    }

    /**
     * Works out which files need downloading.
     *
     * Hashing the whole install costs minutes once the client is a couple of
     * gigabytes, and almost all of it is wasted — the files have not changed
     * since we last checked. So we keep a state file recording the size, mtime
     * and verified hash of every file, and only re-hash when size or mtime has
     * moved. Anything unknown, missing or changed is hashed as before.
     */
    public List<FileEntry> findOutdated(List<FileEntry> manifest) throws Exception {
        List<FileEntry> need = new ArrayList<>();
        boolean stateChanged = false;

        for (FileEntry e : manifest) {
            Path local = installDir.resolve(e.path());

            if (!Files.exists(local)) {
                need.add(e);
                if (state.remove(e.path()) != null) stateChanged = true;
                continue;
            }

            BasicFileAttributes attr =
                    Files.readAttributes(local, BasicFileAttributes.class);
            long size = attr.size();
            long mtime = attr.lastModifiedTime().toMillis();

            if (size != e.size()) {
                need.add(e);
                continue;
            }

            StateEntry cached = state.get(e.path());
            String localHash;
            if (cached != null && cached.size() == size && cached.mtime() == mtime
                    && cached.algo().equals(e.algo())) {
                localHash = cached.hash();        // trusted, no disk read
            } else {
                // Also lands here once per file the first time the manifest
                // switches from MD5 to SHA-256, since the cached hash is MD5.
                localHash = hash(local, e.algo());
                state.put(e.path(), new StateEntry(size, mtime, e.algo(), localHash));
                stateChanged = true;
            }

            if (!localHash.equalsIgnoreCase(e.hash()))
                need.add(e);
        }

        if (stateChanged) saveState();
        return need;
    }

    /**
     * Downloads every entry, several at a time.
     *
     * Sequential downloading spends one full round trip per file before any
     * bytes arrive, which dominates the run when most files are small. Running
     * WORKERS at once overlaps that waiting.
     *
     * Progress callbacks arrive from multiple threads, so the caller must be
     * safe to call concurrently (LauncherApp is: it uses Platform.runLater).
     */
    public void download(List<FileEntry> files, Progress p) throws Exception {
        long total = files.stream().mapToLong(FileEntry::size).sum();
        AtomicLong done = new AtomicLong();
        AtomicInteger index = new AtomicInteger();
        int count = files.size();

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (FileEntry e : files) {
                futures.add(pool.submit(() -> {
                    int i = index.incrementAndGet();
                    if (p != null) p.onFile(e.path(), i, count);
                    fetchOne(e, done, total, p);
                    return null;
                }));
            }
            pool.shutdown();

            // get() rethrows the first failure, wrapped in ExecutionException.
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
            saveState();
        }
    }

    /** Fetches one file to a .part, verifies its checksum, then renames it. */
    private void fetchOne(FileEntry e, AtomicLong done, long total, Progress p)
            throws Exception {
        Path target = resolveInside(installDir, e.path());
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + e.path().replace(" ", "%20")))
                .GET().build();
        HttpResponse<InputStream> res =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200)
            throw new IllegalStateException("download failed " + e.path()
                    + ": HTTP " + res.statusCode());

        try (InputStream in = res.body();
             var out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                long d = done.addAndGet(r);
                if (p != null) p.onBytes(d, total);
            }
        }

        String got = hash(tmp, e.algo());
        if (!got.equalsIgnoreCase(e.hash())) {
            Files.deleteIfExists(tmp);
            throw new IllegalStateException("checksum mismatch on " + e.path());
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);

        // Record what we just verified so the next check can skip hashing it.
        BasicFileAttributes attr =
                Files.readAttributes(target, BasicFileAttributes.class);
        state.put(e.path(), new StateEntry(attr.size(),
                attr.lastModifiedTime().toMillis(), e.algo(), got));
    }

    // ---- state file -------------------------------------------------------

    /** Reads the hash cache. A missing or unreadable file just means no cache. */
    private void loadState() {
        Path f = installDir.resolve(STATE_FILE);
        if (!Files.exists(f)) return;
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(f));
            root.fields().forEachRemaining(en -> {
                JsonNode v = en.getValue();
                // Old state files only have "md5"; newer ones say which algorithm.
                String algo = v.hasNonNull("algo") ? v.get("algo").asText() : "MD5";
                String hash = v.hasNonNull("hash") ? v.get("hash").asText() : v.get("md5").asText();
                state.put(en.getKey(), new StateEntry(
                        v.get("size").asLong(),
                        v.get("mtime").asLong(),
                        algo, hash));
            });
        } catch (Exception ignored) {
            state.clear();   // corrupt cache: fall back to hashing everything
        }
    }

    /** Writes the hash cache. Failure is not fatal — we just hash next time. */
    private void saveState() {
        try {
            ObjectMapper om = new ObjectMapper();
            ObjectNode root = om.createObjectNode();
            for (Map.Entry<String, StateEntry> en : new HashMap<>(state).entrySet()) {
                ObjectNode n = root.putObject(en.getKey());
                n.put("size", en.getValue().size());
                n.put("mtime", en.getValue().mtime());
                n.put("algo", en.getValue().algo());
                n.put("hash", en.getValue().hash());
            }
            Files.createDirectories(installDir);
            Files.writeString(installDir.resolve(STATE_FILE), om.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Rejects manifest paths that could escape the install folder, e.g.
     * "../../Windows/x.dll" or "C:/x". The manifest comes over the network,
     * so it must never be able to write outside the game directory.
     */
    static void checkSafePath(String path) {
        if (path == null || path.isEmpty()
                || path.startsWith("/") || path.startsWith("\\")
                || path.contains(":")
                || path.contains("\u0000"))
            throw new IllegalStateException("unsafe path in manifest: " + path);
        for (String part : path.split("[/\\\\]"))
            if (part.equals(".."))
                throw new IllegalStateException("unsafe path in manifest: " + path);
    }

    /** Resolves a manifest path and double-checks it stays inside {@code root}. */
    static Path resolveInside(Path root, String path) {
        checkSafePath(path);
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(path).normalize();
        if (!target.startsWith(base))
            throw new IllegalStateException("unsafe path in manifest: " + path);
        return target;
    }

    public static String md5(Path file) throws Exception {
        return hash(file, "MD5");
    }

    /** Lower-case hex digest of a file. {@code algo} is "MD5" or "SHA-256". */
    public static String hash(Path file, String algo) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algo);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1048576];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
