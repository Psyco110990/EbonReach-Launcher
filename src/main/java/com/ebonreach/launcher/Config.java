package com.ebonreach.launcher;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class Config {

    private static final String FILE_NAME = "launcher.properties";

    public String serverUrl  = "http://143.47.241.168:8080/";
    public String installDir = "client";
    public boolean dxvk      = false;   // "Modern graphics" toggle

    /**
     * The folder the launcher treats as its own.
     *
     * Relative paths used to resolve against the current working directory,
     * which is the launcher's folder only when it is started from inside that
     * folder. From a desktop shortcut, a pinned taskbar icon or a parent
     * folder the working directory is somewhere else, so the launcher would
     * not find an existing install, would start a fresh 2.3 GB download into
     * that other folder, and would write launcher.properties there as well -
     * losing the DXVK toggle every time it was launched a different way.
     *
     * Resolved once, in this order:
     *   1. jpackage.app-path - the jpackage launcher sets it to the exe
     *   2. the jar's folder, when it sits in a jpackage app-image's app/
     *   3. the working directory - development, `java -jar target\...`
     *
     * Step 2 deliberately only fires on the app/ layout. Resolving against
     * the jar's folder in general would make target/ the base during
     * development and send the dev build off to download its own client.
     */
    private static final Path BASE_DIR = resolveBaseDir();

    private static Path resolveBaseDir() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path dir = Path.of(appPath).toAbsolutePath().getParent();
            if (dir != null) return dir;
        }

        try {
            Path src = Path.of(Config.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath();
            Path dir = Files.isDirectory(src) ? src : src.getParent();
            if (dir != null && "app".equals(String.valueOf(dir.getFileName()))
                    && dir.getParent() != null)
                return dir.getParent();
        } catch (Exception ignored) {
            // Unusual classloader or URI - fall through to the working directory.
        }

        return Path.of("").toAbsolutePath();
    }

    private static Path configFile() {
        return BASE_DIR.resolve(FILE_NAME);
    }

    public static Config load() {
        Config c = new Config();
        Path file = configFile();
        if (Files.exists(file)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
                c.serverUrl  = p.getProperty("serverUrl", c.serverUrl);
                c.installDir = p.getProperty("installDir", c.installDir);
                c.dxvk       = Boolean.parseBoolean(p.getProperty("dxvk", "false"));
            } catch (IOException e) {
                System.err.println("config read failed, using defaults: " + e.getMessage());
            }
        }
        return c;
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("serverUrl", serverUrl);
        p.setProperty("installDir", installDir);
        p.setProperty("dxvk", Boolean.toString(dxvk));
        try (OutputStream out = Files.newOutputStream(configFile())) {
            p.store(out, "EbonReach Launcher");
        } catch (IOException e) {
            System.err.println("config write failed: " + e.getMessage());
        }
    }

    /**
     * Where the game is installed. A relative installDir - the default,
     * "client" - sits beside the launcher. An absolute one in
     * launcher.properties is used as given, so the client can live on
     * another drive.
     */
    public Path installPath() {
        Path p = Path.of(installDir);
        return p.isAbsolute() ? p : BASE_DIR.resolve(p).normalize();
    }

    /** Where the launcher decided it lives - useful when a path looks wrong. */
    public static Path baseDir() {
        return BASE_DIR;
    }
}
