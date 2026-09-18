package org.example;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest;
import com.google.api.services.sheets.v4.model.BatchClearValuesRequest;
import com.google.api.services.sheets.v4.model.ClearValuesRequest;
import com.google.api.services.sheets.v4.model.DeleteDimensionRequest;
import com.google.api.services.sheets.v4.model.DimensionRange;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.ValueRange;

import java.util.ArrayList;
import java.util.List;

public class SheetRepository {

    private final Sheets sheets;
    private final String id;
    private final String name;

    public SheetRepository() throws Exception {
        sheets = GoogleService.sheets();
        id = Config.require("spreadsheet.id");
        name = Config.require("sheet.name");
    }

    /*
     * SHEET COLUMNS
     *
     * A = Date
     * B = Login / Candidate Email
     * C = Password
     * D = Candidate Name (filled from SMY Profile when blank)
     * E = Candidate Mobile / WhatsApp (filled from SMY Profile when blank)
     * F = Source Status
     * G = Existing / untouched
     * H = Process Status
     * I = Mail Status
     * J = WhatsApp Status
     * K = Existing / unused here
     * L = Agent Name
     * M = Completed Date & Time
     *
     * Example:
     * Column L = Muthu
     */

    /**
     * Scans A:L continuously.
     *
     * Priority:
     *
     * 1) Column I = SENT
     *    Email already sent.
     *    Do NOT download/send email again.
     *    Continue WhatsApp + move only.
     *
     * 2) Column F = Completed and Column I blank
     *    New row.
     *
     * 3) Column F = Completed and Column I = FAILED
     *    Retry email workflow.
     *
     * Column E = candidate WhatsApp.
     * Column D = candidate name.
     * Column L = optional additional agent.
     */
    public Candidate findNext() throws Exception {

        ValueRange vr = sheets.spreadsheets().values()
                .get(id, "'" + name + "'!A2:L")
                .execute();

        List<List<Object>> rows = vr.getValues();

        if (rows == null) {
            return null;
        }

        Candidate firstSentContinuation = null;
        Candidate firstNew = null;
        Candidate firstFailedEmail = null;

        for (int i = 0; i < rows.size(); i++) {

            List<Object> r = rows.get(i);
            int rowNumber = i + 2;

            String loginId = v(r, 1);        // B = Login / Email

            // 6-PC SAFE MODE: stable login/email sharding prevents two PCs
            // from intentionally selecting the same candidate.
            if (!MachineShard.owns(loginId)) {
                continue;
            }

            String password = v(r, 2);       // C = Password
            String whatsappStatus = v(r, 9); // J
            String sourceStatus = v(r, 5);   // F
            String mailStatus = v(r, 8);     // I
            String agent = v(r, 11);         // L

            Candidate candidate = new Candidate(
                    rowNumber,
                    loginId,
                    password,
                    v(r, 3),          // D = Candidate Name
                    v(r, 4),          // E = Candidate Mobile / WhatsApp
                    whatsappStatus,   // J
                    v(r, 7),          // H
                    mailStatus,       // I
                    agent             // L
            );

            /*
             * Email already sent.
             * Never require Column F again.
             */
            if ("sent".equalsIgnoreCase(mailStatus)) {

                if (firstSentContinuation == null) {
                    firstSentContinuation = candidate;
                }

                continue;
            }

            /*
             * New email/download work starts only
             * when Column F = Completed.
             */
            if (!"completed".equalsIgnoreCase(sourceStatus)) {
                continue;
            }

            /*
             * New row
             */
            if (mailStatus.isBlank()) {

                if (loginId.isBlank() || password.isBlank()) {

                    updateProcessStatus(rowNumber, "FAILED");
                    updateMailStatus(rowNumber, "FAILED");

                    if (firstFailedEmail == null) {
                        firstFailedEmail = candidate;
                    }

                } else if (firstNew == null) {

                    firstNew = candidate;
                }

                continue;
            }

            /*
             * Previous email failed
             */
            if ("failed".equalsIgnoreCase(mailStatus)) {

                if (firstFailedEmail == null) {
                    firstFailedEmail = candidate;
                }
            }
        }

        /*
         * First priority:
         * finish rows whose email was already sent.
         */
        if (firstSentContinuation != null) {
            return firstSentContinuation;
        }

        /*
         * Second priority:
         * brand-new rows.
         */
        if (firstNew != null) {
            return firstNew;
        }

        /*
         * Third priority:
         * failed email rows.
         */
        return firstFailedEmail;
    }


