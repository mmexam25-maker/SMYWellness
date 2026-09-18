package org.example;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * SMTP-over-SSL client for the SMY certificate mailbox.
 *
 * ONE-MAIL MODE: all 10 certificates are sent together in one email only.
 * This build never sends Part 1 / Part 2 messages. If the complete MIME message
 * is above the configured mailbox limit, the row fails before any email is sent
 * so the candidate never receives an incomplete split set.
 *
 * Supports an optional agent CC address. The CC address is added both to the
 * SMTP envelope (another RCPT TO command) and to the MIME Cc header.
 */
public class MailClient {

    @FunctionalInterface
    public interface BatchProgressListener {
        void onBatchSent(int sentBatchNumber, int totalBatches) throws Exception;
    }

    /**
     * Backward-compatible entry point. Sends from batch 1 with no CC.
     */
    public void sendCertificates(
            String recipient,
            String candidateName,
            String indos,
            List<File> attachments
    ) throws Exception {
        sendCertificates(recipient, "", candidateName, indos, attachments, 0, null);
    }

    /**
     * Backward-compatible split-mail entry point with no CC.
     */
    public int sendCertificates(
            String recipient,
            String candidateName,
            String indos,
            List<File> attachments,
            int alreadySentBatches,
            BatchProgressListener listener
    ) throws Exception {
        return sendCertificates(
                recipient,
                "",
                candidateName,
                indos,
                attachments,
                alreadySentBatches,
                listener
        );
    }

    /**
     * Sends all certificate batches after alreadySentBatches.
     *
     * @param recipient          candidate email address (TO)
     * @param ccEmail            optional agent email address (CC); blank means no CC
     * @param alreadySentBatches number of leading batches that were already
     *                           accepted by SMTP on an earlier attempt
     * @return total number of batches in the deterministic plan
     */
    public int sendCertificates(
            String recipient,
            String ccEmail,
            String candidateName,
            String indos,
            List<File> attachments,
            int alreadySentBatches,
            BatchProgressListener listener
    ) throws Exception {

        validateInputs(recipient, attachments);
        ccEmail = normalizeOptionalEmail(ccEmail);

        String host = Config.get("mail.smtp.host");
        if (host.isBlank()) host = "smtpout.secureserver.net";

        int port = parseInt(Config.get("mail.smtp.port"), 465);

        String username = Config.get("mail.username");
        if (username.isBlank()) username = "certificates@smyseamen.com";

        String from = Config.get("mail.from");
        if (from.isBlank()) from = username;

        String password = Config.mailPassword();
        if (password.isBlank()) {
            throw new IllegalStateException(
                    "Mail password not set. Put the mailbox password in config.properties as mail.password=... "
                            + "or set Windows environment variable SMY_MAIL_PASSWORD."
            );
        }

        String fromName = Config.get("mail.from.name");
        if (fromName.isBlank()) fromName = "Sagar Mein Yog";

        String baseSubject = Config.get("mail.subject");
        if (baseSubject.isBlank()) baseSubject = "Sagar Mein Yog Certificates";
        String displayName = candidateName == null ? "" : candidateName.trim();
        String greetingName = firstNameForGreeting(displayName);
        if (!displayName.isBlank()) {
            baseSubject = baseSubject + " - " + displayName;
        }

        List<List<File>> batches = planBatches(attachments);
        int totalBatches = batches.size();

        if (alreadySentBatches < 0) alreadySentBatches = 0;
        if (alreadySentBatches > totalBatches) {
            // A changed/corrupt resume marker must never cause all mail to be skipped.
            alreadySentBatches = 0;
        }

        if (alreadySentBatches > 0) {
            System.out.println(
                    "EMAIL RESUME: " + alreadySentBatches + "/" + totalBatches
                            + " email already sent - not resending it."
            );
        }

        for (int i = alreadySentBatches; i < totalBatches; i++) {
            int batchNumber = i + 1;
            List<File> batch = batches.get(i);

            String subject = baseSubject;

            String body = (greetingName.isBlank() ? "Dear Candidate" : "Dear " + greetingName)
                    + ",\r\n\r\n"
                    + "As per your request Please find attached all 10 Certificates below.\r\n"
                    + "\r\nThank You!";

            sendOneMessageWithSafeRetry(
                    host,
                    port,
                    username,
                    password,
                    fromName,
                    from,
                    recipient,
                    ccEmail,
                    subject,
                    body,
                    batch
            );

            System.out.println(
                    "ONE EMAIL SENT FROM " + from
                            + " TO " + recipient
                            + (ccEmail.isBlank() ? "" : " | CC " + ccEmail)
                            + " | " + batch.size() + " certificate(s) | INDoS " + indos
            );

            if (listener != null) {
                // Persist progress immediately after SMTP acceptance. A temporary
                // Google Sheets timeout must NOT turn an already-accepted email
                // into a resend. The final Column I=SENT write still provides the
                // durable row-level completion marker.
                try {
                    listener.onBatchSent(batchNumber, totalBatches);
                } catch (Exception progressError) {
                    System.err.println(
                            "WARNING - EMAIL WAS ACCEPTED, BUT SHEET PROGRESS "
                                    + "UPDATE FAILED; CONTINUING WITHOUT RESEND: "
                                    + progressError.getMessage()
                    );
                }
            }
        }

        System.out.println(
                "EMAIL COMPLETE: " + attachments.size()
                        + " certificates sent in ONE email to " + recipient
                        + (ccEmail.isBlank() ? "" : " | CC " + ccEmail)
        );

        return totalBatches;
    }

