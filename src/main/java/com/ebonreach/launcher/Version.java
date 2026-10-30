package com.ebonreach.launcher;

/** This launcher's version. Bump it (and pom.xml) for every release you send out. */
public final class Version {

    public static final String CURRENT = "0.3.0";

    private Version() {
    }

    /** True if {@code remote} is a later version than {@code current}, e.g. "0.10.0" > "0.9.2". */
    public static boolean isNewer(String remote, String current) {
        return compare(remote, current) > 0;
    }

    /** Compares dotted versions number by number. Missing parts count as 0; junk counts as 0. */
    static int compare(String a, String b) {
        String[] x = a == null ? new String[0] : a.trim().split("\\.");
        String[] y = b == null ? new String[0] : b.trim().split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(part(x, i), part(y, i));
            if (c != 0) return c;
        }
        return 0;
    }

    private static int part(String[] p, int i) {
        if (i >= p.length) return 0;
        String digits = p[i].replaceAll("[^0-9].*$", "");   // "2-beta" -> "2"
        try {
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