    // =========================================================
    // COLUMN H - PROCESS STATUS
    // =========================================================

    public void updateProcessStatus(int row, String value) throws Exception {
        cell("H" + row, value);
    }

    public String readProcessStatus(int row) throws Exception {
        return readCell("H" + row);
    }


    // =========================================================
    // COLUMN I - MAIL STATUS
    // =========================================================

    public void updateMailStatus(int row, String value) throws Exception {
        cell("I" + row, value);
    }


    // =========================================================
    // COLUMN J - WHATSAPP STATUS
    // =========================================================

    public void updateWhatsappStatus(int row, String value) throws Exception {
        cell("J" + row, value);
    }


    // =========================================================
    // COLUMN D - CANDIDATE NAME
    // =========================================================

    public void updateName(int row, String value) throws Exception {
        cell("D" + row, value);
    }


    // =========================================================
    // COLUMN E - CANDIDATE MOBILE
    // =========================================================

    public void updateMobile(int row, String value) throws Exception {
        cell("E" + row, value);
    }


    // =========================================================
    // COLUMN M - COMPLETED DATE/TIME
    // =========================================================

    public String updateCompletedDateTimeIfBlank(
            int row,
            String value
    ) throws Exception {

        String existing = readCell("M" + row);

        /*
         * Never replace existing completion time.
         */
        if (!existing.isBlank()) {
            return existing;
        }

        cell("M" + row, value);

        String saved = readCell("M" + row);

        if (!value.equals(saved)) {
            throw new IllegalStateException(
                    "Column M completed date/time verification failed"
            );
        }

        return saved;
    }


    // =========================================================
    // VERIFIED NAME WRITE
    // =========================================================

    public void updateNameVerified(
            int row,
            String value
    ) throws Exception {

        updateName(row, value);

        if (!value.equals(readCell("D" + row))) {

            throw new IllegalStateException(
                    "Column D name verification failed"
            );
        }
    }


    // =========================================================
    // VERIFIED MOBILE WRITE
    // =========================================================

    public void updateMobileVerified(
            int row,
            String value
    ) throws Exception {

        updateMobile(row, value);

        if (!value.equals(readCell("E" + row))) {

            throw new IllegalStateException(
                    "Column E mobile verification failed"
            );
        }
    }


    // =========================================================
    // VERIFIED MAIL STATUS WRITE
    // =========================================================

    public void updateMailStatusVerified(
            int row,
            String value
    ) throws Exception {

        updateMailStatus(row, value);

        if (!value.equalsIgnoreCase(readCell("I" + row))) {

            throw new IllegalStateException(
                    "Column I mail status verification failed"
            );
        }
    }


    // =========================================================
    // VERIFIED WHATSAPP STATUS WRITE
    // =========================================================

    public void updateWhatsappStatusVerified(
            int row,
            String value
    ) throws Exception {

        updateWhatsappStatus(row, value);

        if (!value.equalsIgnoreCase(readCell("J" + row))) {

            throw new IllegalStateException(
                    "Column J WhatsApp status verification failed"
            );
        }
    }


    // =========================================================
    // MOVE SUCCESSFUL ROW TO COMPLETED
    // =========================================================

