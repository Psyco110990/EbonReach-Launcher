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

public class Patcher {

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

    public void download(List<FileEntry> files, Progress p) throws Exception {
        long total = files.stream().mapToLong(FileEntry::size).sum();
        long done = 0;
        int i = 0;
        for (FileEntry e : files) {
            i++;
            if (p != null) p.onFile(e.path(), i, files.size());

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
                    done += r;
                    if (p != null) p.onBytes(done, total);
                }
            }

            String got = md5(tmp);
            if (!got.equalsIgnoreCase(e.md5())) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("checksum mismatch on " + e.path());
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
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
