package org.example;

import java.net.InetAddress;
import java.util.Locale;

/**
 * Stable multi-PC partitioning. Every candidate is owned by exactly one PC
 * based on the login/email text, so multiple PCs can run the same project without
 * deliberately picking the same candidate.
 */
public final class MachineShard {
    private static final int TOTAL = intSetting("TOTAL_PCS", 11, 1, 64);
    private static final int ID = intSetting("PC_ID", 1, 1, TOTAL);

    private MachineShard() {}

    public static boolean owns(String stableKey) {
        if (TOTAL <= 1) return true;
        if (stableKey == null || stableKey.isBlank()) return false;
        String key = stableKey.trim().toLowerCase(Locale.ROOT);
        int slot = Math.floorMod(key.hashCode(), TOTAL) + 1;
        return slot == ID;
    }

    public static int pcId() { return ID; }
    public static int totalPcs() { return TOTAL; }

    public static String label() {
        String host = "PC" + ID;
        try {
            String h = InetAddress.getLocalHost().getHostName();
            if (h != null && !h.isBlank()) host = h;
        } catch (Exception ignored) {}
        return "PC " + ID + "/" + TOTAL + " (" + host + ")";
    }

    private static int intSetting(String key, int def, int min, int max) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) v = System.getProperty(key);
        if (v == null || v.isBlank()) return def;
        try {
            int n = Integer.parseInt(v.trim());
            return Math.max(min, Math.min(max, n));
        } catch (Exception e) {
            return def;
        }
    }
}