    /**
     * IMPORTANT:
     *
     * Reads SOURCE:
     *
     * A:M
     *
     * And appends it into COMPLETED:
     *
     * A:M
     *
     * Therefore:
     *
     * Source A -> Completed A
     * Source B -> Completed B
     * Source C -> Completed C
     * ...
     * Source L -> Completed L
     * Source M -> Completed M
     *
     * NO blank column is inserted before A.
     *
     * Source row is deleted ONLY after Completed append succeeds.
     */
    public synchronized void moveToCompleted(
            int rowNumber
    ) throws Exception {

        String configured = Config.get("completed.sheet.name");
        if (configured.isBlank()) configured = "Completed";
        String completed = resolveSheetTitle(configured);

        // Completed sheet layout:
        // A = Date, B = Email, C = Password, D = Name, E = Mobile, F = Status
        // G:I reserved/blank, J = Sent Date & Time
        ensureCompletedHeader(completed);

        ValueRange source = sheets.spreadsheets()
                .values()
                .get(id, "'" + name + "'!A" + rowNumber + ":M" + rowNumber)
                .execute();

        List<List<Object>> values = source.getValues();
        if (values == null || values.isEmpty()) {
            throw new IllegalStateException("Source row is empty; not moved");
        }

        List<Object> src = values.get(0);

        Object date = raw(src, 0);          // Source A = Date
        Object email = raw(src, 1);         // Source B = Login / Email
        Object password = raw(src, 2);      // Source C = Password
        Object candidateName = raw(src, 3); // Source D = Name
        Object mobile = raw(src, 4);        // Source E = Mobile
        Object status = raw(src, 5);        // Source F = Status
        Object sentDateTime = raw(src, 12);  // Source M = completed/sent date-time

        List<Object> completedRow = List.of(
                date,
                email,
                password,
                candidateName,
                mobile,
                status,
                "",
                "",
                "",
                sentDateTime
        );

        int destinationRow = findNextCompletedRow(completed);

        sheets.spreadsheets()
                .values()
                .update(
                        id,
                        "'" + completed + "'!A" + destinationRow + ":J" + destinationRow,
                        new ValueRange().setValues(List.of(completedRow))
                )
                .setValueInputOption("RAW")
                .execute();

        System.out.println(
                "COMPLETED SHEET WRITE SUCCESS: "
                        + completed
                        + " | SOURCE ROW: " + rowNumber
                        + " | DESTINATION ROW: " + destinationRow
                        + " | A=Date B=Email C=Password D=Name E=Mobile F=Status J=Sent Date/Time"
        );

        int sourceSheetId = findSheetId(name);

        DeleteDimensionRequest delete = new DeleteDimensionRequest()
                .setRange(
                        new DimensionRange()
                                .setSheetId(sourceSheetId)
                                .setDimension("ROWS")
                                .setStartIndex(rowNumber - 1)
                                .setEndIndex(rowNumber)
                );

        sheets.spreadsheets()
                .batchUpdate(
                        id,
                        new BatchUpdateSpreadsheetRequest()
                                .setRequests(List.of(new Request().setDeleteDimension(delete)))
                )
                .execute();

        System.out.println("SOURCE ROW DELETE SUCCESS: " + rowNumber);
    }

    private void ensureCompletedHeader(String completed) throws Exception {
        List<Object> header = List.of("Date", "Email", "Password", "Name", "Mobile", "Status", "", "", "", "Sent Date & Time");

        // Avoid wasting a Google Sheets WRITE request every time a candidate is moved.
        // Read the header first and write only when it is missing/different.
        ValueRange current = sheets.spreadsheets().values()
                .get(id, "'" + completed + "'!A1:J1")
                .execute();

        List<List<Object>> rows = current.getValues();
        if (rows != null && !rows.isEmpty()) {
            List<Object> r = rows.get(0);
            boolean same = true;
            for (int i = 0; i < header.size(); i++) {
                String actual = i < r.size() && r.get(i) != null ? String.valueOf(r.get(i)).trim() : "";
                if (!header.get(i).toString().equalsIgnoreCase(actual)) {
                    same = false;
                    break;
                }
            }
            if (same) return;
        }

        sheets.spreadsheets()
                .values()
                .update(
                        id,
                        "'" + completed + "'!A1:J1",
                        new ValueRange().setValues(List.of(header))
                )
                .setValueInputOption("RAW")
                .execute();
    }

