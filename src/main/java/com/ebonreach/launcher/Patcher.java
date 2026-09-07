package com.ebonreach.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Patcher {

    /** How many files to download at once. */
    private static final int WORKERS = 8;

    public record FileEntry(String path, long size, String md5) {}

    public interface Progress {
        void onFile(String path, int index, int total);
        void onBytes(long done, long total);
    }

    private final String baseUrl;
    private final Path installDir;
    private final HttpClient http = HttpClient.newHttpClient();

    public Patcher(String baseUrl, Path installDir) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.installDir = installDir;
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
        for (JsonNode n : root.get("files"))
            out.add(new FileEntry(n.get("path").asText(),
                                  n.get("size").asLong(),
                                  n.get("md5").asText()));
        return out;
    }

    public List<FileEntry> findOutdated(List<FileEntry> manifest) throws Exception {
        List<FileEntry> need = new ArrayList<>();
        for (FileEntry e : manifest) {
            Path local = installDir.resolve(e.path());
            if (!Files.exists(local) || Files.size(local) != e.size()
                    || !md5(local).equalsIgnoreCase(e.md5()))
                need.add(e);
        }
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
        }
    }

    /** Fetches one file to a .part, verifies its checksum, then renames it. */
    private void fetchOne(FileEntry e, AtomicLong done, long total, Progress p)
            throws Exception {
        Path target = installDir.resolve(e.path());
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

        String got = md5(tmp);
        if (!got.equalsIgnoreCase(e.md5())) {
            Files.deleteIfExists(tmp);
            throw new IllegalStateException("checksum mismatch on " + e.path());
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public static String md5(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
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
