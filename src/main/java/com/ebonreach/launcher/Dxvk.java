package com.ebonreach.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

/**
 * Optional DXVK ("Modern graphics"). The patch server ships DXVK's DLLs in
 * optional/dxvk/, where the game ignores them. Turning it on copies them next
 * to sb.exe, where the game picks them up; turning it off removes them again.
 *
 * Only files that are byte-for-byte our DXVK copies are ever deleted, so a
 * player's own d3d8.dll (another wrapper, say) is never touched.
 */
public final class Dxvk {

    static final List<String> DLLS = List.of("d3d8.dll", "d3d9.dll");
    static final String SOURCE = "optional/dxvk";

    private Dxvk() {
    }

    /** True once the patcher has downloaded the DXVK files. */
    public static boolean available(Path installDir) {
        return DLLS.stream().allMatch(d -> Files.isRegularFile(installDir.resolve(SOURCE).resolve(d)));
    }

    /** True if our DXVK DLLs are currently in place next to sb.exe. */
    public static boolean active(Path installDir) {
        return DLLS.stream().allMatch(d -> sameFile(installDir.resolve(SOURCE).resolve(d), installDir.resolve(d)));
    }

    /**
     * Makes the install match the player's choice. Call after every patch too,
     * so an updated DXVK replaces the old copy.
     *
     * @return a message for the status line, or null if nothing needed saying
     */
    public static String apply(Path installDir, boolean wanted) throws Exception {
        if (wanted) {
            if (!available(installDir))
                return "Modern graphics will turn on after the next update.";
            for (String d : DLLS) {
                Path live = installDir.resolve(d);
                if (Files.exists(live) && !isOurs(installDir, d) && !Files.exists(backup(live)))
                    Files.move(live, backup(live));   // keep a player's own DLL, restore it on disable
                Files.copy(installDir.resolve(SOURCE).resolve(d), live, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        }
        for (String d : DLLS) {
            Path live = installDir.resolve(d);
            if (Files.exists(live) && isOurs(installDir, d)) {
                Files.delete(live);
                if (Files.exists(backup(live)))
                    Files.move(backup(live), live);
            }
        }
        return null;
    }

    private static boolean isOurs(Path installDir, String dll) {
        return sameFile(installDir.resolve(SOURCE).resolve(dll), installDir.resolve(dll));
    }

    private static Path backup(Path live) {
        return live.resolveSibling(live.getFileName() + ".before-dxvk");
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isRegularFile(a) && Files.isRegularFile(b)
                    && Files.size(a) == Files.size(b)
                    && Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b));
        } catch (Exception e) {
            return false;
        }
    }
}
