package app.release.publisher.android;

import app.release.model.CommandLineArguments;
import app.release.publisher.Publisher;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.AndroidPublisherScopes;
import com.google.api.services.androidpublisher.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import lombok.extern.slf4j.Slf4j;
import net.dongliu.apk.parser.ApkFile;
import net.dongliu.apk.parser.bean.ApkMeta;

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
 * Uploads android apk files to Play Store.
 */
@Slf4j
public class ApkPublisher implements Publisher {

    private static final String MIME_TYPE_APK = "application/vnd.android.package-archive";
    private final CommandLineArguments arguments;

    public ApkPublisher(CommandLineArguments arguments) {
        this.arguments = arguments;
    }

    @Override
    public void publish() throws IOException, GeneralSecurityException {

        // load key file credentials
        log.info("Loading account credentials...");
        Path jsonKey = FileSystems.getDefault().getPath(arguments.getJsonKeyPath()).normalize();
        GoogleCredentials credentials = ServiceAccountCredentials.fromStream(new FileInputStream(jsonKey.toFile())).createScoped(Collections.singleton(AndroidPublisherScopes.ANDROIDPUBLISHER));
        // load apk file info
        log.info("Loading apk file information...");
        Path apkFile = FileSystems.getDefault().getPath(arguments.getFile()).normalize();
        ApkFile apkInfo = new ApkFile(apkFile.toFile());
        ApkMeta apkMeta = apkInfo.getApkMeta();
        final String applicationName = arguments.getAppName() == null ? apkMeta.getName() : arguments.getAppName();
        final String packageName = apkMeta.getPackageName();
        log.info("ApplicationPublisher Name: [{}]", apkMeta.getName());
        log.info("ApplicationPublisher Id: [{}]", apkMeta.getPackageName());
        log.info("ApplicationPublisher Version Code: %d%n", apkMeta.getVersionCode());
        log.info("ApplicationPublisher Version Name: [{}]", apkMeta.getVersionName());
        apkInfo.close();

        // load release notes
        log.info("Loading release notes...");
        List<LocalizedText> releaseNotes = new ArrayList<>();
        if (arguments.getNotesPath() != null) {
            Path notesFile = FileSystems.getDefault().getPath(arguments.getNotesPath()).normalize();
            String notesContent = new String(Files.readAllBytes(notesFile));
            releaseNotes.add(new LocalizedText().setLanguage(Locale.US.toString()).setText(notesContent));
        } else if (arguments.getNotes() != null) {
            releaseNotes.add(new LocalizedText().setLanguage(Locale.US.toString()).setText(arguments.getNotes()));
        }

        // init publisher
        log.info("Initialising publisher service...");
        AndroidPublisher publisher = new AndroidPublisher.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JacksonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials)
        ).setApplicationName(applicationName).build();


        // create an edit
        log.info("Initialising new edit...");
        AppEdit edit = publisher.edits().insert(packageName, null).execute();
        final String editId = edit.getId();
        log.info("Edit created. Id: [{}]", editId);

        try {
            // publish the apk
            log.info("Uploading apk file...");
            AbstractInputStreamContent apkContent = new FileContent(MIME_TYPE_APK, apkFile.toFile());
            Apk apk = publisher.edits().apks().upload(packageName, editId, apkContent).execute();
            log.info("Apk uploaded. Version Code: [{}]", apk.getVersionCode());

            // create a release on track
            log.info("Creating a release on track: [{}]", arguments.getTrackName());
            TrackRelease release = new TrackRelease().setName("Automated publish").setStatus("completed")
                    .setVersionCodes(Collections.singletonList((long) apk.getVersionCode()))
                    .setReleaseNotes(releaseNotes);
            Track track = new Track().setReleases(Collections.singletonList(release)).setTrack(arguments.getTrackName());
            publisher.edits().tracks().update(packageName, editId, arguments.getTrackName(), track).execute();
            log.info("Release created on track: [{}]", arguments.getTrackName());

            // commit edit
            log.info("Committing edit...");
            publisher.edits().commit(packageName, editId).execute();
            log.info("Success. Committed Edit id: [{}]", editId);

            // Success
        } catch (Exception e) {
            // error message
            String msg = "Operation Failed: " + e.getMessage();

            // abort
            log.error("Operation failed due to an error!, Deleting edit...");
            try {
                publisher.edits().delete(packageName, editId).execute();
            } catch (Exception e2) {
                // log abort error as well
                msg += "\nFailed to delete edit: " + e2.getMessage();
            }

            // forward error with message
            throw new IOException(msg, e);
        }
    }
}