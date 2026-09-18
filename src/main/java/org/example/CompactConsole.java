package org.example;

import java.io.PrintStream;
import java.util.Locale;

/**
 * Keeps normal console output readable while preserving important failures.
 * The automation itself still runs exactly the same; this only filters stdout.
 */
public final class CompactConsole {

    private CompactConsole() {
    }

    public static void install() {
        PrintStream original = System.out;
        System.setOut(new FilteredPrintStream(original));
    }

    private static final class FilteredPrintStream extends PrintStream {

        private FilteredPrintStream(PrintStream original) {
            super(original, true);
        }

        @Override
        public void println() {
            // Suppress decorative blank lines.
        }

        @Override
        public void println(String line) {
            if (shouldShow(line)) {
                super.println(line);
            }
        }

        @Override
        public void println(Object value) {
            String line = String.valueOf(value);
            if (shouldShow(line)) {
                super.println(line);
            }
        }

        private boolean shouldShow(String line) {
            if (line == null) {
                return false;
            }

            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                return false;
            }

            String upper = trimmed.toUpperCase(Locale.ENGLISH);

            // Normal progress: exactly what is useful during a run.
            if (upper.startsWith("RUNNING |")
                    || upper.startsWith("COMPLETED |")
                    || upper.startsWith("PENDING |")
                    || upper.startsWith("STARTING USER")
                    || upper.startsWith("ALL 10 MODULES COMPLETED")) {
                return true;
            }

            // Keep critical technical messages visible for diagnosis.
            return upper.contains("ERROR")
                    || upper.contains("FAILED")
                    || upper.contains("FAILURE")
                    || upper.contains("PAUSED")
                    || upper.contains("NETWORK")
                    || upper.contains("LOGIN")
                    || upper.contains("LOW MEMORY")
                    || upper.contains("INTERRUPTION")
                    || upper.contains("UNCLEAR")
                    || upper.contains("PASSWORD MISSING")
                    || upper.contains("429")
                    || upper.contains("RATE LIMIT")
                    || upper.startsWith("REASON")
                    || upper.contains("PROFILE PREP")
                    || upper.contains("SMY PROFILE")
                    || upper.contains("DG ")
                    || upper.contains("ESAMUDRA")
                    || upper.contains("PHOTO")
                    || upper.contains("INDOS")
                    || upper.contains("SHIP")
                    || upper.contains("IMO")
                    || upper.contains("AADHAAR")
                    || upper.contains("SUBMIT")
                    || upper.contains("CHROME VISIBLE");
        }
    }
}
