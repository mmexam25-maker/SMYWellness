package org.example;

import java.io.File;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    private static final long POLL_MS = 15_000L;
    private static final long RETRY_MS = 20_000L;

    private static final ZoneId INDIA_ZONE =
            ZoneId.of("Asia/Kolkata");

    private static final DateTimeFormatter COMPLETED_AT_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm:ss a");


    // =========================================================
    // REQUIRED ASSESSMENT COURSES
    // =========================================================

    private static final List<String> REQUIRED_COURSES = List.of(

            "Emotional Wellness",
            "Economic Wellness",
            "Environmental Wellness",
            "Physical Wellness",
            "Intellectual Wellness",
            "Occupational Wellness",
            "Social Wellness",
            "Spiritual Wellness",
            "Climatic Wellness",
            "Cultural Wellness"
    );


    /*
     * Protect against duplicate email in same Java run.
     *
     * Example:
     * SMTP succeeds
     * but Column I update temporarily fails.
     *
     * We must NOT send the email again.
     */
    private static final Set<Integer> EMAILED_THIS_RUN =
            ConcurrentHashMap.newKeySet();

    private static volatile boolean COMPLETED_REPAIR_DONE = false;


    // =========================================================
    // MAIN
    // =========================================================

    public static void main(String[] args) {

        ShortConsole.install();
        System.out.println("START");

        System.out.println(
                "SMY - 10 ASSESSMENT CERTIFICATES"
                        + " -> EMAIL"
                        + " -> CANDIDATE WHATSAPP"
                        + " -> OPTIONAL AGENT WHATSAPP"
        );

        System.out.println("NO GOOGLE DRIVE UPLOAD IS USED.");

        System.out.println(
                "PROFILE CACHE: Column D = Name, Column E = WhatsApp No. Missing values are read from Profile in hidden Chrome."
        );

        System.out.println(
                "Agent email CC and agent WhatsApp are taken from config.properties."
        );

        System.out.println(
                "Successful rows are moved to Completed sheet."
        );

        System.out.println(
                "New rows are watched continuously."
                        + " FAILED rows are retried automatically."
        );


        // =====================================================
        // CONTINUOUS WATCH
        // =====================================================

        while (!Thread.currentThread().isInterrupted()) {

            try {

                SheetRepository sheet =
                        new SheetRepository();

                if (!COMPLETED_REPAIR_DONE) {
                    int repaired = sheet.repairShiftedCompletedRows();
                    if (repaired > 0) {
                        System.out.println("COMPLETED SHEET REPAIRED: " + repaired + " shifted row(s)");
                    }
                    COMPLETED_REPAIR_DONE = true;
                }

                Candidate candidate =
                        sheet.findNext();


                // -------------------------------------------------
                // NOTHING PENDING
                // -------------------------------------------------

                if (candidate == null) {

                    System.out.println(
                            "No pending row. Checking again..."
                    );

                    sleep(POLL_MS);

                    continue;
                }


                System.out.println("TRYING ROW " + candidate.rowNumber());

                // -------------------------------------------------
                // EMAIL ALREADY SENT DURING THIS JAVA RUN
                //
                // SMTP succeeded but sheet Column I write may have
                // temporarily failed.
                //
                // Repair Column I only.
                // DO NOT SEND EMAIL AGAIN.
                // -------------------------------------------------

                if (EMAILED_THIS_RUN.contains(
                        candidate.rowNumber()
                )) {

                    try {

                        sheet.updateMailStatusVerified(
                                candidate.rowNumber(),
                                "SENT"
                        );

                        EMAILED_THIS_RUN.remove(
                                candidate.rowNumber()
                        );

                        sheet.updateProcessStatus(
                                candidate.rowNumber(),
                                "EMAIL SENT - CONTINUING WHATSAPP"
                        );

                    } catch (Exception e) {

                        System.err.println(
                                "EMAIL ALREADY SENT"
                                        + " - RETRYING COLUMN I WRITE: "
                                        + shortMsg(e)
                        );

                        sleep(RETRY_MS);

                        continue;
                    }
                }


                // -------------------------------------------------
                // ONE EMAIL WAS ALREADY SENT
                //
                // Useful after restart where process status says
                // all email parts completed but Column I was not
                // successfully updated.
                //
                // Never resend.
                // -------------------------------------------------

                if (!"sent".equalsIgnoreCase(
                        candidate.mailStatus()
                )
                        && isAllEmailPartsSent(
                        candidate.processStatus()
                )) {

                    sheet.updateMailStatusVerified(
                            candidate.rowNumber(),
                            "SENT"
                    );

                    sheet.updateProcessStatus(
                            candidate.rowNumber(),
                            "EMAIL SENT - CONTINUING WHATSAPP"
                    );

                    finishWhatsappAndMove(
                            sheet,
                            candidate,
                            resolveCandidateMobileForWhatsapp(sheet, candidate)
                    );

                    continue;
                }


                // -------------------------------------------------
                // COLUMN I ALREADY SENT
                //
                // Skip:
                // - login
                // - download
                // - email
                //
                // Only finish WhatsApp + move.
                // -------------------------------------------------

                if ("sent".equalsIgnoreCase(
                        candidate.mailStatus()
                )) {

                    finishWhatsappAndMove(
                            sheet,
                            candidate,
                            resolveCandidateMobileForWhatsapp(sheet, candidate)
                    );

                    continue;
                }


                // -------------------------------------------------
                // NORMAL NEW / FAILED ROW
                // -------------------------------------------------

                processEmailWhatsappAndMove(
                        sheet,
                        candidate
                );


            } catch (Exception e) {

                System.err.println("FAILED - RETRY");

                sleep(RETRY_MS);
            }
        }
    }


    // =========================================================
    // COMPLETE EMAIL + WHATSAPP + MOVE WORKFLOW
    // =========================================================

    private static void processEmailWhatsappAndMove(
            SheetRepository sheet,
            Candidate candidate
    ) {

        int row =
                candidate.rowNumber();

        boolean emailSent =
                false;


        try {

            // -----------------------------------------------------
            // VALIDATE LOGIN
            // -----------------------------------------------------

            if (candidate.loginId() == null
                    || candidate.loginId().isBlank()
                    || candidate.password() == null
                    || candidate.password().isBlank()) {

                throw new IllegalStateException(
                        "Column B login/email"
                                + " or Column C password is blank"
                );
            }


            sheet.updateProcessStatus(
                    row,
                    "RUNNING - FILTERING ASSESSMENT"
                            + " + DOWNLOADING 10 CERTIFICATES"
            );


            // -----------------------------------------------------
            // DOWNLOAD CERTIFICATES
            // -----------------------------------------------------

            SagarCertificateBot bot =
                    new SagarCertificateBot();


            Map<String, File> courseFiles =
                    Collections.synchronizedMap(
                            new LinkedHashMap<>()
                    );


            // Email greeting uses Candidate Name from Column D. If Column D is blank,
            // the headless certificate bot reads the name from the SMY Profile and
            // writes it back to Column D before the email is sent.
            // WhatsApp uses Column E in the same way.
            final String[] resolvedName = { clean(candidate.candidateName()) };
            final String[] resolvedMobile = { normalizeMobile(candidate.mobileNumber()) };

            bot.download(
                    candidate,

                    new SagarCertificateBot.DownloadListener() {


                        // =========================================
                        // PROFILE READ
                        // =========================================

                        @Override
                        public void onProfileRead(
                                SagarCertificateBot.ProfileDetails profile
                        ) throws Exception {
                            if (profile == null) {
                                return;
                            }

                            String profileName = clean(profile.candidateName());
                            String profileMobile = normalizeMobile(profile.mobileNumber());

                            // Fill ONLY blank profile cells. Never replace existing C/D.
                            if (resolvedName[0].isBlank()
                                    && !profileName.isBlank()) {
                                sheet.updateNameVerified(row, profileName);
                                resolvedName[0] = profileName;
                                System.out.println(
                                        "COLUMN C NAME SAVED FROM PROFILE: " + profileName
                                );
                            }

                            if (normalizeMobile(candidate.mobileNumber()).isBlank()
                                    && !profileMobile.isBlank()) {
                                sheet.updateMobileVerified(row, profileMobile);
                                System.out.println(
                                        "COLUMN D WHATSAPP NO SAVED FROM PROFILE: " + profileMobile
                                );
                            }

                            if (resolvedMobile[0].isBlank() && !profileMobile.isBlank()) {
                                resolvedMobile[0] = profileMobile;
                                System.out.println(
                                        "CANDIDATE MOBILE RESOLVED FROM PROFILE: "
                                                + resolvedMobile[0]
                                );
                            }
                        }

                        // =========================================
                        // DRIVE IS NOT USED
                        // =========================================

                        @Override
                        public Set<String> alreadyUploadedCourses() {

                            return Set.of();
                        }


                        // =========================================
                        // CERTIFICATE DOWNLOADED
                        // =========================================

                        @Override
                        public void onCertificateDownloaded(
                                String courseName,
                                File certificate
                        ) throws Exception {


                            courseFiles.put(
                                    courseName,
                                    certificate
                            );


                            sheet.updateProcessStatus(
                                    row,
                                    "RUNNING - DOWNLOADED "
                                            + courseFiles.size()
                                            + "/10 - "
                                            + courseName
                            );


                            System.out.println("DOWNLOADING " + courseFiles.size() + "/10");
                        }


                        // =========================================
                        // CSV NOT USED
                        // =========================================

                        @Override
                        public void onCsvDownloaded(
                                File csv
                        ) {

                            // CSV and Drive are not used.
                        }
                    }
            );


            // -----------------------------------------------------
            // VERIFY ALL 10 CERTIFICATES
            // -----------------------------------------------------

            verifyTenCourses(
                    courseFiles
            );


            sheet.updateProcessStatus(
                    row,
                    "RUNNING - 10/10 DOWNLOADED"
                            + " - READING INDOS NUMBER"
            );


            // -----------------------------------------------------
            // READ INDOS
            // -----------------------------------------------------

            String indos =
                    CertificateMetadata.extractIndosFromAny(
                            courseFiles.values()
                    );


            if (indos.isBlank()) {

                throw new IllegalStateException(
                        "INDoS number not found"
                                + " inside downloaded certificates"
                );
            }


            // -----------------------------------------------------
            // RENAME CERTIFICATES
            // -----------------------------------------------------

            Map<String, File> renamed =
                    CertificateMetadata.renameCertificates(
                            orderedMap(
                                    courseFiles
                            ),
                            "",
                            indos
                    );


            // -----------------------------------------------------
            // CREATE ATTACHMENT LIST
            // -----------------------------------------------------

            List<File> attachments =
                    new ArrayList<>();


            for (String course :
                    REQUIRED_COURSES) {

                File file =
                        renamed.get(
                                course
                        );


                if (file == null
                        || !file.isFile()
                        || file.length() <= 0) {

                    throw new IllegalStateException(
                            "Missing attachment after rename: "
                                    + course
                    );
                }


                attachments.add(
                        file
                );
            }


            // =====================================================
            // EMAIL
            // =====================================================

            MailClient mailClient =
                    new MailClient();


            int resumeAfterBatch =
                    isAllEmailPartsSent(candidate.processStatus())
                            ? 1
                            : 0;


            List<List<File>> emailBatches =
                    mailClient.planBatches(
                            attachments
                    );


            int totalEmailBatches =
                    emailBatches.size();


            if (resumeAfterBatch
                    > totalEmailBatches) {

                // Bad/stale marker.
                // Restart email batching.

                resumeAfterBatch = 0;
            }


            final int[] sentEmailBatches = {
                    resumeAfterBatch
            };


            final int plannedBatches =
                    totalEmailBatches;


            // -----------------------------------------------------
            // ONE EMAIL ALREADY SENT
            // -----------------------------------------------------

            if (resumeAfterBatch
                    == totalEmailBatches
                    && totalEmailBatches > 0) {


                System.out.println(
                        "ONE EMAIL WAS ALREADY SENT"
                                + " - NOT RESENDING"
                );


                emailSent =
                        true;


            } else {


                sheet.updateProcessStatus(
                        row,
                        "RUNNING - SENDING 10 CERTIFICATES IN ONE EMAIL"
                );


                // =================================================
                // AGENT CC
                //
                // Column L contains agent name.
                //
                // Example:
                //
                // Column L = Muthu_Punnai
                //
                // Config:
                //
                // agent.muthu_punnai.email=
                // spmcomputer2024@gmail.com
                //
                // Agent email automatically becomes CC.
                // =================================================

                String agentName =
                        clean(
                                candidate.agentName()
                        );


                String ccEmail =
                        Config.agentEmail(
                                agentName
                        );


                if (agentName.isBlank()) {

                    System.out.println(
                            "COLUMN L AGENT BLANK"
                                    + " - NO EMAIL CC"
                    );

                } else if (ccEmail.isBlank()) {

                    System.out.println(
                            "AGENT EMAIL NOT CONFIGURED"
                                    + " - NO CC"
                                    + " | Agent: "
                                    + agentName
                    );

                } else {

                    System.out.println(
                            "AGENT EMAIL CC: "
                                    + agentName
                                    + " -> "
                                    + ccEmail
                    );
                }


                // =================================================
                // SEND CERTIFICATE EMAIL
                //
                // TO:
                // Candidate email - Column B
                //
                // CC:
                // Agent email from config.properties
                //
                // =================================================

                mailClient.sendCertificates(

                        candidate.loginId(),

                        ccEmail,

                        resolvedName[0],

                        indos,

                        attachments,

                        resumeAfterBatch,

                        (sentBatchNumber,
                         totalBatches) -> {


                            sentEmailBatches[0] =
                                    sentBatchNumber;


                            sheet.updateProcessStatus(
                                    row,
                                    "EMAIL SENT - ALL 10 CERTIFICATES IN ONE MAIL"
                            );
                        }
                );


                emailSent =
                        sentEmailBatches[0]
                                >= plannedBatches;
            }


            // -----------------------------------------------------
            // EMAIL DID NOT FINISH
            // -----------------------------------------------------

            if (!emailSent) {

                throw new IllegalStateException(
                        "The single certificate email did not complete"
                );
            }


            // -----------------------------------------------------
            // PROTECT AGAINST DUPLICATE EMAIL
            // -----------------------------------------------------

            EMAILED_THIS_RUN.add(
                    row
            );


            // -----------------------------------------------------
            // COLUMN I = SENT
            // -----------------------------------------------------

            sheet.updateMailStatusVerified(
                    row,
                    "SENT"
            );
            System.out.println("EMAIL SENT");


            // -----------------------------------------------------
            // DELETE TEMP CERTIFICATE FILES AFTER EMAIL SUCCESS
            // -----------------------------------------------------
            // The SMTP send has completed and Column I is confirmed SENT.
            // The PDFs are no longer required locally, so remove all 10 temp
            // attachments before WhatsApp / Completed-sheet processing.
            deleteTempCertificateFiles(attachments);


            EMAILED_THIS_RUN.remove(
                    row
            );


            sheet.updateProcessStatus(
                    row,
                    "EMAIL SENT"
                            + " - SENDING WHATSAPP"
            );


            // -----------------------------------------------------
            // WHATSAPP + MOVE
            // -----------------------------------------------------

            finishWhatsappAndMove(
                    sheet,
                    candidate,
                    resolvedMobile[0]
            );


            System.out.println("SUCCESS");


        } catch (Exception e) {

            String reason =
                    shortMsg(
                            e
                    );


            System.err.println(
                    "ROW "
                            + row
                            + " FAILED - WILL RETRY: "
                            + reason
            );


            e.printStackTrace();


            // -----------------------------------------------------
            // EMAIL ALREADY SENT
            //
            // Never resend it.
            // Only WhatsApp/move may retry.
            // -----------------------------------------------------

            if (emailSent) {

                EMAILED_THIS_RUN.add(
                        row
                );


                try {

                    sheet.updateProcessStatus(
                            row,
                            "EMAIL SENT"
                                    + " - WHATSAPP/MOVE WILL RETRY"
                                    + " - "
                                    + reason
                    );

                } catch (Exception ignored) {
                }


            } else {


                // -------------------------------------------------
                // EMAIL FAILED
                // -------------------------------------------------

                try {

                    sheet.updateMailStatusVerified(
                            row,
                            "FAILED"
                    );

                } catch (Exception statusError) {

                    System.err.println(
                            "Could not write FAILED to Column I: "
                                    + shortMsg(
                                    statusError
                            )
                    );
                }


                // -------------------------------------------------
                // ONE-MAIL MODE: there is no partial batch to resume.
                // If SMTP had already been accepted, the listener/status
                // above preserves EMAIL SENT and the next cycle will not resend.
                // Otherwise retry the complete single email later.
                // -------------------------------------------------

                try {
                    String savedStatus = sheet.readProcessStatus(row);

                    if (isAllEmailPartsSent(savedStatus)) {
                        sheet.updateProcessStatus(
                                row,
                                "EMAIL SENT - POST-SEND STEP WILL RETRY - " + reason
                        );
                    } else {
                        sheet.updateProcessStatus(
                                row,
                                "FAILED - ONE EMAIL NOT SENT - WILL RETRY AUTOMATICALLY - " + sanitizeReason(reason)
                        );
                    }
                } catch (Exception ignored) {
                }
            }


            sleep(
                    RETRY_MS
            );
        }
    }


    // =========================================================
    // WHATSAPP + COMPLETED SHEET
    // =========================================================

    /**
     * Candidate profile / WhatsApp resolution:
     * 1) use Column D (Name) and Column E (WhatsApp No) when present;
     * 2) if either is blank, login to SMY in hidden/headless Chrome and read
     *    the Profile Name + Mobile Number;
     * 3) write only missing values back to C/D;
     * 4) never resend email just because profile details had to be resolved later.
     */
    private static String resolveCandidateMobileForWhatsapp(
            SheetRepository sheet,
            Candidate candidate
    ) {
        String sheetName = clean(candidate.candidateName());
        String mobile = normalizeMobile(candidate.mobileNumber());

        // If both C and D are already populated, no Profile visit is required.
        if (!sheetName.isBlank() && !mobile.isBlank()) {
            return mobile;
        }

        try {
            System.out.println(
                    "COLUMN C/D PROFILE DETAILS MISSING - READING PROFILE IN HIDDEN CHROME"
            );

            SagarCertificateBot.ProfileDetails profile =
                    new SagarCertificateBot().readProfileOnly(candidate);

            String profileName = clean(profile.candidateName());
            String profileMobile = normalizeMobile(profile.mobileNumber());

            // Fill only blanks; never overwrite user-entered sheet values.
            if (sheetName.isBlank() && !profileName.isBlank()) {
                sheet.updateNameVerified(candidate.rowNumber(), profileName);
                sheetName = profileName;
                System.out.println(
                        "COLUMN C NAME SAVED FROM PROFILE: " + profileName
                );
            }

            if (mobile.isBlank() && !profileMobile.isBlank()) {
                sheet.updateMobileVerified(candidate.rowNumber(), profileMobile);
                mobile = profileMobile;
                System.out.println(
                        "COLUMN D WHATSAPP NO SAVED FROM PROFILE: " + profileMobile
                );
            }

            if (!mobile.isBlank()) {
                System.out.println(
                        "CANDIDATE MOBILE READY FOR WHATSAPP: " + mobile
                );
                return mobile;
            }

        } catch (Exception e) {
            System.err.println(
                    "PROFILE LOOKUP FAILED - WHATSAPP WILL BE MARKED FAILED: "
                            + shortMsg(e)
            );
        }

        return mobile;
    }

    private static void finishWhatsappAndMove(
            SheetRepository sheet,
            Candidate candidate,
            String candidateMobile
    ) throws Exception {

        int row = candidate.rowNumber();
        String agent = clean(candidate.agentName());
        String currentStatus = clean(candidate.whatsappStatus());

        /*
         * IMPORTANT:
         * Column I = SENT means the certificate email is already finished.
         * From this point onward the bot MUST NOT download certificates or
         * send the email again. It only completes the pending WhatsApp work.
         *
         * If WhatsApp fails, keep the row in the current sheet with Column I
         * still SENT. The continuous watcher will pick it again and retry ONLY
         * WhatsApp. A successful candidate/agent WhatsApp is persisted in
         * Column J so it is never sent twice on a retry.
         */
        String upper = currentStatus.toUpperCase();

        boolean allWhatsappDone = "SENT".equalsIgnoreCase(currentStatus);
        boolean candidateDone = allWhatsappDone || upper.contains("CANDIDATE SENT");
        boolean agentDone = agent.isBlank()
                || allWhatsappDone
                || upper.contains("AGENT SENT");

        boolean candidateFailed = false;
        boolean agentFailed = false;

        WhatsAppClient whatsapp = new WhatsAppClient();

        // =========================================================
        // CANDIDATE WHATSAPP - COLUMN D OR PROFILE FALLBACK
        // =========================================================
        if (!candidateDone) {
            if (candidateMobile == null || candidateMobile.isBlank()) {
                candidateFailed = true;
                System.err.println("CANDIDATE WHATSAPP SKIPPED - NO MOBILE IN COLUMN D OR PROFILE");
            } else {
                try {
                    sheet.updateProcessStatus(
                            row,
                            "EMAIL SENT - SENDING WHATSAPP TO CANDIDATE"
                    );

                    whatsapp.sendCandidateNotification(candidateMobile);
                    candidateDone = true;

                    // Persist this immediately. If a later agent/move step fails,
                    // candidate WhatsApp will NOT be repeated on the retry.
                    sheet.updateWhatsappStatusVerified(row, "CANDIDATE SENT");

                    System.out.println("WHATSAPP SENT");

                } catch (Exception e) {
                    candidateFailed = true;
                    System.err.println(
                            "CANDIDATE WHATSAPP FAILED - CONTINUING: " + shortMsg(e)
                    );
                }
            }
        } else {
            System.out.println("CANDIDATE WHATSAPP ALREADY SENT - NOT RESENDING");
        }

        // =========================================================
        // OPTIONAL AGENT WHATSAPP - COLUMN L
        // =========================================================
        if (!agent.isBlank() && !agentDone) {
            String agentNumber = Config.agentNumber(agent);

            if (agentNumber.isBlank()) {
                agentFailed = true;
                System.err.println(
                        "AGENT WHATSAPP NOT CONFIGURED - CONTINUING | Agent: " + agent
                );
            } else {
                try {
                    sheet.updateProcessStatus(
                            row,
                            "EMAIL SENT - SENDING AGENT WHATSAPP: " + agent
                    );

                    whatsapp.sendAgentNotification(agentNumber, agent);
                    agentDone = true;

                    // Persist agent success immediately. This protects against
                    // duplicates if the final Completed-sheet move fails.
                    String partial = candidateDone
                            ? "CANDIDATE SENT | AGENT SENT"
                            : "CANDIDATE FAILED | AGENT SENT";
                    sheet.updateWhatsappStatusVerified(row, partial);

                    System.out.println(
                            "AGENT WHATSAPP SENT: " + agent + " -> " + agentNumber
                    );

                } catch (Exception e) {
                    agentFailed = true;
                    System.err.println(
                            "AGENT WHATSAPP FAILED - CONTINUING | "
                                    + agent + " | " + shortMsg(e)
                    );
                }
            }
        } else if (agent.isBlank()) {
            System.out.println("COLUMN L AGENT BLANK - NO AGENT WHATSAPP REQUIRED");
        } else {
            System.out.println("AGENT WHATSAPP ALREADY SENT - NOT RESENDING: " + agent);
        }

        // =========================================================
        // FINAL WHATSAPP STATUS
        // =========================================================
        String whatsappResult;

        if (candidateDone && agentDone) {
            whatsappResult = "SENT";
        } else if (candidateDone && !agentDone) {
            whatsappResult = "CANDIDATE SENT | AGENT FAILED";
        } else if (!candidateDone && agentDone && !agent.isBlank()) {
            whatsappResult = "CANDIDATE FAILED | AGENT SENT";
        } else {
            whatsappResult = "FAILED";
        }

        sheet.updateWhatsappStatusVerified(row, whatsappResult);

        // =========================================================
        // IF WHATSAPP IS NOT COMPLETE: LEAVE ROW HERE
        // =========================================================
        // Column I remains SENT, so the next watcher pass will enter the
        // "Column I already SENT" branch and retry ONLY the missing WhatsApp.
        if (!"SENT".equalsIgnoreCase(whatsappResult)) {
            String retryMessage = "EMAIL ALREADY SENT - WHATSAPP "
                    + whatsappResult
                    + " - RETRY WHATSAPP ONLY";

            sheet.updateProcessStatus(row, retryMessage);

            System.err.println(
                    "WHATSAPP NOT COMPLETE - ROW KEPT IN CURRENT SHEET"
                            + " | Column I remains SENT"
                            + " | status: " + whatsappResult
            );
            return;
        }

        String completionMessage = agent.isBlank()
                ? "COMPLETED - EMAIL + CANDIDATE WHATSAPP SENT - MOVING ROW"
                : "COMPLETED - EMAIL + CANDIDATE + AGENT WHATSAPP SENT - MOVING ROW";

        sheet.updateProcessStatus(row, completionMessage);

        // =========================================================
        // COLUMN M = COMPLETED DATE/TIME
        // =========================================================
        String completedAt = ZonedDateTime
                .now(INDIA_ZONE)
                .format(COMPLETED_AT_FORMAT);

        String savedCompletedAt = sheet.updateCompletedDateTimeIfBlank(
                row,
                completedAt
        );

        System.out.println("COLUMN M COMPLETED DATE/TIME: " + savedCompletedAt);

        // =========================================================
        // MOVE A:M TO COMPLETED
        // =========================================================
        // Move only after WhatsApp is fully SENT. If WhatsApp failed earlier,
        // the row stayed here with Column I = SENT and email was never resent.
        sheet.moveToCompleted(row);

        System.out.println(
                "MOVED TO COMPLETED SHEET: source row " + row
                        + " | WhatsApp status: " + whatsappResult
        );
    }

    // =========================================================
    // DELETE TEMP CERTIFICATES AFTER EMAIL SUCCESS
    // =========================================================

    private static void deleteTempCertificateFiles(List<File> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }

        int deleted = 0;

        for (File file : attachments) {
            if (file == null) {
                continue;
            }

            try {
                if (Files.deleteIfExists(file.toPath())) {
                    deleted++;
                    System.out.println(
                            "TEMP CERTIFICATE DELETED AFTER EMAIL: " + file.getName()
                    );
                }
            } catch (Exception e) {
                // Email is already sent. A local cleanup problem must never make
                // the bot send the email again. Log it and continue.
                System.err.println(
                        "TEMP CERTIFICATE DELETE FAILED - CONTINUING: "
                                + file.getAbsolutePath() + " | " + shortMsg(e)
                );
            }
        }

        System.out.println(
                "TEMP CERTIFICATE CLEANUP: " + deleted + "/"
                        + attachments.size() + " file(s) deleted after email."
        );
    }


    // =========================================================
    // VERIFY ALL 10 CERTIFICATES
    // =========================================================

    private static void verifyTenCourses(
            Map<String, File> courseFiles
    ) {


        Set<String> missing =
                new LinkedHashSet<>();


        for (String course :
                REQUIRED_COURSES) {


            File file =
                    courseFiles.get(
                            course
                    );


            if (file == null
                    || !file.isFile()
                    || file.length() <= 0) {


                missing.add(
                        course
                );
            }
        }


        if (!missing.isEmpty()) {


            throw new IllegalStateException(

                    "Only "
                            + (
                            10
                                    - missing.size()
                    )
                            + "/10 certificates downloaded."
                            + " Missing: "
                            + String.join(
                            ", ",
                            missing
                    )
            );
        }
    }


    // =========================================================
    // ORDER FILES IN REQUIRED COURSE ORDER
    // =========================================================

    private static Map<String, File> orderedMap(
            Map<String, File> source
    ) {


        Map<String, File> result =
                new LinkedHashMap<>();


        for (String course :
                REQUIRED_COURSES) {


            result.put(
                    course,
                    source.get(
                            course
                    )
            );
        }


        return result;
    }


    // =========================================================
    // NORMALIZE MOBILE TO LAST 10 DIGITS
    // =========================================================

    private static String normalizeMobile(
            String value
    ) {


        if (value == null) {

            return "";
        }


        String digits =
                value.replaceAll(
                        "\\D",
                        ""
                );


        if (digits.isBlank()) {

            return "";
        }


        /*
         * Examples:
         *
         * 09677577715
         * ->
         * 9677577715
         *
         * +91 9677577715
         * ->
         * 9677577715
         *
         * 919677577715
         * ->
         * 9677577715
         */


        while (digits.length() > 10
                && digits.startsWith("0")) {


            digits =
                    digits.substring(
                            1
                    );
        }


        if (digits.length() > 10) {


            digits =
                    digits.substring(
                            digits.length() - 10
                    );
        }


        return digits.length() == 10
                ? digits
                : "";
    }


    // =========================================================
    // SAFE STRING
    // =========================================================

    private static String clean(
            String value
    ) {


        return value == null
                ? ""
                : value.trim();
    }


    // =========================================================
    // EMAIL STATUS HELPERS
    // =========================================================

    private static boolean isAllEmailPartsSent(
            String processStatus
    ) {


        if (processStatus == null
                || processStatus.isBlank()) {


            return false;
        }


        String upper = processStatus.toUpperCase();

        return upper.startsWith("EMAIL SENT")
                || upper.contains("ALL EMAIL PARTS SENT");
    }


    private static int parseSentEmailBatch(
            String processStatus
    ) {


        if (processStatus == null
                || processStatus.isBlank()) {


            return 0;
        }


        Matcher matcher =
                Pattern.compile(

                        "EMAIL\\s+"
                                + "(?:PART|BATCH)"
                                + "\\s+(\\d+)"
                                + "(?:\\s*/\\s*\\d+)?"
                                + "\\s+SENT",

                        Pattern.CASE_INSENSITIVE

                ).matcher(
                        processStatus
                );


        int highest =
                0;


        while (matcher.find()) {


            try {


                highest =
                        Math.max(

                                highest,

                                Integer.parseInt(
                                        matcher.group(
                                                1
                                        )
                                )
                        );


            } catch (NumberFormatException ignored) {
            }
        }


        return highest;
    }


    // =========================================================
    // AGENT EMAIL FOR LOG
    // =========================================================

    private static String ccEmailForLog(
            Candidate candidate
    ) {


        try {


            String email =
                    Config.agentEmail(
                            clean(
                                    candidate.agentName()
                            )
                    );


            return email.isBlank()
                    ? "NONE"
                    : email;


        } catch (Exception e) {


            return "NONE";
        }
    }


    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) return "Unknown error";
        String t = reason.replaceAll("\\s+", " ").trim();
        if (t.toLowerCase().contains("timeoutexception") || t.toLowerCase().contains("timeout")) {
            return "TIMEOUT";
        }
        int build = t.indexOf("Build info:");
        if (build >= 0) t = t.substring(0, build).trim();
        return t.length() <= 120 ? t : t.substring(0, 120);
    }

    // =========================================================
    // SHORT ERROR MESSAGE
    // =========================================================

    private static String shortMsg(
            Exception e
    ) {


        if (e instanceof java.util.concurrent.TimeoutException
                || e instanceof org.openqa.selenium.TimeoutException) {
            return "TIMEOUT - will retry automatically";
        }

        String text =
                e.getMessage();


        if (text == null
                || text.isBlank()) {


            text =
                    e.getClass()
                            .getSimpleName();
        }


        text =
                text.replaceAll(
                        "\\s+",
                        " "
                ).trim();


        return text.length() <= 180

                ? text

                : text.substring(
                0,
                180
        );
    }

    // =========================================================
    // SAFE SLEEP
    // =========================================================

    private static void sleep(
            long ms
    ) {


        try {


            Thread.sleep(
                    ms
            );


        } catch (InterruptedException e) {


            Thread.currentThread()
                    .interrupt();
        }
    }
}