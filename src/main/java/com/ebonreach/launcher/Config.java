package com.ebonreach.launcher;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class Config {
    private static final Path FILE = Path.of("launcher.properties");

    public String serverUrl  = "http://143.47.241.168:8080/";
    public String installDir = "client";
    public boolean dxvk      = false;   // "Modern graphics" toggle
    public boolean hdTextures = false;  // optional HD texture pack (3.2)

    public static Config load() {
        Config c = new Config();
        if (Files.exists(FILE)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(FILE)) {
                p.load(in);
                c.serverUrl  = p.getProperty("serverUrl", c.serverUrl);
                c.installDir = p.getProperty("installDir", c.installDir);
                c.dxvk       = Boolean.parseBoolean(p.getProperty("dxvk", "false"));
                c.hdTextures = Boolean.parseBoolean(p.getProperty("hdTextures", "false"));
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
        p.setProperty("hdTextures", Boolean.toString(hdTextures));
        try (OutputStream out = Files.newOutputStream(FILE)) {
            p.store(out, "EbonReach Launcher");
        } catch (IOException e) {
            System.err.println("config write failed: " + e.getMessage());
        }
    }

    public Path installPath() {
        return Path.of(installDir).toAbsolutePath();
    }
}
