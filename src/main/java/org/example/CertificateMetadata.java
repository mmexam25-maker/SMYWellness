package org.example;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CertificateMetadata {

    private static final Pattern[] INDOS_PATTERNS = new Pattern[]{
            Pattern.compile("(?i)\\bIN\\s*D\\s*O\\s*S\\s*(?:NO\\.?|NUMBER|ID)?\\s*[:#-]?\\s*([A-Z0-9]{5,20})\\b"),
            Pattern.compile("(?i)\\bINDOS\\s*(?:NO\\.?|NUMBER|ID)?\\s*[:#-]?\\s*([A-Z0-9]{5,20})\\b")
    };

    private CertificateMetadata() {}

    public static String extractIndos(File pdf) throws Exception {
        if (pdf == null || !pdf.isFile()) return "";

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            if (text == null) return "";

            String normalized = text.replace('\u00A0', ' ')
                    .replaceAll("[\\r\\n]+", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            for (Pattern pattern : INDOS_PATTERNS) {
                Matcher matcher = pattern.matcher(normalized);
                while (matcher.find()) {
                    String value = matcher.group(1).trim().toUpperCase(Locale.ROOT);
                    if (isPlausibleIndos(value)) {
                        return value;
                    }
                }
            }
        }

        return "";
    }

    public static String extractIndosFromAny(Iterable<File> files) throws Exception {
        for (File file : files) {
            String indos = extractIndos(file);
            if (!indos.isBlank()) {
                System.out.println("INDoS FOUND IN CERTIFICATE: " + indos);
                return indos;
            }
        }
        return "";
    }

    /**
     * Rename the ten files. When candidate name is blank (current workflow),
     * the format is: INDoS - Course Name.pdf
     */
    public static Map<String, File> renameCertificates(
            Map<String, File> courseFiles,
            String candidateName,
            String indos
    ) throws Exception {

        if (indos == null || indos.isBlank()) {
            throw new IllegalArgumentException("INDoS number is blank");
        }

        String safeName = candidateName == null ? "" : safe(candidateName);
        String safeIndos = safe(indos);
        Map<String, File> renamed = new LinkedHashMap<>();

        for (Map.Entry<String, File> entry : courseFiles.entrySet()) {
            String course = entry.getKey();
            File source = entry.getValue();
            if (source == null || !source.isFile()) {
                throw new IllegalStateException("Certificate file missing for " + course);
            }

            String fileName = safeName.isBlank()
                    ? safeIndos + " - " + safe(course) + ".pdf"
                    : safeName + " - " + safeIndos + " - " + safe(course) + ".pdf";

            Path destination = source.toPath().getParent().resolve(fileName);

            Files.move(
                    source.toPath(),
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
            );

            File target = destination.toFile();
            renamed.put(course, target);
            System.out.println("CERTIFICATE RENAMED: " + target.getName());
        }

        return renamed;
    }

    private static boolean isPlausibleIndos(String value) {
        if (value == null) return false;
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.length() < 5 || v.length() > 20) return false;
        if (v.equals("NUMBER") || v.equals("NO") || v.equals("INDOS")) return false;
        return v.matches("[A-Z0-9]+") && v.chars().anyMatch(Character::isDigit);
    }

    private static String safe(String value) {
        return value
                .replaceAll("[\\\\/:*?\"<>|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
