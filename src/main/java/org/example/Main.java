package org.example;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class Main {

    // Per-PC worker count. Each computer reads its own PCn sheet tab.
    private static final int MAX_PARALLEL_USERS = intSetting("MAX_PARALLEL_USERS", 1);
    // Fast pickup/retry. The 429 fix is done by caching/de-duplicating Sheet
    // calls below, so active users do not need a long polling gap.
    private static final long SHEET_POLL_MS = 3_000L;
    private static final long SHEET_ERROR_BACKOFF_MS = 5_000L;

    /*
     * COLUMN F still stops e-learning for a completed candidate.
     * IMPORTANT PHOTO FIX: a completed row with column V blank is NOT ignored.
     * We still open SMY Profile in the lightweight/background profile worker:
     *   - if a real photo is already there -> write "Photo Exist" to U
     *   - if the photo is missing -> run the existing DG photo upload flow
     * E-learning itself is never reopened for that completed row.
     */
    private static final long COMPLETED_PHOTO_RETRY_MS = 60_000L;
    private static final Map<Integer, Long> COMPLETED_PHOTO_RETRY_AFTER =
            new ConcurrentHashMap<>();


    /*
     * Continuous module order.
     * This is the old Day 1 -> Day 2 -> Day 3 order flattened into one list.
     * There is NO normal gap between modules and NO next-day gate.
     */
    private static final List<String> CONTINUOUS_MODULE_ORDER = List.of(
            "Emotional Wellness",
            "Economic Wellness",
            "Physical Wellness",
            "Social Wellness",
            "Occupational Wellness",
            "Environmental Wellness",
            "Intellectual Wellness",
            "Spiritual Wellness",
            "Climatic Wellness",
            "Cultural Wellness"
    );

    public static void main(String[] args) {

        // Show only useful progress + critical errors in the console.
        CompactConsole.install();

        ExecutorService workers =
                Executors.newFixedThreadPool(MAX_PARALLEL_USERS);

        Set<Integer> rowsInProgress =
                ConcurrentHashMap.newKeySet();

        // Reserve a slot only when a worker can actually start.
        // Users are handled in normal local PC sheet-row order.
        Semaphore workerSlots =
                new Semaphore(MAX_PARALLEL_USERS);

        try {
            List<QuizPlanRow> quizPlan =
                    SheetRepository.readQuizPlan();

            printSchedule();

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // ONE API read gets this PC's own sheet rows.
                    List<UserCourseRow> users =
                            SheetRepository.readAllUsers();

                    for (UserCourseRow user : users) {
                        int row = user.sheetRowNumber();

                        if (user.username() == null || user.username().isBlank()) {
                            continue;
                        }

                        if (user.password() == null || user.password().isBlank()) {
                            SheetRepository.updateOverallStatus(row, "Password Missing");
                            continue;
                        }

                        // COLUMN F = master e-learning status. Never reopen
                        // wellness modules for a completed row. But if U is blank,
                        // still check the SMY profile/photo in the separate profile
                        // worker so existing photos are marked and missing photos
                        // can still be uploaded.
                        if ("Completed".equalsIgnoreCase(user.status().trim())) {
                            // Keep the certificate-download queue in sync even for
                            // candidates that were already Completed before this build.
                            try {
                                SheetRepository.copyCompletedCandidateToDownloadCertificate(
                                        row,
                                        user.username(),
                                        user.password(),
                                        user.studentName(),
                                        user.mobileNumber()
                                );
                            } catch (Exception queueError) {
                                System.out.println(
                                        "CERTIFICATE QUEUE SYNC FAILED | ROW " + row
                                                + " | " + conciseError(queueError)
                                );
                            }

                            boolean identityMissing = user.studentName() == null
                                    || user.studentName().trim().isBlank()
                                    || user.mobileNumber() == null
                                    || user.mobileNumber().trim().isBlank();

                            if (!DgProfileSync.isPhotoAlreadyMarked(user.photoStatus())
                                    || identityMissing) {
                                long now = System.currentTimeMillis();
                                long retryAfter = COMPLETED_PHOTO_RETRY_AFTER
                                        .getOrDefault(row, 0L);

                                if (now >= retryAfter) {
                                    COMPLETED_PHOTO_RETRY_AFTER.put(
                                            row,
                                            now + COMPLETED_PHOTO_RETRY_MS
                                    );

                                    try {
                                        Path workingDir =
                                                DgProfileSync.prepareWorkingDirectory(row);

                                        System.out.println(
                                                "PROFILE CHECK | ROW " + row
                                                        + " | E=Completed"
                                                        + (identityMissing ? " | C/D missing" : " | U blank")
                                        );

                                        DgProfileSync.startParallelSync(
                                                user.username(),
                                                user.password(),
                                                user.studentName(),
                                                user.mobileNumber(),
                                                user.indosNumber(),
                                                user.changedDgPassword(),
                                                user.aadhaarNumber(),
                                                row,
                                                workingDir
                                        );
                                    } catch (Exception photoCheckError) {
                                        System.out.println(
                                                "PHOTO CHECK FAILED | ROW " + row
                                                        + " | " + conciseError(photoCheckError)
                                        );
                                    }
                                }
                            }
                            continue;
                        }

                        // FAST CONTINUOUS MODE:
                        // Ignore/clear every old WAIT or DAY schedule status.
                        // No module/day/retry interval is allowed to block a user.
                        if (isAnyLegacyWaitStatus(user.status())) {
                            try {
                                SheetRepository.updateOverallStatus(row, "Ready");
                            } catch (Exception clearWaitError) {
                                System.out.println("Could not clear old wait for row "
                                        + row + ": " + conciseError(clearWaitError));
                            }
                        }

                        /*
                         * IMPORTANT:
                         * Old statuses such as
                         *   DAY 2 START AFTER ...
                         *   DAY 1 WAIT 1 HOUR UNTIL ...
                         * are old formats and are intentionally ignored.
                         * No schedule/wait status blocks fast continuous mode.
                         */

                        if (rowsInProgress.contains(row)) {
                            continue;
                        }

                        if (!workerSlots.tryAcquire()) {
                            // All four PC1 worker slots are already occupied.
                            // Refresh the sheet again after the normal polling interval.
                            break;
                        }

                        if (!rowsInProgress.add(row)) {
                            workerSlots.release();
                            continue;
                        }

                        workers.submit(() -> {
                            try {
                                processOneUser(user, quizPlan);
                            } finally {
                                rowsInProgress.remove(row);
                                workerSlots.release();
                            }
                        });
                    }

                } catch (Exception pollingError) {
                    System.out.println();
                    System.out.println("SHEET/NETWORK REFRESH PAUSED: "
                            + conciseError(pollingError));
                    System.out.println("Refreshing again after a short technical backoff.");
                    sleepSafely(SHEET_ERROR_BACKOFF_MS);
                }

                sleepSafely(SHEET_POLL_MS);
            }

        } catch (Exception mainError) {
            System.out.println("MAIN PAUSED: " + conciseError(mainError));

        } finally {
            workers.shutdownNow();
        }
    }

    private static void processOneUser(
            UserCourseRow user,
            List<QuizPlanRow> quizPlan
    ) {
        int row = user.sheetRowNumber();
        String displayName =
                user.studentName() == null || user.studentName().isBlank()
                        ? user.username()
                        : user.studentName();

        try {
            System.out.println();
            System.out.println("####################################");
            System.out.println("STARTING USER : " + displayName);
            System.out.println("SHEET ROW     : " + row);
            System.out.println("MODE          : ALL PENDING MODULES - NO GAP");
            System.out.println("####################################");

            SheetRepository.formatPendingRow(row);

            /*
             * STRICT ONE-BY-ONE MODE:
             * Every module is checked against the website in order.
             * ChapterRunner stops at the first module that is still Pending,
             * so a later course can never start while an earlier one is
             * incomplete. There is NO time gap between verified modules.
             */
            /*
             * CRITICAL FIX: do NOT use old G:P "Completed" values to decide
             * which modules to skip. Older code may have written Completed
             * while the SMY page still showed Pending. Queue all 10 modules;
             * ChapterRunner opens each module and Course skips only chapters
             * whose WEBSITE HEADER itself is green Completed.
             */
            List<String> modulesToCheck =
                    CONTINUOUS_MODULE_ORDER;

            System.out.println();
            System.out.println("STRICT SEQUENTIAL MODULES : " + modulesToCheck.size());
            System.out.println("NEXT MODULE              : " + modulesToCheck.get(0));
            for (String module : modulesToCheck) {
                System.out.println(" - QUEUED " + module);
            }

            SheetRepository.updateOverallStatus(
                    row,
                    "Running"
            );

            /*
             * Keep one login/browser session for this user and complete every
             * pending module continuously. The browser closes only after this
             * user's pending module list has been processed.
             */
            ChapterRunner.runMatchingModulesAndChapters(
                    user.username(),
                    user.password(),
                    modulesToCheck,
                    quizPlan,
                    row,
                    user.studentName(),
                    user.mobileNumber(),
                    user.indosNumber(),
                    user.changedDgPassword(),
                    user.aadhaarNumber(),
                    user.photoStatus()
            );

            // One fresh G:P read after the complete continuous run.
            List<String> freshStatuses =
                    SheetRepository.readModuleStatuses(row);

            List<String> remainingModules =
                    findIncompleteModules(freshStatuses);

            if (remainingModules.isEmpty()) {
                markFullyCompleted(
                        row,
                        displayName,
                        user.username(),
                        user.password(),
                        user.studentName(),
                        user.mobileNumber()
                );
                return;
            }

            // No retry interval. Leave the row ready for the next worker pass
            // immediately after the current browser session is closed.
            SheetRepository.updateOverallStatus(row, "Retrying");

            System.out.println();
            System.out.println("CONTINUOUS RUN ENDED WITH PENDING MODULES.");
            System.out.println("REMAINING MODULES : " + remainingModules);
            System.out.println("RETRY MODE        : IMMEDIATE - NO GAP");

        } catch (Exception userError) {
            System.out.println();

            try {
                SheetRepository.formatPendingRow(row);

                if (isLowMemoryProblem(userError)) {
                    SheetRepository.updateOverallStatus(row, "Retrying - Memory");
                    System.out.println("LOW MEMORY - WILL RETRY ON NEXT PASS: " + displayName);

                } else if (isNetworkOrLoginProblem(userError)) {
                    SheetRepository.updateOverallStatus(row, "Retrying - Network");
                    System.out.println("NETWORK/LOGIN PROBLEM - WILL RETRY ON NEXT PASS.");
                    System.out.println("USER : " + displayName);

                } else {
                    SheetRepository.updateOverallStatus(row, "Retrying");
                    System.out.println("USER RUN RETRY QUEUED IMMEDIATELY : " + displayName);
                }
            } catch (Exception sheetError) {
                System.out.println("Could not save retry status: " + sheetError.getMessage());
            }

            // Keep console clean: one concise reason instead of a long red stack trace.
            System.out.println("Reason: " + conciseError(userError));
        }
    }


    private static boolean isAnyLegacyWaitStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }

        String text = status.trim().toUpperCase(Locale.ENGLISH);
        return text.startsWith("WAIT ")
                || text.startsWith("DAY ")
                || text.contains(" START AFTER ")
                || text.contains(" WAIT ");
    }

    private static boolean isLowMemoryProblem(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null) {
                String text = message.toLowerCase(Locale.ENGLISH);
                if (text.contains("low system memory")
                        || text.contains("outofmemory")
                        || text.contains("out of memory")
                        || text.contains("cannot allocate memory")
                        || text.contains("native memory")) {
                    return true;
                }
            }

            if (current instanceof OutOfMemoryError) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNetworkOrLoginProblem(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String className = current.getClass().getSimpleName().toLowerCase(Locale.ENGLISH);
            String message = current.getMessage() == null
                    ? ""
                    : current.getMessage().toLowerCase(Locale.ENGLISH);

            // IMPORTANT: Selenium TimeoutException is normally just a slow/missing
            // page element. Do NOT turn every page timeout into a network cooldown.
            // Only explicit transport/DNS/socket failures are treated as network.
            if (className.contains("sockettimeout")
                    || className.contains("unknownhost")
                    || className.contains("connectexception")
                    || message.contains("net::")
                    || message.contains("err_connection")
                    || message.contains("err_internet")
                    || message.contains("err_name_not_resolved")
                    || message.contains("err_network_changed")
                    || message.contains("connection reset")
                    || message.contains("connection refused")
                    || message.contains("connection timed out")
                    || message.contains("connect timed out")
                    || message.contains("read timed out")
                    || message.contains("no route to host")
                    || message.contains("host is unreachable")
                    || message.contains("network is unreachable")
                    || message.contains("failed to establish")) {
                return true;
            }
        }
        return false;
    }

    private static String conciseError(Throwable error) {
        if (error == null) {
            return "Unknown";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        String oneLine = message.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 220) {
            oneLine = oneLine.substring(0, 220) + "...";
        }
        return error.getClass().getSimpleName() + " - " + oneLine;
    }

    private static List<String> findIncompleteModules(
            List<String> statuses
    ) {
        return CONTINUOUS_MODULE_ORDER.stream()
                .filter(moduleName -> !isModuleCompleted(statuses, moduleName))
                .toList();
    }

    private static boolean isModuleCompleted(
            List<String> statuses,
            String moduleName
    ) {
        int index = SheetRepository.moduleIndexOf(moduleName);

        if (index < 0 || index >= statuses.size()) {
            return false;
        }

        String status = statuses.get(index);
        String cleanedStatus = normalize(status);

        // Only ChapterRunner writes this after two website verification passes.
        return cleanedStatus.equals("completed");
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value
                .trim()
                .toLowerCase(Locale.ENGLISH)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static void markFullyCompleted(
            int sheetRowNumber,
            String displayName,
            String username,
            String password,
            String studentName,
            String mobileNumber
    ) throws Exception {
        if (!SheetRepository.areAllModulesCompleted(sheetRowNumber)) {
            System.out.println("FINAL COMPLETION CHECK FAILED - one or more modules still pending.");
            SheetRepository.formatPendingRow(sheetRowNumber);
            return;
        }

        SheetRepository.updateOverallStatus(
                sheetRowNumber,
                "Completed"
        );

        SheetRepository.formatCompletedRow(sheetRowNumber);

        // After final completion, queue the candidate in "Download certificate":
        // A = Date Entry (IST), B = username/email, C = SMY Password, D = Name, E = Mobile.
        // If PC1 D/E are still blank, the completed-row profile sync fills PC1 and
        // the next poll automatically repairs the queue row without duplicating it.
        // Do not clear PC1; the original completed row remains as the audit record.
        try {
            SheetRepository.copyCompletedCandidateToDownloadCertificate(
                    sheetRowNumber,
                    username,
                    password,
                    studentName,
                    mobileNumber
            );
        } catch (Exception queueError) {
            System.out.println(
                    "CERTIFICATE QUEUE COPY FAILED | ROW " + sheetRowNumber
                            + " | " + conciseError(queueError)
            );
        }


        System.out.println();
        System.out.println("============================================");
        System.out.println("ALL 10 MODULES COMPLETED");
        System.out.println("USER : " + displayName);
        System.out.println("============================================");
    }

    private static void printSchedule() {
        System.out.println();
        System.out.println("====================================");
        System.out.println(" YOGA MULTI-PC CONTINUOUS NO-GAP MODE");
        System.out.println(" Workers       : " + MAX_PARALLEL_USERS);
        System.out.println(" Sheet         : PC" + MachineShard.pcId());
        System.out.println(" Browser UI    : HIDDEN (HEADLESS)");
        System.out.println(" Browser load  : UP TO 4 ACTIVE HEADLESS SESSIONS (RAM-GUARDED)");
        System.out.println(" PC workers    : " + MAX_PARALLEL_USERS + " ACTIVE USER(S) ON THIS PC; NO MODULE GAP");
        System.out.println(" Sheet refresh : 3 seconds");
        System.out.println(" Module order  : Emotional -> Economic -> Physical -> Social");
        System.out.println("                 -> Occupational -> Environmental -> Intellectual");
        System.out.println("                 -> Spiritual -> Climatic -> Cultural");
        System.out.println(" Module gap    : NONE");
        System.out.println(" Run limit     : STRICT ONE-BY-ONE UNTIL ALL COMPLETE");
        System.out.println(" Day schedule  : REMOVED");
        System.out.println(" 24-hour gap   : REMOVED");
        System.out.println(" Priority      : NORMAL PC" + MachineShard.pcId() + " SHEET ORDER");
        System.out.println(" Module check  : WEBSITE HEADER STATUS IS FINAL TRUTH");
        System.out.println(" Retry delay   : NONE - next worker pass immediately");
        System.out.println(" 429 protection: G:P bulk reads + quota retry");
        System.out.println("====================================");
    }

    private static void sleepSafely(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int intSetting(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) value = System.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(1, Math.min(8, parsed));
        } catch (Exception e) {
            return defaultValue;
        }
    }

}
