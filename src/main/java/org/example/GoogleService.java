package org.example;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


public final class GoogleService {

    private GoogleService() {}

    private static GoogleCredentials credentials() throws Exception {
        Path credentialsPath = findServiceAccountFile();

        if (credentialsPath == null) {
            throw new IllegalStateException(
                    "Google service-account credentials.json not found. "
                            + "Copy credentials.json into this project root, or keep the old "
                            + "SMY-Certificate-Emailer-No-Drive project beside this project."
            );
        }

        System.out.println("GOOGLE CREDENTIALS FOUND: " + credentialsPath);

        try (FileInputStream in = new FileInputStream(credentialsPath.toFile())) {
            return GoogleCredentials.fromStream(in)
                    .createScoped(List.of(SheetsScopes.SPREADSHEETS));
        }
    }

    public static Sheets sheets() throws Exception {
        return new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials())
        )
                .setApplicationName("SMY Certificate Emailer")
                .build();
    }

    private static Path findServiceAccountFile() {
        Path p;

        p = existingFile(System.getProperty("google.credentials.path"));
        if (p != null) return p;

        p = existingFile(System.getenv("GOOGLE_CREDENTIALS_PATH"));
        if (p != null) return p;

        p = existingFile(System.getenv("GOOGLE_APPLICATION_CREDENTIALS"));
        if (p != null) return p;

        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();

        p = existingFile(current.resolve("credentials.json"));
        if (p != null) return p;

        Path parent = current.getParent();
        if (parent != null) {
            p = existingFile(parent.resolve("credentials.json"));
            if (p != null) return p;

            p = existingFile(
                    parent.resolve("SMY-Certificate-Emailer-No-Drive")
                            .resolve("credentials.json")
            );
            if (p != null) return p;

            try (DirectoryStream<Path> stream =
                         Files.newDirectoryStream(parent, "SMY-Certificate-Emailer*")) {

                for (Path sibling : stream) {
                    if (!Files.isDirectory(sibling)) continue;
                    if (sibling.toAbsolutePath().normalize().equals(current)) continue;

                    p = existingFile(sibling.resolve("credentials.json"));
                    if (p != null) return p;
                }

            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private static Path existingFile(String value) {
        if (value == null || value.isBlank()) return null;
        return existingFile(Path.of(value));
    }

    private static Path existingFile(Path path) {
        if (path == null) return null;
        Path normalized = path.toAbsolutePath().normalize();
        return Files.isRegularFile(normalized) ? normalized : null;
    }
}
