package org.example;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.TimeoutException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

public class SagarCertificateBot {

    public record ProfileDetails(String candidateName, String mobileNumber) {}

    @FunctionalInterface
    public interface ProfileListener {
        void onProfileRead(ProfileDetails profile) throws Exception;
    }

    public interface DownloadListener {
        void onProfileRead(ProfileDetails profile) throws Exception;
        Set<String> alreadyUploadedCourses() throws Exception;
        void onCertificateDownloaded(String courseName, File certificate) throws Exception;
        void onCsvDownloaded(File csv) throws Exception;
    }

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

    // SMY has used both labels on the Progress table:
    //   old: Download Certificate
    //   new: View certificate
    // Match either one so the bot survives the UI change.
    private static final By DOWNLOAD_BUTTON =
            By.xpath("//button["
                    + "contains(translate(normalize-space(.),"
                    + "'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'download certificate')"
                    + " or contains(translate(normalize-space(.),"
                    + "'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'view certificate')"
                    + "]");

    // A visible View certificate / Download Certificate button already means the
    // attempt is certificate-eligible.  The current SMY table no longer needs an
    // ASSESSMENT badge in the same row, so do not require that badge here.
    private static final By ASSESSMENT_DOWNLOAD_BUTTON = DOWNLOAD_BUTTON;

    // User-provided filter: the Test Type combobox can initially show All.
    private static final By COMBOBOX = By.cssSelector("button[role='combobox']");

    private static final By ASSESSMENT_BUTTON =
            By.xpath("//*[self::button or self::a or @role='button']"
                    + "[contains(translate(normalize-space(.),"
                    + "'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),"
                    + "'ASSESSMENT')][not(@disabled)]");

    private static final By NEXT_PAGE_BUTTON =
            By.xpath("//button[.//*[name()='path' and @d='m9 18 6-6-6-6']]");

    private static final By UNDERSTAND_BUTTON =
            By.xpath("//button[normalize-space()='I understand']");

    private final Path dir;
    private ProfileDetails profileDetails;
    private File csvReport;
    private boolean temporaryDownloadFailure;

    public SagarCertificateBot() throws Exception {

        dir = Paths.get(
                Config.require("download.folder")
        ).toAbsolutePath();

        Files.createDirectories(dir);
    }

    public List<File> download(Candidate candidate)
            throws Exception {

        return download(candidate, profile -> { });
    }

    public List<File> download(Candidate candidate, ProfileListener profileListener)
            throws Exception {

        return download(candidate, new DownloadListener() {
            public void onProfileRead(ProfileDetails profile) throws Exception { profileListener.onProfileRead(profile); }
            public Set<String> alreadyUploadedCourses() { return Set.of(); }
            public void onCertificateDownloaded(String courseName, File certificate) { }
            public void onCsvDownloaded(File csv) { }
        });
    }

    public List<File> download(Candidate candidate, DownloadListener downloadListener)
            throws Exception {

        clearDownloadFolder();

        ChromeOptions options =
                new ChromeOptions();

        // Run Chrome visibly (headless disabled).
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--no-sandbox");

        Map<String, Object> preferences = new HashMap<>();
        preferences.put("download.default_directory", dir.toString());
        preferences.put("savefile.default_directory", dir.toString());
        preferences.put("download.prompt_for_download", false);
        preferences.put("download.directory_upgrade", true);
        preferences.put("plugins.always_open_pdf_externally", true);
        preferences.put("profile.default_content_setting_values.automatic_downloads", 1);
        preferences.put("profile.default_content_settings.popups", 0);
        preferences.put("safebrowsing.enabled", true);

        String printPreviewState = "{\"recentDestinations\":[{\"id\":\"Save as PDF\",\"origin\":\"local\",\"account\":\"\"}],"
                + "\"selectedDestinationId\":\"Save as PDF\","
                + "\"version\":2}";
        preferences.put("printing.print_preview_sticky_settings.appState", printPreviewState);

        options.setExperimentalOption("prefs", preferences);

        ChromeDriver driver =
                new ChromeDriver(options);

        Map<String, Object> downloadBehaviour = new LinkedHashMap<>();
        downloadBehaviour.put("behavior", "allow");
        downloadBehaviour.put("downloadPath", dir.toString());
        downloadBehaviour.put("eventsEnabled", true);
        driver.executeCdpCommand("Browser.setDownloadBehavior", downloadBehaviour);

        WebDriverWait wait =
                new WebDriverWait(
                        driver,
                        Duration.ofSeconds(60)
                );

        List<File> downloaded =
                new ArrayList<>();

        try {

            openProgress(
                    driver,
                    wait,
                    candidate
            );

            // Sheet profile cache:
            // C = candidate name
            // D = candidate WhatsApp/mobile
            // If either value is missing, read the SMY Profile in hidden Chrome.
            // Main writes only the missing values back to C/D. Existing values are
            // never overwritten.
            String sheetName = candidate.candidateName() == null
                    ? ""
                    : candidate.candidateName().trim();
            String sheetMobile = normalizeMobile(candidate.mobileNumber());

            if (sheetName.isBlank() || sheetMobile.isBlank()) {
                System.out.println(
                        "COLUMN C/D PROFILE DETAILS MISSING - READING NAME + MOBILE FROM PROFILE"
                );

                ProfileDetails liveProfile = readProfileDetails(driver, wait, sheetMobile);

                String resolvedName = sheetName.isBlank()
                        ? liveProfile.candidateName()
                        : sheetName;
                String resolvedMobile = sheetMobile.isBlank()
                        ? normalizeMobile(liveProfile.mobileNumber())
                        : sheetMobile;

                profileDetails = new ProfileDetails(resolvedName, resolvedMobile);

                // Return to Progress after the Profile lookup.
                driver.get(Config.require("sagar.progress.url"));
                waitForProgress(driver, wait);
            } else {
                profileDetails = new ProfileDetails(sheetName, sheetMobile);
                System.out.println(
                        "PROFILE DETAILS ALREADY IN SHEET - COLUMN C: "
                                + sheetName
                                + " | COLUMN D: "
                                + sheetMobile
                );
            }

            downloadListener.onProfileRead(profileDetails);

            Set<String> downloadedCourses =
                    new HashSet<>(downloadListener.alreadyUploadedCourses());
            if (!downloadedCourses.isEmpty())
                System.out.println("RESUME: already downloaded this run: " + String.join(", ", downloadedCourses));
            int round = 0;

            while (round++ < 20) {

                // Manual uploads may have happened since the previous round.
                // Refresh the in-memory course set so we do not download them again.
                downloadedCourses.addAll(downloadListener.alreadyUploadedCourses());
                if (downloadedCourses.size() == REQUIRED_COURSES.size()) {
                    System.out.println("All 10 Wellness certificates are ready locally.");
                    break;
                }

                driver.get(
                        Config.require("sagar.progress.url")
                );

                waitForProgress(
                        driver,
                        wait
                );

                scanAllPagesForCertificates(
                        driver,
                        wait,
                        candidate,
                        downloaded,
                        downloadedCourses,
                        downloadListener
                );

                if (downloadedCourses.size() == REQUIRED_COURSES.size()) {
                    System.out.println("All 10 Wellness course certificates downloaded. CSV/Drive step skipped.");
                    break;
                }

                // If a visible Download Certificate button failed to return a PDF,
                // retry Progress first. Do not jump into another Assessment because
                // of a temporary certificate-download/server problem.
                if (temporaryDownloadFailure) {
                    System.out.println("TEMPORARY CERTIFICATE FAILURE - RETRYING PROGRESS BEFORE ANY ASSESSMENT.");
                    sleep(5000);
                    continue;
                }

                /*
                 * Only after every progress page has been checked
                 * for certificates, look for an Assessment.
                 */
                driver.get(
                        Config.require("sagar.progress.url")
                );
                waitForProgress(driver, wait);

                if (!openFirstAssessmentOnAnyPage(driver, wait)) {

                    System.out.println(
                            "No pending Assessment found."
                    );

                    break;
                }

                System.out.println();
                System.out.println(
                        "Certificate not ready for next course."
                );
                System.out.println(
                        "Opening Assessment..."
                );

                clickUnderstandIfPresent(
                        driver
                );

                waitForAssessmentResult(
                        driver
                );

                System.out.println(
                        "Assessment finished. "
                                + "Checking Progress again..."
                );

                driver.get(
                        Config.require(
                                "sagar.progress.url"
                        )
                );

                waitForProgress(
                        driver,
                        wait
                );

                Thread.sleep(2500);

                driver.navigate().refresh();

                waitForProgress(
                        driver,
                        wait
                );
            }

            return downloaded;

        } finally {

            driver.quit();
        }
    }

