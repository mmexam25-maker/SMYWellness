package org.example;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Loads this project's config.properties.
 *
 * If a value is still a placeholder, it can automatically borrow that value
 * from the sibling old project:
 *   SMY-Certificate-Emailer-No-Drive/config.properties
 *
 * Agent configuration uses:
 *   agent.<normalized_name>.phone=...
 *   agent.<normalized_name>.email=...
 *
 * Example for Column L = Muthu_Punnai:
 *   agent.muthu_punnai.phone=7708544895
 *   agent.muthu_punnai.email=spmcomputer2024@gmail.com
 */
public final class Config {

    private static final Properties P = new Properties();

    static {
        loadConfiguration();
    }

    private Config() {}

    private static void loadConfiguration() {
        Path currentDir = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();

        Path currentConfig = currentDir.resolve("config.properties");

        if (!Files.isRegularFile(currentConfig)) {
            throw new RuntimeException(
                    "Cannot load config.properties from: " + currentConfig
            );
        }

        try (InputStream in = new FileInputStream(currentConfig.toFile())) {
            P.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load config.properties", e);
        }

        Path legacyConfig = findLegacyConfig(currentDir);

        if (legacyConfig != null) {
            Properties old = new Properties();

            try (InputStream in = new FileInputStream(legacyConfig.toFile())) {
                old.load(in);
            } catch (IOException ignored) {
                return;
            }

            int copied = 0;

            for (String key : old.stringPropertyNames()) {
                String currentValue = P.getProperty(key, "");
                String oldValue = old.getProperty(key, "");

                if (isPlaceholderOrBlank(currentValue)
                        && !isPlaceholderOrBlank(oldValue)) {
                    P.setProperty(key, oldValue);
                    copied++;
                }
            }

            if (copied > 0) {
                System.out.println(
                        "CONFIG FALLBACK: copied " + copied
                                + " working value(s) from " + legacyConfig
                );
            }
        }
    }

    private static Path findLegacyConfig(Path currentDir) {
        Path parent = currentDir.getParent();

        if (parent == null) {
            return null;
        }

        Path exact = parent
                .resolve("SMY-Certificate-Emailer-No-Drive")
                .resolve("config.properties");

        if (Files.isRegularFile(exact)) {
            return exact;
        }

        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(parent, "SMY-Certificate-Emailer*")) {

            for (Path sibling : stream) {
                if (!Files.isDirectory(sibling)) continue;
                if (sibling.toAbsolutePath().normalize().equals(currentDir)) continue;

                Path candidate = sibling.resolve("config.properties");

                if (Files.isRegularFile(candidate)
                        && containsWorkingSpreadsheetId(candidate)) {
                    return candidate;
                }
            }

        } catch (Exception ignored) {
        }

        return null;
    }

    private static boolean containsWorkingSpreadsheetId(Path file) {
        Properties test = new Properties();

        try (InputStream in = new FileInputStream(file.toFile())) {
            test.load(in);
            String value = test.getProperty("spreadsheet.id", "");
            return !isPlaceholderOrBlank(value);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPlaceholderOrBlank(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }

        String v = value.trim().toUpperCase(Locale.ROOT);

        return v.startsWith("YOUR_EXISTING_")
                || v.startsWith("PUT_YOUR_")
                || v.equals("CHANGE_ME")
                || v.equals("REPLACE_ME")
                || v.equals("YOUR_TOKEN")
                || v.equals("YOUR_PASSWORD");
    }

    public static String get(String key) {
        return P.getProperty(key, "").trim();
    }

    public static String require(String key) {
        String value = get(key);

        if (isPlaceholderOrBlank(value)) {
            throw new IllegalArgumentException(
                    "Missing/placeholder property: " + key
                            + ". Copy the working value from the old SMY project."
            );
        }

        return value;
    }

    public static String mailPassword() {
        String env = System.getenv("SMY_MAIL_PASSWORD");

        if (env != null && !env.isBlank()) {
            return env.trim();
        }

        String value = get("mail.password");

        if (isPlaceholderOrBlank(value)) {
            return "";
        }

        return value;
    }

    /**
     * Reads an agent phone number from the new format first:
     *   agent.muthu_punnai.phone=7708544895
     *
     * It also supports old project formats such as:
     *   agent.muthu_punnai=7708544895
     *   agent.muthupunnai=7708544895
     */
    public static String agentNumber(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return "";
        }

        String key = normalizeAgentKey(agentName);

        String phone = get("agent." + key + ".phone");
        if (!isPlaceholderOrBlank(phone)) {
            return phone;
        }

        // Legacy key preserving underscore/spaces as normalized underscores.
        phone = get("agent." + key);
        if (!isPlaceholderOrBlank(phone)) {
            return phone;
        }

        // Older compact key with all punctuation removed.
        String compact = agentName
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");

        phone = get("agent." + compact);
        return isPlaceholderOrBlank(phone) ? "" : phone;
    }

    /**
     * Reads the optional agent CC email.
     * Example:
     *   agent.muthu_punnai.email=spmcomputer2024@gmail.com
     */
    public static String agentEmail(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return "";
        }

        String key = normalizeAgentKey(agentName);
        String email = get("agent." + key + ".email");

        return isPlaceholderOrBlank(email) ? "" : email;
    }

    private static String normalizeAgentKey(String agentName) {
        return agentName
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
