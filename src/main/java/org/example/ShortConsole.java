package org.example;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Keeps the console readable during continuous automation. */
public final class ShortConsole {
    private ShortConsole() {}

    private static final Set<String> PREFIXES = Set.of(
            "START",
            "TRYING",
            "LOGIN OK",
            "LOGIN RETRY",
            "PROGRESS OK",
            "DOWNLOADING",
            "EMAIL SENT",
            "WHATSAPP SENT",
            "SUCCESS",
            "FAILED - RETRY",
            "No pending row"
    );

    public static void install() {
        try {
            Logger.getLogger("org.openqa.selenium").setLevel(Level.OFF);
            Logger.getLogger("org.openqa.selenium.devtools").setLevel(Level.OFF);
            Logger.getLogger("com.google").setLevel(Level.SEVERE);
        } catch (Exception ignored) {}

        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        System.setOut(new PrintStream(new LineFilter(realOut), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new LineFilter(realErr), true, StandardCharsets.UTF_8));
    }

    private static boolean allowed(String line) {
        String t = line == null ? "" : line.trim();
        for (String prefix : PREFIXES) {
            if (t.startsWith(prefix)) return true;
        }
        return false;
    }

    private static final class LineFilter extends OutputStream {
        private final PrintStream target;
        private final StringBuilder buf = new StringBuilder();
        LineFilter(PrintStream target) { this.target = target; }

        @Override public void write(int b) {
            char c = (char) b;
            if (c == '\n') flushLine();
            else if (c != '\r') buf.append(c);
        }

        @Override public void flush() { if (buf.length() > 0) flushLine(); target.flush(); }

        private void flushLine() {
            String line = buf.toString();
            buf.setLength(0);
            if (allowed(line)) target.println(line);
        }
    }
}
