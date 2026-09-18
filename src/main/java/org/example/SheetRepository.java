package org.example;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.CellFormat;
import com.google.api.services.sheets.v4.model.Color;
import com.google.api.services.sheets.v4.model.GridRange;
import com.google.api.services.sheets.v4.model.RepeatCellRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.TextFormat;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SheetRepository {

    private static final String APPLICATION_NAME =
            "Sagar Mein Yog Automation";

    private static final String SPREADSHEET_ID =
            "1YCWNaiJENZuNTPfDd0-w6DYOSkLifehy1y0CX-9YSc4";

    private static final String CREDENTIALS_FILE =
            "credentials.json";

    // IMPORTANT: each PC reads its own sheet tab: PC1, PC2, ... PC11.
    private static final String USER_SHEET =
            "PC" + MachineShard.pcId();

    private static final String QUIZ_SHEET =
            "Sheet2";

    // Completed candidates are queued here for the certificate downloader.
    // Download certificate layout:
    // A = Date Entry (dd/MM/yyyy, India time)
    // B = SMY username/email
    // C = SMY Password
    // D = Name
    // E = Mobile
    // Keep the original PC1 completed row untouched.
    private static final String DOWNLOAD_CERTIFICATE_SHEET =
            "Download certificate";

    /*
     * PC1 layout (current sheet):
     * A = Date Entry (dd/MM/yyyy)
     * B = SMY User Name
     * C = SMY Password
     * D = Name (filled from SMY Profile when blank)
     * E = Mobile No (filled from SMY Profile when blank)
     * F = Overall Running/Completed Status
     * G = Emotional Wellness
     * H = Economic Wellness
     * I = Physical Wellness
     * J = Occupational Wellness
     * K = Social Wellness
     * L = Environmental Wellness
     * M = Intellectual Wellness
     * N = Spiritual Wellness
     * O = Climatic Wellness
     * P = Cultural Wellness
     * R = optional URGENT flag
     * S = INDoS number (filled from SMY Profile)
     * T = Changed DG/eSamudra password (optional)
     * U = Aadhaar number
     * V = Photo status ("Photo Exist" means no future profile/photo check)
     */
    public static final List<String> ALL_MODULES = List.of(
            "Emotional Wellness",
            "Economic Wellness",
            "Physical Wellness",
            "Occupational Wellness",
            "Social Wellness",
            "Environmental Wellness",
            "Intellectual Wellness",
            "Spiritual Wellness",
            "Climatic Wellness",
            "Cultural Wellness"
    );

    private static volatile Sheets sheetsService;
    private static volatile Integer userSheetId;
    private static volatile Integer downloadCertificateSheetId;

    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter ENTRY_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static volatile boolean downloadCertificateHeadersReady;

    /*
     * Small in-memory caches remove redundant Google Sheets requests.
     * The main A:V bulk read refreshes these values every poll, and successful
     * writes update them immediately. Active course playback never waits for an
     * extra Sheet read just to discover the status we already wrote.
     */
    private static final Map<String, String> MODULE_STATUS_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<Integer, String> OVERALL_STATUS_CACHE =
            new ConcurrentHashMap<>();
    private static final Set<Integer> PENDING_FORMATTED_ROWS =
            ConcurrentHashMap.newKeySet();
    private static final Set<Integer> COMPLETED_FORMATTED_ROWS =
            ConcurrentHashMap.newKeySet();
    private static final Set<String> CERTIFICATE_QUEUE_CACHE =
            ConcurrentHashMap.newKeySet();

    private SheetRepository() {
    }

    @FunctionalInterface
    private interface ApiCall<T> {
        T run() throws Exception;
    }

    /*
     * Google Sheets can return HTTP 429 when the per-user quota is reached.
     * Instead of failing the candidate immediately, wait and retry.
     */
    private static <T> T withQuotaRetry(ApiCall<T> call)
            throws Exception {

        long[] delays = {
                5_000L,
                15_000L,
                30_000L,
                60_000L
        };

        int retry = 0;

        while (true) {
            try {
                return call.run();

            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() != 429 || retry >= delays.length) {
                    throw e;
                }

                long delay = delays[retry++];

                System.out.println();
                System.out.println("GOOGLE SHEETS 429 RATE LIMIT.");
                System.out.println("Waiting " + (delay / 1000)
                        + " seconds before retry " + retry + "...");

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
    }

    private static Sheets getSheetsService()
            throws Exception {

        Sheets current = sheetsService;
        if (current != null) {
            return current;
        }

        synchronized (SheetRepository.class) {
            if (sheetsService != null) {
                return sheetsService;
            }

            GoogleCredentials credentials;

            try (FileInputStream input =
                         new FileInputStream(CREDENTIALS_FILE)) {

                credentials = GoogleCredentials
                        .fromStream(input)
                        .createScoped(
                                Collections.singleton(
                                        SheetsScopes.SPREADSHEETS
                                )
                        );
            }

            sheetsService = new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials)
            )
                    .setApplicationName(APPLICATION_NAME)
                    .build();

            return sheetsService;
        }
    }

    /*
     * Reads all candidates in ONE request.
     * A Date Entry
     * B SMY Username
     * C SMY Password
     * D Student Name
     * E Mobile No
     * F Overall Status
     * G:P Module statuses
     * R optional URGENT flag
     * S INDoS number (filled from SMY Profile)
     * T Changed DG password
     * U Aadhaar
     * V Photo status
     */
    public static List<UserCourseRow> readAllUsers()
            throws Exception {

        ValueRange response = withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .get(
                                SPREADSHEET_ID,
                                USER_SHEET + "!A2:V"
                        )
                        .execute()
        );

        List<List<Object>> values = response.getValues();
        List<UserCourseRow> users = new ArrayList<>();

        if (values == null || values.isEmpty()) {
            System.out.println("No users found in " + USER_SHEET + ".");
            return users;
        }

        for (int index = 0; index < values.size(); index++) {
            List<Object> row = values.get(index);
            int sheetRowNumber = index + 2;

            String entryDate = getCell(row, 0);          // A
            String username = getCell(row, 1);           // B
            String password = getCell(row, 2);           // C
            String studentName = getCell(row, 3);        // D
            String mobileNumber = getCell(row, 4);       // E
            String overallStatus = getCell(row, 5);      // F
            String priorityFlag = getCell(row, 17);      // R (optional URGENT)
            String indosNumber = getCell(row, 18);       // S
            String changedDgPassword = getCell(row, 19); // T
            String aadhaarNumber = getCell(row, 20);     // U
            String photoStatus = getCell(row, 21);       // V

            // Backward compatibility: older sheet versions stored URGENT in S before the Date column was inserted.
            // If found there, keep the priority for this read but let SMY Profile
            // replace S with the real INDoS number during profile preparation.
            boolean legacyUrgentInR =
                    "URGENT".equalsIgnoreCase(indosNumber.trim());
            boolean urgent =
                    "URGENT".equalsIgnoreCase(priorityFlag.trim())
                            || legacyUrgentInR;
            if (legacyUrgentInR) {
                indosNumber = "";
            }

            if (username.isBlank()
                    && password.isBlank()
                    && studentName.isBlank()) {
                continue;
            }

            // The user wants the entry date in PC1 column A. For any real
            // candidate row that has no date yet, stamp today's India date once.
            if (entryDate.isBlank()) {
                updateEntryDateIfBlank(sheetRowNumber);
            }

            OVERALL_STATUS_CACHE.put(sheetRowNumber, overallStatus);

            List<String> moduleNames = new ArrayList<>();

            for (int moduleIndex = 0;
                 moduleIndex < ALL_MODULES.size();
                 moduleIndex++) {

                String moduleName = ALL_MODULES.get(moduleIndex);
                String moduleStatus = getCell(row, 6 + moduleIndex);

                // Keep all 10 module names in the row model and cache their
                // current G:P values from this same A:V bulk read. This avoids
                // separate G:P reads during every chapter retry.
                moduleNames.add(moduleName);
                MODULE_STATUS_CACHE.put(
                        moduleStatusKey(sheetRowNumber, moduleName),
                        moduleStatus
                );
            }

            users.add(
                    new UserCourseRow(
                            sheetRowNumber,
                            username,
                            password,
                            studentName,
                            mobileNumber,
                            overallStatus,
                            moduleNames,
                            urgent,
                            indosNumber,
                            changedDgPassword,
                            aadhaarNumber,
                            photoStatus
                    )
            );
        }

        return users;
    }

    /*
     * Read all 10 module cells G:P in ONE request.
     * This is the main fix for the old 429 problem.
     */
    public static synchronized List<String> readModuleStatuses(
            int sheetRowNumber
    ) throws Exception {

        String range = USER_SHEET
                + "!G" + sheetRowNumber
                + ":P" + sheetRowNumber;

        ValueRange response = withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .get(SPREADSHEET_ID, range)
                        .execute()
        );

        List<String> result = new ArrayList<>();
        List<List<Object>> values = response.getValues();

        List<Object> row =
                values == null || values.isEmpty()
                        ? Collections.emptyList()
                        : values.get(0);

        // Always return exactly 10 positions and refresh the local cache.
        for (int i = 0; i < ALL_MODULES.size(); i++) {
            String status = getCell(row, i);
            result.add(status);
            MODULE_STATUS_CACHE.put(
                    moduleStatusKey(sheetRowNumber, ALL_MODULES.get(i)),
                    status
            );
        }

        return result;
    }

    public static int moduleIndexOf(String moduleName) {
        return findModuleIndex(moduleName);
    }

    public static List<QuizPlanRow> readQuizPlan()
            throws Exception {

        ValueRange response = withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .get(
                                SPREADSHEET_ID,
                                QUIZ_SHEET + "!A2:F"
                        )
                        .execute()
        );

        List<List<Object>> values = response.getValues();
        List<QuizPlanRow> rows = new ArrayList<>();

        if (values == null || values.isEmpty()) {
            System.out.println("No quiz plan rows found in "
                    + QUIZ_SHEET + ".");
            return rows;
        }

        for (List<Object> row : values) {
            String moduleText = getCell(row, 0);
            String chapterText = getCell(row, 1);
            String partCode = getCell(row, 2);

            if (moduleText.isBlank()
                    || chapterText.isBlank()
                    || partCode.isBlank()) {
                continue;
            }

            int moduleNumber;
            int chapterNumber;

            try {
                String moduleDigits = moduleText.replaceAll("[^0-9]", "");
                String chapterDigits = chapterText.replaceAll("[^0-9]", "");

                if (moduleDigits.isBlank() || chapterDigits.isBlank()) {
                    continue;
                }

                moduleNumber = Integer.parseInt(moduleDigits);
                chapterNumber = Integer.parseInt(chapterDigits);

            } catch (NumberFormatException e) {
                System.out.println("Invalid quiz plan row skipped: " + row);
                continue;
            }

            List<Integer> answers = new ArrayList<>();

            for (int columnIndex = 3; columnIndex <= 5; columnIndex++) {
                String answerText = getCell(row, columnIndex);

                if (answerText.isBlank()) {
                    continue;
                }

                String numbersOnly = answerText.replaceAll("[^0-9]", "");

                if (!numbersOnly.isBlank()) {
                    answers.add(Integer.parseInt(numbersOnly));
                }
            }

            rows.add(
                    new QuizPlanRow(
                            moduleNumber,
                            chapterNumber,
                            partCode,
                            answers
                    )
            );
        }

        System.out.println("Quiz plan rows loaded: " + rows.size());
        return rows;
    }

    public static synchronized void updateModuleStatus(
            int sheetRowNumber,
            String moduleName,
            String status
    ) throws Exception {

        // Show the exact live position in the module cell, for example:
        // "Running Part 2/5 Sub-part 3/8". Only the final verified state is
        // shortened to "Completed".
        String displayStatus;
        if (status == null || status.isBlank()) {
            displayStatus = "Running";
        } else if (status.trim().equalsIgnoreCase("Completed")) {
            displayStatus = "Completed";
        } else {
            displayStatus = status.trim();
        }

        int moduleIndex = findModuleIndex(moduleName);

        if (moduleIndex < 0) {
            throw new IllegalArgumentException(
                    "Unknown module: " + moduleName
            );
        }

        String cacheKey = moduleStatusKey(sheetRowNumber, moduleName);
        String cachedStatus = MODULE_STATUS_CACHE.get(cacheKey);
        if (displayStatus.equals(cachedStatus)) {
            return;
        }

        int columnNumber = 7 + moduleIndex; // G = 7
        String columnLetter = columnNumberToLetter(columnNumber);
        String range = USER_SHEET + "!" + columnLetter + sheetRowNumber;

        ValueRange body = new ValueRange()
                .setValues(
                        List.of(
                                List.of(displayStatus)
                        )
                );

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .update(
                                SPREADSHEET_ID,
                                range,
                                body
                        )
                        .setValueInputOption("RAW")
                        .execute()
        );

        MODULE_STATUS_CACHE.put(cacheKey, displayStatus);

        // Keep console output compact. For normal progress show only the
        // current module + part/sub-part. Completion/pending gets one line.
        if (displayStatus.matches("(?i)^Running\\s+Part\\s+.*Sub-part\\s+.*$")) {
            String compact = displayStatus
                    .replaceFirst("(?i)^Running\\s+", "")
                    .replaceFirst("(?i)\\s+Sub-part\\s+", " | Sub-part ");
            System.out.println("RUNNING | " + moduleName + " | " + compact);
        } else if (displayStatus.equalsIgnoreCase("Completed")) {
            System.out.println("COMPLETED | " + moduleName);
        } else if (displayStatus.toLowerCase().startsWith("pending")) {
            System.out.println("PENDING | " + moduleName + " | " + displayStatus);
        }
    }

    /*
     * Kept for ChapterRunner compatibility. It is now protected by 429 retry.
     * Main.java does NOT use this repeatedly anymore.
     */
    public static synchronized String readModuleStatus(
            int sheetRowNumber,
            String moduleName
    ) throws Exception {

        int moduleIndex = findModuleIndex(moduleName);

        if (moduleIndex < 0) {
            throw new IllegalArgumentException(
                    "Unknown module: " + moduleName
            );
        }

        String cacheKey = moduleStatusKey(sheetRowNumber, moduleName);
        String cached = MODULE_STATUS_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<String> statuses = readModuleStatuses(sheetRowNumber);
        return statuses.get(moduleIndex);
    }

    public static void markModuleCompleted(
            int sheetRowNumber,
            String moduleName
    ) throws Exception {
        updateModuleStatus(
                sheetRowNumber,
                moduleName,
                "Completed"
        );
    }

    /** Stamp PC1 column A with today's India date only when it is blank. */
    public static synchronized void updateEntryDateIfBlank(
            int sheetRowNumber
    ) throws Exception {

        String range = USER_SHEET + "!A" + sheetRowNumber;
        ValueRange current = withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .get(SPREADSHEET_ID, range)
                        .execute()
        );

        List<List<Object>> values = current.getValues();
        if (values != null && !values.isEmpty()
                && values.get(0) != null && !values.get(0).isEmpty()) {
            String existing = String.valueOf(values.get(0).get(0)).trim();
            if (!existing.isBlank()) {
                return;
            }
        }

        String today = LocalDate.now(INDIA_ZONE).format(ENTRY_DATE_FORMAT);
        ValueRange body = new ValueRange().setValues(List.of(List.of(today)));

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .update(SPREADSHEET_ID, range, body)
                        .setValueInputOption("USER_ENTERED")
                        .execute()
        );

        System.out.println("DATE ENTRY -> SHEET | A" + sheetRowNumber + " = " + today);
    }

    public static synchronized void updateOverallStatus(
            int sheetRowNumber,
            String status
    ) throws Exception {

        String cleanedStatus = status == null ? "" : status.trim();
        String cachedStatus = OVERALL_STATUS_CACHE.get(sheetRowNumber);
        if (cleanedStatus.equals(cachedStatus)) {
            return;
        }

        String range = USER_SHEET + "!F" + sheetRowNumber;

        ValueRange body = new ValueRange()
                .setValues(
                        List.of(
                                List.of(cleanedStatus)
                        )
                );

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .update(
                                SPREADSHEET_ID,
                                range,
                                body
                        )
                        .setValueInputOption("RAW")
                        .execute()
        );

        OVERALL_STATUS_CACHE.put(sheetRowNumber, cleanedStatus);

        System.out.println(
                "OVERALL STATUS UPDATED: "
                        + range + " = " + cleanedStatus
        );
    }

    /**
     * Writes the verified SMY profile identity in one API request.
     * D = Name, E = 10-digit mobile number.
     */
    public static synchronized void updateProfileIdentity(
            int sheetRowNumber,
            String name,
            String mobileNumber
    ) throws Exception {

        String cleanName = name == null ? "" : name.trim();
        String cleanMobile = mobileNumber == null ? "" : mobileNumber.trim();

        String range = USER_SHEET + "!D" + sheetRowNumber + ":E" + sheetRowNumber;
        ValueRange body = new ValueRange()
                .setValues(List.of(List.of(cleanName, cleanMobile)));

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .update(SPREADSHEET_ID, range, body)
                        .setValueInputOption("RAW")
                        .execute()
        );

        System.out.println("PROFILE -> SHEET | D" + sheetRowNumber
                + " = " + cleanName + " | E" + sheetRowNumber
                + " = " + cleanMobile);
    }


    /**
     * Repairs D/E without ever replacing a nonblank sheet value with blank.
     * This is used by the SMY profile sync because Name and Mobile may be read
     * independently and one selector can temporarily fail.
     */
    public static synchronized void updateProfileIdentityPreservingExisting(
            int sheetRowNumber,
            String name,
            String mobileNumber
    ) throws Exception {

        String range = USER_SHEET + "!D" + sheetRowNumber + ":E" + sheetRowNumber;
        ValueRange current = withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .get(SPREADSHEET_ID, range)
                        .execute()
        );

        String existingName = "";
        String existingMobile = "";
        List<List<Object>> values = current.getValues();
        if (values != null && !values.isEmpty()) {
            List<Object> row = values.get(0);
            existingName = getCell(row, 0).trim();
            existingMobile = getCell(row, 1).trim();
        }

        String cleanName = name == null ? "" : name.trim();
        String cleanMobile = mobileNumber == null ? "" : mobileNumber.trim();

        if (cleanName.isBlank()) {
            cleanName = existingName;
        }
        if (cleanMobile.isBlank()) {
            cleanMobile = existingMobile;
        }

        updateProfileIdentity(sheetRowNumber, cleanName, cleanMobile);
    }


    /**
     * V = persistent photo marker. Once the real SMY profile photo is confirmed,
     * later program runs can skip opening the profile/DG browser completely.
     */
    public static synchronized void markPhotoExists(int sheetRowNumber) throws Exception {
        String range = USER_SHEET + "!V" + sheetRowNumber;
        ValueRange body = new ValueRange().setValues(List.of(List.of("Photo Exist")));

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .update(SPREADSHEET_ID, range, body)
                        .setValueInputOption("RAW")
                        .execute()
        );

        System.out.println("PHOTO STATUS -> SHEET | V" + sheetRowNumber + " = Photo Exist");
    }

    /**
     * Column V is also the concise DG/Profile error/status column.
     * Keep Selenium build information and long stack text out of the Sheet.
     */
    public static synchronized void updateProfileStatus(int sheetRowNumber, String value) throws Exception {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.length() > 120) clean = clean.substring(0, 120);
        String range = USER_SHEET + "!V" + sheetRowNumber;
        ValueRange body = new ValueRange().setValues(List.of(List.of(clean)));
        withQuotaRetry(() -> getSheetsService().spreadsheets().values()
                .update(SPREADSHEET_ID, range, body)
                .setValueInputOption("RAW")
                .execute());
        System.out.println("PROFILE STATUS -> SHEET | V" + sheetRowNumber + " = " + clean);
    }

    /**
     * S = INDoS number. The value is sourced from the logged-in SMY Profile
     * whenever available, so DG login never depends on the SMY password format.
     */
    public static synchronized void updateIndosNumber(
            int sheetRowNumber,
            String indosNumber
    ) throws Exception {

        String cleanIndos = indosNumber == null ? "" : indosNumber.trim();
        if (cleanIndos.isBlank()) {
            throw new IllegalArgumentException("INDoS number is blank for row " + sheetRowNumber);
        }

        String range = USER_SHEET + "!S" + sheetRowNumber;
        ValueRange body = new ValueRange()
                .setValues(List.of(List.of(cleanIndos)));

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .update(SPREADSHEET_ID, range, body)
                        .setValueInputOption("RAW")
                        .execute()
        );

        System.out.println("SMY INDOS -> SHEET | S" + sheetRowNumber
                + " = " + cleanIndos);
    }


    /**
     * Reads the latest changed DG/eSamudra password from column T.
     * Used only when the normal INDoS+1 password is rejected.
     * The password itself is never printed.
     */
    public static synchronized String readChangedDgPassword(
            int sheetRowNumber
    ) throws Exception {

        String range = USER_SHEET + "!T" + sheetRowNumber;
        ValueRange response = withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .get(SPREADSHEET_ID, range)
                        .execute()
        );

        List<List<Object>> values = response.getValues();
        if (values == null || values.isEmpty()
                || values.get(0) == null || values.get(0).isEmpty()) {
            return "";
        }

        Object value = values.get(0).get(0);
        return value == null ? "" : String.valueOf(value).trim();
    }



    /* Cache the numeric sheet id so formatting does not call
       spreadsheets.get() for every candidate. */
    private static int getUserSheetId()
            throws Exception {

        Integer cached = userSheetId;
        if (cached != null) {
            return cached;
        }

        synchronized (SheetRepository.class) {
            if (userSheetId != null) {
                return userSheetId;
            }

            Spreadsheet spreadsheet = withQuotaRetry(
                    () -> getSheetsService()
                            .spreadsheets()
                            .get(SPREADSHEET_ID)
                            .setIncludeGridData(false)
                            .execute()
            );

            for (Sheet sheet : spreadsheet.getSheets()) {
                if (USER_SHEET.equals(sheet.getProperties().getTitle())) {
                    userSheetId = sheet.getProperties().getSheetId();
                    return userSheetId;
                }
            }

            throw new IllegalStateException(
                    "Sheet tab not found: " + USER_SHEET
            );
        }
    }

    /**
     * Queue a fully completed candidate in the "Download certificate" tab.
     *
     * Source PC1:
     *   A = Date Entry
     *   B = username/email
     *   C = SMY password
     *   D = name
     *   E = mobile
     *
     * Target "Download certificate":
     *   A = Date Entry (dd/MM/yyyy, India time)
     *   B = username/email
     *   C = SMY Password
     *   D = name
     *   E = mobile
     *
     * Important:
     * - Existing completed candidates are never duplicated.
     * - Four-column queue rows from the previous build
     *   (A=date, B=username, C=name, D=mobile) are repaired in-place.
     * - Older rows where username was in A and password was in B are also
     *   migrated in-place to the new five-column layout.
     * - If Password/Name/Mobile is blank when completion is detected, the row
     *   stays eligible for repair on the next poll.
     * - The original PC row remains as the local audit record; the certificate queue is consolidated.
     */
    public static synchronized void copyCompletedCandidateToDownloadCertificate(
            int sourceSheetRowNumber,
            String username,
            String password,
            String studentName,
            String mobileNumber
    ) throws Exception {

        ensureDownloadCertificateHeaders();

        String entryDate = LocalDate.now(INDIA_ZONE).format(ENTRY_DATE_FORMAT);
        String cleanUsername = username == null ? "" : username.trim();
        String cleanPassword = password == null ? "" : password.trim();
        String cleanName = studentName == null ? "" : studentName.trim();
        String cleanMobile = mobileNumber == null ? "" : mobileNumber.trim();

        if (cleanUsername.isBlank()
                && cleanPassword.isBlank()
                && cleanName.isBlank()
                && cleanMobile.isBlank()) {
            return;
        }

        String cacheKey = !cleanUsername.isBlank()
                ? "u:" + cleanUsername.toLowerCase()
                : "m:" + cleanMobile;

        // Only cache a complete five-column row. If password/name/mobile is
        // missing, the next poll must be able to repair it.
        if (CERTIFICATE_QUEUE_CACHE.contains(cacheKey)) {
            return;
        }

        ValueRange existing = withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .get(
                                SPREADSHEET_ID,
                                "'" + DOWNLOAD_CERTIFICATE_SHEET.replace("'", "''")
                                        + "'!A2:E"
                        )
                        .execute()
        );

        List<List<Object>> rows = existing.getValues();
        if (rows == null) {
            rows = Collections.emptyList();
        }

        for (int i = 0; i < rows.size(); i++) {
            List<Object> row = rows.get(i);
            String colA = getCell(row, 0).trim();
            String colB = getCell(row, 1).trim();
            String colC = getCell(row, 2).trim();
            String colD = getCell(row, 3).trim();
            String colE = getCell(row, 4).trim();

            boolean sameCurrentUsername = !cleanUsername.isBlank()
                    && cleanUsername.equalsIgnoreCase(colB);

            // Legacy rows in this sheet used A=username and B=password.
            boolean sameLegacyUsername = !cleanUsername.isBlank()
                    && cleanUsername.equalsIgnoreCase(colA);

            boolean sameMobileOnly = cleanUsername.isBlank()
                    && !cleanMobile.isBlank()
                    && (cleanMobile.equals(colE) || cleanMobile.equals(colD));

            if (!(sameCurrentUsername || sameLegacyUsername || sameMobileOnly)) {
                continue;
            }

            int targetRow = i + 2;
            boolean legacyUsernameInA = sameLegacyUsername;

            // Previous four-column layout:
            // A=date, B=username, C=name, D=mobile, E blank.
            boolean oldFourColumnLayout = sameCurrentUsername
                    && colE.isBlank()
                    && !cleanPassword.isBlank()
                    && !cleanPassword.equals(colC)
                    && (
                            (!cleanName.isBlank() && cleanName.equalsIgnoreCase(colC))
                                    || (!cleanMobile.isBlank() && cleanMobile.equals(colD))
                                    || looksLikeMobile(colD)
                    );

            String finalDate = legacyUsernameInA || colA.isBlank()
                    ? entryDate
                    : colA;

            String finalUsername = legacyUsernameInA ? colA : colB;
            if (finalUsername.isBlank()) {
                finalUsername = cleanUsername;
            }

            String finalPassword;
            String finalName;
            String finalMobile;

            if (legacyUsernameInA) {
                finalPassword = cleanPassword.isBlank() ? colB : cleanPassword;
                finalName = colC.isBlank() ? cleanName : colC;
                finalMobile = colD.isBlank() ? cleanMobile : colD;
            } else if (oldFourColumnLayout) {
                finalPassword = cleanPassword;
                finalName = colC.isBlank() ? cleanName : colC;
                finalMobile = colD.isBlank() ? cleanMobile : colD;
            } else {
                finalPassword = colC.isBlank() ? cleanPassword : colC;
                if (!cleanPassword.isBlank() && !cleanPassword.equals(finalPassword)) {
                    // The active PC sheet is the source of truth for the SMY login password.
                    finalPassword = cleanPassword;
                }
                finalName = colD.isBlank() ? cleanName : colD;
                finalMobile = colE.isBlank() ? cleanMobile : colE;
            }

            boolean needsRepair = legacyUsernameInA
                    || oldFourColumnLayout
                    || !finalDate.equals(colA)
                    || !finalUsername.equals(colB)
                    || !finalPassword.equals(colC)
                    || !finalName.equals(colD)
                    || !finalMobile.equals(colE);

            if (needsRepair) {
                ValueRange repairBody = new ValueRange()
                        .setValues(List.of(List.of(
                                finalDate,
                                finalUsername,
                                finalPassword,
                                finalName,
                                finalMobile
                        )));

                final int rowToRepair = targetRow;
                withQuotaRetry(
                        () -> getSheetsService()
                                .spreadsheets()
                                .values()
                                .update(
                                        SPREADSHEET_ID,
                                        "'" + DOWNLOAD_CERTIFICATE_SHEET.replace("'", "''")
                                                + "'!A" + rowToRepair + ":E" + rowToRepair,
                                        repairBody
                                )
                                .setValueInputOption("USER_ENTERED")
                                .execute()
                );

                formatDownloadCertificateRow(targetRow);

                System.out.println(
                        "CERTIFICATE QUEUE REPAIRED | TARGET ROW " + targetRow
                                + " | DATE=" + finalDate
                                + " | USER=" + finalUsername
                                + " | PASSWORD=" + (finalPassword.isBlank() ? "PENDING" : "OK")
                                + " | NAME=" + (finalName.isBlank() ? "PENDING" : finalName)
                                + " | MOBILE=" + (finalMobile.isBlank() ? "PENDING" : finalMobile)
                );
            } else {
                // Reapply style so column C Password is also red/white/bold.
                formatDownloadCertificateRow(targetRow);
                System.out.println(
                        "CERTIFICATE QUEUE ALREADY EXISTS | SOURCE ROW "
                                + sourceSheetRowNumber + " | " + cleanUsername
                );
            }

            if (!finalUsername.isBlank()
                    && !finalPassword.isBlank()
                    && !finalName.isBlank()
                    && !finalMobile.isBlank()) {
                CERTIFICATE_QUEUE_CACHE.add(cacheKey);
            }
            return;
        }

        int targetRowNumber = rows.size() + 2;

        ValueRange body = new ValueRange()
                .setValues(List.of(List.of(
                        entryDate,
                        cleanUsername,
                        cleanPassword,
                        cleanName,
                        cleanMobile
                )));

        final int rowToWrite = targetRowNumber;
        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .update(
                                SPREADSHEET_ID,
                                "'" + DOWNLOAD_CERTIFICATE_SHEET.replace("'", "''")
                                        + "'!A" + rowToWrite + ":E" + rowToWrite,
                                body
                        )
                        .setValueInputOption("USER_ENTERED")
                        .execute()
        );

        formatDownloadCertificateRow(targetRowNumber);
        if (!cleanUsername.isBlank()
                && !cleanPassword.isBlank()
                && !cleanName.isBlank()
                && !cleanMobile.isBlank()) {
            CERTIFICATE_QUEUE_CACHE.add(cacheKey);
        }

        System.out.println(
                "CERTIFICATE QUEUE ADDED | SOURCE ROW "
                        + sourceSheetRowNumber
                        + " -> " + DOWNLOAD_CERTIFICATE_SHEET
                        + " ROW " + targetRowNumber
                        + " | A DATE=" + entryDate
                        + " | B USER=" + cleanUsername
                        + " | C PASSWORD=" + (cleanPassword.isBlank() ? "PENDING" : "OK")
                        + " | D NAME=" + (cleanName.isBlank() ? "PENDING" : cleanName)
                        + " | E MOBILE=" + (cleanMobile.isBlank() ? "PENDING" : cleanMobile)
        );
    }

    private static boolean looksLikeMobile(String value) {
        if (value == null) {
            return false;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.length() >= 8 && digits.length() <= 15;
    }

    /** Ensure the queue header matches the five-column certificate layout. */
    private static synchronized void ensureDownloadCertificateHeaders()
            throws Exception {

        if (downloadCertificateHeadersReady) {
            return;
        }

        String range = "'" + DOWNLOAD_CERTIFICATE_SHEET.replace("'", "''") + "'!A1:E1";
        ValueRange current = withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .get(SPREADSHEET_ID, range)
                        .execute()
        );

        List<String> wanted = List.of(
                "Date Entry",
                "SMY User Name",
                "SMY Password",
                "Name",
                "Mobile"
        );

        List<List<Object>> values = current.getValues();
        List<Object> row = (values == null || values.isEmpty())
                ? Collections.emptyList()
                : values.get(0);

        boolean same = true;
        for (int i = 0; i < wanted.size(); i++) {
            if (!wanted.get(i).equalsIgnoreCase(getCell(row, i).trim())) {
                same = false;
                break;
            }
        }

        if (!same) {
            ValueRange headerBody = new ValueRange()
                    .setValues(List.of(new ArrayList<>(wanted)));

            withQuotaRetry(
                    () -> getSheetsService()
                            .spreadsheets()
                            .values()
                            .update(SPREADSHEET_ID, range, headerBody)
                            .setValueInputOption("RAW")
                            .execute()
            );

            System.out.println(
                    "DOWNLOAD CERTIFICATE HEADER READY | A=Date Entry | B=SMY User Name | C=SMY Password | D=Name | E=Mobile"
            );
        }

        downloadCertificateHeadersReady = true;
    }

    /** Apply the same red completed style to A:E in the queue. */
    private static void formatDownloadCertificateRow(int targetRowNumber)
            throws Exception {

        GridRange targetRange = new GridRange()
                .setSheetId(getDownloadCertificateSheetId())
                .setStartRowIndex(targetRowNumber - 1)
                .setEndRowIndex(targetRowNumber)
                .setStartColumnIndex(0)
                .setEndColumnIndex(5); // A:E including password

        CellFormat completedFormat = new CellFormat()
                .setBackgroundColor(
                        new Color()
                                .setRed(0.80f)
                                .setGreen(0.00f)
                                .setBlue(0.00f)
                )
                .setTextFormat(
                        new TextFormat()
                                .setForegroundColor(
                                        new Color()
                                                .setRed(1.00f)
                                                .setGreen(1.00f)
                                                .setBlue(1.00f)
                                )
                                .setBold(true)
                );

        RepeatCellRequest repeatCell = new RepeatCellRequest()
                .setRange(targetRange)
                .setCell(new CellData().setUserEnteredFormat(completedFormat))
                .setFields(
                        "userEnteredFormat.backgroundColor,"
                                + "userEnteredFormat.textFormat.foregroundColor,"
                                + "userEnteredFormat.textFormat.bold"
                );

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .batchUpdate(
                                SPREADSHEET_ID,
                                new BatchUpdateSpreadsheetRequest()
                                        .setRequests(List.of(
                                                new Request().setRepeatCell(repeatCell)
                                        ))
                        )
                        .execute()
        );
    }

    private static int getDownloadCertificateSheetId()
            throws Exception {

        Integer cached = downloadCertificateSheetId;
        if (cached != null) {
            return cached;
        }

        synchronized (SheetRepository.class) {
            if (downloadCertificateSheetId != null) {
                return downloadCertificateSheetId;
            }

            Spreadsheet spreadsheet = withQuotaRetry(
                    () -> getSheetsService()
                            .spreadsheets()
                            .get(SPREADSHEET_ID)
                            .setIncludeGridData(false)
                            .execute()
            );

            for (Sheet sheet : spreadsheet.getSheets()) {
                if (DOWNLOAD_CERTIFICATE_SHEET.equals(
                        sheet.getProperties().getTitle()
                )) {
                    downloadCertificateSheetId =
                            sheet.getProperties().getSheetId();
                    return downloadCertificateSheetId;
                }
            }

            throw new IllegalStateException(
                    "Sheet tab not found: " + DOWNLOAD_CERTIFICATE_SHEET
            );
        }
    }

    public static synchronized void formatCompletedRow(
            int sheetRowNumber
    ) throws Exception {

        if (COMPLETED_FORMATTED_ROWS.contains(sheetRowNumber)) {
            return;
        }

        GridRange range = new GridRange()
                .setSheetId(getUserSheetId())
                .setStartRowIndex(sheetRowNumber - 1)
                .setEndRowIndex(sheetRowNumber)
                .setStartColumnIndex(0)
                .setEndColumnIndex(16); // A:P only

        CellFormat format = new CellFormat()
                .setBackgroundColor(
                        new Color()
                                .setRed(0.80f)
                                .setGreen(0.00f)
                                .setBlue(0.00f)
                )
                .setTextFormat(
                        new TextFormat()
                                .setForegroundColor(
                                        new Color()
                                                .setRed(1.00f)
                                                .setGreen(1.00f)
                                                .setBlue(1.00f)
                                )
                                .setBold(true)
                );

        RepeatCellRequest repeatCell = new RepeatCellRequest()
                .setRange(range)
                .setCell(
                        new CellData()
                                .setUserEnteredFormat(format)
                )
                .setFields(
                        "userEnteredFormat.backgroundColor,"
                                + "userEnteredFormat.textFormat.foregroundColor,"
                                + "userEnteredFormat.textFormat.bold"
                );

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .batchUpdate(
                                SPREADSHEET_ID,
                                new BatchUpdateSpreadsheetRequest()
                                        .setRequests(
                                                List.of(
                                                        new Request()
                                                                .setRepeatCell(repeatCell)
                                                )
                                        )
                        )
                        .execute()
        );

        COMPLETED_FORMATTED_ROWS.add(sheetRowNumber);
        PENDING_FORMATTED_ROWS.remove(sheetRowNumber);

        System.out.println(
                "COMPLETED ROW RED/WHITE: "
                        + USER_SHEET + "!A" + sheetRowNumber
                        + ":P" + sheetRowNumber
        );
    }

    public static synchronized void formatPendingRow(
            int sheetRowNumber
    ) throws Exception {

        if (PENDING_FORMATTED_ROWS.contains(sheetRowNumber)) {
            return;
        }

        GridRange range = new GridRange()
                .setSheetId(getUserSheetId())
                .setStartRowIndex(sheetRowNumber - 1)
                .setEndRowIndex(sheetRowNumber)
                .setStartColumnIndex(0)
                .setEndColumnIndex(16); // A:P only

        CellFormat format = new CellFormat()
                .setBackgroundColor(
                        new Color()
                                .setRed(1.00f)
                                .setGreen(1.00f)
                                .setBlue(1.00f)
                )
                .setTextFormat(
                        new TextFormat()
                                .setForegroundColor(
                                        new Color()
                                                .setRed(0.00f)
                                                .setGreen(0.00f)
                                                .setBlue(0.00f)
                                )
                                .setBold(false)
                );

        RepeatCellRequest repeatCell = new RepeatCellRequest()
                .setRange(range)
                .setCell(
                        new CellData()
                                .setUserEnteredFormat(format)
                )
                .setFields(
                        "userEnteredFormat.backgroundColor,"
                                + "userEnteredFormat.textFormat.foregroundColor,"
                                + "userEnteredFormat.textFormat.bold"
                );

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .batchUpdate(
                                SPREADSHEET_ID,
                                new BatchUpdateSpreadsheetRequest()
                                        .setRequests(
                                                List.of(
                                                        new Request()
                                                                .setRepeatCell(repeatCell)
                                                )
                                        )
                        )
                        .execute()
        );

        PENDING_FORMATTED_ROWS.add(sheetRowNumber);
        COMPLETED_FORMATTED_ROWS.remove(sheetRowNumber);

        System.out.println(
                "PENDING ROW NORMAL FORMAT: "
                        + USER_SHEET + "!A" + sheetRowNumber
                        + ":P" + sheetRowNumber
        );
    }

    public static boolean areAllModulesCompleted(
            int sheetRowNumber
    ) throws Exception {

        List<String> statuses =
                readModuleStatuses(sheetRowNumber);

        for (int moduleIndex = 0;
             moduleIndex < ALL_MODULES.size();
             moduleIndex++) {

            String moduleName = ALL_MODULES.get(moduleIndex);
            String moduleCell = statuses.get(moduleIndex);

            if (!isModuleCompletedCell(moduleCell, moduleName)) {
                System.out.println(
                        "Still pending: " + moduleName
                                + " | Status: "
                                + (moduleCell.isBlank()
                                ? "Blank"
                                : moduleCell)
                );
                return false;
            }
        }

        return true;
    }

    private static boolean isModuleCompletedCell(
            String moduleCell,
            String expectedModuleName
    ) {
        String cleanedCell = normalizeModuleName(moduleCell);

        if (cleanedCell.isBlank()) {
            return false;
        }

        // Strict final truth in the Sheet: only the literal status Completed.
        // Legacy cells that merely contain the module name are NOT accepted.
        return cleanedCell.equalsIgnoreCase("Completed");
    }

    private static String moduleStatusKey(
            int sheetRowNumber,
            String moduleName
    ) {
        return sheetRowNumber + "|" + normalizeModuleName(moduleName).toLowerCase();
    }

    private static int findModuleIndex(String moduleName) {
        String cleanedModule = normalizeModuleName(moduleName);

        for (int index = 0; index < ALL_MODULES.size(); index++) {
            String expected =
                    normalizeModuleName(ALL_MODULES.get(index));

            if (expected.equalsIgnoreCase(cleanedModule)) {
                return index;
            }
        }

        return -1;
    }

    private static String normalizeModuleName(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\u00A0", " ")
                .replace(",", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String getCell(
            List<Object> row,
            int index
    ) {
        if (row == null
                || index < 0
                || index >= row.size()
                || row.get(index) == null) {
            return "";
        }

        return String.valueOf(row.get(index)).trim();
    }

    private static String columnNumberToLetter(int columnNumber) {
        StringBuilder result = new StringBuilder();
        int number = columnNumber;

        while (number > 0) {
            int remainder = (number - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            number = (number - 1) / 26;
        }

        return result.toString();
    }
}