    private static Object raw(List<Object> row, int index) {
        return index < row.size() && row.get(index) != null ? row.get(index) : "";
    }


    // =========================================================
    // REPAIR OLD ROWS THAT WERE ACCIDENTALLY SHIFTED TO COLUMN M
    // =========================================================

    /**
     * Repairs the old append bug visible as:
     *
     *   A:L blank, M = candidate email, N = password, ... Y = completion time
     *
     * The shifted M:Y values are copied back to A:M, then N:Y are cleared.
     * A normal completion timestamp in M is NOT touched because it is not an email.
     */
    public int repairShiftedCompletedRows() throws Exception {

        String configured = Config.get("completed.sheet.name");
        if (configured.isBlank()) configured = "Completed";
        String completed = resolveSheetTitle(configured);

        ensureCompletedHeader(completed);

        ValueRange vr = sheets.spreadsheets().values()
                .get(id, "'" + completed + "'!A2:Y")
                .execute();

        List<List<Object>> rows = vr.getValues();
        if (rows == null || rows.isEmpty()) return 0;

        // IMPORTANT: do NOT update/clear one row at a time.
        // That old logic could make 60+ Sheets write requests in one minute and cause HTTP 429.
        // Build all row repairs first, then send ONE batch update and ONE batch clear.
        List<ValueRange> updates = new ArrayList<>();
        List<String> clearRanges = new ArrayList<>();
        List<Integer> repairedRows = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            List<Object> r = rows.get(i);
            int sheetRow = i + 2;

            String colA = v(r, 0);
            String colB = v(r, 1);

            // Already in the requested format: A=date, B=email.
            if (looksLikeEmail(colB)) continue;

            List<Object> normalized = null;

            // Legacy normal row: A=email, B=password, C=name, D=mobile, E=status, M=date.
            if (looksLikeEmail(colA)) {
                Object date = raw(r, 12);
                // Some older sheets used K for Completed Date. Use it only if M is blank.
                if (String.valueOf(date).trim().isBlank()) date = raw(r, 10);

                normalized = List.of(
                        date, raw(r, 0), raw(r, 1), raw(r, 2), raw(r, 3), raw(r, 4),
                        "", "", "", date
                );
            }

            // Old shifted append bug: M=email, N=password, O=name, P=mobile, Q=status, Y=date.
            else if (looksLikeEmail(v(r, 12))) {
                normalized = List.of(
                        raw(r, 24), raw(r, 12), raw(r, 13), raw(r, 14), raw(r, 15), raw(r, 16),
                        "", "", "", raw(r, 24)
                );
            }

            if (normalized == null) continue;

            updates.add(new ValueRange()
                    .setRange("'" + completed + "'!A" + sheetRow + ":J" + sheetRow)
                    .setValues(List.of(normalized)));
            clearRanges.add("'" + completed + "'!G" + sheetRow + ":Y" + sheetRow);
            repairedRows.add(sheetRow);
        }

        if (updates.isEmpty()) return 0;

        sheets.spreadsheets().values()
                .batchUpdate(
                        id,
                        new BatchUpdateValuesRequest()
                                .setValueInputOption("RAW")
                                .setData(updates)
                )
                .execute();

        sheets.spreadsheets().values()
                .batchClear(
                        id,
                        new BatchClearValuesRequest().setRanges(clearRanges)
                )
                .execute();

        for (Integer sheetRow : repairedRows) {
            System.out.println(
                    "NORMALIZED COMPLETED ROW " + sheetRow
                            + " -> A=Date B=Email C=Password D=Name E=Mobile F=Status J=Sent Date/Time"
            );
        }