    /**
     * Resolve candidate profile details from SMY without showing Chrome.
     * Used when email is already SENT and Column C and/or D is blank, so the
     * missing name/mobile can be saved without resending the certificate email.
     */
    public ProfileDetails readProfileOnly(Candidate candidate) throws Exception {
        ChromeOptions options = new ChromeOptions();
        // Run Chrome visibly (headless disabled).
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--no-sandbox");

        ChromeDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));
        try {
            openProgress(driver, wait, candidate);
            ProfileDetails profile = readProfileDetails(driver, wait, candidate.mobileNumber());
            profileDetails = profile;
            return profile;
        } finally {
            try { driver.quit(); } catch (Exception ignored) { }
        }
    }

    public ProfileDetails getProfileDetails() {
        if (profileDetails == null) {
            profileDetails = new ProfileDetails("", "");
        }
        return profileDetails;
    }

    public File getCsvReport() {
        if (csvReport == null || !csvReport.isFile()) {
            throw new IllegalStateException("Export All Filtered CSV was not downloaded.");
        }
        return csvReport;
    }

    private String normalizeMobile(String value) {
        if (value == null) return "";
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 10) return digits;
        if (digits.length() == 12 && digits.startsWith("91")) return digits;
        return "";
    }

    private File exportAllFilteredCsv(WebDriver driver, WebDriverWait wait) throws Exception {
        driver.get(Config.require("sagar.progress.url"));
        waitForProgress(driver, wait);
        long started = System.currentTimeMillis();
        WebElement exportButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//button[contains(normalize-space(.),'Export CSV')]")));
        scrollTo(driver, exportButton);
        exportButton.click();
        WebElement allFiltered = wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//*[@role='menuitem'][contains(normalize-space(.),'Export All Filtered')]")));
        allFiltered.click();
        File csv = waitForCsv(started);
        if (csv == null) throw new IllegalStateException("Export All Filtered CSV download timed out.");
        System.out.println("CSV DOWNLOADED (original filename kept): " + csv.getName());
        return csv;
    }

    private File waitForCsv(long after) throws Exception {
        long until = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < until) {
            try (var stream = Files.list(dir)) {
                File file = stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".csv"))
                        .filter(p -> { try { return Files.getLastModifiedTime(p).toMillis() >= after; } catch (Exception e) { return false; } })
                        .map(Path::toFile).max(Comparator.comparingLong(File::lastModified)).orElse(null);
                if (file != null) {
                    long first = file.length();
                    Thread.sleep(750);
                    if (first == file.length()) return file;
                }
            }
            Thread.sleep(1000);
        }
        return null;
    }

    private ProfileDetails readProfileDetails(WebDriver driver, WebDriverWait wait, String fallbackMobile) throws InterruptedException {
        String profileUrl = "https://sagarmeinyog.com/dashboard/profile";
        Exception lastError = null;

        for (int attempt = 1; attempt <= 6; attempt++) {
            try {
                driver.get(profileUrl);
                wait.until(d -> d.getCurrentUrl().contains("/dashboard/profile"));
                wait.until(d -> !d.getPageSource().toLowerCase().contains("network error")
                        && !d.getPageSource().toLowerCase().contains("server busy"));

                String candidateName = wait.until(d -> {
                    List<WebElement> names = d.findElements(By.xpath(
                            "//div[contains(concat(' ',normalize-space(@class),' '),' font-bold ') " +
                                    "and contains(concat(' ',normalize-space(@class),' '),' text-2xl ')]"));
                    for (WebElement element : names) {
                        String text = element.getText();
                        if (text != null && !text.trim().isBlank()) return text.trim();
                    }
                    return null;
                });

                String mobile = findMobileNumber(driver);
                if (mobile.isBlank()) mobile = normalizeMobile(fallbackMobile);
                if (mobile.length() < 10) throw new IllegalStateException("Valid mobile number not found in Profile or Column D.");
                System.out.println("PROFILE READ SUCCESS - Name: " + candidateName + " | Mobile: " + mobile);
                return new ProfileDetails(candidateName, mobile);
            } catch (Exception e) {
                lastError = e;
                System.out.println("Profile attempt " + attempt + " failed: " + e.getMessage());
                Thread.sleep(Math.min(15000, attempt * 2500L));
                try { driver.navigate().refresh(); } catch (Exception ignored) {}
            }
        }
        throw new IllegalStateException("Unable to open/read Profile after retries.", lastError);
    }

    private String findMobileNumber(WebDriver driver) {
        List<WebElement> exact = driver.findElements(By.xpath(
                "//div[normalize-space()='Mobile Number']/following-sibling::input[1]"));
        if (!exact.isEmpty()) {
            String value = exact.get(0).getAttribute("value");
            if (value != null) {
                String digits = value.replaceAll("\\D", "");
                if (digits.length() == 10 || (digits.length() == 12 && digits.startsWith("91"))) {
                    return digits;
                }
            }
        }
        for (WebElement input : driver.findElements(By.cssSelector("input[readonly], input"))) {
            try {
                String value = input.getAttribute("value");
                if (value == null) continue;
                String digits = value.replaceAll("\\D", "");
                if (digits.length() == 10 || (digits.length() == 12 && digits.startsWith("91"))) {
                    return digits;
                }
            } catch (StaleElementReferenceException ignored) {
                // React can replace profile inputs while the page is loading.
            }
        }
        return "";
    }

    private void openProgress(
            WebDriver driver,
            WebDriverWait wait,
            Candidate candidate
    ) {
        String progressUrl = Config.require("sagar.progress.url");
        Exception lastError = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                System.out.println("LOGIN TRY " + attempt);

                driver.get(progressUrl);
                waitForDocumentReady(driver, 20);

                if (!isProgressReady(driver)) {
                    login(driver, candidate);
                }

                driver.get(progressUrl);
                waitForDocumentReady(driver, 20);
                waitForProgress(driver);

                System.out.println("LOGIN OK");
                System.out.println("PROGRESS OK");
                return;
            } catch (Exception e) {
                lastError = e;
                System.out.println("LOGIN RETRY " + attempt);
                if (attempt < 3) {
                    sleep(2500L);
                    try { driver.manage().deleteAllCookies(); } catch (Exception ignored) {}
                }
            }
        }

        throw new IllegalStateException("Unable to login/open Progress after retries.", lastError);
    }

    private void login(WebDriver driver, Candidate candidate) {
        WebDriverWait loginWait = new WebDriverWait(driver, Duration.ofSeconds(20));

        // If progress is already usable, no login is required.
        if (isProgressReady(driver)) return;

        // SMY can redirect /dashboard/progress to the home page instead of directly
        // showing the login form. If there is no password field, click a Login/Sign in
        // control first. If that still does not reveal the form, open the site root and try again.
        if (!hasVisiblePassword(driver)) {
            clickLoginLinkIfPresent(driver);
            sleep(1200L);
        }

        if (!hasVisiblePassword(driver)) {
            driver.get("https://sagarmeinyog.com/");
            waitForDocumentReady(driver, 20);
            clickLoginLinkIfPresent(driver);
            sleep(1200L);
        }

        WebElement password = loginWait.until(d -> firstVisible(d,
                By.cssSelector("input[type='password']"),
                By.cssSelector("input[name='password']"),
                By.cssSelector("input[autocomplete='current-password']")
        ));

        WebElement login = loginWait.until(d -> firstVisible(d,
                By.cssSelector("input[type='email']"),
                By.cssSelector("input[name='email']"),
                By.cssSelector("input[name='username']"),
                By.cssSelector("input[name='userName']"),
                By.cssSelector("input[name='login']"),
                By.cssSelector("input[autocomplete='username']"),
                By.cssSelector("input[placeholder*='mail' i]"),
                By.cssSelector("input[placeholder*='user' i]"),
                By.cssSelector("input[type='text']")
        ));

        login.click();
        login.sendKeys(Keys.chord(Keys.CONTROL, "a"));
        login.sendKeys(candidate.loginId());

        password.click();
        password.sendKeys(Keys.chord(Keys.CONTROL, "a"));
        password.sendKeys(candidate.password());

        WebElement submit = firstVisible(driver,
                By.cssSelector("button[type='submit']"),
                By.cssSelector("input[type='submit']"),
                By.xpath("//button[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'login')]"),
                By.xpath("//button[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'sign in')]"),
                By.xpath("//button[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'submit')]")
        );

        if (submit != null) {
            submit.click();
        } else {
            password.sendKeys(Keys.ENTER);
        }

        loginWait.until(d -> {
            if (isProgressReady(d)) return true;
            String u = safeUrl(d).toLowerCase();
            boolean leftLogin = !hasVisiblePassword(d)
                    && !u.contains("login")
                    && !u.contains("signin");
            return u.contains("/dashboard") || leftLogin;
        });
    }

    private boolean isProgressReady(WebDriver driver) {
        try {
            String url = safeUrl(driver).toLowerCase();
            if (!url.contains("/dashboard/progress")) return false;
            if (hasVisiblePassword(driver)) return false;

            String source = driver.getPageSource().toLowerCase();
            return source.contains("your progress")
                    || source.contains("view certificate")
                    || source.contains("download certificate")
                    || source.contains("assessment")
                    || source.contains("export csv")
                    || source.contains("wellness")
                    || documentReady(driver);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasVisiblePassword(WebDriver driver) {
        try {
            for (WebElement el : driver.findElements(By.cssSelector("input[type='password']"))) {
                if (el.isDisplayed()) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void clickLoginLinkIfPresent(WebDriver driver) {
        WebElement link = firstVisible(driver,
                By.xpath("//*[self::a or self::button or @role='button'][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'login')]"),
                By.xpath("//*[self::a or self::button or @role='button'][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'sign in')]"),
                By.cssSelector("a[href*='login']"),
                By.cssSelector("a[href*='signin']")
        );
        if (link != null) {
            try { link.click(); }
            catch (Exception e) {
                try { ((JavascriptExecutor) driver).executeScript("arguments[0].click();", link); }
                catch (Exception ignored) {}
            }
        }
    }

    private void waitForProgress(WebDriver driver) {
        WebDriverWait progressWait = new WebDriverWait(driver, Duration.ofSeconds(25));
        progressWait.until(this::isProgressReady);
    }

    // Compatibility overload for existing calls that already pass a WebDriverWait.
    // The new progress detector owns its timeout, so the older wait argument is no longer needed.
    private void waitForProgress(WebDriver driver, WebDriverWait ignoredWait) {
        waitForProgress(driver);
    }

    private boolean looksLikeLoginPage(WebDriver driver, String url) {
        try {
            if (!driver.findElements(By.cssSelector("input[type='password']")).isEmpty()) return true;
            String u = url == null ? "" : url.toLowerCase();
            return u.contains("/login") || u.contains("/signin");
        } catch (Exception e) {
            return false;
        }
    }

    private WebElement firstVisible(WebDriver driver, By... selectors) {
        for (By by : selectors) {
            try {
                for (WebElement el : driver.findElements(by)) {
                    if (el.isDisplayed() && el.isEnabled()) return el;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void waitForDocumentReady(WebDriver driver, int seconds) {
        new WebDriverWait(driver, Duration.ofSeconds(seconds)).until(this::documentReady);
    }

    private boolean documentReady(WebDriver driver) {
        try {
            Object state = ((JavascriptExecutor) driver).executeScript("return document.readyState");
            return "complete".equals(String.valueOf(state)) || "interactive".equals(String.valueOf(state));
        } catch (Exception e) {
            return true;
        }
    }

    private String safeUrl(WebDriver driver) {
        try { return driver.getCurrentUrl(); } catch (Exception e) { return ""; }
    }

    private String shortMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        int nl = m.indexOf('\n');
        return nl >= 0 ? m.substring(0, nl) : m;
    }

    private void scanAllPagesForCertificates(
            WebDriver driver,
            WebDriverWait wait,
            Candidate candidate,
            List<File> downloaded,
            Set<String> downloadedCourses,
            DownloadListener downloadListener
    ) throws Exception {

        int page = 1;
        temporaryDownloadFailure = false;

        // Step 1: change Test Type from All to Assessment once.
        selectAssessmentFilter(driver, wait);

        while (true) {
            waitForProgress(driver, wait);
            downloadedCourses.addAll(downloadListener.alreadyUploadedCourses());

            // Step 2: any row exposing View certificate / Download Certificate is eligible.
            List<WebElement> buttons = visible(driver, ASSESSMENT_DOWNLOAD_BUTTON);
            List<String> pageCourses = new ArrayList<>();

            for (WebElement button : buttons) {
                String course = courseNameFromRow(button);
                if (course != null && !pageCourses.contains(course)) {
                    pageCourses.add(course);
                }
            }

            System.out.println("PAGE " + page
                    + " | ASSESSMENT CERTIFICATES AVAILABLE: " + pageCourses.size());

            for (String courseName : pageCourses) {
                if (downloadedCourses.contains(courseName)) {
                    continue;
                }

                File certificate = downloadCertificate(
                        driver,
                        wait,
                        candidate,
                        courseName,
                        downloaded,
                        downloadedCourses,
                        downloadListener
                );

                if (certificate != null) {
                    downloadListener.onCertificateDownloaded(courseName, certificate);
                    downloaded.add(certificate);
                    downloadedCourses.add(courseName);
                    System.out.println("UNIQUE COURSES: " + downloadedCourses.size() + "/10");
                } else {
                    temporaryDownloadFailure = true;
                    // Do not abandon the candidate. The outer loop will revisit
                    // Progress and this course will be attempted again.
                    System.out.println("TEMPORARY DOWNLOAD FAILURE: " + courseName
                            + " - keeping it pending for automatic retry.");
                }

                if (downloadedCourses.size() == REQUIRED_COURSES.size()) {
                    return;
                }
            }

            if (!goToNextPage(driver)) {
                break;
            }

            page++;
            Thread.sleep(1200);
        }
    }

    private String courseNameFromRow(WebElement button) {
        try {
            String rowText = button.findElement(By.xpath("ancestor::tr[1]"))
                    .getText()
                    .replaceAll("\\s+", " ")
                    .trim()
                    .toLowerCase();

            for (String course : REQUIRED_COURSES) {
                if (rowText.contains(course.toLowerCase())) {
                    return course;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean openFirstAssessmentOnAnyPage(
            WebDriver driver,
            WebDriverWait wait
    ) throws InterruptedException {

        int page = 1;

        while (true) {

            waitForProgress(driver, wait);

            List<WebElement> assessments =
                    visible(driver, ASSESSMENT_BUTTON);

            if (!assessments.isEmpty()) {
                WebElement assessment = assessments.get(0);
                scrollTo(driver, assessment);
                safeClick(driver, assessment);

                System.out.println(
                        "Assessment opened from page " + page
                );

                return true;
            }

            if (!goToNextPage(driver)) {
                return false;
            }

            page++;
            Thread.sleep(1200);
        }
    }

    private boolean goToNextPage(WebDriver driver) {

        for (WebElement button :
                driver.findElements(NEXT_PAGE_BUTTON)) {

            try {
                if (!button.isDisplayed()) {
                    continue;
                }

                String disabled =
                        button.getAttribute("disabled");
                String ariaDisabled =
                        button.getAttribute("aria-disabled");

                if (disabled != null
                        || "true".equalsIgnoreCase(ariaDisabled)
                        || !button.isEnabled()) {
                    return false;
                }

                scrollTo(driver, button);
                safeClick(driver, button);
                return true;

            } catch (StaleElementReferenceException ignored) {
            }
        }

        return false;
    }

    private File downloadCertificate(
            WebDriver driver,
            WebDriverWait wait,
            Candidate candidate,
            String courseName,
            List<File> downloaded,
            Set<String> downloadedCourses,
            DownloadListener downloadListener
    ) throws Exception {

        File good = null;

        for (int attempt = 1; attempt <= 6 && good == null; attempt++) {
            WebElement button = findAssessmentDownloadButtonOnCurrentPage(driver, courseName);

            if (button == null) {
                System.out.println("Certificate button moved/not visible for " + courseName
                        + ". Reloading Assessment filter and locating the course again...");

                driver.get(Config.require("sagar.progress.url"));
                waitForProgress(driver, wait);
                selectAssessmentFilter(driver, wait);

                button = findAssessmentDownloadButtonOnAnyPage(driver, wait, courseName);
                if (button == null) {
                    System.out.println("Assessment certificate button not found for " + courseName
                            + " on retry " + attempt + ".");
                    sleep(2000L * attempt);
                    continue;
                }
            }

            scrollTo(driver, button);

            String progressHandle = driver.getWindowHandle();
            Set<String> handlesBeforeClick = new HashSet<>(driver.getWindowHandles());
            long clickedAt = System.currentTimeMillis();
            safeClick(driver, button);

            System.out.println("Download clicked: Certificate " + courseName
                    + " | attempt " + attempt);

            // New SMY behaviour:
            // View certificate / Download Certificate opens the certificate page or modal. The page's
            // visible "Save as PDF" button opens Chrome Print Preview. Chrome is
            // started with its Destination preselected to "Save as PDF" and
            // kiosk-printing enabled, so the real print flow saves the PDF into
            // our temporary certificate folder without a Windows Save As popup.
            File pdf = receiveCertificatePdf(
                    (ChromeDriver) driver,
                    wait,
                    clickedAt,
                    progressHandle,
                    handlesBeforeClick,
                    courseName
            );

            if (pdf == null) {
                System.out.println("PDF not received/generated for " + courseName
                        + ". Retrying without abandoning the row...");
                cleanupPartialDownloads();

                // Return to Progress before retrying in case SMY left us on the
                // certificate page.
                restoreAssessmentProgress(driver, wait, progressHandle);
                sleep(Math.min(10000L, 2000L * attempt));
                continue;
            }

            String detectedCourse = detectCourseFromPdf(pdf);

            // Safety: if another certificate arrives, save it under its real course
            // and keep waiting for the expected course without consuming the retry.
            if (detectedCourse != null && !detectedCourse.equalsIgnoreCase(courseName)) {
                File manualRenamed = rename(
                        pdf,
                        "",
                        detectedCourse
                );

                // Candidate photo is validated on the live certificate page BEFORE
                // Chrome creates the PDF. Do not try to rediscover the HTML image
                // inside the generated PDF; Chrome may encode it in different ways.
                if (!downloadedCourses.contains(detectedCourse)) {
                    downloadListener.onCertificateDownloaded(detectedCourse, manualRenamed);
                    downloaded.add(manualRenamed);
                    downloadedCourses.add(detectedCourse);
                    System.out.println("DIFFERENT CERTIFICATE DETECTED + KEPT: " + detectedCourse);
                }

                attempt--;
                continue;
            }

            File renamed = rename(
                    pdf,
                    "",
                    courseName
            );

            // Photo was already verified on the certificate HTML page using
            // img.certificate-profile-pic before Save as PDF was clicked.
            good = renamed;
            System.out.println("VALID CERTIFICATE: " + renamed.getName());
        }

        return good;
    }

    /**
     * Receives a certificate after the table's View certificate / Download Certificate button.
     *
     * Supports BOTH SMY behaviours:
     *  - old behaviour: browser downloads a PDF directly;
     *  - new behaviour: browser opens /dashboard/certificate/... and the page
     *    exposes a Save as PDF button.
     */
    private File receiveCertificatePdf(
            ChromeDriver driver,
            WebDriverWait wait,
            long clickedAt,
            String progressHandle,
            Set<String> handlesBeforeClick,
            String courseName
    ) throws Exception {

        long deadline = System.currentTimeMillis() + 20_000L;

        while (System.currentTimeMillis() < deadline) {
            // Old/direct-download behaviour still works without any change.
            File direct = newestCompletedPdfAfter(clickedAt);
            if (direct != null) {
                return direct;
            }

            switchToNewWindowIfAny(driver, handlesBeforeClick);

            if (isCertificatePage(driver)) {
                try {
                    File generated = saveCertificatePageAsPdf(driver, wait, courseName);
                    if (generated != null) {
                        return generated;
                    }
                } catch (Exception e) {
                    System.err.println(
                            "SAVE AS PDF FAILED FOR " + courseName + ": " + e.getMessage()
                    );
                    return null;
                } finally {
                    restoreAssessmentProgress(driver, wait, progressHandle);
                }
            }

            sleep(250);
        }

        // A slow old-style direct download may still be arriving. Give it the
        // original wait window as a final compatibility fallback.
        return waitPdf(clickedAt);
    }

    private void switchToNewWindowIfAny(
            WebDriver driver,
            Set<String> handlesBeforeClick
    ) {
        try {
            for (String handle : driver.getWindowHandles()) {
                if (!handlesBeforeClick.contains(handle)) {
                    driver.switchTo().window(handle);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isCertificatePage(WebDriver driver) {
        try {
            String url = driver.getCurrentUrl();
            if (url != null && url.toLowerCase().contains("/dashboard/certificate/")) {
                return true;
            }

            return !visible(driver, By.xpath(
                    "//button[normalize-space()='Save as PDF' or contains(normalize-space(.),'Save as PDF')]"
            )).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Validates the candidate photo directly from the SMY certificate page.
     *
     * Expected HTML:
     * <img class="certificate-profile-pic" alt="Candidate Photo" src="...">
     *
     * We require a non-empty source plus a browser-loaded image with real
     * natural dimensions. A broken/blank image has naturalWidth/naturalHeight 0.
     */
    private boolean isCandidatePhotoValid(WebDriver driver) {
        try {
            WebDriverWait photoWait = new WebDriverWait(driver, Duration.ofSeconds(15));

            WebElement photo = photoWait.until(d -> {
                try {
                    List<WebElement> photos = d.findElements(
                            By.cssSelector("img.certificate-profile-pic")
                    );

                    for (WebElement element : photos) {
                        if (element.isDisplayed()) {
                            return element;
                        }
                    }
                } catch (StaleElementReferenceException ignored) {
                }
                return null;
            });

            String src = photo.getAttribute("src");
            if (src == null || src.isBlank()) {
                System.out.println("CANDIDATE PHOTO INVALID | src is blank");
                return false;
            }

            JavascriptExecutor js = (JavascriptExecutor) driver;

            // Give the image itself time to finish loading even when the page's
            // document.readyState is already complete.
            Boolean loaded = photoWait.until(d -> {
                try {
                    Object value = ((JavascriptExecutor) d).executeScript(
                            "return arguments[0].complete === true "
                                    + "&& arguments[0].naturalWidth > 0 "
                                    + "&& arguments[0].naturalHeight > 0;",
                            photo
                    );
                    return Boolean.TRUE.equals(value) ? Boolean.TRUE : null;
                } catch (StaleElementReferenceException e) {
                    return null;
                }
            });

            Number widthNumber = (Number) js.executeScript(
                    "return arguments[0].naturalWidth;", photo
            );
            Number heightNumber = (Number) js.executeScript(
                    "return arguments[0].naturalHeight;", photo
            );

            long width = widthNumber == null ? 0L : widthNumber.longValue();
            long height = heightNumber == null ? 0L : heightNumber.longValue();

            System.out.println(
                    "CANDIDATE PHOTO CHECK | loaded=" + Boolean.TRUE.equals(loaded)
                            + " | width=" + width
                            + " | height=" + height
                            + " | src=" + src
            );

            return Boolean.TRUE.equals(loaded) && width > 0 && height > 0;

        } catch (Exception e) {
            System.out.println(
                    "CANDIDATE PHOTO NOT FOUND/BROKEN: " + e.getMessage()
            );
            return false;
        }
    }

    /**
     * Saves the currently open certificate page as a PDF while Chrome is visible.
     *
     * The visible SMY "Save as PDF" button normally opens Chrome Print Preview.
     * Headless Chrome has no visible Print Preview UI, so we perform the equivalent
     * browser operation with Page.printToPDF after verifying the live candidate
     * photo has fully loaded. This keeps Chrome completely hidden and writes the
     * certificate directly into the temporary certificate folder.
     */
    private File saveCertificatePageAsPdf(
            ChromeDriver driver,
            WebDriverWait wait,
            String courseName
    ) throws Exception {

        By saveAsPdfButton = By.xpath(
                "//button[normalize-space()='Save as PDF' or contains(normalize-space(.),'Save as PDF')]"
        );

        // Wait for the real SMY Save as PDF button and CLICK IT.
        // PDF is generated programmatically so normal visible Chrome can remain open.
        // Therefore window.print() is intercepted only to prevent the hidden
        // browser from blocking. After the website button is clicked, the same
        // Chrome print engine is used below to save the PDF bytes to disk.
        WebElement realSaveButton = wait.until(d -> {
            for (WebElement element : d.findElements(saveAsPdfButton)) {
                try {
                    if (element.isDisplayed() && element.isEnabled()) return element;
                } catch (StaleElementReferenceException ignored) {
                }
            }
            return null;
        });

        wait.until(d -> {
            try {
                Object state = ((JavascriptExecutor) d).executeScript(
                        "return document.readyState"
                );
                return "complete".equals(String.valueOf(state));
            } catch (Exception e) {
                return false;
            }
        });

        sleep(800);

        if (!isCandidatePhotoValid(driver)) {
            System.out.println(
                    "CANDIDATE PHOTO INVALID - certificate will not be saved for "
                            + courseName
            );
            return null;
        }

        System.out.println(
                "CANDIDATE PHOTO VERIFIED ON CERTIFICATE PAGE: " + courseName
        );

        cleanupPartialDownloads();

        // The site button calls window.print(). Keep the browser hidden by
        // intercepting only that native UI call, but still perform the REAL
        // website-button click first. This also lets any page-side pre-print
        // logic/styles run before the PDF is created.
        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript(
                "window.__smyPrintRequested=false;"
                        + "window.__smyOriginalPrint=window.print;"
                        + "window.print=function(){window.__smyPrintRequested=true;};"
        );

        try {
            scrollTo(driver, realSaveButton);
            safeClick(driver, realSaveButton);
            System.out.println("SMY SAVE AS PDF BUTTON CLICKED: " + courseName);

            try {
                new WebDriverWait(driver, Duration.ofSeconds(5)).until(d -> {
                    try {
                        Object requested = ((JavascriptExecutor) d).executeScript(
                                "return window.__smyPrintRequested === true;"
                        );
                        return Boolean.TRUE.equals(requested);
                    } catch (Exception ignored) {
                        return false;
                    }
                });
            } catch (TimeoutException ignored) {
                // Some SMY builds call their print helper asynchronously or
                // prepare the document without directly invoking window.print.
                // The button was still genuinely clicked, so continue.
                sleep(750);
            }
        } finally {
            try {
                js.executeScript(
                        "if(window.__smyOriginalPrint){window.print=window.__smyOriginalPrint;}"
                );
            } catch (Exception ignored) {
            }
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("landscape", false);
        params.put("displayHeaderFooter", false);
        params.put("printBackground", true);
        params.put("preferCSSPageSize", true);
        params.put("scale", 1.0);
        params.put("marginTop", 0.0);
        params.put("marginBottom", 0.0);
        params.put("marginLeft", 0.0);
        params.put("marginRight", 0.0);

        Map<String, Object> result = driver.executeCdpCommand("Page.printToPDF", params);
        Object dataObject = result.get("data");
        if (dataObject == null || String.valueOf(dataObject).isBlank()) {
            throw new IllegalStateException("Chrome printToPDF returned no PDF data for " + courseName);
        }

        byte[] pdfBytes = Base64.getDecoder().decode(String.valueOf(dataObject));
        if (pdfBytes.length < 1000) {
            throw new IllegalStateException("Chrome printToPDF returned an unexpectedly small PDF for " + courseName);
        }

        String safeCourse = courseName
                .replaceAll("[\\/:*?\"<>|]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        Path output = dir.resolve("SMY-TEMP-" + System.currentTimeMillis() + "-" + safeCourse + ".pdf");
        Files.write(output, pdfBytes);

        if (!isPdf(output)) {
            Files.deleteIfExists(output);
            throw new IllegalStateException("Chrome created an invalid PDF for " + courseName);
        }

        System.out.println("SMY BUTTON CLICK + PDF DOWNLOAD COMPLETE: " + courseName);
        System.out.println("PDF SAVED TO TEMP FOLDER: " + output.toAbsolutePath());
        return output.toFile();
    }

    /**
     * Wait for Chrome's Save as PDF output. Chrome can create a temporary
     * .crdownload/.tmp first, so only a stable completed PDF is returned.
     */
    private File waitForPrintedPdf(long afterMillis, long timeoutMillis)
            throws InterruptedException {

        long deadline = System.currentTimeMillis() + timeoutMillis;
        Path lastPath = null;
        long lastSize = -1L;
        int stableChecks = 0;

        while (System.currentTimeMillis() < deadline) {
            try (var stream = Files.list(dir)) {
                List<Path> pdfs = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                        .filter(path -> {
                            try {
                                return Files.getLastModifiedTime(path).toMillis() >= afterMillis - 1500L;
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .sorted((a, b) -> {
                            try {
                                return Long.compare(
                                        Files.getLastModifiedTime(b).toMillis(),
                                        Files.getLastModifiedTime(a).toMillis()
                                );
                            } catch (Exception e) {
                                return 0;
                            }
                        })
                        .toList();

                if (!pdfs.isEmpty()) {
                    Path newest = pdfs.get(0);
                    long size = Files.size(newest);

                    if (newest.equals(lastPath) && size > 0 && size == lastSize) {
                        stableChecks++;
                    } else {
                        lastPath = newest;
                        lastSize = size;
                        stableChecks = 0;
                    }

                    // Two stable checks (~700 ms) avoids grabbing a file while
                    // Chrome is still finishing it.
                    if (stableChecks >= 2 && size > 1000L) {
                        return newest.toFile();
                    }
                }
            } catch (Exception ignored) {
            }

            sleep(350);
        }

        return null;
    }

    private void restoreAssessmentProgress(
            WebDriver driver,
            WebDriverWait wait,
            String progressHandle
    ) {
        try {
            // If the certificate opened a new tab/window, close only that
            // certificate tab and return to the original progress tab.
            if (driver.getWindowHandles().contains(progressHandle)
                    && !driver.getWindowHandle().equals(progressHandle)) {
                try { driver.close(); } catch (Exception ignored) { }
                driver.switchTo().window(progressHandle);
            }
        } catch (Exception ignored) {
        }

        try {
            String currentUrl = driver.getCurrentUrl().toLowerCase();

            // New SMY opens View certificate as a modal on /dashboard/progress.
            // Merely checking the URL leaves that modal open and blocks the next
            // row.  Reload Progress whenever a certificate page/modal is active.
            if (isCertificatePage(driver) || !currentUrl.contains("/dashboard/progress")) {
                driver.get(Config.require("sagar.progress.url"));
            }

            waitForProgress(driver, wait);
            selectAssessmentFilter(driver, wait);
        } catch (Exception e) {
            System.err.println(
                    "WARNING - could not immediately restore Assessment progress page: "
                            + e.getMessage()
            );
        }
    }

    private File newestCompletedPdfAfter(long after) {
        try (var stream = Files.list(dir)) {
            File file = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase().endsWith(".pdf"))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis() >= after;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(Path::toFile)
                    .max(Comparator.comparingLong(File::lastModified))
                    .orElse(null);

            if (file == null || file.length() <= 1000) return null;

            long firstSize = file.length();
            sleep(250);
            long secondSize = file.length();

            return firstSize == secondSize && isPdf(file.toPath()) ? file : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Select Test Type = Assessment. No rows-per-page manipulation is used.
     * The locator first looks for the combobox in the Test Type column and then
     * falls back to the first visible All/Assessment combobox if the table markup
     * changes slightly.
     */
    private void selectAssessmentFilter(WebDriver driver, WebDriverWait wait)
            throws InterruptedException {

        WebElement filter = findTestTypeCombobox(driver);
        if (filter == null) {
            System.out.println("TEST TYPE FILTER NOT FOUND - continuing with ASSESSMENT row badge only.");
            return;
        }

        String current = filter.getText() == null ? "" : filter.getText().trim();
        if (current.toLowerCase().contains("assessment")) {
            System.out.println("TEST TYPE FILTER: ASSESSMENT");
            return;
        }

        scrollTo(driver, filter);
        safeClick(driver, filter);

        By assessmentOption = By.xpath(
                "//*[@role='option'][contains(translate(normalize-space(.),"
                        + "'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'ASSESSMENT')]"
                        + " | //*[@role='listbox']//*[normalize-space()='Assessment']"
        );

        WebElement option = null;
        long until = System.currentTimeMillis() + 8000L;
        while (System.currentTimeMillis() < until && option == null) {
            for (WebElement e : driver.findElements(assessmentOption)) {
                try {
                    if (e.isDisplayed() && e.isEnabled()) {
                        option = e;
                        break;
                    }
                } catch (StaleElementReferenceException ignored) {
                }
            }
            if (option == null) sleep(200);
        }

        if (option == null) {
            System.out.println("ASSESSMENT option not found after opening Test Type filter; "
                    + "continuing with badge-only filtering.");
            try { safeClick(driver, filter); } catch (Exception ignored) {}
            return;
        }

        safeClick(driver, option);
        sleep(1500);
        waitForProgress(driver, wait);
        System.out.println("TEST TYPE FILTER CHANGED: ALL -> ASSESSMENT");
    }

    private WebElement findTestTypeCombobox(WebDriver driver) {
        // Best match: combobox located inside/near a header whose text contains Test Type.
        for (WebElement combo : driver.findElements(COMBOBOX)) {
            try {
                if (!combo.isDisplayed()) continue;

                String own = combo.getText() == null ? "" : combo.getText().trim();
                String context = "";
                try {
                    context = combo.findElement(By.xpath("ancestor::*[self::th or @role='columnheader'][1]"))
                            .getText();
                } catch (Exception ignored) {
                    try {
                        context = combo.findElement(By.xpath("ancestor::div[1]" )).getText();
                    } catch (Exception ignored2) {
                    }
                }

                if (context != null && context.toLowerCase().contains("test type")) {
                    return combo;
                }

                // Fallback candidate. In the current page Test Type is the first
                // All/Assessment filter combobox above the table.
                if (own.equalsIgnoreCase("All") || own.equalsIgnoreCase("Assessment")) {
                    return combo;
                }
            } catch (StaleElementReferenceException ignored) {
            }
        }
        return null;
    }

    private WebElement findAssessmentDownloadButtonOnCurrentPage(
            WebDriver driver,
            String courseName
    ) {
        String wanted = courseName.toLowerCase();

        for (WebElement button : visible(driver, ASSESSMENT_DOWNLOAD_BUTTON)) {
            try {
                String rowText = button.findElement(By.xpath("ancestor::tr[1]"))
                        .getText()
                        .replaceAll("\\s+", " ")
                        .trim()
                        .toLowerCase();

                // Current SMY Progress rows may not contain an ASSESSMENT badge.
                // The presence of View certificate / Download Certificate is the
                // eligibility signal, so course-name matching is sufficient.
                if (rowText.contains(wanted)) {
                    return button;
                }
            } catch (StaleElementReferenceException ignored) {
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private WebElement findAssessmentDownloadButtonOnAnyPage(
            WebDriver driver,
            WebDriverWait wait,
            String courseName
    ) throws InterruptedException {
        int page = 1;

        while (true) {
            waitForProgress(driver, wait);

            WebElement found = findAssessmentDownloadButtonOnCurrentPage(driver, courseName);
            if (found != null) {
                System.out.println("RETRY LOCATED " + courseName + " ON PAGE " + page);
                return found;
            }

            if (!goToNextPage(driver)) {
                return null;
            }

            page++;
            sleep(900);
        }
    }

    private void clickFirst(
            WebDriver driver,
            WebDriverWait wait,
            By locator
    ) {

        WebElement button =
                wait.until(
                        d -> {

                            for (
                                    WebElement element :
                                    d.findElements(
                                            locator
                                    )
                            ) {

                                try {

                                    if (element.isDisplayed()
                                            && element.isEnabled()) {

                                        return element;
                                    }

                                } catch (
                                        StaleElementReferenceException ignored
                                ) {
                                }
                            }

                            return null;
                        }
                );

        scrollTo(
                driver,
                button
        );

        safeClick(
                driver,
                button
        );
    }

    private void clickUnderstandIfPresent(
            WebDriver driver
    ) {

        long until =
                System.currentTimeMillis()
                        + 5000;

        while (
                System.currentTimeMillis()
                        < until
        ) {

            List<WebElement> buttons =
                    visible(
                            driver,
                            UNDERSTAND_BUTTON
                    );

            if (!buttons.isEmpty()) {

                safeClick(
                        driver,
                        buttons.get(0)
                );

                System.out.println(
                        "I understand clicked."
                );

                return;
            }

            sleep(
                    250
            );
        }
    }

    private void waitForAssessmentResult(
            WebDriver driver
    ) throws InterruptedException {

        System.out.println();
        System.out.println(
                "======================================"
        );
        System.out.println(
                "ASSESSMENT OPEN"
        );
        System.out.println(
                "Complete and submit the assessment "
                        + "in this Chrome window."
        );
        System.out.println(
                "Java will continue automatically "
                        + "when the result appears."
        );
        System.out.println(
                "======================================"
        );

        long until =
                System.currentTimeMillis()
                        + Duration
                        .ofMinutes(40)
                        .toMillis();

        while (
                System.currentTimeMillis()
                        < until
        ) {

            clickUnderstandIfPresent(
                    driver
            );

            String body = "";

            try {

                body =
                        driver.findElement(
                                By.tagName(
                                        "body"
                                )
                        ).getText();

            } catch (Exception ignored) {
            }

            String text =
                    body == null
                            ? ""
                            : body.toLowerCase();

            if (text.contains(
                    "you've scored"
            )
                    || text.contains(
                    "you have scored"
            )
                    || text.contains(
                    "correct answers"
            )
                    || text.contains(
                    "wrong answers"
            )
                    || text.contains(
                    "re-attempt"
            )
                    || text.contains(
                    "cleared"
            )) {

                System.out.println(
                        "Assessment result detected."
                );

                Thread.sleep(
                        1500
                );

                return;
            }

            if (driver.getCurrentUrl()
                    .contains(
                            "/dashboard/progress"
                    )) {

                return;
            }

            Thread.sleep(
                    700
            );
        }

        throw new RuntimeException(
                "Assessment did not finish "
                        + "within 40 minutes."
        );
    }

    private List<WebElement> visible(
            WebDriver driver,
            By locator
    ) {

        List<WebElement> result =
                new ArrayList<>();

        for (
                WebElement element :
                driver.findElements(
                        locator
                )
        ) {

            try {

                if (element.isDisplayed()) {

                    result.add(
                            element
                    );
                }

            } catch (
                    StaleElementReferenceException ignored
            ) {
            }
        }

        return result;
    }

    private void safeClick(
            WebDriver driver,
            WebElement element
    ) {

        try {

            element.click();

        } catch (Exception e) {

            ((JavascriptExecutor) driver)
                    .executeScript(
                            "arguments[0].click();",
                            element
                    );
        }
    }

    private void scrollTo(
            WebDriver driver,
            WebElement element
    ) {

        ((JavascriptExecutor) driver)
                .executeScript(
                        "arguments[0].scrollIntoView({block:'center'});",
                        element
                );
    }

    private void sleep(long millis) {

        try {

            Thread.sleep(
                    millis
            );

        } catch (
                InterruptedException e
        ) {

            Thread.currentThread()
                    .interrupt();
        }
    }

    private File waitPdf(
            long after
    ) throws Exception {

        long until =
                System.currentTimeMillis()
                        + 60000;

        while (
                System.currentTimeMillis()
                        < until
        ) {

            try (
                    var stream =
                            Files.list(
                                    dir
                            )
            ) {

                File file =
                        stream
                                .filter(
                                        Files::isRegularFile
                                )
                                .filter(
                                        path ->
                                                path.toString()
                                                        .toLowerCase()
                                                        .endsWith(
                                                                ".pdf"
                                                        )
                                )
                                .filter(
                                        path -> {

                                            try {

                                                return Files
                                                        .getLastModifiedTime(
                                                                path
                                                        )
                                                        .toMillis()
                                                        >= after;

                                            } catch (Exception e) {

                                                return false;
                                            }
                                        }
                                )
                                .map(
                                        Path::toFile
                                )
                                .max(
                                        Comparator
                                                .comparingLong(
                                                        File::lastModified
                                                )
                                )
                                .orElse(
                                        null
                                );

                if (file != null && file.length() > 1000) {
                    long firstSize = file.length();
                    Thread.sleep(750);
                    long secondSize = file.length();

                    if (firstSize == secondSize && isPdf(file.toPath())) {
                        return file;
                    }
                }
            }

            Thread.sleep(
                    1000
            );
        }

        return null;
    }

    private String detectCourseFromPdf(File pdf) {
        String haystack = pdf.getName().toLowerCase();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            if (text != null) haystack += "\n" + text.toLowerCase();
        } catch (Exception ignored) {
            // Some certificates may be image-heavy. In that case the expected row
            // course remains the fallback and normal processing continues.
        }

        for (String course : REQUIRED_COURSES) {
            if (haystack.contains(course.toLowerCase())) return course;
        }
        if (haystack.contains("envoinmental wellness") || haystack.contains("enviornmental wellness"))
            return "Environmental Wellness";
        if (haystack.contains("intellectural wellness")) return "Intellectual Wellness";
        if (haystack.contains("occuptional wellness")) return "Occupational Wellness";
        if (haystack.contains("spirtual wellness")) return "Spiritual Wellness";
        return null;
    }

    private boolean isPdf(Path path) {
        try (var input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(5);
            return header.length == 5
                    && header[0] == '%'
                    && header[1] == 'P'
                    && header[2] == 'D'
                    && header[3] == 'F'
                    && header[4] == '-';
        } catch (Exception ignored) {
            return false;
        }
    }

    private void cleanupPartialDownloads() {
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString().toLowerCase();
                if (name.endsWith(".crdownload") || name.endsWith(".tmp")) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private File rename(
            File file,
            String candidateName,
            String courseName
    ) throws Exception {

        // Candidate name is intentionally not used. The Profile page is not opened.
        String safeCourse = courseName
                .replaceAll("[\\/:*?\"<>|]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        Path destination =
                dir.resolve(safeCourse + ".pdf");

        Files.move(
                file.toPath(),
                destination,
                StandardCopyOption
                        .REPLACE_EXISTING
        );

        return destination.toFile();
    }

    private void clearDownloadFolder()
            throws Exception {

        try (
                var stream =
                        Files.list(
                                dir
                        )
        ) {

            for (
                    Path path :
                    stream.toList()
            ) {

                if (Files.isRegularFile(
                        path
                )) {

                    Files.deleteIfExists(
                            path
                    );
                }
            }
        }
    }
}
