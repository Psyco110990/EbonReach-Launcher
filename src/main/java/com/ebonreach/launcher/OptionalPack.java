package com.ebonreach.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An optional download that replaces some of the game's normal files, such as
 * the HD texture pack (plan 3.2).
 *
 * On the patch server the pack's files sit under optional/&lt;id&gt;/, mirroring
 * the game's own paths: optional/hdtextures/Cache/Textures.cache replaces
 * Cache/Textures.cache. Players who leave the box unticked never download it.
 *
 * Turning it on copies the pack over the game's files and keeps each original
 * beside it (Textures.cache.before-hdtextures); turning it off puts the
 * originals back, so switching never re-downloads anything. While it's on,
 * the patcher doesn't check the files the pack replaces, so it never
 * "repairs" them back to the originals.
 *
 * A small marker file records that the pack is in place, so the launcher
 * doesn't have to read hundreds of megabytes on every start to find out.
 */
public final class OptionalPack {

    public static final OptionalPack HD_TEXTURES = new OptionalPack("hdtextures");

    final String id;
    final String prefix;

    OptionalPack(String id) {
        this.id = id;
        this.prefix = "optional/" + id + "/";
    }

    /** The game paths this pack replaces, as listed in the manifest. */
    List<String> replacedPaths(List<Patcher.FileEntry> manifest) {
        List<String> out = new ArrayList<>();
        for (Patcher.FileEntry e : manifest)
            if (e.path().startsWith(prefix))
                out.add(e.path().substring(prefix.length()));
        return out;
    }

    /** True if the patch server offers this pack at all. */
    public boolean offered(List<Patcher.FileEntry> manifest) {
        return !replacedPaths(manifest).isEmpty();
    }

    /**
     * The manifest the patcher should check. Unticked: the pack's own files are
     * left out, so they're never downloaded. Ticked: the game files the pack
     * replaces are left out, so the patcher doesn't undo the pack.
     */
    public List<Patcher.FileEntry> filter(List<Patcher.FileEntry> manifest, boolean wanted) {
        Set<String> replaced = new HashSet<>(replacedPaths(manifest));
        List<Patcher.FileEntry> out = new ArrayList<>();
        for (Patcher.FileEntry e : manifest) {
            boolean packFile = e.path().startsWith(prefix);
            if (wanted ? !replaced.contains(e.path()) : !packFile)
                out.add(e);
        }
        return out;
    }

    /**
     * Makes the install match the player's choice. Call before checking for
     * updates (so turning it off restores the originals instead of
     * re-downloading them) and again once downloads finish (to put a freshly
     * downloaded pack in place).
     *
     * @return a message for the status line, or null
     */
    public String apply(Path installDir, List<Patcher.FileEntry> manifest, boolean wanted) throws Exception {
        List<String> paths = replacedPaths(manifest);
        Path marker = installDir.resolve(prefix).resolve(".applied");

        if (wanted) {
            if (paths.isEmpty())
                return null;                       // not offered by this server
            for (String rel : paths)
                if (!Files.isRegularFile(installDir.resolve(prefix + rel)))
                    return null;                   // still downloading; the post-download call does it
            if (Files.exists(marker) && allInPlace(installDir, paths))
                return null;
            for (String rel : paths) {
                Path live = installDir.resolve(rel);
                Path backup = backup(live);
                if (Files.exists(live) && !Files.exists(backup))
                    Files.move(live, backup);
                Files.createDirectories(live.getParent());
                Files.copy(installDir.resolve(prefix + rel), live, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(marker, "applied\n");
            return null;
        }

        if (!Files.exists(marker))
            return null;
        for (String rel : paths) {
            Path live = installDir.resolve(rel);
            Path backup = backup(live);
            if (Files.exists(backup))
                Files.move(backup, live, StandardCopyOption.REPLACE_EXISTING);
            // No backup: the patcher sees the file differs and fetches the original.
        }
        Files.delete(marker);
        return null;
    }

    private boolean allInPlace(Path installDir, List<String> paths) throws Exception {
        for (String rel : paths) {
            Path live = installDir.resolve(rel), pack = installDir.resolve(prefix + rel);
            if (!Files.isRegularFile(live) || Files.size(live) != Files.size(pack)
                    || Files.getLastModifiedTime(live).compareTo(Files.getLastModifiedTime(pack)) < 0)
                return false;                      // missing, or the pack was updated since
        }
        return true;
    }

    private Path backup(Path live) {
        return live.resolveSibling(live.getFileName() + ".before-" + id);
    }
}
