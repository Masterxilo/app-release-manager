package app.release.publisher.android;

import app.release.model.CommandLineArguments;
import app.release.publisher.Publisher;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.AndroidPublisherScopes;
import com.google.api.services.androidpublisher.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Uploads android aab files to Play Store.
 */
@Slf4j
public class AabPublisher implements Publisher {

    private static final String MIME_TYPE_AAB = "application/octet-stream";
    private final CommandLineArguments arguments;

    public AabPublisher(CommandLineArguments arguments) {
        this.arguments = arguments;
    }

    /**
     * Perform aab publish an release on given track
     *
     * @throws Exception Upload error
     */
    @Override
    public void publish() throws Exception {

        // load key file credentials
        log.info("Loading account credentials...");
        Path jsonKey = FileSystems.getDefault().getPath(arguments.getJsonKeyPath()).normalize();
        GoogleCredentials credentials = ServiceAccountCredentials.fromStream(new FileInputStream(jsonKey.toFile()))
                .createScoped(Collections.singleton(AndroidPublisherScopes.ANDROIDPUBLISHER));

        // load aab file info
        log.info("Loading file information...");
        Path file = FileSystems.getDefault().getPath(arguments.getFile()).normalize();
        String applicationName = arguments.getAppName();
        String packageName = arguments.getPackageName();
        log.info("Application Name: [{}]", applicationName);
        log.info("Package Name: [{}]", packageName);

        // load release notes
        log.info("Loading release notes...");
        List<LocalizedText> releaseNotes = new ArrayList<>();
        if (arguments.getNotesPath() != null) {
            Path notesFile = FileSystems.getDefault().getPath(arguments.getNotesPath()).normalize();
            try {
                String notesContent = new String(Files.readAllBytes(notesFile));
                releaseNotes.add(new LocalizedText().setLanguage(Locale.US.toString()).setText(notesContent));
            } catch (IOException e) {
                e.printStackTrace();
            }

        } else if (arguments.getNotes() != null) {
            releaseNotes.add(new LocalizedText().setLanguage(Locale.US.toString()).setText(arguments.getNotes()));
        }

        // init publisher
        log.info("Initialising publisher service...");
        AndroidPublisher publisher = new AndroidPublisher.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JacksonFactory.getDefaultInstance(),
                setHttpTimeout(new HttpCredentialsAdapter(credentials))).setApplicationName(applicationName).build();

        // create an edit
        log.info("Initialising new edit...");
        AppEdit edit = null;
        try {
            edit = publisher.edits().insert(packageName, null).execute();
        } catch (IOException e) {
            e.printStackTrace();
        }
        final String editId = edit.getId();
        log.info("Edit created with Id: [{}]", editId);

        long bundleVersionCode = 0;
        try {
            
            // publish the file
            log.info("Uploading AAB file...");
            AbstractInputStreamContent aabContent = new FileContent(MIME_TYPE_AAB, file.toFile());
            Bundle bundle = publisher.edits().bundles().upload(packageName, editId, aabContent).execute();
            bundleVersionCode = (long) bundle.getVersionCode();
            log.info("AAB File uploaded with Version Code: [{}]", bundle.getVersionCode());

            // create a release on track
            log.info("Creating a release on track:[{}]", arguments.getTrackName());
            TrackRelease release = new TrackRelease()
                    .setName(arguments.getReleaseName())
                    .setStatus(arguments.getStatus() == null ? "completed" : arguments.getStatus())
                    .setVersionCodes(Collections.singletonList(bundleVersionCode))
                    //.setUserFraction(1.0) // IN_PROGRESS release must have fraction
                    .setReleaseNotes(releaseNotes);

            Track track = new Track().setReleases(Collections.singletonList(release))
                    .setTrack(arguments.getTrackName());
            publisher.edits().tracks().update(packageName, editId, arguments.getTrackName(), track).execute();
            log.info("Release created on track: [{}]", arguments.getTrackName());

            // commit edit
            log.info("Committing edit...");
            publisher.edits().commit(packageName, editId).execute();
            log.info("Success. Committed Edit id: [{}]. Release created.", editId);

            // Success

        } catch (final Exception e) {
            String errorMessage = "Operation Failed: " + e.getMessage();
            e.printStackTrace();
            log.error("Operation failed due to an errorMessage!, Deleting edit...");
            try {
                publisher.edits().delete(packageName, editId).execute();
            } catch (Exception e2) {
                errorMessage += "\nFailed to delete edit: " + e2.getMessage();
            }
            log.error("Error: [{}]", errorMessage);
            throw new IOException(errorMessage, e);
        }

        // extra step, try to activate draft release on internalt testing track.. doesn't work for unreleased draft app?
        // TODO not working
        //updateVersionStatusCompleted(publisher, packageName, bundleVersionCode);

    }

    public void updateVersionStatusCompleted(AndroidPublisher publisher, String packageName, long bundleVersionCode) throws Exception {

        // create an edit
        log.info("Initialising new edit...");
        AppEdit edit = null;
        try {
            edit = publisher.edits().insert(packageName, null).execute();
        } catch (IOException e) {
            e.printStackTrace();
        }
        final String editId = edit.getId();
        log.info("Edit created with Id: [{}]", editId);

        try {
            // create a release on track
            log.info("Updating a release on track:[{}]", arguments.getTrackName());
            TrackRelease release = new TrackRelease()
                    .setStatus("inProgress")
                    .setUserFraction(0.0)
                    .setVersionCodes(Collections.singletonList(bundleVersionCode));

            Track track = new Track().setReleases(Collections.singletonList(release))
                    .setTrack(arguments.getTrackName());
            publisher.edits().tracks().update(packageName, editId, arguments.getTrackName(), track).execute();
            log.info("Release updated on track: [{}]", arguments.getTrackName());

            // commit edit
            log.info("Committing edit...");
            publisher.edits().commit(packageName, editId).execute();
            log.info("Success. Committed Edit id: [{}]. Release updated.", editId);

            // Success
        } catch (final Exception e) {
            String errorMessage = "Operation Failed: " + e.getMessage();
            e.printStackTrace();
            log.error("Operation failed due to an errorMessage!, Deleting edit...");
            try {
                publisher.edits().delete(packageName, editId).execute();
            } catch (Exception e2) {
                errorMessage += "\nFailed to delete edit: " + e2.getMessage();
            }
            log.error("Error: [{}]", errorMessage);
            throw new IOException(errorMessage, e);
        }
    }

    private HttpRequestInitializer setHttpTimeout(final HttpRequestInitializer requestInitializer) {
        return httpRequest -> {
            requestInitializer.initialize(httpRequest);
            httpRequest.setConnectTimeout(3 * 60000); // 3 minutes connect timeout
            httpRequest.setReadTimeout(3 * 60000); // 3 minutes read timeout
        };
    }
}