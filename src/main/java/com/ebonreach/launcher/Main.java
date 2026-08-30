package com.ebonreach.launcher;

import java.nio.file.Path;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "http://143.47.241.168:8080/";
        Path dir  = Path.of(args.length > 1 ? args[1] : "testclient");

        System.out.println("Server:  " + url);
        System.out.println("Install: " + dir.toAbsolutePath());

        Patcher p = new Patcher(url, dir);

        var manifest = p.fetchManifest();
        System.out.println("Manifest lists " + manifest.size() + " files.");

        var need = p.findOutdated(manifest);
        if (need.isEmpty()) {
            System.out.println("Up to date. Nothing to do.");
            return;
        }

        long bytes = need.stream().mapToLong(Patcher.FileEntry::size).sum();
        System.out.println("Need " + need.size() + " file(s), " + bytes + " bytes:");
        need.forEach(e -> System.out.println("   " + e.path()));

        p.download(need, new Patcher.Progress() {
            public void onFile(String path, int i, int total) {
                System.out.println("[" + i + "/" + total + "] " + path);
            }
            public void onBytes(long done, long total) { }
        });

        System.out.println("Done. Re-checking...");
        System.out.println(p.findOutdated(manifest).isEmpty()
                ? "Verified: all files match."
                : "PROBLEM: files still mismatched.");
    }
}
