package org.example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WhatsAppClient {

    private static final String GRAPH_VERSION = "v23.0";
    private static final String FALLBACK_MESSAGE =
            "As per your request, your certificates are attached and forwarded to your mail. Please check your mail.";

    /**
     * Once a language succeeds for a template, remember it for the rest of the run.
     * This avoids probing every language for every candidate.
     */
    private static final Map<String, String> WORKING_LANGUAGE = new ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * Candidate notification: ALWAYS sent to the phone number stored in Column G.
     */
    public void sendCandidateNotification(String candidateMobile) throws Exception {
        String template = Config.get("whatsapp.template");
        String language = Config.get("whatsapp.language");
        sendNotification(candidateMobile, "candidate", template, language);
    }

    /**
     * Optional agent notification: sent only when Column L contains an agent name.
     */
    public void sendAgentNotification(String agentMobile, String agentName) throws Exception {
        String template = Config.get("whatsapp.agent.template");
        if (template.isBlank()) template = Config.get("whatsapp.template");

        String language = Config.get("whatsapp.agent.language");
        if (language.isBlank()) language = Config.get("whatsapp.language");

        sendNotification(agentMobile, "agent " + agentName, template, language);
    }

    private void sendNotification(
            String mobile,
            String label,
            String template,
            String configuredLanguage
    ) throws Exception {

        String phoneId = Config.require("whatsapp.phone.number.id");
        String token = Config.require("whatsapp.token");

        String recipient = normalizeIndianNumber(mobile);

        if (!template.isBlank()) {
            String language = sendTemplateWithLanguageFallback(
                    phoneId,
                    token,
                    recipient,
                    template,
                    configuredLanguage
            );

            System.out.println("WHATSAPP SENT: " + label
                    + " -> " + recipient
                    + " | template=" + template
                    + " | language=" + language
                    + " | no variables");
            return;
        }

        // Fallback only. Outside the 24-hour conversation window Meta normally
        // requires an approved template.
        sendText(phoneId, token, recipient, FALLBACK_MESSAGE);
        System.out.println("WHATSAPP TEXT SENT: " + label + " -> " + recipient);
    }

    /**
     * Meta error 132001 often means the template exists but not in the language
     * code supplied by the API. Try the configured code first, then common English
     * codes. If the template belongs to another WABA/phone-number-id, all attempts
     * will fail and a clear error is returned.
     */
    private String sendTemplateWithLanguageFallback(
            String phoneId,
            String token,
            String recipient,
            String template,
            String configuredLanguage
    ) throws Exception {

        String remembered = WORKING_LANGUAGE.get(template.toLowerCase(Locale.ROOT));
        if (remembered != null && !remembered.isBlank()) {
            sendTemplateWithoutVariables(phoneId, token, recipient, template, remembered);
            return remembered;
        }

        LinkedHashSet<String> languages = new LinkedHashSet<>();
        if (configuredLanguage != null && !configuredLanguage.isBlank()) {
            languages.add(configuredLanguage.trim());
        }

        String extra = Config.get("whatsapp.language.fallbacks");
        if (!extra.isBlank()) {
            for (String item : extra.split(",")) {
                if (!item.isBlank()) languages.add(item.trim());
            }
        }

        // Common Meta English language codes.
        languages.add("en_US");
        languages.add("en");
        languages.add("en_GB");

        List<String> tried = new ArrayList<>();
        Exception last = null;

        for (String language : languages) {
            tried.add(language);
            try {
                sendTemplateWithoutVariables(
                        phoneId,
                        token,
                        recipient,
                        template,
                        language
                );

                WORKING_LANGUAGE.put(template.toLowerCase(Locale.ROOT), language);
                if (configuredLanguage == null || !language.equals(configuredLanguage.trim())) {
                    System.out.println("WHATSAPP TEMPLATE LANGUAGE AUTO-DETECTED: "
                            + template + " -> " + language);
                }
                return language;

            } catch (WhatsAppApiException e) {
                last = e;

                // Only 132001 is a language/template-translation mismatch.
                // Other errors should not be hidden by trying other languages.
                if (!e.isTemplateTranslationMissing()) {
                    throw e;
                }

                System.err.println("WHATSAPP TEMPLATE NOT FOUND FOR LANGUAGE "
                        + language + " - TRYING NEXT LANGUAGE");
            }
        }

        throw new IllegalStateException(
                "WhatsApp template '" + template + "' was not found for languages " + tried + ". "
                        + "If the template is Approved, verify that whatsapp.phone.number.id belongs to the SAME "
                        + "WhatsApp Business Account (WABA) where this template is approved.",
                last
        );
    }

    private void sendTemplateWithoutVariables(
            String phoneId,
            String token,
            String recipient,
            String template,
            String language
    ) throws Exception {

        String body = "{"
                + "\"messaging_product\":\"whatsapp\","
                + "\"to\":\"" + json(recipient) + "\","
                + "\"type\":\"template\","
                + "\"template\":{"
                + "\"name\":\"" + json(template) + "\","
                + "\"language\":{\"code\":\"" + json(language) + "\"}"
                + "}"
                + "}";

        sendRequest(phoneId, token, body);
    }

    private void sendText(
            String phoneId,
            String token,
            String recipient,
            String message
    ) throws Exception {

        String body = "{"
                + "\"messaging_product\":\"whatsapp\","
                + "\"to\":\"" + json(recipient) + "\","
                + "\"type\":\"text\","
                + "\"text\":{\"preview_url\":false,\"body\":\"" + json(message) + "\"}"
                + "}";

        sendRequest(phoneId, token, body);
    }

    private void sendRequest(String phoneId, String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://graph.facebook.com/" + GRAPH_VERSION + "/" + phoneId + "/messages"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new WhatsAppApiException(
                    response.statusCode(),
                    response.body()
            );
        }
    }

    private static String normalizeIndianNumber(String value) {
        if (value == null) throw new IllegalArgumentException("WhatsApp number is blank");

        String n = value.replaceAll("\\D", "");
        if (n.startsWith("0091") && n.length() == 14) n = n.substring(2);
        if (n.length() == 11 && n.startsWith("0")) n = n.substring(1);
        if (n.length() == 10) n = "91" + n;

        if (n.length() != 12 || !n.startsWith("91")) {
            throw new IllegalArgumentException("Invalid Indian WhatsApp number: " + value);
        }
        return n;
    }

    private static String json(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private static String shorten(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500) + "...";
    }

    private static final class WhatsAppApiException extends IllegalStateException {
        private final int httpStatus;
        private final String responseBody;

        private WhatsAppApiException(int httpStatus, String responseBody) {
            super("WhatsApp HTTP " + httpStatus + ": " + shorten(responseBody));
            this.httpStatus = httpStatus;
            this.responseBody = responseBody == null ? "" : responseBody;
        }

        private boolean isTemplateTranslationMissing() {
            return responseBody.contains("\"code\":132001")
                    || responseBody.contains("(#132001)")
                    || responseBody.toLowerCase(Locale.ROOT)
                    .contains("template name does not exist in the translation");
        }
    }
}