        return repairedRows.size();
    }



    // =========================================================
    // FIND NEXT EXPLICIT ROW IN COMPLETED
    // =========================================================

    private int findNextCompletedRow(String completed) throws Exception {

        ValueRange vr = sheets.spreadsheets().values()
                .get(id, "'" + completed + "'!A:Y")
                .execute();

        List<List<Object>> rows = vr.getValues();
        if (rows == null || rows.isEmpty()) return 1;

        int lastNonBlankRow = 0;

        for (int i = 0; i < rows.size(); i++) {
            List<Object> row = rows.get(i);
            boolean any = false;

            for (Object value : row) {
                if (value != null && !String.valueOf(value).trim().isBlank()) {
                    any = true;
                    break;
                }
            }

            if (any) lastNonBlankRow = i + 1;
        }

        return Math.max(1, lastNonBlankRow + 1);
    }

    private static boolean looksLikeEmail(String value) {
        if (value == null) return false;
        String email = value.trim();
        int at = email.indexOf('@');
        return at > 0
                && at == email.lastIndexOf('@')
                && at < email.length() - 1
                && email.indexOf('.', at) > at + 1
                && !email.contains(" ");
    }


    // =========================================================
    // RESOLVE COMPLETED SHEET TITLE
    // =========================================================

    private String resolveSheetTitle(
            String wanted
    ) throws Exception {

        Spreadsheet spreadsheet =
                sheets.spreadsheets()
                        .get(id)
                        .setFields(
                                "sheets.properties"
                        )
                        .execute();

        for (Sheet sheet : spreadsheet.getSheets()) {

            String title =
                    sheet.getProperties()
                            .getTitle();

            if (wanted.equalsIgnoreCase(title)) {
                return title;
            }
        }

        throw new IllegalStateException(
                "Completed sheet not found. "
                        + "Expected sheet/tab named: "
                        + wanted
        );
    }


    // =========================================================
    // READ SINGLE CELL
    // =========================================================

    private String readCell(
            String cell
    ) throws Exception {

        ValueRange result =
                sheets.spreadsheets()
                        .values()
                        .get(
                                id,
                                "'" + name + "'!"
                                        + cell
                        )
                        .execute();

        return result.getValues() == null
                || result.getValues().isEmpty()
                || result.getValues().get(0).isEmpty()

                ? ""

                : String.valueOf(
                result.getValues()
                        .get(0)
                        .get(0)
        ).trim();
    }


    // =========================================================
    // FIND SOURCE SHEET ID
    // =========================================================

    private int findSheetId(
            String title
    ) throws Exception {

        Spreadsheet spreadsheet =
                sheets.spreadsheets()
                        .get(id)
                        .setFields(
                                "sheets.properties"
                        )
                        .execute();

        for (Sheet sheet :
                spreadsheet.getSheets()) {

            if (title.equals(
                    sheet.getProperties()
                            .getTitle()
            )) {

                return sheet.getProperties()
                        .getSheetId();
            }
        }

        throw new IllegalStateException(
                "Sheet not found: "
                        + title
        );
    }


    // =========================================================
    // WRITE SINGLE CELL
    // =========================================================

    private void cell(
            String cell,
            String value
    ) throws Exception {

        sheets.spreadsheets()
                .values()
                .update(
                        id,

                        "'" + name + "'!"
                                + cell,

                        new ValueRange()
                                .setValues(
                                        List.of(
                                                List.of(value)
                                        )
                                )
                )
                .setValueInputOption("RAW")
                .execute();
    }


    // =========================================================
    // SAFE VALUE FROM ROW
    // =========================================================

    private static String v(
            List<Object> row,
            int index
    ) {

        return index < row.size()
                && row.get(index) != null

                ? row.get(index)
                .toString()
                .trim()

                : "";
    }
}