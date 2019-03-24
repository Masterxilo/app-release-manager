package app.release.publish;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.FileContent;
import com.google.api.services.androidpublisher.AndroidPublisherScopes;
import com.google.api.services.androidpublisher.model.Apk;
import com.google.api.services.androidpublisher.model.AppEdit;
import com.google.api.services.androidpublisher.model.LocalizedText;
import com.google.api.services.androidpublisher.model.Track;
import com.google.api.services.androidpublisher.model.TrackRelease;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Localizable;
import org.kohsuke.args4j.Option;

import net.dongliu.apk.parser.ApkFile;
import net.dongliu.apk.parser.bean.ApkMeta;

/**
 * Uploads android apk files to Play Store.
 */
public class AndroidPublisher {

    private static final String MIME_TYPE_APK = "application/vnd.android.package-archive";

    @Option(name = "-key", required = true, usage = "JSON key file of authorized service account")
    private String jsonKeyPath;

    @Option(name = "-name", usage = "(optional) AndroidPublisher name on Play Store (defaults to name in apk)")
    private String appName;

    @Option(name = "-apk", required = true, usage = "The apk file to publish")
    private String apkPath;

    @Option(name = "-track", required = true, usage = "Release track to use. Eg. internal, alpha, beta or production")
    private String trackName;

    @Option(name = "-notes", forbids = "-notesFile", usage = "(optional) Release notes")
    private String notes;

    @Option(name = "-notesFile", forbids = "-notes", usage = "(optional) Release notes from file")
    private String notesPath;

    public static void main(String... args) {
        try {
            new AndroidPublisher().parseArgs(args).publish();
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(2);
        }
    }

    /**
     * Parse process arguments.
     *
     * @param args process arguments
     * @throws CmdLineException arguments error
     * @return {@link AndroidPublisher} instance
     */
    private AndroidPublisher parseArgs(String... args) throws CmdLineException {
        // init parser
        CmdLineParser parser = new CmdLineParser(this);

        try {
            // must have args
            if (args == null || args.length < 1) {
                String msg = "No arguments given";
                throw new CmdLineException(parser, this.localize(msg), msg);
            }

            // parse args
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            // print usage and forward error
            System.err.println("Invalid arguments.");
            System.err.println("Options:");
            parser.printUsage(System.err);
            throw e;
        }

        // return instance
        return this;
    }

    /**
     * Perform apk publish an release on given track
     *
     * @throws Exception Upload error
     */
    private void publish() throws Exception {

        // load key file credentials
        System.out.println("Loading account credentials...");
        Path jsonKey = FileSystems.getDefault().getPath(this.jsonKeyPath).normalize();
        System.err.println(jsonKey.toFile().exists());
        GoogleCredential cred = GoogleCredential.fromStream(new FileInputStream(jsonKey.toFile()));
        cred = cred.createScoped(Collections.singleton(AndroidPublisherScopes.ANDROIDPUBLISHER));

        // load apk file info
        System.out.println("Loading apk file information...");
        Path apkFile = FileSystems.getDefault().getPath(this.apkPath).normalize();
        ApkFile apkInfo = new ApkFile(apkFile.toFile());
        ApkMeta apkMeta = apkInfo.getApkMeta();
        final String applicationName = this.appName == null ? apkMeta.getName() : this.appName;
        final String packageName = apkMeta.getPackageName();
        System.out.println(String.format("AndroidPublisher Name: %s", apkMeta.getName()));
        System.out.println(String.format("AndroidPublisher Id: %s", apkMeta.getPackageName()));
        System.out.println(String.format("AndroidPublisher Version Code: %d", apkMeta.getVersionCode()));
        System.out.println(String.format("AndroidPublisher Version Name: %s", apkMeta.getVersionName()));
        apkInfo.close();

        // load release notes
        System.out.println("Loading release notes...");
        List<LocalizedText> releaseNotes = new ArrayList<>();
        if (this.notesPath != null) {
            Path notesFile = FileSystems.getDefault().getPath(this.notesPath).normalize();
            String notesContent = new String(Files.readAllBytes(notesFile));
            releaseNotes.add(new LocalizedText().setLanguage(Locale.US.toString()).setText(notesContent));
        } else if (this.notes != null) {
            releaseNotes.add(new LocalizedText().setLanguage(Locale.US.toString()).setText(this.notes));
        }

        // init publisher
        System.out.println("Initialising publisher service...");
        com.google.api.services.androidpublisher.AndroidPublisher.Builder ab = new com.google.api.services.androidpublisher.AndroidPublisher.Builder(cred.getTransport(), cred.getJsonFactory(), cred);
        com.google.api.services.androidpublisher.AndroidPublisher publisher = ab.setApplicationName(applicationName).build();

        // create an edit
        System.out.println("Initialising new edit...");
        AppEdit edit = publisher.edits().insert(packageName, null).execute();
        final String editId = edit.getId();
        System.out.println(String.format("Edit created. Id: %s", editId));

        try {
            // publish the apk
            System.out.println("Uploading apk file...");
            AbstractInputStreamContent apkContent = new FileContent(MIME_TYPE_APK, apkFile.toFile());
            Apk apk = publisher.edits().apks().upload(packageName, editId, apkContent).execute();
            System.out.println(String.format("Apk uploaded. Version Code: %s", apk.getVersionCode()));

            // create a release on track
            System.out.println(String.format("On track:%s. Creating a release...", this.trackName));
            TrackRelease release = new TrackRelease().setName("Automated publish").setStatus("completed")
                    .setVersionCodes(Collections.singletonList((long) apk.getVersionCode()))
                    .setReleaseNotes(releaseNotes);
            Track track = new Track().setReleases(Collections.singletonList(release));
            publisher.edits().tracks().update(packageName, editId, this.trackName, track).execute();
            System.out.println(String.format("Release created on track: %s", this.trackName));

            // commit edit
            System.out.println("Committing edit...");
            publisher.edits().commit(packageName, editId).execute();
            System.out.println(String.format("Success. Committed Edit id: %s", editId));

            // Success
        } catch (Exception e) {
            // error message
            String msg = "Operation Failed: " + e.getMessage();

            // abort
            System.err.println("Opertaion failed due to an error!, Deleting edit...");
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

    /**
     * Construct localized version on message
     *
     * @param message message
     * @return localized version
     */
    private Localizable localize(String message) {
        return new Localizable() {

            @Override
            public String formatWithLocale(Locale locale, Object... args) {
                return String.format(locale, message, args);
            }

            @Override
            public String format(Object... args) {
                return String.format(message, args);
            }
        };
    }
}