    /**
     * ONE-MAIL MODE.
     *
     * All 10 certificate PDFs are kept in one deterministic message. We check
     * the estimated MIME size before SMTP starts. If it is too large, nothing
     * is sent; we do not fall back to Part 1 / Part 2.
     */
    public List<List<File>> planBatches(List<File> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            throw new IllegalArgumentException("No certificate attachments supplied");
        }

        long maxMessageMb = parseLong(Config.get("mail.max.message.mb"), 25L);
        if (maxMessageMb < 2L) maxMessageMb = 2L;
        long maxEstimatedBytes = maxMessageMb * 1024L * 1024L;

        final long fixedOverhead = 96L * 1024L;
        long estimatedBytes = fixedOverhead;

        for (File file : attachments) {
            if (file == null || !file.isFile() || file.length() <= 0) {
                throw new IllegalStateException("Attachment missing/empty: " + file);
            }
            estimatedBytes += estimateMimeAttachmentBytes(file.length());
        }

        if (estimatedBytes > maxEstimatedBytes) {
            throw new IllegalStateException(
                    "ONE EMAIL TOO LARGE: estimated MIME " + humanMb(estimatedBytes)
                            + " MB is above configured limit " + maxMessageMb
                            + " MB. No partial/Part email was sent."
            );
        }

