package org.example;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.cos.COSName;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time-per-row profile preparation before the wellness modules start.
 *
 * Flow:
 * 1. Open SMY Profile from the top-right profile menu.
 * 2. Read Name + Mobile, normalize mobile to 10 digits and save C/D.
 * 3. Read INDoS directly from SMY Profile, save it to column S, and use it
 *    for DG/eSamudra login. Column S is a fallback if the profile field is unavailable.
 * 4. Open DG/eSamudra in a temporary tab, login, open Update Seafarer Profile,
 *    download the DG profile PDF, and extract candidate photo + latest ship + IMO.
 * 5. Close all DG tabs, return to SMY, Edit Profile, upload the DG photo, fill
 *    Aadhaar from column U, latest ship/IMO from DG PDF, and Submit.
 */
public final class DgProfileSync {

    private static final String SMY_DASHBOARD =
            "https://sagarmeinyog.com/dashboard";
    private static final String SMY_PROFILE =
            "https://sagarmeinyog.com/dashboard/profile";
    private static final String DG_BASE =
            "http://220.156.189.33/esamudraUI";
    private static final String DG_LOGIN_URL =
            DG_BASE + "/logOut.do?method=loadIndexPage";
    private static final String DG_UPDATE_PROFILE_URL =
            DG_BASE + "/jsp/examination/UpdateProfile/SEAFARER_DTL.jsp?hidProcessMode=beforeAdd&ProcessId=SFRR";

    private static final DateTimeFormatter DG_DATE =
            DateTimeFormatter.ofPattern("d/M/uuuu");

    // A successful row is not repeated again during the same Java run.
    private static final Set<Integer> SYNCED_ROWS =
            ConcurrentHashMap.newKeySet();