        return List.of(List.copyOf(attachments));
    }

    /**
     * Use a friendly first name in the greeting, e.g.
     * "RATHEES KUMAR" -> "Rathees".
     */
    private static String firstNameForGreeting(String fullName) {
        if (fullName == null) return "";

        String value = fullName.trim();
        if (value.isBlank()) return "";

        String first = value.split("\\s+")[0].trim();
        if (first.isBlank()) return "";

        boolean allUpper = first.equals(first.toUpperCase());
        boolean allLower = first.equals(first.toLowerCase());

        if ((allUpper || allLower) && first.length() > 1) {
            return Character.toUpperCase(first.charAt(0))
                    + first.substring(1).toLowerCase();
        }

        return first;
    }

    private static void validateInputs(String recipient, List<File> attachments) {
        if (!isValidEmail(recipient)) {
            throw new IllegalArgumentException(
                    "Column B is not a valid email address: " + recipient
            );
        }

        if (attachments == null || attachments.size() != 10) {
            throw new IllegalArgumentException("Exactly 10 certificate attachments are required");
        }

        for (File file : attachments) {
            if (file == null || !file.isFile() || file.length() <= 0) {
                throw new IllegalStateException("Attachment missing/empty: " + file);
            }
        }
    }

    private static String normalizeOptionalEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }

        String value = email.trim();
        if (!isValidEmail(value)) {
            throw new IllegalArgumentException("Agent CC is not a valid email address: " + value);
        }

        return value;
    }

    private static boolean isValidEmail(String value) {
        if (value == null) return false;

        String email = value.trim();
        int at = email.indexOf('@');

        return at > 0
                && at == email.lastIndexOf('@')
                && at < email.length() - 1
                && email.indexOf('.', at) > at + 1
                && !email.contains(" ");
    }

    /**
     * Retry only failures that happen while the SMTP DATA bytes are still being
     * uploaded. At that point the server has not returned the final 2xx
     * acceptance, so reconnecting and trying the same part again is safe.
     *
     * We deliberately do NOT auto-retry a failure while waiting for the final
     * server response because delivery would be uncertain and an automatic
     * retry could duplicate the candidate's email.
     */
    private static void sendOneMessageWithSafeRetry(
            String host,
            int port,
            String username,
            String password,
            String fromName,
            String from,
            String recipient,
            String ccEmail,
            String subject,
            String body,
            List<File> attachments
    ) throws Exception {

        int maxAttempts = parseInt(Config.get("mail.smtp.data.write.retries"), 3);
        if (maxAttempts < 1) maxAttempts = 1;
        if (maxAttempts > 5) maxAttempts = 5;

        long delayMs = parseLong(Config.get("mail.smtp.retry.delay.ms"), 3000L);
        if (delayMs < 0L) delayMs = 0L;
        if (delayMs > 30000L) delayMs = 30000L;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                sendOneMessage(
                        host, port, username, password, fromName, from,
                        recipient, ccEmail, subject, body, attachments
                );
                return;

            } catch (SmtpDataWriteException dataWriteError) {
                if (attempt >= maxAttempts) {
                    throw dataWriteError;
                }

                System.err.println(
                        "SMTP DATA CONNECTION RESET/WRITE FAILED - SAFE RETRY "
                                + attempt + "/" + maxAttempts
                                + " | " + dataWriteError.getMessage()
                );

                if (delayMs > 0L) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
            }
        }
    }

    private static final class SmtpDataWriteException extends IOException {
        private SmtpDataWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static void writeLargeSmtpData(BufferedWriter writer, String value)
            throws IOException {
        final int chunkChars = 32 * 1024;
        for (int offset = 0; offset < value.length(); offset += chunkChars) {
            int end = Math.min(value.length(), offset + chunkChars);
            writer.write(value, offset, end - offset);
        }
    }

    private static void sendOneMessage(
            String host,
            int port,
            String username,
            String password,
            String fromName,
            String from,
            String recipient,
            String ccEmail,
            String subject,
            String body,
            List<File> attachments
    ) throws Exception {

        String boundary = "----SMY_" + UUID.randomUUID();
        String message = buildMessage(
                fromName,
                from,
                recipient,
                ccEmail,
                subject,
                body,
                boundary,
                attachments
        );

        // A final local guard checks the actual MIME size after Base64 encoding.
        long configuredMb = parseLong(Config.get("mail.max.message.mb"), 12L);
        if (configuredMb < 2L) configuredMb = 2L;
        long configuredBytes = configuredMb * 1024L * 1024L;
        long actualBytes = message.getBytes(StandardCharsets.UTF_8).length;

        System.out.println(
                "SMTP MESSAGE PREPARED: " + attachments.size()
                        + " attachment(s) | MIME " + humanMb(actualBytes) + " MB"
                        + " | safe limit " + configuredMb + " MB"
        );

        if (actualBytes > configuredBytes) {
            throw new IllegalStateException(
                    "Prepared email part is " + humanMb(actualBytes)
                            + " MB, above safe per-message limit " + configuredMb + " MB"
            );
        }

        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            expect(readResponse(reader), 220, "SMTP greeting");

            sendLine(writer, "EHLO smyseamen.com");
            expect(readResponse(reader), 250, "EHLO");

            sendLine(writer, "AUTH LOGIN");
            expect(readResponse(reader), 334, "AUTH LOGIN");

            sendLine(
                    writer,
                    Base64.getEncoder().encodeToString(
                            username.getBytes(StandardCharsets.UTF_8))
            );
            expect(readResponse(reader), 334, "SMTP username");

            sendLine(
                    writer,
                    Base64.getEncoder().encodeToString(
                            password.getBytes(StandardCharsets.UTF_8))
            );
            expect(readResponse(reader), 235, "SMTP password");

            sendLine(writer, "MAIL FROM:<" + from + ">");
            expect2xx(readResponse(reader), "MAIL FROM");

            // Candidate TO recipient.
            sendLine(writer, "RCPT TO:<" + recipient.trim() + ">");
            expect2xx(readResponse(reader), "CANDIDATE RCPT TO");

            // Optional agent CC. SMTP uses another RCPT TO command for CC delivery.
            if (!ccEmail.isBlank()) {
                sendLine(writer, "RCPT TO:<" + ccEmail + ">");
                expect2xx(readResponse(reader), "AGENT CC RCPT TO");
            }

            sendLine(writer, "DATA");
            expect(readResponse(reader), 354, "DATA");

            try {
                String stuffedMessage = dotStuff(message);
                writeLargeSmtpData(writer, stuffedMessage);
                if (!message.endsWith("\r\n")) writer.write("\r\n");
                writer.write(".\r\n");
                writer.flush();
            } catch (IOException dataWriteError) {
                throw new SmtpDataWriteException(
                        "connection failed while uploading SMTP DATA",
                        dataWriteError
                );
            }

            // Once this 2xx is received, SMTP has accepted the message. Any
            // later QUIT/close problem must never make Main resend it.
            expect2xx(readResponse(reader), "Message send");

            try {
                sendLine(writer, "QUIT");
                readResponse(reader);
            } catch (Exception quitError) {
                System.err.println(
                        "WARNING - SMTP MESSAGE ALREADY ACCEPTED; QUIT FAILED: "
                                + quitError.getMessage()
                );
            }
        }

        // SMTP only delivers the message; it does not normally create a copy
        // in the mailbox Sent folder. Append the exact MIME message through IMAP
        // so the outgoing certificate email is visible in Sent / Sent Items.
        if (!"false".equalsIgnoreCase(Config.get("mail.save.sent"))) {
            try {
                saveCopyToSent(username, password, message);
                System.out.println("SENT ITEMS COPY SAVED");
            } catch (Exception sentCopyError) {
                // IMPORTANT: SMTP already accepted the email. Never throw here,
                // otherwise Main could resend the same certificates.
                System.err.println(
                        "WARNING - EMAIL SENT, BUT SENT ITEMS COPY COULD NOT BE SAVED: "
                                + sentCopyError.getMessage()
                );
            }
        }
    }

    private static String buildMessage(
            String fromName,
            String from,
            String to,
            String ccEmail,
            String subject,
            String body,
            String boundary,
            List<File> attachments
    ) throws Exception {

        StringBuilder out = new StringBuilder();

        out.append("Date: ")
                .append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()))
                .append("\r\n");

        out.append("Message-ID: <")
                .append(UUID.randomUUID())
                .append("@smyseamen.com>\r\n");

        out.append("From: ")
                .append(encodeWord(fromName))
                .append(" <")
                .append(from)
                .append(">\r\n");

        out.append("To: <")
                .append(to.trim())
                .append(">\r\n");

        if (!ccEmail.isBlank()) {
            out.append("Cc: <")
                    .append(ccEmail)
                    .append(">\r\n");
        }

        out.append("Subject: ")
                .append(encodeWord(subject))
                .append("\r\n");

        out.append("MIME-Version: 1.0\r\n");
        out.append("Content-Type: multipart/mixed; boundary=\"")
                .append(boundary)
                .append("\"\r\n");
        out.append("\r\n");

        out.append("--").append(boundary).append("\r\n");
        out.append("Content-Type: text/plain; charset=UTF-8\r\n");
        out.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
        out.append(body).append("\r\n");

        for (File file : attachments) {
            String encodedName = encodeWord(file.getName());
            out.append("--").append(boundary).append("\r\n");
            out.append("Content-Type: application/pdf; name=\"")
                    .append(encodedName)
                    .append("\"\r\n");
            out.append("Content-Transfer-Encoding: base64\r\n");
            out.append("Content-Disposition: attachment; filename=\"")
                    .append(encodedName)
                    .append("\"\r\n\r\n");

            String base64 = Base64.getMimeEncoder(
                            76,
                            "\r\n".getBytes(StandardCharsets.US_ASCII)
                    )
                    .encodeToString(Files.readAllBytes(file.toPath()));

            out.append(base64).append("\r\n");
        }

        out.append("--").append(boundary).append("--\r\n");
        return out.toString();
    }

    // =========================================================
    // IMAP SENT-FOLDER COPY
    // =========================================================

    private static void saveCopyToSent(
            String username,
            String password,
            String message
    ) throws Exception {

        String host = Config.get("mail.imap.host");
        if (host.isBlank()) host = "imap.secureserver.net";

        int port = parseInt(Config.get("mail.imap.port"), 993);

        List<String> folders = new ArrayList<>();
        String configuredFolder = Config.get("mail.sent.folder");
        if (!configuredFolder.isBlank()) folders.add(configuredFolder);
        if (!folders.contains("Sent")) folders.add("Sent");
        if (!folders.contains("Sent Items")) folders.add("Sent Items");
        if (!folders.contains("INBOX.Sent")) folders.add("INBOX.Sent");

        byte[] mime = message.getBytes(StandardCharsets.UTF_8);

        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port)) {
            socket.setSoTimeout(45_000);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String greeting = readImapLine(in);
            if (greeting == null || !greeting.toUpperCase(java.util.Locale.ROOT).startsWith("* OK")) {
                throw new IOException("IMAP greeting failed: " + greeting);
            }

            String loginTag = "A001";
            writeImapLine(out,
                    loginTag + " LOGIN " + imapQuote(username) + " " + imapQuote(password));

            String loginResult = readUntilImapTag(in, loginTag);
            if (!isImapOk(loginResult, loginTag)) {
                throw new IOException("IMAP login failed: " + loginResult);
            }

            int tagNo = 2;
            Exception last = null;

            for (String folder : folders) {
                String tag = String.format(java.util.Locale.ROOT, "A%03d", tagNo++);

                try {
                    if (appendImapMessage(in, out, tag, folder, mime)) {
                        String logoutTag = String.format(java.util.Locale.ROOT, "A%03d", tagNo);
                        writeImapLine(out, logoutTag + " LOGOUT");
                        try { readUntilImapTag(in, logoutTag); } catch (Exception ignored) { }

                        System.out.println("SENT ITEMS FOLDER: " + folder);
                        return;
                    }
                } catch (Exception e) {
                    last = e;
                }
            }

            if (last != null) throw last;
            throw new IOException("No usable IMAP Sent folder found");
        }
    }

    private static boolean appendImapMessage(
            InputStream in,
            OutputStream out,
            String tag,
            String folder,
            byte[] mime
    ) throws Exception {

        String command = tag + " APPEND " + imapQuote(folder)
                + " (\\Seen) {" + mime.length + "}";
        writeImapLine(out, command);

        while (true) {
            String line = readImapLine(in);
            if (line == null) throw new EOFException("IMAP closed during APPEND");

            if (line.startsWith("+")) {
                out.write(mime);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String result = readUntilImapTag(in, tag);
                return isImapOk(result, tag);
            }

            if (line.regionMatches(true, 0, tag + " ", 0, tag.length() + 1)) {
                return isImapOk(line, tag);
            }
        }
    }

    private static void writeImapLine(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readUntilImapTag(InputStream in, String tag) throws IOException {
        StringBuilder all = new StringBuilder();

        while (true) {
            String line = readImapLine(in);
            if (line == null) throw new EOFException("IMAP server closed connection");

            if (!all.isEmpty()) all.append(" | ");
            all.append(line);

            if (line.regionMatches(true, 0, tag + " ", 0, tag.length() + 1)) {
                return line;
            }
        }
    }

    private static String readImapLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;

        while (true) {
            int b = in.read();
            if (b < 0) {
                if (buffer.size() == 0) return null;
                break;
            }

            if (previous == '\r' && b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') len--;
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }

            buffer.write(b);
            previous = b;
        }

        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static boolean isImapOk(String line, String tag) {
        if (line == null) return false;
        return line.regionMatches(true, 0, tag + " OK", 0, tag.length() + 3);
    }

    private static String imapQuote(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }


    private static long estimateMimeAttachmentBytes(long rawBytes) {
        long base64Chars = ((rawBytes + 2L) / 3L) * 4L;
        long lineBreakBytes = ((base64Chars + 75L) / 76L) * 2L;
        return base64Chars + lineBreakBytes + 4096L;
    }

    private static String humanMb(long bytes) {
        return String.format(
                java.util.Locale.ENGLISH,
                "%.2f",
                bytes / 1024.0 / 1024.0
        );
    }

    private static String encodeWord(String value) {
        String b64 = Base64.getEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8)
        );
        return "=?UTF-8?B?" + b64 + "?=";
    }

    private static void sendLine(BufferedWriter writer, String value) throws IOException {
        writer.write(value);
        writer.write("\r\n");
        writer.flush();
    }

    private static SmtpResponse readResponse(BufferedReader reader) throws IOException {
        String first = reader.readLine();
        if (first == null || first.length() < 3) {
            throw new EOFException("SMTP server closed connection");
        }

        int code;
        try {
            code = Integer.parseInt(first.substring(0, 3));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid SMTP response: " + first, e);
        }

        StringBuilder text = new StringBuilder(first);
        if (first.length() > 3 && first.charAt(3) == '-') {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(" | ").append(line);
                if (line.startsWith(String.valueOf(code) + " ")) break;
            }
        }

        return new SmtpResponse(code, text.toString());
    }

    private static void expect(SmtpResponse response, int expected, String stage) {
        if (response.code != expected) {
            throw new IllegalStateException(stage + " failed: " + response.text);
        }
    }

    private static void expect2xx(SmtpResponse response, String stage) {
        if (response.code < 200 || response.code >= 300) {
            throw new IllegalStateException(stage + " failed: " + response.text);
        }
    }

    private static String dotStuff(String message) {
        return message.replace("\r\n.", "\r\n..");
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private record SmtpResponse(int code, String text) {}
}