    // Profile/DG/photo work is completely separate from e-learning.  Keep one
    // background profile browser at a time so four learning browsers do not
    // get starved of RAM.  More profile jobs simply queue; learning never waits.
    private static final Set<Integer> SYNC_ROWS_IN_PROGRESS =
            ConcurrentHashMap.newKeySet();
    private static final ExecutorService PROFILE_SYNC_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "DG-PROFILE-SYNC");
                thread.setDaemon(false);
                return thread;
            });

    // Strict photo-first mode: only one profile/DG job at a time.
    // Fair ordering prevents later rows from jumping ahead of earlier rows.
    private static final Semaphore PROFILE_SYNC_GATE = new Semaphore(1, true);

    private DgProfileSync() {
    }

    public static boolean isPhotoAlreadyMarked(String photoStatus) {
        return photoStatus != null
                && "PHOTO EXIST".equalsIgnoreCase(photoStatus.trim());
    }

    public static Path prepareWorkingDirectory(int sheetRowNumber)
            throws IOException {
        Path dir = Path.of("dg-profile-temp", "row-" + sheetRowNumber)
                .toAbsolutePath();
        Files.createDirectories(dir);

        // Never delete files underneath a profile worker that is still using
        // them.  A fresh retry may clear old leftovers before it starts.
        if (!SYNC_ROWS_IN_PROGRESS.contains(sheetRowNumber)) {
            try (var stream = Files.list(dir)) {
                for (Path file : stream.toList()) {
                    if (Files.isRegularFile(file)) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
        return dir;
    }

    /**
     * Starts DG/photo/profile work on a completely separate browser thread.
     * The caller returns immediately so e-learning can start at once.
     */
    public static void startParallelSync(
            String smyUsername,
            String smyPassword,
            String existingName,
            String existingMobile,
            String indosFromSheet,
            String changedDgPassword,
            String aadhaarFromSheet,
            int sheetRowNumber,
            Path workingDir
    ) {
        if (SYNCED_ROWS.contains(sheetRowNumber)) {
            System.out.println("PROFILE BACKGROUND SKIPPED | already verified this run | row "
                    + sheetRowNumber);
            return;
        }

        if (!SYNC_ROWS_IN_PROGRESS.add(sheetRowNumber)) {
            System.out.println("PROFILE BACKGROUND ALREADY RUNNING | row " + sheetRowNumber);
            return;
        }

        PROFILE_SYNC_EXECUTOR.submit(() -> {
            WebDriver profileDriver = null;
            try {
                System.out.println();
                System.out.println("====================================");
                System.out.println("PROFILE BACKGROUND START | ROW " + sheetRowNumber);
                System.out.println("E-LEARNING IS NOT WAITING FOR THIS");
                System.out.println("====================================");

                profileDriver = createSmyProfileChromeDriver(workingDir);
                Login.login(profileDriver, smyUsername, smyPassword);

                syncProfile(
                        profileDriver,
                        existingName,
                        existingMobile,
                        indosFromSheet,
                        changedDgPassword,
                        aadhaarFromSheet,
                        sheetRowNumber,
                        workingDir
                );

            } catch (Exception profileError) {
                String concise = oneLine(profileError.getClass().getSimpleName()
                        + " - " + profileError.getMessage(), 180);
                System.out.println("PROFILE BACKGROUND RETRY NEEDED | ROW "
                        + sheetRowNumber + " | " + concise);
                System.out.println("E-LEARNING CONTINUES | DG TEMP FILES KEPT IF CREATED");

                // Keep DG/INDoS failures visible in the sheet without touching
                // Column F course progress. Column V is the profile/photo status.
                try {
                    String lower = concise.toLowerCase();
                    String status;
                    if (lower.contains("indos") || lower.contains("dg login")
                            || lower.contains("esamudra") || lower.contains("login failed")) {
                        status = "DG ERROR - INDoS/Login not working";
                    } else if (profileError instanceof TimeoutException) {
                        status = "PROFILE ERROR - Timeout - will retry";
                    } else {
                        status = "PROFILE ERROR - will retry";
                    }
                    SheetRepository.updateProfileStatus(sheetRowNumber, status);
                } catch (Exception statusWriteError) {
                    System.out.println("PROFILE ERROR STATUS WRITE FAILED | ROW " + sheetRowNumber);
                }

            } finally {
                if (profileDriver != null) {
                    try {
                        profileDriver.quit();
                    } catch (Exception ignored) {
                    }
                }
                SYNC_ROWS_IN_PROGRESS.remove(sheetRowNumber);
            }
        });
    }

    /**
     * Strict photo-first entry point. The e-learning caller waits for this
     * profile/photo attempt to finish before continuing to the wellness modules.
     */
    public static void syncBeforeLearning(
            WebDriver driver,
            String existingName,
            String existingMobile,
            String indosFromSheet,
            String changedDgPassword,
            String aadhaarFromSheet,
            int sheetRowNumber,
            Path workingDir
    ) throws Exception {
        if (SYNCED_ROWS.contains(sheetRowNumber)) {
            System.out.println("PHOTO-FIRST SKIPPED | already verified this run | row "
                    + sheetRowNumber);
            return;
        }

        PROFILE_SYNC_GATE.acquire();
        SYNC_ROWS_IN_PROGRESS.add(sheetRowNumber);
        try {
            System.out.println("PHOTO-FIRST START | ROW " + sheetRowNumber);
            syncProfile(
                    driver,
                    existingName,
                    existingMobile,
                    indosFromSheet,
                    changedDgPassword,
                    aadhaarFromSheet,
                    sheetRowNumber,
                    workingDir
            );
            System.out.println("PHOTO-FIRST COMPLETE | ROW " + sheetRowNumber);
        } finally {
            SYNC_ROWS_IN_PROGRESS.remove(sheetRowNumber);
            PROFILE_SYNC_GATE.release();
        }
    }

    /** Older signature retained for compatibility/testing. */
    public static void syncBeforeLearning(
            WebDriver driver,
            String indosFromSheet,
            String changedDgPassword,
            String aadhaarFromSheet,
            int sheetRowNumber,
            Path workingDir
    ) throws Exception {
        syncBeforeLearning(
                driver,
                "",
                "",
                indosFromSheet,
                changedDgPassword,
                aadhaarFromSheet,
                sheetRowNumber,
                workingDir
        );
    }

    private static WebDriver createSmyProfileChromeDriver(Path workingDir) {
        ChromeOptions options = new ChromeOptions();
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        options.setAcceptInsecureCerts(true);

        // SMY stays in Chrome. Firefox is created ONLY for the DG/eSamudra
        // section later in syncProfile(). This prevents Firefox from opening
        // the SMY login page.
        Map<String, Object> prefs = new java.util.HashMap<>();
        prefs.put("profile.managed_default_content_settings.images", 1);
        prefs.put("profile.default_content_setting_values.images", 1);
        prefs.put("profile.default_content_setting_values.notifications", 2);
        prefs.put("profile.default_content_setting_values.popups", 2);
        options.setExperimentalOption("prefs", prefs);

        if (!ChapterRunner.showBrowserForTesting()) {
            options.addArguments("--headless=new");
        } else {
            options.addArguments("--start-maximized");
        }

        options.addArguments("--window-size=1366,768");
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--no-first-run");

        WebDriver driver = new ChromeDriver(options);
        try {
            driver.manage().window().setSize(new org.openqa.selenium.Dimension(1366, 768));
        } catch (Exception ignored) {
        }

        if (ChapterRunner.showBrowserForTesting()) {
            try {
                driver.manage().window().maximize();
            } catch (Exception ignored) {
            }
            System.out.println(
                    "PROFILE SMY BROWSER VISIBLE | CHROME | SMY READ/UPDATE ONLY"
            );
        }
        return driver;
    }

    private static WebDriver createDgFirefoxDriver(Path workingDir) {
        FirefoxOptions options = new FirefoxOptions();
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        options.setAcceptInsecureCerts(true);

        // Firefox is used ONLY for DG/eSamudra because the DG site itself
        // advises Firefox. PDF downloads are saved to this row's temp folder.
        String downloadDir = workingDir.toAbsolutePath().toString();
        options.addPreference("browser.download.folderList", 2);
        options.addPreference("browser.download.dir", downloadDir);
        options.addPreference("browser.download.useDownloadDir", true);
        options.addPreference("browser.download.manager.showWhenStarting", false);
        options.addPreference("browser.download.alwaysOpenPanel", false);
        options.addPreference(
                "browser.helperApps.neverAsk.saveToDisk",
                "application/pdf,application/octet-stream,application/x-pdf"
        );
        options.addPreference("pdfjs.disabled", true);
        options.addPreference("permissions.default.image", 1);
        options.addPreference("dom.webnotifications.enabled", false);
        options.addPreference("dom.push.enabled", false);
        options.addPreference("browser.tabs.warnOnClose", false);

        if (!ChapterRunner.showBrowserForTesting()) {
            options.addArguments("-headless");
        }

        WebDriver driver = new FirefoxDriver(options);
        try {
            driver.manage().window().setSize(new org.openqa.selenium.Dimension(1366, 768));
        } catch (Exception ignored) {
        }

        if (ChapterRunner.showBrowserForTesting()) {
            try {
                driver.manage().window().maximize();
            } catch (Exception ignored) {
            }
            System.out.println(
                    "DG PROFILE BROWSER VISIBLE | FIREFOX | DG/eSAMUDRA ONLY"
            );
            System.out.println(
                    "SMY PROFILE/UPLOAD REMAINS IN CHROME"
            );
        }
        return driver;
    }

    private static void syncProfile(
            WebDriver driver,
            String existingName,
            String existingMobile,
            String indosFromSheet,
            String changedDgPassword,
            String aadhaarFromSheet,
            int sheetRowNumber,
            Path workingDir
    ) throws Exception {

        if (SYNCED_ROWS.contains(sheetRowNumber)) {
            System.out.println("PROFILE PREP ALREADY DONE THIS RUN | row "
                    + sheetRowNumber);
            return;
        }

        System.out.println();
        System.out.println("====================================");
        System.out.println("PROFILE PREP START | ROW " + sheetRowNumber);
        System.out.println("====================================");

        /*
         * Always read/fill C = Name and D = Mobile BEFORE deciding that the
         * profile photo already exists. Earlier builds returned immediately
         * when a photo was found, which could leave C/D blank forever.
         */
        openSmyProfileFromMenu(driver);
        waitForSmyProfileReadyWithRefresh(driver);

        String profileName = cleanText(existingName);
        String tenDigitMobile = normalizeTenDigitMobileOrBlank(existingMobile);
        String indos = normalizeIndos(indosFromSheet);

        boolean needName = profileName.isBlank();
        boolean needMobile = tenDigitMobile.isBlank();
        boolean needIndos = indos.isBlank();

        if (!needName) {
            System.out.println("NAME ALREADY IN SHEET - LEAVE C" + sheetRowNumber
                    + " | " + profileName);
        }
        if (!needMobile) {
            System.out.println("MOBILE ALREADY IN SHEET - LEAVE D" + sheetRowNumber
                    + " | " + tenDigitMobile);
        }
        if (!needIndos) {
            System.out.println("INDOS ALREADY IN SHEET - USING R" + sheetRowNumber
                    + " | " + indos);
        }

        if (needName) {
            try {
                profileName = readSmyProfileName(driver);
                System.out.println("SMY PROFILE NAME FOUND | " + profileName);
            } catch (Exception nameError) {
                System.out.println("NAME READ SKIPPED FOR NOW | "
                        + oneLine(nameError.getMessage(), 140));
            }
        }

        if (needMobile) {
            try {
                tenDigitMobile = normalizeTenDigitMobile(
                        readSmyMobile(driver)
                );
                System.out.println("SMY PROFILE MOBILE FOUND | " + tenDigitMobile);
            } catch (Exception mobileError) {
                System.out.println("MOBILE READ SKIPPED FOR NOW | "
                        + oneLine(mobileError.getMessage(), 140));
            }
        }

        if (needIndos) {
            indos = normalizeIndos(readSmyIndos(driver));
        }

        // Write C/D as soon as profile identity is available. Existing values
        // stay untouched; blank values can be repaired even on Completed rows.
        if (needName || needMobile) {
            if (!profileName.isBlank() || !tenDigitMobile.isBlank()) {
                SheetRepository.updateProfileIdentityPreservingExisting(
                        sheetRowNumber,
                        profileName,
                        tenDigitMobile
                );
            }
        }

        if (needIndos && !indos.isBlank()) {
            SheetRepository.updateIndosNumber(sheetRowNumber, indos);
        }

        /*
         * Photo state is checked only AFTER C/D/R have been repaired.
         * If a real photo already exists, no DG/eSamudra work is needed.
         */
        if (hasRealSmyProfilePhoto(driver)) {
            SheetRepository.markPhotoExists(sheetRowNumber);
            SYNCED_ROWS.add(sheetRowNumber);
            System.out.println("SMY PHOTO ALREADY EXISTS | IDENTITY SAVED | U MARKED | NO DG CHECK NEEDED");
            return;
        }

        System.out.println("SMY PHOTO MISSING | FIRST-TIME DG PHOTO FLOW WILL RUN");

        if (indos.isBlank()) {
            throw new IllegalStateException(
                    "INDoS number was not found in SMY Profile or PC1 column S."
            );
        }

        // DG password rule:
        // - If this candidate already has a value in column T, use S directly.
        //   Do NOT waste a login attempt on INDoS + "1".
        // - Only when S is blank do we try the normal INDoS + "1" password.
        // - If that normal password fails, fetch S again so a password entered
        //   while the bot is running can still be used immediately.
        String defaultDgPassword = indos + "1";
        String changedDgPasswordFallback = changedDgPassword == null
                ? ""
                : changedDgPassword.trim();

        String aadhaar = normalizeAadhaar(aadhaarFromSheet);
        if (aadhaar.isBlank()) {
            System.out.println("AADHAAR T" + sheetRowNumber
                    + " IS BLANK/INVALID - DO NOT BLOCK DG PHOTO");
            System.out.println("DG PDF/PHOTO WILL STILL BE FETCHED; AADHAAR FIELD IS SKIPPED.");
        }

        // DG must happen BEFORE any Aadhaar-related profile validation so a
        // missing Aadhaar can never prevent PDF/photo extraction.
        // IMPORTANT: DG/eSamudra runs in its OWN Firefox instance. The current
        // 'driver' remains the logged-in SMY Chrome session for the later upload.
        System.out.println("DG PROFILE BACKGROUND | OPENING eSAMUDRA IN FIREFOX NOW");
        WebDriver dgDriver = null;
        DgProfileData dgData;
        try {
            dgDriver = createDgFirefoxDriver(workingDir);
            dgData = fetchDgProfileData(
                    dgDriver,
                    indos,
                    defaultDgPassword,
                    changedDgPasswordFallback,
                    workingDir,
                    sheetRowNumber
            );
        } finally {
            if (dgDriver != null) {
                try {
                    dgDriver.quit();
                } catch (Exception ignored) {
                }
            }
        }

        System.out.println("DG FIREFOX CLOSED | RETURNING TO SMY CHROME");
        if (dgData.photoFile != null
                && dgData.photoFile.isFile()
                && dgData.photoFile.length() > 0) {
            System.out.println("DG PHOTO READY | " + dgData.photoFile.getAbsolutePath());
        } else {
            System.out.println("DG PHOTO NOT AVAILABLE THIS RUN | CONTINUING WITH AADHAAR/SHIP/IMO");
        }
        System.out.println("DG LAST SHIP   | " + safeText(dgData.shipName));
        System.out.println("DG IMO         | " + safeText(dgData.imoNumber));

        updateSmyProfile(
                driver,
                dgData.photoFile,
                aadhaar,
                dgData.shipName,
                dgData.imoNumber
        );

        // Only mark U when a real photo was available and updateSmyProfile()
        // returned after its fresh-page verification. If DG had no photo, keep U
        // blank so a later run can retry the photo flow.
        if (dgData.photoFile != null
                && dgData.photoFile.isFile()
                && dgData.photoFile.length() > 0) {
            SheetRepository.markPhotoExists(sheetRowNumber);
        }

        // Only after the server-backed SMY profile page proves that the photo
        // is really present do we remove the temporary DG PDF/photo files.
        deleteWorkingDirectoryAfterVerifiedUpload(workingDir);

        SYNCED_ROWS.add(sheetRowNumber);

        System.out.println("PROFILE PREP COMPLETE | "
                + (profileName.isBlank() ? "row " + sheetRowNumber : profileName)
                + (tenDigitMobile.isBlank() ? "" : " | " + tenDigitMobile)
                + " | Ship: " + dgData.shipName
                + " | IMO: " + dgData.imoNumber);
    }

    private static String normalizeTenDigitMobileOrBlank(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return "";
            }
            return normalizeTenDigitMobile(raw);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void openSmyProfileFromMenu(WebDriver driver)
            throws Exception {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(25));
        JavascriptExecutor js = (JavascriptExecutor) driver;

        try {
            if (!driver.getCurrentUrl().contains("/dashboard")) {
                driver.get(SMY_DASHBOARD);
            }

            WebElement menuButton = firstVisibleEnabled(
                    driver,
                    List.of(
                            // Exact stable shape of the top-right candidate menu:
                            // name span + chevron-down.  Never depend on the Radix id.
                            By.xpath("//button[@aria-haspopup='menu'"
                                    + " and .//span[contains(@class,'font-semibold')]"
                                    + " and .//*[name()='svg' and contains(@class,'chevron-down')]]"),
                            By.xpath("//button[@aria-haspopup='menu'][.//img[@alt='Profile'] or .//span]")
                    )
            );
            if (menuButton == null) {
                throw new IllegalStateException("SMY top-right profile dropdown not found.");
            }

            click(driver, menuButton);

            WebElement profileLink = wait.until(
                    ExpectedConditions.elementToBeClickable(
                            By.cssSelector("a[href='/dashboard/profile']")
                    )
            );
            click(driver, profileLink);

        } catch (Exception menuError) {
            // Stable direct fallback if Radix menu markup changes.
            driver.get(SMY_PROFILE);
        }

        waitForSmyProfileReadyWithRefresh(driver);

        js.executeScript("window.scrollTo(0,0);");
    }

    /**
     * SMY occasionally renders a plain "Network Error" inside the SPA while
     * the authenticated session is still valid. Refresh the Profile page and
     * retry instead of treating that as a candidate failure.
     */
    private static void waitForSmyProfileReadyWithRefresh(WebDriver driver)
            throws Exception {
        Exception lastError = null;

        for (int attempt = 1; attempt <= 6; attempt++) {
            try {
                String url = cleanText(driver.getCurrentUrl());
                if (!url.contains("/dashboard/profile")) {
                    driver.get(SMY_PROFILE);
                }

                WebDriverWait shortWait =
                        new WebDriverWait(driver, Duration.ofSeconds(10));

                Boolean ready = shortWait.until(webDriver -> {
                    String body = safeBodyText(webDriver);
                    if (body.toLowerCase(Locale.ROOT).contains("network error")) {
                        return Boolean.FALSE;
                    }

                    boolean profileMarker = !webDriver.findElements(
                            By.xpath("//*[normalize-space()='Profile'"
                                    + " or contains(normalize-space(.),'SEAFARER')"
                                    + " or normalize-space()='Additional Details']")
                    ).isEmpty();

                    return profileMarker ? Boolean.TRUE : null;
                });

                if (Boolean.TRUE.equals(ready)) {
                    return;
                }

                System.out.println("SMY NETWORK ERROR - REFRESH PROFILE | attempt "
                        + attempt + "/6");

            } catch (Exception e) {
                lastError = e;
                String body = safeBodyText(driver).toLowerCase(Locale.ROOT);
                if (body.contains("network error")) {
                    System.out.println("SMY NETWORK ERROR - REFRESH PROFILE | attempt "
                            + attempt + "/6");
                } else {
                    System.out.println("SMY PROFILE NOT READY - REFRESH | attempt "
                            + attempt + "/6");
                }
            }

            try {
                driver.navigate().refresh();
            } catch (Exception refreshError) {
                lastError = refreshError;
                driver.get(SMY_PROFILE);
            }
            Thread.sleep(1800L * attempt);
        }

        throw new IllegalStateException(
                "SMY Profile stayed on Network Error/not-ready after refresh retries.",
                lastError
        );
    }

    private static String readSmyProfileName(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));

        // Current SMY profile card shows the candidate name immediately above
        // the role text (SEAFARER). Try the direct DOM relationships first.
        List<By> locators = List.of(
                By.xpath("//*[normalize-space()='SEAFARER']/preceding-sibling::*[normalize-space()][1]"),
                By.xpath("//*[normalize-space()='SEAFARER']/preceding::*[self::h1 or self::h2 or self::h3][normalize-space()][1]"),
                By.xpath("//div[contains(@class,'font-bold') and contains(@class,'text-2xl')][normalize-space()]"),
                By.xpath("//span[contains(@class,'font-semibold') and normalize-space()]")
        );

        for (By locator : locators) {
            try {
                WebElement element = wait.until(
                        ExpectedConditions.visibilityOfElementLocated(locator)
                );
                String name = cleanText(element.getText());
                if (looksLikeProfileName(name)) {
                    return name;
                }
            } catch (Exception ignored) {
            }
        }

        // Robust fallback for the current card layout: inspect visible text and
        // take the line immediately before the exact role line "SEAFARER".
        try {
            String[] lines = driver.findElement(By.tagName("body"))
                    .getText()
                    .split("\\R+");

            for (int i = 1; i < lines.length; i++) {
                if ("SEAFARER".equalsIgnoreCase(lines[i].trim())) {
                    String candidate = cleanText(lines[i - 1]);
                    if (looksLikeProfileName(candidate)) {
                        return candidate;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        throw new IllegalStateException("SMY Profile name could not be read.");
    }

    private static boolean looksLikeProfileName(String value) {
        String text = cleanText(value);
        if (text.isBlank()
                || text.equalsIgnoreCase("Profile")
                || text.equalsIgnoreCase("Profile Creation")
                || text.equalsIgnoreCase("SEAFARER")
                || text.contains("@")
                || (text.length() <= 3 && text.matches("[A-Za-z]{1,3}"))) {
            return false;
        }

        // Candidate names normally contain alphabetic characters and should
        // not be one of the profile field labels.
        if (!text.matches(".*[A-Za-z].*")) {
            return false;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        return !lower.contains("mobile number")
                && !lower.contains("indos number")
                && !lower.contains("additional details")
                && !lower.contains("edit profile");
    }

    private static String readSmyMobile(WebDriver driver)
            throws Exception {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

        // First try the normal Profile page Additional Details field.
        String mobile = findMobileValue(driver);
        if (!mobile.isBlank()) {
            return mobile;
        }

        // Fallback: open Edit Profile and read the mobile field there.
        WebElement edit = wait.until(
                ExpectedConditions.elementToBeClickable(
                        By.xpath("//button[normalize-space()='Edit Profile']")
                )
        );
        click(driver, edit);

        wait.until(driverInstance -> {
            String value = findMobileValue(driverInstance);
            return value.isBlank() ? null : value;
        });

        mobile = findMobileValue(driver);
        if (!mobile.isBlank()) {
            return mobile;
        }

        throw new IllegalStateException("SMY Profile mobile number could not be read.");
    }

    private static String findMobileValue(WebDriver driver) {
        List<By> inputLocators = List.of(
                By.xpath("//*[normalize-space()='Mobile Number']/following::input[1]"),
                By.cssSelector("input[name='mobileNumber']"),
                By.cssSelector("input[name='phoneNumber']"),
                By.cssSelector("input[name='phone']")
        );

        for (By locator : inputLocators) {
            for (WebElement element : driver.findElements(locator)) {
                try {
                    String value = cleanText(element.getAttribute("value"));
                    if (containsPhoneDigits(value)) {
                        return value;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // Current profile page may render the value in a readonly DIV rather
        // than an INPUT. Read the first visible value after the label.
        List<By> textLocators = List.of(
                By.xpath("//*[normalize-space()='Mobile Number']/following-sibling::*[normalize-space()][1]"),
                By.xpath("//*[normalize-space()='Mobile Number']/following::*[normalize-space()][1]")
        );

        for (By locator : textLocators) {
            for (WebElement element : driver.findElements(locator)) {
                try {
                    String value = cleanText(element.getText());
                    if (value.isBlank()) {
                        value = cleanText(element.getAttribute("value"));
                    }
                    if (containsPhoneDigits(value)) {
                        return value;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // Final fallback: Selenium's body text preserves the label/value rows
        // visible in the profile card, e.g. "Mobile Number" then 09677537270.
        String bodyValue = valueOnLineAfterLabel(driver, "Mobile Number");
        if (containsPhoneDigits(bodyValue)) {
            return bodyValue;
        }

        return "";
    }

    private static boolean containsPhoneDigits(String value) {
        if (value == null) {
            return false;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.length() >= 10 && digits.length() <= 14;
    }

    private static String valueOnLineAfterLabel(WebDriver driver, String label) {
        try {
            String body = driver.findElement(By.tagName("body")).getText();
            String[] lines = body.split("\\R+");
            for (int i = 0; i < lines.length - 1; i++) {
                if (label.equalsIgnoreCase(lines[i].trim())) {
                    for (int j = i + 1; j < Math.min(lines.length, i + 4); j++) {
                        String candidate = cleanText(lines[j]);
                        if (!candidate.isBlank()) {
                            return candidate;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String normalizeTenDigitMobile(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");

        // Some SMY profiles store Indian mobile numbers with a trunk-prefix 0,
        // for example 09488351975.  The sheet must contain only the real
        // 10-digit mobile number, so ignore leading zeroes before doing any
        // other country-code cleanup.
        digits = digits.replaceFirst("^0+", "");

        // Normal +91 / 91 prefix.
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }

        // Defensive fallback for values containing another prefix.  Keep the
        // final 10 digits, which is how the old automation handled +91 values.
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }

        // Never save a value that still starts with 0.  If removing the 0 did
        // not leave a complete 10-digit number, simply treat the mobile as
        // unavailable and let the rest of the candidate continue normally.
        if (digits.length() != 10 || digits.startsWith("0")) {
            throw new IllegalStateException(
                    "Valid 10-digit mobile number not found after ignoring leading 0: " + raw
            );
        }
        return digits;
    }

    private static String readSmyIndos(WebDriver driver) {
        // Support both editable INPUT fields and the current readonly profile
        // card where INDoS is rendered as plain text beneath its label.
        List<By> locators = List.of(
                By.xpath("//*[normalize-space()='INDoS Number']/following::input[1]"),
                By.cssSelector("input[name='indosNumber']"),
                By.cssSelector("input[name='indos']"),
                By.cssSelector("input[name*='indos' i]"),
                By.xpath("//*[normalize-space()='INDoS Number']/following-sibling::*[normalize-space()][1]"),
                By.xpath("//*[normalize-space()='INDoS Number']/following::*[normalize-space()][1]")
        );

        for (By locator : locators) {
            for (WebElement element : driver.findElements(locator)) {
                try {
                    String value = cleanText(element.getAttribute("value"));
                    if (value.isBlank()) {
                        value = cleanText(element.getText());
                    }
                    String clean = normalizeIndos(value);
                    if (looksLikeIndos(clean)) {
                        System.out.println("SMY PROFILE INDOS FOUND | " + clean);
                        return clean;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        String bodyValue = valueOnLineAfterLabel(driver, "INDoS Number");
        String clean = normalizeIndos(bodyValue);
        if (looksLikeIndos(clean)) {
            System.out.println("SMY PROFILE INDOS FOUND | " + clean);
            return clean;
        }

        return "";
    }

    private static boolean looksLikeIndos(String value) {
        return value != null && value.matches("^[0-9]{2}[A-Z]{2}[0-9]{4}$");
    }

    private static String chooseIndos(
            String indosFromProfile,
            String indosFromSheet
    ) {
        String profile = normalizeIndos(indosFromProfile);
        if (!profile.isBlank()) {
            return profile;
        }

        String sheet = normalizeIndos(indosFromSheet);
        if (!sheet.isBlank()) {
            System.out.println("SMY PROFILE INDOS NOT READ - USING PC1 COLUMN S | " + sheet);
            return sheet;
        }

        throw new IllegalStateException(
                "INDoS number was not found in SMY Profile or PC1 column S."
        );
    }

    private static String normalizeIndos(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ENGLISH);
        // Accept either 23NM1233 or manually-entered 23NM1233@ in column S.
        value = value.replaceAll("@+$", "");
        value = value.replaceAll("[^A-Z0-9]", "");
        return value;
    }

    private static String normalizeAadhaar(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() != 12) {
            return "";
        }
        return digits.substring(0, 4)
                + " " + digits.substring(4, 8)
                + " " + digits.substring(8, 12);
    }

    private static DgProfileData fetchDgProfileData(
            WebDriver driver,
            String indos,
            String defaultDgPassword,
            String changedDgPasswordFallback,
            Path workingDir,
            int sheetRowNumber
    ) throws Exception {

        // Always re-read T immediately before DG login. This makes the current
        // sheet value authoritative even if it was changed after the user row
        // was first loaded.
        String latestColumnT = SheetRepository.readChangedDgPassword(sheetRowNumber);
        String columnTPassword = latestColumnT == null || latestColumnT.isBlank()
                ? changedDgPasswordFallback
                : latestColumnT.trim();

        boolean loggedIn;

        if (columnTPassword != null && !columnTPassword.isBlank()) {
            // User requested this exact rule: when T has a value, go straight
            // to that password. Do not try INDoS+1 first.
            System.out.println("DG LOGIN | COLUMN T HAS PASSWORD - USING T DIRECTLY");
            loggedIn = attemptDgLogin(
                    driver,
                    indos,
                    columnTPassword
            );

            if (!loggedIn) {
                throw new IllegalStateException(
                        "DG login failed using PC1 column T password for INDoS "
                                + indos + ". Check column T."
                );
            }

        } else {
            System.out.println("DG LOGIN | COLUMN T BLANK - USING INDoS + 1");
            loggedIn = attemptDgLogin(
                    driver,
                    indos,
                    defaultDgPassword
            );

            if (!loggedIn) {
                System.out.println("DG DEFAULT PASSWORD NOT MATCHED");

                // One last re-read supports typing the changed password into T
                // while the visible DG login attempt is in progress.
                latestColumnT = SheetRepository.readChangedDgPassword(sheetRowNumber);
                String newlyEnteredPassword = latestColumnT == null
                        ? ""
                        : latestColumnT.trim();

                if (newlyEnteredPassword.isBlank()) {
                    throw new IllegalStateException(
                            "DG password did not match for INDoS " + indos
                                    + ". Put the changed DG password in PC1 column T."
                    );
                }

                System.out.println("DG LOGIN RETRY | NEW PASSWORD FOUND IN COLUMN T");
                loggedIn = attemptDgLogin(
                        driver,
                        indos,
                        newlyEnteredPassword
                );

                if (!loggedIn) {
                    throw new IllegalStateException(
                            "DG login failed using the newly entered PC1 column T password for INDoS "
                                    + indos + ". Check column T."
                    );
                }
            }
        }

        System.out.println("DG LOGIN SUCCESS CONFIRMED | INDoS " + indos);

        // IMPORTANT: do NOT jump directly to SEAFARER_DTL.jsp after login.
        // DG sometimes returns "500 Internal Server Error / doFilter methodnull"
        // when that internal JSP is opened directly.  The authenticated HOME
        // page contains an "Update Seafarer Profile" link whose onclick sets
        // the server/session state required by DG.  Click the real site link
        // exactly as a user would, then retry from HOME if DG gives a transient 500.
        openDgUpdateProfileFromAuthenticatedHome(driver);

        System.out.println("DG UPDATE SEAFARER PROFILE OPENED");

        // Read ship/IMO directly from DG Sea Going Service. Do not depend on
        // the heavy PDF report for these optional values.
        SeaService directSeaService = readLatestSeaServiceDirectlyFromDg(
                driver,
                workingDir,
                sheetRowNumber
        );
        String directShip = directSeaService == null
                ? ""
                : cleanText(directSeaService.shipName);
        String directImo = directSeaService == null
                ? ""
                : cleanText(directSeaService.imoNumber);

        // Reuse a previously extracted DG photo whenever available.
        Path cachedPhoto = getDgPhotoCachePath(indos);
        Path workingPhoto = workingDir.resolve(
                "dg-photo-row-" + sheetRowNumber + ".jpg"
        );

        if (isValidPhotoFile(cachedPhoto)) {
            Files.copy(
                    cachedPhoto,
                    workingPhoto,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
            System.out.println(
                    "DG PHOTO CACHE USED | " + cachedPhoto.toAbsolutePath()
            );
            return new DgProfileData(
                    workingPhoto.toFile(),
                    directShip,
                    directImo
            );
        }

        // No cached photo yet. Ask DG reportServlet only once. The DG server
        // is currently returning java.lang.OutOfMemoryError for repeated report
        // generation, so retries only make the server situation worse.
        try {
            Path pdf = downloadDgProfilePdfInsideFirefoxSession(
                    driver,
                    indos,
                    workingDir
            );

            System.out.println("DG PROFILE PDF SAVED | " + pdf.toAbsolutePath());
            DgProfileData pdfData = parseDgProfilePdf(
                    pdf,
                    workingDir,
                    sheetRowNumber
            );

            if (pdfData.photoFile != null
                    && pdfData.photoFile.isFile()
                    && pdfData.photoFile.length() > 0) {
                Files.createDirectories(cachedPhoto.getParent());
                Files.copy(
                        pdfData.photoFile.toPath(),
                        cachedPhoto,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
                System.out.println(
                        "DG PHOTO CACHE SAVED | " + cachedPhoto.toAbsolutePath()
                );
            }

            String finalShip = !directShip.isBlank()
                    ? directShip
                    : cleanText(pdfData.shipName);
            String finalImo = !directImo.isBlank()
                    ? directImo
                    : cleanText(pdfData.imoNumber);

            return new DgProfileData(
                    pdfData.photoFile,
                    finalShip,
                    finalImo
            );

        } catch (Exception reportError) {
            System.out.println(
                    "DG PHOTO REPORT UNAVAILABLE | "
                            + oneLine(reportError.getMessage(), 180)
            );

            // The DG reportServlet is intermittently returning a server-side
            // OutOfMemoryError. Before giving up on the photo, inspect the
            // normal authenticated DG pages for an already-rendered candidate
            // portrait/thumbnail and screenshot that element. This does not
            // generate another PDF report.
            File browserPhoto = tryCaptureDgPhotoFromAuthenticatedPages(
                    driver,
                    indos,
                    workingDir,
                    sheetRowNumber,
                    cachedPhoto
            );

            if (browserPhoto != null && browserPhoto.isFile()) {
                System.out.println(
                        "DG PHOTO CAPTURED WITHOUT PDF | "
                                + browserPhoto.getAbsolutePath()
                );
                return new DgProfileData(
                        browserPhoto,
                        directShip,
                        directImo
                );
            }

            System.out.println(
                    "DG SERVER REPORT FAILED - PHOTO UNAVAILABLE THIS RUN; AADHAAR/SHIP/IMO STILL CONTINUE"
            );

            return new DgProfileData(
                    null,
                    directShip,
                    directImo
            );
        }
    }


    private static File tryCaptureDgPhotoFromAuthenticatedPages(
            WebDriver driver,
            String indos,
            Path workingDir,
            int sheetRowNumber,
            Path cachedPhoto
    ) {
        String originalUrl = safeText(driver.getCurrentUrl());

        List<String> candidatePages = List.of(
                DG_BASE + "/loadsfrrUploadStatus.do?method=loadSfrrFileUploadPage&transId=kik",
                DG_BASE + "/jsp/examination/UpdateProfile/SEAFARER_DTL.jsp?hidProcessMode=beforeAdd&ProcessId=SFRR"
        );

        try {
            for (String pageUrl : candidatePages) {
                try {
                    System.out.println(
                            "DG PHOTO FALLBACK | CHECKING AUTHENTICATED PAGE | "
                                    + pageUrl
                    );
                    driver.get(pageUrl);
                    Thread.sleep(1400L);

                    WebElement best = null;
                    double bestScore = -1.0;

                    for (WebElement img : driver.findElements(By.cssSelector("img"))) {
                        try {
                            if (!img.isDisplayed()) {
                                continue;
                            }

                            String src = safeText(img.getAttribute("src"));
                            String lower = src.toLowerCase(Locale.ROOT);

                            if (lower.contains("dgshipping")
                                    || lower.contains("shipping.jpg")
                                    || lower.contains("logo")
                                    || lower.contains("emblem")
                                    || lower.contains("spacer")
                                    || lower.contains("home.gif")
                                    || lower.contains("logout.gif")
                                    || lower.contains("ari.gif")
                                    || lower.contains("hcl.gif")
                                    || lower.contains("grbg")
                                    || lower.contains("brbg")
                                    || lower.endsWith("/new.gif")) {
                                continue;
                            }

                            Object dims = ((JavascriptExecutor) driver).executeScript(
                                    "return [arguments[0].naturalWidth||arguments[0].width||0,"
                                            + "arguments[0].naturalHeight||arguments[0].height||0];",
                                    img
                            );

                            if (!(dims instanceof List<?> values)
                                    || values.size() < 2) {
                                continue;
                            }

                            double width = ((Number) values.get(0)).doubleValue();
                            double height = ((Number) values.get(1)).doubleValue();

                            if (width < 80 || height < 80) {
                                continue;
                            }

                            double ratio = width / Math.max(1.0, height);
                            if (ratio < 0.48 || ratio > 1.45) {
                                continue;
                            }

                            double score = width * height;
                            if (ratio >= 0.65 && ratio <= 1.20) {
                                score *= 2.0;
                            }
                            if (lower.contains("photo")
                                    || lower.contains("profile")
                                    || lower.contains("sfrr")
                                    || lower.contains("seafar")) {
                                score *= 4.0;
                            }

                            if (score > bestScore) {
                                bestScore = score;
                                best = img;
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    if (best == null) {
                        continue;
                    }

                    File shot = best.getScreenshotAs(OutputType.FILE);
                    BufferedImage image = ImageIO.read(shot);
                    if (image == null
                            || image.getWidth() < 80
                            || image.getHeight() < 80) {
                        continue;
                    }

                    Path workingPhoto = workingDir.resolve(
                            "dg-photo-row-" + sheetRowNumber + ".jpg"
                    );
                    saveJpegForSmy(image, workingPhoto);

                    if (!isValidPhotoFile(workingPhoto)) {
                        continue;
                    }

                    Files.createDirectories(cachedPhoto.getParent());
                    Files.copy(
                            workingPhoto,
                            cachedPhoto,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    );
                    System.out.println(
                            "DG PHOTO CACHE SAVED | "
                                    + cachedPhoto.toAbsolutePath()
                    );
                    return workingPhoto.toFile();

                } catch (Exception pageError) {
                    System.out.println(
                            "DG PHOTO FALLBACK PAGE SKIPPED | "
                                    + oneLine(pageError.getMessage(), 140)
                    );
                }
            }
        } finally {
            try {
                if (!originalUrl.isBlank()) {
                    driver.get(originalUrl);
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }


    private static Path getDgPhotoCachePath(String indos) throws IOException {
        Path cacheDir = Path.of("dg-photo-cache").toAbsolutePath();
        Files.createDirectories(cacheDir);
        String safeIndos = normalizeIndos(indos);
        if (safeIndos.isBlank()) {
            safeIndos = "UNKNOWN";
        }
        return cacheDir.resolve(safeIndos + ".jpg");
    }

    private static boolean isValidPhotoFile(Path photo) {
        if (photo == null || !Files.isRegularFile(photo)) {
            return false;
        }
        try {
            if (Files.size(photo) < 2_000L) {
                return false;
            }
            BufferedImage image = ImageIO.read(photo.toFile());
            return image != null
                    && image.getWidth() >= 80
                    && image.getHeight() >= 80;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Read latest ship/IMO from DG's authenticated Sea Going Service page.
     * Failure is non-blocking: blank means leave SMY field unchanged.
     */
    private static SeaService readLatestSeaServiceDirectlyFromDg(
            WebDriver driver,
            Path workingDir,
            int sheetRowNumber
    ) {
        String profileUrl = "";
        boolean openedSeaPage = false;
        try {
            profileUrl = safeText(driver.getCurrentUrl());

            // DG keeps the Professional menu collapsed, so the Sea Going
            // Service anchor can exist in the DOM while Selenium reports it as
            // not displayed. Do NOT require visibility here. Read its href
            // directly from the authenticated profile page and navigate to it.
            WebElement seaGoing = null;
            List<WebElement> seaLinks = driver.findElements(
                    By.cssSelector("a[href*='SEAFARER_SEA_GOING']")
            );
            if (!seaLinks.isEmpty()) {
                seaGoing = seaLinks.get(0);
            }
            if (seaGoing == null) {
                List<WebElement> byText = driver.findElements(
                        By.xpath("//a[contains(normalize-space(.),'Sea Going Service')]")
                );
                if (!byText.isEmpty()) {
                    seaGoing = byText.get(0);
                }
            }

            String seaHref = seaGoing == null
                    ? ""
                    : safeText(seaGoing.getAttribute("href"));

            if (seaHref.isBlank()) {
                // This is the authenticated DG path shown in the profile menu.
                // Use it as a same-session fallback rather than treating a
                // hidden menu item as missing.
                seaHref = DG_BASE
                        + "/jsp/examination/UpdateProfile/SEAFARER_SEA_GOING.jsp?hidProcessMode=beforeAdd";
                System.out.println(
                        "DG SEA GOING SERVICE HIDDEN/MISSING IN VISIBLE MENU | OPENING AUTHENTICATED PAGE DIRECTLY"
                );
            } else {
                System.out.println("DG OPEN | Sea Going Service | hidden menu href accepted");
            }

            driver.get(seaHref);
            openedSeaPage = true;

            WebDriverWait wait = new WebDriverWait(
                    driver,
                    Duration.ofSeconds(25)
            );
            try {
                wait.until(webDriver -> {
                    String url = safeText(webDriver.getCurrentUrl()).toUpperCase(Locale.ROOT);
                    String body = safeBodyText(webDriver).toUpperCase(Locale.ROOT);
                    return url.contains("SEAFARER_SEA_GOING")
                            || body.contains("SEA GOING SERVICE");
                });
            } catch (TimeoutException ignored) {
            }

            String body = safeBodyText(driver);
            String source = safePageSource(driver);

            try {
                Files.writeString(
                        workingDir.resolve(
                                "dg-sea-going-row-" + sheetRowNumber + ".txt"
                        ),
                        body == null ? "" : body,
                        java.nio.charset.StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
                Files.writeString(
                        workingDir.resolve(
                                "dg-sea-going-row-" + sheetRowNumber + ".html"
                        ),
                        source == null ? "" : source,
                        java.nio.charset.StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (Exception ignored) {
            }

            SeaService service = findLatestSeaService(body);
            if (service == null) {
                StringBuilder rowText = new StringBuilder();
                for (WebElement row : driver.findElements(By.xpath("//tr"))) {
                    String value = cleanText(row.getText());
                    if (!value.isBlank()) {
                        rowText.append(value).append('\n');
                    }
                }
                service = findLatestSeaService(rowText.toString());
            }

            if (service == null) {
                System.out.println(
                        "DG SEA GOING SERVICE | NO RECORD FOUND | SHIP/IMO LEFT BLANK"
                );
                return null;
            }

            String ship = cleanText(service.shipName);
            String imo = cleanText(service.imoNumber);

            if (ship.isBlank()) {
                System.out.println("DG LAST SHIP NOT FOUND | LEAVE SMY FIELD");
            } else {
                System.out.println("DG LATEST SHIP | " + ship);
            }
            if (imo.isBlank()) {
                System.out.println("DG IMO NOT FOUND | LEAVE SMY FIELD");
            } else {
                System.out.println("DG IMO NUMBER | " + imo);
            }

            return service;

        } catch (Exception e) {
            System.out.println(
                    "DG SEA GOING SERVICE READ SKIPPED | "
                            + oneLine(e.getMessage(), 160)
            );
            return null;
        } finally {
            if (openedSeaPage) {
                try {
                    driver.navigate().back();
                    WebDriverWait backWait = new WebDriverWait(
                            driver,
                            Duration.ofSeconds(15)
                    );
                    backWait.until(webDriver -> isDgProfilePage(
                            webDriver,
                            safeBodyText(webDriver),
                            safePageSource(webDriver)
                    ));
                } catch (Exception backError) {
                    try {
                        if (profileUrl != null && !profileUrl.isBlank()) {
                            driver.get(profileUrl);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Path downloadDgProfilePdfInsideFirefoxSession(
            WebDriver driver,
            String indos,
            Path workingDir
    ) throws Exception {

        Files.createDirectories(workingDir);
        Path target = workingDir.resolve("reportServlet.pdf");
        Files.deleteIfExists(target);

        String reportUrl = "/esamudraUI/reportServlet?processId=SfrrProfile&Indosno=" + indos;
        JavascriptExecutor js = (JavascriptExecutor) driver;

        try {
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(95));
        } catch (Exception ignored) {
        }

        for (int attempt = 1; attempt <= 1; attempt++) {
            System.out.println(
                    "DG REPORT | ONE AUTHENTICATED FIREFOX REQUEST | TRY 1/1"
            );

            Object raw = js.executeAsyncScript(
                    "var url=arguments[0], done=arguments[arguments.length-1];" +
                    "fetch(url,{credentials:'include',cache:'no-store'})" +
                    ".then(async function(r){" +
                    "  var ct=r.headers.get('content-type')||'';" +
                    "  var ab=await r.arrayBuffer();" +
                    "  var u=new Uint8Array(ab);" +
                    "  var chunks=[];" +
                    "  var size=0x8000;" +
                    "  for(var i=0;i<u.length;i+=size){" +
                    "    chunks.push(String.fromCharCode.apply(null,u.subarray(i,Math.min(i+size,u.length))));" +
                    "  }" +
                    "  done({ok:r.ok,status:r.status,ct:ct,b64:btoa(chunks.join(''))});" +
                    "}).catch(function(e){done({ok:false,status:0,ct:'',error:String(e)});});",
                    reportUrl
            );

            if (!(raw instanceof Map)) {
                System.out.println("DG REPORT RESPONSE INVALID | TRY 1/1");
                if (attempt < 1) Thread.sleep(8000L);
                continue;
            }

            Map<String, Object> result = (Map<String, Object>) raw;
            Object statusObj = result.get("status");
            long status = statusObj instanceof Number
                    ? ((Number) statusObj).longValue()
                    : 0L;
            String contentType = String.valueOf(result.getOrDefault("ct", ""));
            String b64 = String.valueOf(result.getOrDefault("b64", ""));
            String error = String.valueOf(result.getOrDefault("error", ""));

            if (!error.isBlank() && !"null".equalsIgnoreCase(error)) {
                System.out.println("DG REPORT FIREFOX FETCH ERROR | " + error);
            }

            if (!b64.isBlank() && !"null".equalsIgnoreCase(b64)) {
                byte[] bytes;
                try {
                    bytes = java.util.Base64.getDecoder().decode(b64);
                } catch (IllegalArgumentException badBase64) {
                    bytes = new byte[0];
                }

                boolean pdfHeader = bytes.length >= 5
                        && bytes[0] == '%'
                        && bytes[1] == 'P'
                        && bytes[2] == 'D'
                        && bytes[3] == 'F'
                        && bytes[4] == '-';

                if (status == 200 && pdfHeader) {
                    Files.write(
                            target,
                            bytes,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE
                    );
                    System.out.println(
                            "DG PDF RECEIVED IN FIREFOX SESSION | "
                                    + bytes.length + " bytes"
                    );
                    return target;
                }

                String preview = new String(
                        bytes,
                        0,
                        Math.min(bytes.length, 220),
                        java.nio.charset.StandardCharsets.ISO_8859_1
                ).replaceAll("\\s+", " ").trim();

                System.out.println(
                        "DG REPORT NOT PDF | TRY 1/1"
                                + " | HTTP " + status
                                + " | TYPE " + contentType
                                + (preview.isBlank() ? "" : " | " + preview)
                );
            } else {
                System.out.println(
                        "DG REPORT EMPTY | TRY 1/1 | HTTP " + status
                );
            }

            if (attempt < 1) {
                Thread.sleep(10000L);
            }
        }

        throw new IllegalStateException(
                "DG Profile PDF was not returned by the authenticated Firefox session."
        );
    }

    private static void openDgUpdateProfileFromAuthenticatedHome(
            WebDriver driver
    ) throws Exception {

        String homeHandle = driver.getWindowHandle();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(25));

        for (int attempt = 1; attempt <= 5; attempt++) {

            // If a previous try left us on the DG 500 page, close that popup/tab
            // (when applicable) and return to the authenticated HOME page.
            String currentBody = safeBodyText(driver);
            if (isDgServer500(currentBody)) {
                System.out.println(
                        "DG PROFILE PAGE SERVER 500 | RETURNING HOME | TRY "
                                + attempt + "/5"
                );
                returnToDgAuthenticatedHome(driver, homeHandle);
                Thread.sleep(1800L);
            }

            if (isDgFirefoxAdvicePage(currentBody)) {
                System.out.println(
                        "DG FIREFOX ADVICE PAGE DETECTED | RETURNING HOME | TRY "
                                + attempt + "/5"
                );
                returnToDgAuthenticatedHome(driver, homeHandle);
                Thread.sleep(1200L);
            }

            // We must be on the authenticated page.  Do not accept the public
            // page because it has the same link text but popup() only says Please Log In.
            String homeBody = safeBodyText(driver);
            if (!isDgAuthenticatedPage(driver, homeBody)) {
                returnToDgAuthenticatedHome(driver, homeHandle);
                homeBody = safeBodyText(driver);
            }

            if (!isDgAuthenticatedPage(driver, homeBody)) {
                throw new IllegalStateException(
                        "DG authenticated HOME session was lost before opening Update Seafarer Profile."
                );
            }

            WebElement updateProfile = firstVisibleEnabled(
                    driver,
                    List.of(
                            By.xpath("//a[normalize-space()='Update Seafarer Profile']"),
                            By.xpath("//a[contains(normalize-space(.),'Update Seafarer Profile')]")
                    )
            );

            if (updateProfile == null) {
                System.out.println(
                        "DG UPDATE PROFILE LINK NOT READY | REFRESH HOME | TRY "
                                + attempt + "/5"
                );
                driver.navigate().refresh();
                Thread.sleep(1500L);
                continue;
            }

            Set<String> before = new HashSet<>(driver.getWindowHandles());
            String beforeUrl = driver.getCurrentUrl();

            System.out.println(
                    "DG CLICK | Update Seafarer Profile | TRY " + attempt + "/5"
            );
            click(driver, updateProfile);

            // Public/not-authenticated pages show a JavaScript alert "Please Log In".
            // A real authenticated click must never be accepted in that state.
            try {
                WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(2));
                Alert alert = shortWait.until(ExpectedConditions.alertIsPresent());
                String alertText = alert == null ? "" : safeText(alert.getText());
                if (alert != null) {
                    alert.accept();
                }
                if (alertText.toLowerCase(Locale.ROOT).contains("please log in")) {
                    throw new IllegalStateException(
                            "DG session expired: Update Seafarer Profile returned Please Log In."
                    );
                }
            } catch (TimeoutException noAlert) {
                // Normal authenticated path: no alert.
            }

            long end = System.currentTimeMillis() + 18_000L;
            while (System.currentTimeMillis() < end) {

                // The DG link may open the profile in another window/tab.
                Set<String> now = driver.getWindowHandles();
                if (now.size() > before.size()) {
                    for (String handle : now) {
                        if (!before.contains(handle)) {
                            driver.switchTo().window(handle);
                            break;
                        }
                    }
                }

                String body = safeBodyText(driver);
                String source = safePageSource(driver);

                if (isDgProfilePage(driver, body, source)) {
                    System.out.println(
                            "DG PROFILE PAGE CONFIRMED | Click to View and Print Your Profile FOUND"
                    );
                    return;
                }

                if (isDgFirefoxAdvicePage(body)) {
                    System.out.println(
                            "DG BROWSER ADVICE PAGE RETURNED | FIREFOX RETRY "
                                    + attempt + "/5"
                    );
                    break;
                }

                if (isDgServer500(body)) {
                    System.out.println(
                            "DG SERVER 500 WHILE OPENING PROFILE | AUTO RETRY "
                                    + attempt + "/5"
                    );
                    break;
                }

                // Some versions navigate in the same tab but take a few seconds.
                if (!safeText(driver.getCurrentUrl()).equals(beforeUrl)) {
                    Thread.sleep(500L);
                } else {
                    Thread.sleep(350L);
                }
            }

            // Close any failed profile popup and return to the authenticated home.
            returnToDgAuthenticatedHome(driver, homeHandle);
            Thread.sleep(1800L + (attempt * 500L));
        }

        throw new IllegalStateException(
                "DG Update Seafarer Profile could not open after 5 site-click retries. "
                        + "DG server kept returning 500 or did not load the authenticated profile page."
        );
    }

    private static void returnToDgAuthenticatedHome(
            WebDriver driver,
            String preferredHomeHandle
    ) throws Exception {

        // Prefer the original authenticated home window if it still exists.
        Set<String> handles = driver.getWindowHandles();
        if (preferredHomeHandle != null && handles.contains(preferredHomeHandle)) {
            String current = driver.getWindowHandle();
            if (!preferredHomeHandle.equals(current)) {
                try {
                    driver.close();
                } catch (Exception ignored) {
                }
                driver.switchTo().window(preferredHomeHandle);
            }
        } else if (!handles.isEmpty()) {
            driver.switchTo().window(handles.iterator().next());
        }

        // If we are on an error/profile page in the same window, Back normally
        // restores the authenticated home without destroying the session.
        String body = safeBodyText(driver);
        if (isDgServer500(body)
                || isDgFirefoxAdvicePage(body)
                || isDgProfilePage(driver, body, safePageSource(driver))) {
            driver.navigate().back();
        }

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        try {
            wait.until(webDriver -> isDgAuthenticatedPage(
                    webDriver,
                    safeBodyText(webDriver)
            ));
        } catch (TimeoutException notHomeYet) {
            // Last resort: reload the DG index in the SAME authenticated session.
            // This is not the internal profile JSP, so it is safe to reopen.
            driver.get(DG_LOGIN_URL);
            wait.until(webDriver -> isDgAuthenticatedPage(
                    webDriver,
                    safeBodyText(webDriver)
            ));
        }
    }

    private static boolean isDgAuthenticatedPage(
            WebDriver driver,
            String body
    ) {
        String upper = safeText(body).toUpperCase(Locale.ROOT);
        boolean welcome = upper.contains("WELCOME ");
        boolean logout = upper.contains("LOG OUT") || upper.contains("LOGOUT");
        boolean resetPassword = upper.contains("RESET PASSWORD");
        boolean loginForm = !driver.findElements(
                By.cssSelector("input[type='password']")
        ).isEmpty();
        return welcome && (logout || resetPassword) && !loginForm;
    }

    private static boolean isDgFirefoxAdvicePage(String body) {
        String lower = safeText(body).toLowerCase(Locale.ROOT);
        return lower.contains("advised to use mozilla firefox")
                || lower.contains("better performance")
                && lower.contains("mozilla firefox");
    }

    private static boolean isDgServer500(String body) {
        String lower = safeText(body).toLowerCase(Locale.ROOT);
        return lower.contains("500 internal server error")
                || lower.contains("servlet error") && lower.contains("dofilter");
    }

    private static boolean isDgProfilePage(
            WebDriver driver,
            String body,
            String source
    ) {
        String lowerBody = safeText(body).toLowerCase(Locale.ROOT);
        String lowerSource = safeText(source).toLowerCase(Locale.ROOT);
        if (isDgServer500(body)) {
            return false;
        }
        return lowerBody.contains("click to view and print your profile")
                || lowerSource.contains("viewprofilereport")
                || !driver.findElements(
                        By.xpath("//a[contains(normalize-space(.),'Click to View and Print Your Profile')]")
                ).isEmpty();
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String safePageSource(WebDriver driver) {
        try {
            return driver.getPageSource();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean attemptDgLogin(
            WebDriver driver,
            String indos,
            String dgPassword
    ) throws Exception {

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(25));
        driver.get(DG_LOGIN_URL);

        WebElement passwordBox;
        try {
            passwordBox = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            By.cssSelector("input[type='password']")
                    )
            );
        } catch (TimeoutException firstPageNotLogin) {
            WebElement loginLink = firstVisibleEnabled(
                    driver,
                    List.of(
                            By.linkText("Login"),
                            By.xpath("//a[normalize-space()='Login']"),
                            By.xpath("//button[normalize-space()='Login']")
                    )
            );
            if (loginLink != null) {
                click(driver, loginLink);
            }
            passwordBox = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            By.cssSelector("input[type='password']")
                    )
            );
        }

        WebElement userIdBox = firstVisibleEnabled(
                driver,
                List.of(
                        By.cssSelector("input[name='userId']"),
                        By.cssSelector("input[id='userId']"),
                        By.cssSelector("input[name*='user' i]"),
                        By.cssSelector("input[id*='user' i]"),
                        By.xpath("//input[(@type='text' or not(@type)) and not(@disabled)]")
                )
        );

        if (userIdBox == null) {
            throw new IllegalStateException("DG User Id field not found.");
        }

        userIdBox.clear();
        userIdBox.sendKeys(indos);
        passwordBox.clear();
        passwordBox.sendKeys(dgPassword);

        WebElement loginButton = firstVisibleEnabled(
                driver,
                List.of(
                        By.xpath("//input[@type='submit' and translate(@value,'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ')='LOGIN']"),
                        By.xpath("//input[@type='button' and translate(@value,'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ')='LOGIN']"),
                        By.xpath("//button[normalize-space()='Login']"),
                        By.xpath("//input[@value='Login']")
                )
        );

        if (loginButton == null) {
            throw new IllegalStateException("DG Login button not found.");
        }
        click(driver, loginButton);

        long end = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < end) {
            String body = safeBodyText(driver);
            String lower = body.toLowerCase(Locale.ROOT);

            if (lower.contains("username and password")
                    && lower.contains("does not match")) {
                return false;
            }

            // REAL success markers from the authenticated DG home page.
            // DG renders the control as "Log Out" (with a space), so checking only
            // for the contiguous word "LOGOUT" causes a false failure even after
            // a successful login.  Accept either spelling and also use Reset Password
            // as a second authenticated marker.
            String upperBody = body.toUpperCase(Locale.ROOT);

            boolean logoutPresent = upperBody.contains("LOG OUT")
                    || upperBody.contains("LOGOUT")
                    || !driver.findElements(
                    By.xpath("//a[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'LOG OUT')]")
            ).isEmpty()
                    || !driver.findElements(
                    By.xpath("//a[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'LOGOUT')]")
            ).isEmpty()
                    || !driver.findElements(
                    By.xpath("//input[contains(translate(@value,'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'LOG OUT')]")
            ).isEmpty()
                    || !driver.findElements(
                    By.xpath("//input[contains(translate(@value,'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'LOGOUT')]")
            ).isEmpty();

            boolean welcomePresent = lower.contains("welcome ");
            boolean resetPasswordPresent = upperBody.contains("RESET PASSWORD");
            boolean loginFormStillPresent = !driver.findElements(
                    By.cssSelector("input[type='password']")
            ).isEmpty();

            if (welcomePresent
                    && (logoutPresent || resetPasswordPresent)
                    && !loginFormStillPresent) {
                System.out.println(
                        "DG AUTHENTICATED HOME CONFIRMED | Welcome + Log Out/Reset Password"
                );
                return true;
            }

            Thread.sleep(400);
        }

        String body = safeBodyText(driver);
        System.out.println("DG LOGIN NOT CONFIRMED | PAGE = " + oneLine(body, 180));
        return false;
    }

    private static Path downloadDgProfilePdfWithSession(
            WebDriver driver,
            String indos,
            Path workingDir
    ) throws Exception {

        String reportUrl = DG_BASE
                + "/reportServlet?processId=SfrrProfile&Indosno="
                + indos;

        String cookieHeader = driver.manage().getCookies().stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");

        if (cookieHeader.isBlank()) {
            throw new IllegalStateException(
                    "DG authenticated cookies were not available for PDF download."
            );
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        Path target = workingDir.resolve("reportServlet.pdf");
        Files.deleteIfExists(target);

        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(reportUrl))
                        .timeout(Duration.ofSeconds(35))
                        .header("Cookie", cookieHeader)
                        .header("Referer", driver.getCurrentUrl())
                        .header(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:142.0) Gecko/20100101 Firefox/142.0"
                        )
                        .header("Accept", "application/pdf,*/*;q=0.8")
                        .GET()
                        .build();

                HttpResponse<byte[]> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofByteArray()
                );

                byte[] body = response.body();
                boolean pdfBytes = body != null
                        && body.length > 1000
                        && body.length >= 4
                        && body[0] == '%'
                        && body[1] == 'P'
                        && body[2] == 'D'
                        && body[3] == 'F';

                if (response.statusCode() >= 200
                        && response.statusCode() < 300
                        && pdfBytes) {
                    Files.write(
                            target,
                            body,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE
                    );
                    System.out.println(
                            "DG PDF SESSION DOWNLOAD SUCCESS | "
                                    + target.toAbsolutePath()
                                    + " | bytes=" + body.length
                    );
                    return target;
                }

                String preview = body == null
                        ? ""
                        : new String(
                                body,
                                0,
                                Math.min(body.length, 220),
                                java.nio.charset.StandardCharsets.ISO_8859_1
                        );
                System.out.println(
                        "DG PDF SESSION DOWNLOAD NOT PDF | TRY "
                                + attempt + "/3 | HTTP " + response.statusCode()
                                + " | " + oneLine(preview, 160)
                );
            } catch (Exception e) {
                lastError = e;
                System.out.println(
                        "DG PDF SESSION DOWNLOAD ERROR | TRY "
                                + attempt + "/3 | "
                                + oneLine(e.getMessage(), 180)
                );
            }

            Thread.sleep(1200L * attempt);
        }

        if (lastError != null) {
            throw new IllegalStateException(
                    "DG Profile PDF could not be downloaded with the authenticated session.",
                    lastError
            );
        }
        return null;
    }

    private static void deletePendingDgReportDownload(Path dir) {
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.equals("reportservlet.pdf")
                        || name.equals("reportservlet.pdf.part")
                        || name.endsWith(".pdf.part")) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Path waitForDownloadedPdf(Path dir, int timeoutSeconds)
            throws Exception {
        long end = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < end) {
            try (var stream = Files.list(dir)) {
                List<Path> candidates = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                            return !name.endsWith(".crdownload")
                                    && !name.endsWith(".part")
                                    && !name.endsWith(".tmp");
                        })
                        .sorted(Comparator.comparingLong(DgProfileSync::lastModifiedSafe)
                                .reversed())
                        .toList();

                for (Path candidate : candidates) {
                    if (Files.size(candidate) > 1000 && looksLikePdf(candidate)) {
                        return candidate;
                    }
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    private static boolean looksLikePdf(Path path) {
        try (var input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(4);
            return header.length == 4
                    && header[0] == '%'
                    && header[1] == 'P'
                    && header[2] == 'D'
                    && header[3] == 'F';
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static DgProfileData parseDgProfilePdf(
            Path pdf,
            Path workingDir,
            int sheetRowNumber
    ) throws Exception {

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            // IMPORTANT: extract/save the photo FIRST.  Ship/IMO text parsing must
            // never block the photo upload.  The older code checked ship/IMO first,
            // so a text-layout mismatch caused the whole profile job to stop before
            // the candidate photo was even written to disk.
            BufferedImage photo = extractCandidatePhoto(document);
            if (photo == null) {
                throw new IllegalStateException(
                        "Candidate photo could not be extracted from DG Profile PDF."
                );
            }

            Path photoFile = workingDir.resolve(
                    "dg-photo-row-" + sheetRowNumber + ".jpg"
            );
            saveJpegForSmy(photo, photoFile);
            System.out.println("DG PHOTO EXTRACTED | " + photoFile.toAbsolutePath());

            // DG's PDF is a table.  PDFBox's default extraction order can mix the
            // left/right columns, so sort by visual position before parsing.
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);

            // Keep the raw extracted text beside the PDF while testing.  This is
            // extremely useful if DG changes the report layout for a particular
            // seafarer.  The temp folder is removed only after verified SMY upload.
            Path textDump = workingDir.resolve(
                    "dg-profile-text-row-" + sheetRowNumber + ".txt"
            );
            Files.writeString(
                    textDump,
                    text == null ? "" : text,
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            System.out.println("DG PROFILE TEXT SAVED | " + textDump.toAbsolutePath());

            SeaService service = findLatestSeaService(text);
            String shipName = service == null ? "" : cleanText(service.shipName);
            String imoNumber = service == null ? "" : cleanText(service.imoNumber);

            if (shipName.isBlank()) {
                System.out.println("DG LAST SHIP NOT READ | PHOTO/AADHAAR UPDATE WILL STILL CONTINUE");
            } else {
                System.out.println("DG LATEST SHIP     | " + shipName);
            }

            if (imoNumber.isBlank()) {
                System.out.println("DG IMO NOT READ | PHOTO/AADHAAR UPDATE WILL STILL CONTINUE");
            } else {
                System.out.println("DG IMO NUMBER      | " + imoNumber);
            }

            return new DgProfileData(
                    photoFile.toFile(),
                    shipName,
                    imoNumber
            );
        }
    }

    /**
     * Reads the most recent Sea Going Service entry from the DG profile report.
     * The report is an old table-based PDF and the extracted text can vary by
     * browser/PDF generator, so this parser deliberately accepts several label
     * forms (IMO Number / IMO No., Service To (Date) / Service To Date, etc.).
     */
    private static SeaService findLatestSeaService(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String normalized = text
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        Pattern shipPattern = Pattern.compile(
                "(?im)^\\s*(?:\\d+\\s*[.)-]?\\s*)?Ship\\s*Name\\s*[:\\-]?\\s*([^\\n]*)$"
        );

        Matcher matcher = shipPattern.matcher(normalized);
        List<ShipMatch> ships = new ArrayList<>();
        while (matcher.find()) {
            String shipName = cleanShipName(matcher.group(1));

            // Some PDF layouts put the value on the next line.
            if (shipName.isBlank()) {
                shipName = cleanShipName(nextUsefulPdfLine(normalized, matcher.end()));
            }

            if (!shipName.isBlank()) {
                ships.add(new ShipMatch(
                        matcher.start(),
                        matcher.end(),
                        shipName
                ));
            }
        }

        List<SeaService> services = new ArrayList<>();

        for (int i = 0; i < ships.size(); i++) {
            ShipMatch ship = ships.get(i);

            int segmentStart = ship.start;
            int segmentEnd = (i + 1 < ships.size())
                    ? ships.get(i + 1).start
                    : Math.min(normalized.length(), ship.end + 4500);

            if (segmentEnd <= segmentStart) {
                continue;
            }

            String segment = normalized.substring(segmentStart, segmentEnd);

            String imo = findFirst(
                    segment,
                    "(?is)IMO\\s*(?:Number|No\\.?)\\s*[:\\-]?\\s*([0-9]{6,8})"
            );

            // DG occasionally places the right-hand column slightly outside the
            // Ship Name text segment.  Search a larger local window as fallback.
            if (imo.isBlank()) {
                int localStart = Math.max(0, ship.start - 250);
                int localEnd = Math.min(normalized.length(), ship.end + 1800);
                imo = findFirst(
                        normalized.substring(localStart, localEnd),
                        "(?is)IMO\\s*(?:Number|No\\.?)\\s*[:\\-]?\\s*([0-9]{6,8})"
                );
            }

            String fromText = findFirst(
                    segment,
                    "(?is)Service\\s*From\\s*(?:\\(\\s*Date\\s*\\)|Date)?\\s*[:\\-]?\\s*(\\d{1,2}/\\d{1,2}/\\d{4})"
            );
            String toText = findFirst(
                    segment,
                    "(?is)Service\\s*To\\s*(?:\\(\\s*Date\\s*\\)|Date)?\\s*[:\\-]?\\s*(\\d{1,2}/\\d{1,2}/\\d{4})"
            );

            LocalDate from = parseDgDate(fromText);
            LocalDate to = parseDgDate(toText);

            services.add(new SeaService(ship.name, imo, from, to));
        }

        if (!services.isEmpty()) {
            SeaService latest = services.stream()
                    .max(Comparator
                            .comparing((SeaService s) -> s.toDate == null
                                    ? LocalDate.MIN : s.toDate)
                            .thenComparing(s -> s.fromDate == null
                                    ? LocalDate.MIN : s.fromDate))
                    .orElse(services.get(services.size() - 1));

            // If the chosen latest record has no IMO because of PDF column order,
            // use the nearest IMO around that ship occurrence instead of failing.
            if (latest.imoNumber == null || latest.imoNumber.isBlank()) {
                String nearestImo = findNearestImoForShip(normalized, latest.shipName);
                if (!nearestImo.isBlank()) {
                    latest = new SeaService(
                            latest.shipName,
                            nearestImo,
                            latest.fromDate,
                            latest.toDate
                    );
                }
            }
            return latest;
        }

        // Last-resort fallback for unusually flattened PDFs: take the last
        // labelled Ship Name and nearest labelled IMO.  This is preferable to
        // blocking the photo/profile update completely.
        String fallbackShip = findLastLabelValue(
                normalized,
                "(?im)^\\s*(?:\\d+\\s*[.)-]?\\s*)?Ship\\s*Name\\s*[:\\-]?\\s*([^\\n]+)$"
        );
        fallbackShip = cleanShipName(fallbackShip);

        String fallbackImo = findLastLabelValue(
                normalized,
                "(?is)IMO\\s*(?:Number|No\\.?)\\s*[:\\-]?\\s*([0-9]{6,8})"
        );

        if (fallbackShip.isBlank() && fallbackImo.isBlank()) {
            return null;
        }

        return new SeaService(fallbackShip, fallbackImo, null, null);
    }

    private static String nextUsefulPdfLine(String text, int fromIndex) {
        if (text == null || fromIndex < 0 || fromIndex >= text.length()) {
            return "";
        }

        int end = Math.min(text.length(), fromIndex + 500);
        String tail = text.substring(fromIndex, end);
        for (String line : tail.split("\\n")) {
            String value = cleanText(line);
            if (value.isBlank()) {
                continue;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("flag")
                    || lower.startsWith("official no")
                    || lower.startsWith("imo number")
                    || lower.startsWith("imo no")
                    || lower.startsWith("ship type")
                    || lower.startsWith("service from")
                    || lower.startsWith("service to")) {
                return "";
            }
            return value;
        }
        return "";
    }

    private static String findNearestImoForShip(String text, String shipName) {
        if (text == null || text.isBlank() || shipName == null || shipName.isBlank()) {
            return "";
        }

        String upperText = text.toUpperCase(Locale.ROOT);
        String upperShip = shipName.toUpperCase(Locale.ROOT);
        int pos = upperText.lastIndexOf(upperShip);
        if (pos < 0) {
            return "";
        }

        int start = Math.max(0, pos - 400);
        int end = Math.min(text.length(), pos + upperShip.length() + 2200);
        return findFirst(
                text.substring(start, end),
                "(?is)IMO\\s*(?:Number|No\\.?)\\s*[:\\-]?\\s*([0-9]{6,8})"
        );
    }

    private static String findLastLabelValue(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text == null ? "" : text);
        String value = "";
        while (matcher.find()) {
            value = cleanText(matcher.group(1));
        }
        return value;
    }

    private static String cleanShipName(String raw) {
        String value = cleanText(raw)
                .replaceFirst("^[.:\\-\\s]+", "");

        value = value.replaceFirst(
                "(?i)\\s+(Flag|Official\\s*No\\.?|Port\\s+of\\s+Registry|"
                        + "IMO\\s*(?:Number|No\\.?)|Ship\\s*Type|GT|Trade\\s*Area|Rank|"
                        + "Nature\\s+of\\s+watch|Service\\s+From|Service\\s+To).*$",
                ""
        ).trim();

        if (value.equalsIgnoreCase("Ship Name")) {
            return "";
        }
        return value;
    }

    private static LocalDate parseDgDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DG_DATE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String findFirst(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text == null ? "" : text);
        return matcher.find() ? cleanText(matcher.group(1)) : "";
    }

    private static BufferedImage extractCandidatePhoto(PDDocument document)
            throws IOException {
        PhotoCandidate best = null;

        int maxPages = Math.min(document.getNumberOfPages(), 4);
        for (int pageIndex = 0; pageIndex < maxPages; pageIndex++) {
            PDPage page = document.getPage(pageIndex);
            List<PhotoCandidate> found = new ArrayList<>();
            collectImages(page.getResources(), pageIndex, 0, found);

            for (PhotoCandidate candidate : found) {
                if (best == null || candidate.score > best.score) {
                    best = candidate;
                }
            }
        }

        if (best != null) {
            System.out.println(
                    "DG PHOTO SOURCE | embedded PDF image "
                            + best.image.getWidth() + "x" + best.image.getHeight()
            );
            return best.image;
        }

        // Some DG reports flatten the photograph into the page artwork instead
        // of exposing it as a normal PDImageXObject.  In that case render page 1
        // and crop the fixed photograph area in Personal Details.  This exact
        // fallback handles the 2-page DG profile layout as well as the longer
        // profiles where Sea Going Service appears on later pages.
        BufferedImage renderedPhoto = extractCandidatePhotoFromRenderedPage(document);
        if (renderedPhoto != null) {
            System.out.println(
                    "DG PHOTO SOURCE | rendered page-1 crop "
                            + renderedPhoto.getWidth() + "x" + renderedPhoto.getHeight()
            );
        }
        return renderedPhoto;
    }

    private static BufferedImage extractCandidatePhotoFromRenderedPage(
            PDDocument document
    ) throws IOException {
        if (document == null || document.getNumberOfPages() == 0) {
            return null;
        }

        PDFRenderer renderer = new PDFRenderer(document);
        BufferedImage page = renderer.renderImageWithDPI(0, 180);
        if (page == null || page.getWidth() < 300 || page.getHeight() < 400) {
            return null;
        }

        // DG Seafarer Profile page 1: candidate photograph is the upper-right
        // portrait inside Personal Details.  Keep the crop tight so the DG ship
        // banner/table border cannot be mistaken for part of the candidate image.
        int x1 = clamp((int) Math.round(page.getWidth() * 0.805), 0, page.getWidth() - 2);
        int y1 = clamp((int) Math.round(page.getHeight() * 0.145), 0, page.getHeight() - 2);
        int x2 = clamp((int) Math.round(page.getWidth() * 0.945), x1 + 1, page.getWidth());
        int y2 = clamp((int) Math.round(page.getHeight() * 0.248), y1 + 1, page.getHeight());

        BufferedImage crop = copySubImage(page, x1, y1, x2 - x1, y2 - y1);
        crop = trimNearWhiteBorder(crop);

        if (crop == null || crop.getWidth() < 55 || crop.getHeight() < 55) {
            return null;
        }
        return crop;
    }

    private static BufferedImage copySubImage(
            BufferedImage source,
            int x,
            int y,
            int width,
            int height
    ) {
        BufferedImage out = new BufferedImage(
                Math.max(1, width),
                Math.max(1, height),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(
                    source,
                    0, 0, out.getWidth(), out.getHeight(),
                    x, y, x + width, y + height,
                    null
            );
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage trimNearWhiteBorder(BufferedImage image) {
        if (image == null) {
            return null;
        }

        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >>> 16) & 0xff;
                int g = (rgb >>> 8) & 0xff;
                int b = rgb & 0xff;

                // Candidate photos have plenty of non-white pixels.  Ignore the
                // surrounding white PDF background while retaining light shirts/skin.
                if (r < 242 || g < 242 || b < 242) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return image;
        }

        int padX = Math.max(2, image.getWidth() / 40);
        int padY = Math.max(2, image.getHeight() / 40);
        minX = Math.max(0, minX - padX);
        minY = Math.max(0, minY - padY);
        maxX = Math.min(image.getWidth() - 1, maxX + padX);
        maxY = Math.min(image.getHeight() - 1, maxY + padY);

        return copySubImage(
                image,
                minX,
                minY,
                maxX - minX + 1,
                maxY - minY + 1
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void collectImages(
            PDResources resources,
            int pageIndex,
            int depth,
            List<PhotoCandidate> out
    ) throws IOException {
        if (resources == null || depth > 3) {
            return;
        }

        for (COSName name : resources.getXObjectNames()) {
            PDXObject object;
            try {
                object = resources.getXObject(name);
            } catch (Exception ignored) {
                continue;
            }

            if (object instanceof PDImageXObject imageObject) {
                BufferedImage image;
                try {
                    image = imageObject.getImage();
                } catch (Exception ignored) {
                    continue;
                }

                if (image == null) {
                    continue;
                }

                int width = image.getWidth();
                int height = image.getHeight();
                if (width < 45 || height < 55) {
                    continue;
                }

                double ratio = width / (double) height;
                // DG candidate photos are not always portrait-oriented.  Some
                // reports embed a head/shoulders image that is almost square or
                // slightly wider than tall.  The previous <=0.95 filter rejected
                // valid candidate photos (for example Naveen's report).
                if (ratio < 0.48 || ratio > 1.35) {
                    continue;
                }

                double score = width * (double) height;
                if (pageIndex == 0) {
                    score *= 5.0;
                }
                if (ratio >= 0.65 && ratio <= 1.20) {
                    score *= 2.5;
                }

                out.add(new PhotoCandidate(image, score));

            } else if (object instanceof PDFormXObject form) {
                collectImages(form.getResources(), pageIndex, depth + 1, out);
            }
        }
    }

    private static void saveJpegForSmy(BufferedImage source, Path target)
            throws IOException {
        int maxWidth = 600;
        int maxHeight = 800;

        double scale = Math.min(
                1.0,
                Math.min(maxWidth / (double) source.getWidth(),
                        maxHeight / (double) source.getHeight())
        );

        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer not available.");
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.86f);
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }

        if (Files.size(target) >= 2_000_000L) {
            throw new IOException("Extracted DG photo is still larger than 2MB: " + target);
        }
    }

    /**
     * Make an SMY-safe upload copy. It preserves the whole portrait, upscales
     * small DG images, removes unusual color models/alpha, and writes a normal
     * 600x600 RGB JPEG under 2 MB.
     */
    private static File preparePhotoForSmyUpload(File sourceFile) throws IOException {
        BufferedImage source = ImageIO.read(sourceFile);
        if (source == null) {
            throw new IOException("DG photo cannot be decoded before SMY upload: "
                    + sourceFile);
        }

        final int canvasSize = 600;
        final int margin = 18;
        final int available = canvasSize - (margin * 2);

        double scale = Math.min(
                available / (double) Math.max(1, source.getWidth()),
                available / (double) Math.max(1, source.getHeight())
        );
        int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        int x = (canvasSize - drawWidth) / 2;
        int y = (canvasSize - drawHeight) / 2;

        BufferedImage canvas = new BufferedImage(
                canvasSize, canvasSize, BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, canvasSize, canvasSize);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, x, y, drawWidth, drawHeight, null);
        } finally {
            g.dispose();
        }

        Path parent = sourceFile.toPath().toAbsolutePath().getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath();
        }
        String base = sourceFile.getName().replaceFirst("(?i)\\.(jpe?g|png)$", "");
        Path target = parent.resolve(base + "-smy-upload.jpg");

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer not available for SMY upload copy.");
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.92f);
            }
            writer.write(null, new IIOImage(canvas, null, null), param);
        } finally {
            writer.dispose();
        }

        long bytes = Files.size(target);
        if (bytes < 4_000L || bytes > 1_900_000L) {
            throw new IOException("Prepared SMY photo has unexpected size: "
                    + bytes + " bytes");
        }

        System.out.println("SMY PHOTO PREPARED | original "
                + source.getWidth() + "x" + source.getHeight()
                + " -> 600x600 RGB | " + bytes + " bytes");
        return target.toFile();
    }

    private static void updateSmyProfile(
            WebDriver driver,
            File dgPhoto,
            String aadhaar,
            String shipName,
            String imoNumber
    ) throws Exception {

        boolean hasDgPhoto =
                dgPhoto != null && dgPhoto.isFile() && dgPhoto.length() > 0;

        if (!hasDgPhoto) {
            System.out.println(
                    "SMY PHOTO SKIPPED THIS RUN | DG REPORT SERVER DID NOT RETURN A PHOTO"
            );
            updateSmyProfileFieldsWithoutPhoto(
                    driver,
                    aadhaar,
                    shipName,
                    imoNumber
            );
            return;
        }

        // Do not trust the site's success toast alone.  The backend can
        // occasionally say that the profile was saved while the photo itself
        // was not persisted.  Therefore upload -> submit -> reopen Profile ->
        // verify the real rendered image.  Retry the whole save if needed.
        final int maxPhotoUploadAttempts = 5;
        // Verification is based on the actual rendered DG portrait, not on a
        // changed image URL.  Keeping this blank also guarantees that the first
        // visible action after DG details are ready is exactly the requested
        // top-right dropdown -> Profile flow below.
        String originalPhotoSrc = "";
        Exception lastFailure = null;

        // Always re-encode the DG/cache portrait into a predictable standard
        // RGB JPEG before uploading. Some very small DG embedded JPEGs are
        // accepted by Chrome but silently ignored by SMY after Submit.
        File smyUploadPhoto = preparePhotoForSmyUpload(dgPhoto);

        for (int attempt = 1; attempt <= maxPhotoUploadAttempts; attempt++) {
            try {
                System.out.println("SMY PHOTO UPLOAD ATTEMPT " + attempt
                        + "/" + maxPhotoUploadAttempts);

                // Follow the same visible SMY path as a human:
                // top-right candidate dropdown -> Profile -> Edit Profile.
                // openSmyProfileFromMenu() still has a direct-URL fallback only if
                // the Radix menu is temporarily unavailable/network-erroring.
                openSmyProfileFromMenu(driver);
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

                if (isProfileInitialsPlaceholderVisible(driver)) {
                    System.out.println("SMY CURRENT PHOTO | initials placeholder visible - upload required");
                } else {
                    System.out.println("SMY CURRENT PHOTO | real image already visible - replacing with DG photo");
                }

                WebElement editProfile = wait.until(
                        ExpectedConditions.elementToBeClickable(
                                By.xpath("//button[normalize-space()='Edit Profile']")
                        )
                );
                click(driver, editProfile);

                // Photo is the priority.  Aadhaar/ship/IMO are optional helpers and
                // must never prevent the photo from being selected and submitted.
                WebElement aadhaarInput = null;
                if (aadhaar != null && !aadhaar.isBlank()) {
                    try {
                        aadhaarInput = new WebDriverWait(driver, Duration.ofSeconds(5)).until(
                                ExpectedConditions.presenceOfElementLocated(
                                        By.cssSelector("input[name='aadhaarNumber']")
                                )
                        );
                    } catch (Exception missingAadhaarField) {
                        System.out.println(
                                "SMY AADHAAR FIELD NOT FOUND - SKIPPING IT SO PHOTO CAN STILL UPLOAD"
                        );
                    }
                } else {
                    System.out.println("SMY AADHAAR SKIPPED - COLUMN U HAS NO VALID 12 DIGITS");
                }

                WebElement photoInput = waitForProfilePhotoInput(driver, Duration.ofSeconds(8));
                if (photoInput == null) {
                    // Some SMY builds create the file input only after clicking
                    // the visible upload icon/button.
                    WebElement uploadIcon = firstVisibleEnabled(
                            driver,
                            List.of(
                                    By.cssSelector("[title='Upload image']"),
                                    By.cssSelector("[aria-label*='upload']"),
                                    By.xpath("//*[@title='Upload image']"),
                                    By.xpath("//button[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'upload')]")
                            )
                    );
                    if (uploadIcon != null) {
                        click(driver, uploadIcon);
                        Thread.sleep(600);
                        photoInput = waitForProfilePhotoInput(driver, Duration.ofSeconds(5));
                    }
                }

                if (photoInput == null) {
                    throw new IllegalStateException(
                            "SMY Profile Picture file input not found."
                    );
                }

                selectProfilePhotoFile(driver, photoInput, smyUploadPhoto);

                if (aadhaarInput != null) {
                    setInputValue(aadhaarInput, aadhaar);
                    System.out.println("SMY AADHAAR FILLED | " + aadhaar);
                }

                // User-requested field order: Aadhaar -> IMO -> Last/Current Ship.
                if (imoNumber != null && !imoNumber.isBlank()) {
                    try {
                        WebElement imoInput = new WebDriverWait(driver, Duration.ofSeconds(5)).until(
                                ExpectedConditions.presenceOfElementLocated(
                                        By.cssSelector("input[name='currentShipImo']")
                                )
                        );
                        setInputValue(imoInput, imoNumber);
                        System.out.println("SMY IMO FILLED | " + imoNumber);
                    } catch (Exception missingImoField) {
                        System.out.println("SMY IMO FIELD NOT FOUND - SKIPPED; PHOTO WILL STILL SUBMIT");
                    }
                } else {
                    System.out.println("SMY IMO SKIPPED | DG PDF has no IMO value");
                }

                if (shipName != null && !shipName.isBlank()) {
                    try {
                        WebElement shipInput = new WebDriverWait(driver, Duration.ofSeconds(5)).until(
                                ExpectedConditions.presenceOfElementLocated(
                                        By.cssSelector("input[name='currentShipName']")
                                )
                        );
                        setInputValue(shipInput, shipName);
                        System.out.println("SMY LAST/CURRENT SHIP FILLED | " + shipName);
                    } catch (Exception missingShipField) {
                        System.out.println("SMY SHIP FIELD NOT FOUND - SKIPPED; PHOTO WILL STILL SUBMIT");
                    }
                } else {
                    System.out.println("SMY LAST/CURRENT SHIP SKIPPED | DG PDF has no Sea Going Service");
                }

                // Allow the local preview/upload state to settle before saving.
                Thread.sleep(1800);

                // The Submit button sits at the bottom of the Profile Creation
                // dialog.  On smaller browser windows Selenium can report it as
                // not displayed while it is simply below the dialog's visible
                // scroll area.  Find it in the DOM first, scroll it into view,
                // then click it.  Do not use firstVisibleEnabled() here.
                clickSmyProfileSubmit(driver);

                // A toast is not proof.  Give the backend a moment, then force a
                // new Profile page request and verify the actual persisted photo.
                Thread.sleep(3000);

                String body = safeBodyText(driver).toLowerCase(Locale.ROOT);
                if (body.contains("please complete your profile")
                        && body.contains("required")) {
                    throw new IllegalStateException(
                            "SMY Profile did not save because another required field is missing."
                    );
                }

                verifyPersistedProfilePhoto(
                        driver,
                        smyUploadPhoto,
                        originalPhotoSrc,
                        attempt
                );

                System.out.println(
                        "SMY PROFILE VERIFIED | DG photo really exists after fresh reload"
                );
                System.out.println(
                        "SMY PROFILE SUBMITTED | photo"
                                + (shipName == null || shipName.isBlank() ? "" : " + ship")
                                + (imoNumber == null || imoNumber.isBlank() ? "" : " + IMO")
                                + (aadhaar == null || aadhaar.isBlank()
                                ? " | Aadhaar skipped"
                                : " | Aadhaar updated")
                );
                return;

            } catch (Exception uploadFailure) {
                lastFailure = uploadFailure;
                System.out.println(
                        "SMY PHOTO NOT VERIFIED | attempt " + attempt
                                + "/" + maxPhotoUploadAttempts
                                + " | " + oneLine(uploadFailure.getMessage(), 180)
                );

                if (attempt < maxPhotoUploadAttempts) {
                    // Server-side image processing can briefly lag/fail.  Retry
                    // from a completely fresh Profile page instead of trusting
                    // a stale preview or success message.
                    Thread.sleep(2500L * attempt);
                }
            }
        }

        throw new IllegalStateException(
                "SMY profile photo could not be verified after "
                        + maxPhotoUploadAttempts
                        + " upload attempts. Temporary DG PDF/photo were KEPT for retry/debug.",
                lastFailure
        );
    }


    private static void updateSmyProfileFieldsWithoutPhoto(
            WebDriver driver,
            String aadhaar,
            String shipName,
            String imoNumber
    ) throws Exception {

        openSmyProfileFromMenu(driver);
        WebDriverWait wait = new WebDriverWait(
                driver,
                Duration.ofSeconds(30)
        );

        WebElement editProfile = wait.until(
                ExpectedConditions.elementToBeClickable(
                        By.xpath("//button[normalize-space()='Edit Profile']")
                )
        );
        click(driver, editProfile);

        boolean changed = false;

        if (aadhaar != null && !aadhaar.isBlank()) {
            WebElement aadhaarInput = wait.until(
                    ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector("input[name='aadhaarNumber']")
                    )
            );
            setInputValue(aadhaarInput, aadhaar);
            System.out.println("SMY AADHAAR FILLED | " + aadhaar);
            changed = true;
        } else {
            System.out.println(
                    "SMY AADHAAR SKIPPED - COLUMN U HAS NO VALID 12 DIGITS"
            );
        }

        if (imoNumber != null && !imoNumber.isBlank()) {
            WebElement imoInput = wait.until(
                    ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector("input[name='currentShipImo']")
                    )
            );
            setInputValue(imoInput, imoNumber);
            System.out.println("SMY IMO FILLED | " + imoNumber);
            changed = true;
        } else {
            System.out.println("SMY IMO SKIPPED | DG has no IMO value");
        }

        if (shipName != null && !shipName.isBlank()) {
            WebElement shipInput = wait.until(
                    ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector("input[name='currentShipName']")
                    )
            );
            setInputValue(shipInput, shipName);
            System.out.println(
                    "SMY LAST/CURRENT SHIP FILLED | " + shipName
            );
            changed = true;
        } else {
            System.out.println(
                    "SMY LAST/CURRENT SHIP SKIPPED | DG has no Sea Going Service"
            );
        }

        if (!changed) {
            System.out.println(
                    "SMY PROFILE NO DATA TO CHANGE | PHOTO/SHIP/IMO/AADHAAR ALL UNAVAILABLE"
            );
            return;
        }

        Thread.sleep(900L);
        clickSmyProfileSubmit(driver);
        Thread.sleep(1800L);
        System.out.println(
                "SMY PROFILE SUBMITTED | available fields only | photo not changed"
        );
    }

    private static void verifyPersistedProfilePhoto(
            WebDriver driver,
            File localDgPhoto,
            String originalPhotoSrc,
            int uploadAttempt
    ) throws Exception {

        // Navigate away first, then reopen Profile.  This avoids validating only
        // the optimistic React preview that existed before the server save.
        driver.get(SMY_DASHBOARD);
        Thread.sleep(900);
        driver.get(SMY_PROFILE);
        waitForSmyProfileReadyWithRefresh(driver);

        // IMPORTANT: never accept only a changed URL or the site's success toast.
        // We have seen SMY return "Profile updated successfully" while the profile
        // still shows only the initials placeholder.  The only success condition is
        // that a fresh server-backed Profile page actually renders the DG photo.
        BufferedImage local = ImageIO.read(localDgPhoto);
        if (local == null) {
            throw new IllegalStateException("Could not decode the local DG photo for verification.");
        }

        final int freshReloadChecks = 5;
        Exception lastCheckFailure = null;

        for (int check = 1; check <= freshReloadChecks; check++) {
            try {
                if (check > 1) {
                    Thread.sleep(1800L * check);
                    driver.navigate().refresh();
                    waitForSmyProfileReadyWithRefresh(driver);
                }

                if (isProfileInitialsPlaceholderVisible(driver)) {
                    throw new IllegalStateException(
                            "Profile still shows the initials placeholder (for example DD); photo was not persisted."
                    );
                }

                WebElement actualPhoto = findLargestVisibleProfileImage(driver);
                if (actualPhoto == null) {
                    throw new IllegalStateException(
                            "Profile has no real photo image after fresh reload."
                    );
                }

                Object loaded = ((JavascriptExecutor) driver).executeScript(
                        "return !!arguments[0].complete"
                                + " && arguments[0].naturalWidth > 30"
                                + " && arguments[0].naturalHeight > 30;",
                        actualPhoto
                );
                if (!Boolean.TRUE.equals(loaded)) {
                    throw new IllegalStateException("Profile image element exists but image is not loaded yet.");
                }

                String actualSrc = cleanText(actualPhoto.getAttribute("src"));
                if (actualSrc.isBlank()
                        || actualSrc.toLowerCase(Locale.ROOT).contains("placeholder")) {
                    throw new IllegalStateException("Fresh Profile page still has no real photo URL.");
                }

                File screenshot = actualPhoto.getScreenshotAs(OutputType.FILE);
                BufferedImage rendered = ImageIO.read(screenshot);
                if (rendered == null) {
                    throw new IllegalStateException("Could not decode the rendered SMY profile photo.");
                }

                double similarity = imageSimilarityForProfile(local, rendered);
                System.out.printf(
                        Locale.ROOT,
                        "PHOTO VERIFY CHECK %d/%d: visual similarity %.1f%%%n",
                        check,
                        freshReloadChecks,
                        similarity * 100.0
                );

                // SMY can crop/compress the portrait when rendering the 90x90
                // avatar. A slightly tolerant threshold still verifies the same
                // DG portrait while avoiding false failures after a real upload.
                if (similarity >= 0.58) {
                    if (!originalPhotoSrc.isBlank() && !actualSrc.equals(originalPhotoSrc)) {
                        System.out.println("PHOTO VERIFY: server URL changed AND rendered DG photo matches.");
                    } else {
                        System.out.println("PHOTO VERIFY: rendered DG photo matches on fresh server reload.");
                    }
                    return;
                }

                throw new IllegalStateException(
                        "Displayed image does not yet match the DG photo."
                );

            } catch (Exception checkFailure) {
                lastCheckFailure = checkFailure;
                System.out.println(
                        "PHOTO VERIFY WAIT | upload attempt " + uploadAttempt
                                + " | fresh check " + check + "/" + freshReloadChecks
                                + " | " + oneLine(checkFailure.getMessage(), 150)
                );
            }
        }

        throw new IllegalStateException(
                "SMY said profile updated, but the real DG photo was not visible after fresh reload checks."
                        + " A second upload attempt is required.",
                lastCheckFailure
        );
    }

    /**
     * True when the fresh SMY Profile page visibly contains a real candidate
     * photograph.  SMY has used both normal <img> avatars and CSS background
     * images in different UI builds, so check both.  The 90x90 initials tile
     * (for example "DD") always wins and is treated as "photo missing".
     */
    private static boolean hasRealSmyProfilePhoto(WebDriver driver) {
        try {
            if (isProfileInitialsPlaceholderVisible(driver)) {
                return false;
            }

            if (findLargestVisibleProfileImage(driver) != null) {
                return true;
            }

            Object cssBackgroundPhoto = ((JavascriptExecutor) driver).executeScript(
                    "const btn=[...document.querySelectorAll('button')].find(b=>"
                            + "(b.textContent||'').trim()==='Edit Profile');"
                            + "if(!btn) return false;"
                            + "let node=btn;"
                            + "for(let level=0; level<8 && node; level++, node=node.parentElement){"
                            + " const all=[node,...node.querySelectorAll('*')];"
                            + " for(const el of all){"
                            + "  const r=el.getBoundingClientRect();"
                            + "  if(r.width<55||r.height<55||r.width>320||r.height>320) continue;"
                            + "  const st=getComputedStyle(el);"
                            + "  if(st.display==='none'||st.visibility==='hidden'||Number(st.opacity)===0) continue;"
                            + "  const bg=st.backgroundImage||'';"
                            + "  if(bg && bg!=='none' && /url\\(/i.test(bg)"
                            + "     && !/logo|favicon|placeholder/i.test(bg)) return true;"
                            + " }"
                            + "}"
                            + "return false;"
            );

            return Boolean.TRUE.equals(cssBackgroundPhoto);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isProfileInitialsPlaceholderVisible(WebDriver driver) {
        List<By> locators = List.of(
                // Exact 90x90 Profile-card initials placeholder supplied by the
                // user, e.g. <div ...>DD</div>.  Do NOT use the smaller header
                // avatar here because it can update/cache independently.
                By.xpath("//div[contains(@class,'w-[90px]') and contains(@class,'h-[90px]') and not(.//img)]")
        );

        for (By locator : locators) {
            for (WebElement element : driver.findElements(locator)) {
                try {
                    String text = cleanText(element.getText());
                    if (element.isDisplayed()
                            && !text.isBlank()
                            && text.length() <= 3
                            && text.matches("[A-Za-z]{1,3}")) {
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    private static String readLargestLoadedProfilePhotoSrc(WebDriver driver) {
        try {
            driver.get(SMY_PROFILE);
            waitForSmyProfileReadyWithRefresh(driver);
            WebElement image = findLargestVisibleProfileImage(driver);
            if (image == null) {
                return "";
            }
            Object loaded = ((JavascriptExecutor) driver).executeScript(
                    "return !!arguments[0].complete"
                            + " && arguments[0].naturalWidth > 30"
                            + " && arguments[0].naturalHeight > 30;",
                    image
            );
            return Boolean.TRUE.equals(loaded)
                    ? cleanText(image.getAttribute("src"))
                    : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static WebElement findLargestVisibleProfileImage(WebDriver driver) {
        List<WebElement> candidates = new ArrayList<>();

        // First look inside the same Profile card that contains Edit Profile.
        // This avoids accidentally treating the small top-right header avatar
        // or the SMY logo as the candidate's saved profile photograph.
        candidates.addAll(driver.findElements(
                By.xpath("//button[normalize-space()='Edit Profile']/ancestor::div[.//img][1]//img")
        ));
        candidates.addAll(driver.findElements(By.cssSelector("img[alt='Profile']")));
        candidates.addAll(driver.findElements(By.cssSelector("img[alt*='profile']")));
        candidates.addAll(driver.findElements(By.xpath("//img[contains(@src,'/uploads/') or contains(@src,'profile')]")));

        WebElement best = null;
        double bestArea = 0.0;
        Set<WebElement> seen = new HashSet<>();
        for (WebElement image : candidates) {
            if (!seen.add(image)) {
                continue;
            }
            try {
                if (!image.isDisplayed()) {
                    continue;
                }

                int width = image.getRect().getWidth();
                int height = image.getRect().getHeight();
                if (width < 50 || height < 50) {
                    // Header avatars are normally around 32-40 px.
                    continue;
                }

                String src = cleanText(image.getAttribute("src")).toLowerCase(Locale.ROOT);
                if (src.contains("logo") || src.contains("favicon") || src.contains("placeholder")) {
                    continue;
                }

                Object loaded = ((JavascriptExecutor) driver).executeScript(
                        "return !!arguments[0].complete && arguments[0].naturalWidth > 30 && arguments[0].naturalHeight > 30;",
                        image
                );
                if (!Boolean.TRUE.equals(loaded)) {
                    continue;
                }

                double area = width * (double) height;
                if (area > bestArea) {
                    best = image;
                    bestArea = area;
                }
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private static double imageSimilarityForProfile(
            BufferedImage source,
            BufferedImage rendered
    ) {
        final int size = 32;
        double[] a = normalizedGraySignature(
                source,
                size,
                rendered.getWidth(),
                rendered.getHeight()
        );
        double[] b = normalizedGraySignature(
                rendered,
                size,
                rendered.getWidth(),
                rendered.getHeight()
        );

        double difference = 0.0;
        for (int i = 0; i < a.length; i++) {
            difference += Math.abs(a[i] - b[i]);
        }
        double pixelSimilarity = Math.max(
                0.0,
                1.0 - (difference / a.length)
        );

        boolean[] sourceHash = differenceHash(
                source,
                rendered.getWidth(),
                rendered.getHeight()
        );
        boolean[] renderedHash = differenceHash(
                rendered,
                rendered.getWidth(),
                rendered.getHeight()
        );

        int equalBits = 0;
        for (int i = 0; i < sourceHash.length; i++) {
            if (sourceHash[i] == renderedHash[i]) {
                equalBits++;
            }
        }
        double edgeSimilarity = equalBits / (double) sourceHash.length;

        System.out.printf(
                Locale.ROOT,
                "PHOTO VERIFY DETAIL: pixels %.1f%% | edges %.1f%%%n",
                pixelSimilarity * 100.0,
                edgeSimilarity * 100.0
        );

        // Pixel structure + edge structure together are much harder for a
        // different portrait to pass accidentally than a simple brightness check.
        return (pixelSimilarity * 0.45) + (edgeSimilarity * 0.55);
    }

    private static boolean[] differenceHash(
            BufferedImage image,
            int targetWidth,
            int targetHeight
    ) {
        final int width = 9;
        final int height = 8;
        BufferedImage scaled = resizeCoverForComparison(
                image,
                width,
                height,
                targetWidth,
                targetHeight
        );

        boolean[] hash = new boolean[64];
        int bit = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width - 1; x++) {
                hash[bit++] = grayValue(scaled.getRGB(x, y))
                        < grayValue(scaled.getRGB(x + 1, y));
            }
        }
        return hash;
    }

    private static double grayValue(int rgb) {
        int r = (rgb >>> 16) & 0xff;
        int g = (rgb >>> 8) & 0xff;
        int b = rgb & 0xff;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    private static BufferedImage resizeCoverForComparison(
            BufferedImage image,
            int outputWidth,
            int outputHeight,
            int targetWidth,
            int targetHeight
    ) {
        double targetAspect = targetHeight <= 0
                ? 1.0
                : targetWidth / (double) targetHeight;
        double sourceAspect = image.getWidth() / (double) image.getHeight();

        int cropX = 0;
        int cropY = 0;
        int cropWidth = image.getWidth();
        int cropHeight = image.getHeight();

        if (sourceAspect > targetAspect) {
            cropWidth = Math.max(
                    1,
                    (int) Math.round(image.getHeight() * targetAspect)
            );
            cropX = Math.max(0, (image.getWidth() - cropWidth) / 2);
        } else if (sourceAspect < targetAspect) {
            cropHeight = Math.max(
                    1,
                    (int) Math.round(image.getWidth() / targetAspect)
            );
            cropY = Math.max(0, (image.getHeight() - cropHeight) / 2);
        }

        BufferedImage scaled = new BufferedImage(
                outputWidth,
                outputHeight,
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            graphics.drawImage(
                    image,
                    0,
                    0,
                    outputWidth,
                    outputHeight,
                    cropX,
                    cropY,
                    cropX + cropWidth,
                    cropY + cropHeight,
                    null
            );
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static double[] normalizedGraySignature(
            BufferedImage image,
            int size,
            int targetWidth,
            int targetHeight
    ) {
        double targetAspect = targetHeight <= 0
                ? 1.0
                : targetWidth / (double) targetHeight;
        double sourceAspect = image.getWidth() / (double) image.getHeight();

        int cropX = 0;
        int cropY = 0;
        int cropWidth = image.getWidth();
        int cropHeight = image.getHeight();

        // Mirror CSS object-fit: cover; object-position: center.
        if (sourceAspect > targetAspect) {
            cropWidth = Math.max(1, (int) Math.round(image.getHeight() * targetAspect));
            cropX = Math.max(0, (image.getWidth() - cropWidth) / 2);
        } else if (sourceAspect < targetAspect) {
            cropHeight = Math.max(1, (int) Math.round(image.getWidth() / targetAspect));
            cropY = Math.max(0, (image.getHeight() - cropHeight) / 2);
        }

        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            graphics.drawImage(
                    image,
                    0,
                    0,
                    size,
                    size,
                    cropX,
                    cropY,
                    cropX + cropWidth,
                    cropY + cropHeight,
                    null
            );
        } finally {
            graphics.dispose();
        }

        double[] values = new double[size * size];
        double mean = 0.0;
        int index = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int rgb = scaled.getRGB(x, y);
                int r = (rgb >>> 16) & 0xff;
                int g = (rgb >>> 8) & 0xff;
                int bl = rgb & 0xff;
                double gray = (0.299 * r + 0.587 * g + 0.114 * bl) / 255.0;
                values[index++] = gray;
                mean += gray;
            }
        }
        mean /= values.length;

        // Normalize overall brightness so minor browser/server compression and
        // exposure differences do not cause false failures.
        for (int i = 0; i < values.length; i++) {
            values[i] = Math.max(0.0, Math.min(1.0, values[i] - mean + 0.5));
        }
        return values;
    }

    private static void deleteWorkingDirectoryAfterVerifiedUpload(Path workingDir) {
        if (workingDir == null || !Files.exists(workingDir)) {
            return;
        }

        try (var paths = Files.walk(workingDir)) {
            List<Path> all = paths
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : all) {
                Files.deleteIfExists(path);
            }
            System.out.println(
                    "DG TEMP CLEANED | verified SMY photo upload succeeded"
            );
        } catch (Exception cleanupError) {
            // Cleanup failure must never turn a successfully verified profile
            // into a failed candidate.  It can be removed on the next run.
            System.out.println(
                    "DG TEMP CLEANUP WARNING | "
                            + oneLine(cleanupError.getMessage(), 160)
            );
        }
    }

    private static void clickSmyProfileSubmit(WebDriver driver) throws Exception {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        JavascriptExecutor js = (JavascriptExecutor) driver;

        // Wait for the edit form itself WITHOUT depending on Aadhaar.
        // Aadhaar is optional and some SMY form versions do not render that
        // input at all; the old Aadhaar-only wait stopped the photo flow before
        // Submit even though the DG image had already been selected.
        wait.until(d -> {
            try {
                boolean hasSubmit = !d.findElements(
                        By.xpath("//button[normalize-space(.)='Submit']")
                ).isEmpty();
                boolean hasPhotoInput = !d.findElements(
                        By.cssSelector("input[type='file']")
                ).isEmpty();
                return hasSubmit || hasPhotoInput;
            } catch (Exception ignored) {
                return false;
            }
        });

        WebElement submit = null;
        List<By> submitLocators = List.of(
                By.xpath("//button[@type='button' and normalize-space(.)='Submit']"),
                By.xpath("//button[normalize-space(.)='Submit']"),
                By.cssSelector("button[type='button']")
        );

        for (By locator : submitLocators) {
            for (WebElement candidate : driver.findElements(locator)) {
                try {
                    String text = cleanText(candidate.getText());
                    if (text.isBlank()) {
                        text = cleanText((String) js.executeScript(
                                "return arguments[0].innerText || arguments[0].textContent || '';",
                                candidate
                        ));
                    }
                    if ("submit".equalsIgnoreCase(text)) {
                        submit = candidate;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (submit != null) {
                break;
            }
        }

        // Last fallback: locate by DOM text through JavaScript. This also works
        // when React has rerendered the button between Selenium searches.
        if (submit == null) {
            Object found = js.executeScript(
                    "return Array.from(document.querySelectorAll('button')).find(b => "
                            + "((b.innerText||b.textContent||'').trim().toLowerCase()==='submit')) || null;"
            );
            if (found instanceof WebElement) {
                submit = (WebElement) found;
            }
        }

        if (submit == null) {
            String buttons = cleanText((String) js.executeScript(
                    "return Array.from(document.querySelectorAll('button'))"
                            + ".map(b=>(b.innerText||b.textContent||'').trim())"
                            + ".filter(Boolean).join(' | ');"
            ));
            throw new IllegalStateException(
                    "SMY Profile Submit button not found in DOM. Buttons present: "
                            + oneLine(buttons, 220)
            );
        }

        try {
            js.executeScript(
                    "arguments[0].scrollIntoView({block:'center',inline:'nearest'});",
                    submit
            );
            Thread.sleep(500);
        } catch (Exception ignored) {
        }

        // If the form is inside a scrollable modal, scroll the closest scrolling
        // ancestor too. This is what fixes the 'button exists but not visible'
        // case on smaller screens.
        try {
            js.executeScript(
                    "let e=arguments[0], p=e.parentElement;"
                            + "while(p){let s=getComputedStyle(p);"
                            + "if((s.overflowY==='auto'||s.overflowY==='scroll') && p.scrollHeight>p.clientHeight){"
                            + "p.scrollTop=Math.max(0,e.offsetTop-p.clientHeight/2);break;}p=p.parentElement;}",
                    submit
            );
            Thread.sleep(350);
        } catch (Exception ignored) {
        }

        try {
            wait.until(ExpectedConditions.elementToBeClickable(submit));
            click(driver, submit);
        } catch (Exception normalClickFailure) {
            // The button is a type=button React handler. A direct JS click is a
            // safe final fallback after the fields have already been filled.
            try {
                js.executeScript("arguments[0].click();", submit);
            } catch (Exception jsClickFailure) {
                throw new IllegalStateException(
                        "SMY Profile Submit button was found but could not be clicked.",
                        jsClickFailure
                );
            }
        }

        System.out.println("SMY PROFILE SUBMIT CLICKED");

        // Give React/backend a chance to process the save. The real success
        // condition remains verifyPersistedProfilePhoto() after a fresh reload.
        try {
            new WebDriverWait(driver, Duration.ofSeconds(8)).until(d -> {
                String body = safeBodyText(d).toLowerCase(Locale.ROOT);
                return body.contains("profile updated successfully")
                        || d.findElements(By.cssSelector("input[name='aadhaarNumber']")).isEmpty();
            });
        } catch (Exception ignored) {
            // Do not fail on toast timing; fresh server-backed photo verification
            // below is authoritative.
        }
    }

    private static void selectProfilePhotoFile(
            WebDriver driver,
            WebElement photoInput,
            File dgPhoto
    ) throws Exception {
        if (dgPhoto == null || !dgPhoto.isFile() || dgPhoto.length() == 0) {
            throw new IllegalStateException("DG photo file is missing before SMY upload.");
        }

        String absolutePath = dgPhoto.getCanonicalFile().getAbsolutePath();
        Exception firstFailure = null;

        try {
            photoInput.sendKeys(absolutePath);
        } catch (Exception hiddenInput) {
            firstFailure = hiddenInput;
            try {
                ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].style.display='block';"
                                + "arguments[0].style.visibility='visible';"
                                + "arguments[0].style.opacity='1';"
                                + "arguments[0].removeAttribute('hidden');"
                                + "arguments[0].removeAttribute('disabled');",
                        photoInput
                );
                photoInput.sendKeys(absolutePath);
            } catch (Exception secondFailure) {
                if (firstFailure != null) {
                    secondFailure.addSuppressed(firstFailure);
                }
                throw secondFailure;
            }
        }

        // ChromeDriver normally fires change automatically for file inputs, but
        // some React builds of SMY listen through a wrapper. Re-fire both native
        // events so React receives the selected file reliably.
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].dispatchEvent(new Event('input',{bubbles:true}));"
                            + "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));",
                    photoInput
            );
        } catch (Exception ignored) {
        }

        Boolean accepted = new WebDriverWait(driver, Duration.ofSeconds(8)).until(d -> {
            try {
                Object count = ((JavascriptExecutor) d).executeScript(
                        "return arguments[0].files ? arguments[0].files.length : 0;",
                        photoInput
                );
                String value = cleanText(photoInput.getAttribute("value"));
                return (count instanceof Number && ((Number) count).intValue() > 0)
                        || !value.isBlank();
            } catch (Exception ignored) {
                return false;
            }
        });

        if (!Boolean.TRUE.equals(accepted)) {
            throw new IllegalStateException("SMY browser did not accept the DG photo file.");
        }

        System.out.println("SMY PHOTO FILE SELECTED | " + dgPhoto.getName()
                + " | " + dgPhoto.length() + " bytes");

        // Some SMY releases show an image-crop dialog after choosing a file.
        // If it appears, confirm it automatically. If there is no crop dialog,
        // this method simply returns without changing the main Profile form.
        confirmPhotoCropDialogIfPresent(driver);

        // Let React settle the selected file before Submit.
        waitForSmyPhotoPreviewOrStableFile(driver, photoInput);
        Thread.sleep(900L);
    }

    private static void waitForSmyPhotoPreviewOrStableFile(
            WebDriver driver, WebElement photoInput
    ) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(12)).until(d -> {
                try {
                    Object count = ((JavascriptExecutor) d).executeScript(
                            "return arguments[0].files ? arguments[0].files.length : 0;",
                            photoInput
                    );
                    if (!(count instanceof Number) || ((Number) count).intValue() < 1) {
                        return false;
                    }

                    // SMY versions differ: some show blob/data previews and some
                    // keep only the selected File in React state. Either is OK.
                    Object preview = ((JavascriptExecutor) d).executeScript(
                            "return Array.from(document.querySelectorAll('img'))"
                                    + ".some(i => (i.src||'').startsWith('blob:')"
                                    + " || (i.src||'').startsWith('data:image'));"
                    );
                    return Boolean.TRUE.equals(preview)
                            || ((Number) count).intValue() == 1;
                } catch (Exception ignored) {
                    return false;
                }
            });
            System.out.println("SMY PHOTO INPUT STABLE | ready for Submit");
        } catch (Exception waitError) {
            System.out.println("SMY PHOTO PREVIEW WAIT ENDED | continuing with selected file");
        }
    }

    private static void confirmPhotoCropDialogIfPresent(WebDriver driver) {
        try {
            List<WebElement> dialogs = driver.findElements(By.cssSelector("[role='dialog']"));
            for (WebElement dialog : dialogs) {
                try {
                    if (!dialog.isDisplayed()) {
                        continue;
                    }

                    String dialogText = cleanText(dialog.getText()).toLowerCase(Locale.ROOT);
                    boolean looksLikePhotoDialog = dialogText.contains("photo")
                            || dialogText.contains("image")
                            || !dialog.findElements(By.cssSelector("canvas")).isEmpty()
                            || !dialog.findElements(By.cssSelector("img")).isEmpty();
                    if (!looksLikePhotoDialog) {
                        continue;
                    }

                    List<String> buttonLabels = List.of(
                            "Use Photo", "Set Photo", "Apply", "Crop", "Done", "Save", "Confirm"
                    );

                    for (String label : buttonLabels) {
                        List<WebElement> buttons = dialog.findElements(
                                By.xpath(".//button[normalize-space()=\"" + label + "\"]")
                        );
                        for (WebElement button : buttons) {
                            if (button.isDisplayed() && button.isEnabled()) {
                                click(driver, button);
                                System.out.println("SMY PHOTO CROP CONFIRMED | " + label);
                                Thread.sleep(900L);
                                return;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static WebElement waitForProfilePhotoInput(
            WebDriver driver,
            Duration timeout
    ) {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            WebElement input = findProfilePhotoInput(driver);
            if (input != null) {
                return input;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (System.nanoTime() < deadline);
        return null;
    }

    private static WebElement findProfilePhotoInput(WebDriver driver) {
        // Prefer inputs that are clearly image/profile related.  The old generic
        // //input[@type='file'][1] fallback could select another document upload
        // when SMY changed the Edit Profile form order, so the DG portrait was
        // sometimes sent to the wrong file control.
        List<By> locators = List.of(
                By.xpath("//*[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'profile picture')]/following::input[@type='file'][1]"),
                By.cssSelector("input[type='file'][name*='profile']"),
                By.cssSelector("input[type='file'][name*='photo']"),
                By.cssSelector("input[type='file'][accept*='image']"),
                By.xpath("//*[@title='Upload image']/ancestor::*[self::div or self::label][1]//input[@type='file']")
        );

        for (By locator : locators) {
            List<WebElement> elements = driver.findElements(locator);
            for (WebElement element : elements) {
                try {
                    if (element.isEnabled()) {
                        return element;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!elements.isEmpty()) {
                // Hidden file inputs are normal; Selenium can still sendKeys to
                // them after the visibility fallback in selectProfilePhotoFile().
                return elements.get(0);
            }
        }

        // Final fallback only when the form contains exactly one file input.
        List<WebElement> allFileInputs = driver.findElements(By.cssSelector("input[type='file']"));
        return allFileInputs.size() == 1 ? allFileInputs.get(0) : null;
    }

    private static void setInputValue(WebElement input, String value) {
        String wanted = value == null ? "" : value;
        try {
            input.click();
            input.sendKeys(Keys.chord(Keys.CONTROL, "a"));
            input.sendKeys(Keys.BACK_SPACE);
            input.sendKeys(wanted);

            String after = cleanText(input.getAttribute("value"));
            if (after.equals(wanted)
                    || after.replaceAll("\\s", "").equals(wanted.replaceAll("\\s", ""))) {
                return;
            }
        } catch (Exception ignored) {
        }

        // React-controlled-input fallback.  Native setter + input/change events
        // makes SMY's form state receive the value even if normal sendKeys is
        // swallowed by a rerender.
        try {
            WebDriver driver = ((org.openqa.selenium.WrapsDriver) input).getWrappedDriver();
            ((JavascriptExecutor) driver).executeScript(
                    "const el=arguments[0], val=arguments[1];"
                            + "const p=Object.getPrototypeOf(el);"
                            + "const d=Object.getOwnPropertyDescriptor(p,'value');"
                            + "if(d&&d.set){d.set.call(el,val);}else{el.value=val;}"
                            + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                            + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                            + "el.blur();",
                    input, wanted
            );
        } catch (Exception jsFailure) {
            throw new IllegalStateException(
                    "Could not fill SMY profile field with value: " + wanted,
                    jsFailure
            );
        }
    }

    private static void openNewTab(WebDriver driver) {
        ((JavascriptExecutor) driver).executeScript(
                "window.open('about:blank','_blank');"
        );
    }

    private static String firstHandleOtherThan(WebDriver driver, String excluded)
            throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            for (String handle : driver.getWindowHandles()) {
                if (!handle.equals(excluded)) {
                    return handle;
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    private static void closeEveryWindowExcept(WebDriver driver, String keep) {
        for (String handle : new ArrayList<>(driver.getWindowHandles())) {
            if (handle.equals(keep)) {
                continue;
            }
            try {
                driver.switchTo().window(handle);
                driver.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static WebElement firstVisibleEnabled(
            WebDriver driver,
            List<By> locators
    ) {
        for (By locator : locators) {
            for (WebElement element : driver.findElements(locator)) {
                try {
                    if (element.isDisplayed() && element.isEnabled()) {
                        return element;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static void click(WebDriver driver, WebElement element) {
        try {
            element.click();
        } catch (Exception normalClickFailed) {
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].click();",
                    element
            );
        }
    }

    private static String safeBodyText(WebDriver driver) {
        try {
            return cleanText(driver.findElement(By.tagName("body")).getText());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String cleanText(String value) {
        return value == null
                ? ""
                : value.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String oneLine(String value, int max) {
        String text = cleanText(value);
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static final class DgProfileData {
        private final File photoFile;
        private final String shipName;
        private final String imoNumber;

        private DgProfileData(File photoFile, String shipName, String imoNumber) {
            this.photoFile = photoFile;
            this.shipName = shipName;
            this.imoNumber = imoNumber;
        }
    }

    private static final class PhotoCandidate {
        private final BufferedImage image;
        private final double score;

        private PhotoCandidate(BufferedImage image, double score) {
            this.image = image;
            this.score = score;
        }
    }

    private static final class ShipMatch {
        private final int start;
        private final int end;
        private final String name;

        private ShipMatch(int start, int end, String name) {
            this.start = start;
            this.end = end;
            this.name = name;
        }
    }

    private static final class SeaService {
        private final String shipName;
        private final String imoNumber;
        private final LocalDate fromDate;
        private final LocalDate toDate;

        private SeaService(
                String shipName,
                String imoNumber,
                LocalDate fromDate,
                LocalDate toDate
        ) {
            this.shipName = shipName;
            this.imoNumber = imoNumber;
            this.fromDate = fromDate;
            this.toDate = toDate;
        }
    }
}
