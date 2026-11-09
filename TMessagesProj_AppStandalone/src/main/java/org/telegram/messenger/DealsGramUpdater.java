package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.web.HttpGetFileTask;
import org.telegram.ui.web.HttpGetTask;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * DealsGram: Telegram's beta updater (TMessagesProj_AppHockeyApp/DealsGramUpdater) reading our own file:
 * {"version": "13.1.1", "version_code": 7237019, "file_url": "https://dealsgram.app/apk/...", "changelog": "..."}.
 * The update file is trusted only when its file_url is an HTTPS APK under dealsgram.app/apk/, and the downloaded
 * file is offered for installation only when it is this app's package signed like the installed app (a different
 * package would install as a new app, which Android does not stop).
 */
public class DealsGramUpdater {

    public static final String UPDATE_URL = "https://dealsgram.app/update.json";
    private static final String APK_ORIGIN = "https://dealsgram.app";
    private static final Pattern APK_PATH = Pattern.compile("^/apk/[A-Za-z0-9_.-]+\\.apk$");


    private static DealsGramUpdater instance;
    public static DealsGramUpdater getInstance() {
        if (instance == null) {
            instance = new DealsGramUpdater();
        }
        return instance;
    }

    private String version;
    private int versionCode;
    private String changelog;
    private String path;
    private long lastCheck;

    private String fileUrl;

    public DealsGramUpdater() {
        load();
    }

    private SharedPreferences getSharedPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("dealsgram_update", Activity.MODE_PRIVATE);
    }

    private void load() {
        final SharedPreferences prefs = getSharedPreferences();

        version = prefs.getString("version", null);
        versionCode = prefs.getInt("versionCode", 0);
        changelog = prefs.getString("changelog", null);
        path = prefs.getString("path", null);
        lastCheck = prefs.getLong("lastCheck", 0L);

        if (getCurrentVersionCode() >= versionCode || !TextUtils.isEmpty(path) && !new File(path).exists()) {
            version = null;
            versionCode = 0;
            path = null;
            changelog = null;
            lastCheck = 0;
            save();
        }
    }

    private void save() {
        final SharedPreferences.Editor e = getSharedPreferences().edit();
        if (TextUtils.isEmpty(version)) {
            e.remove("version");
        } else {
            e.putString("version", version);
        }
        if (TextUtils.isEmpty(changelog)) {
            e.remove("changelog");
        } else {
            e.putString("changelog", changelog);
        }
        if (versionCode == 0) {
            e.remove("versionCode");
        } else {
            e.putInt("versionCode", versionCode);
        }
        if (TextUtils.isEmpty(path)) {
            e.remove("path");
        } else {
            e.putString("path", path);
        }
        if (lastCheck == 0) {
            e.remove("lastCheck");
        } else {
            e.putLong("lastCheck", lastCheck);
        }
        e.apply();
    }

    private final static long CHECK_INTERVAL_PAUSED = 1000 * 60 * 60 * 24; // 1 day
    private final static long CHECK_INTERVAL = 1000 * 60 * 20; // 20 minutes
    private final static long CHECK_INTERVAL_PRIVATE = 1000 * 60 * 4; // 5 minutes

    private boolean firstCheck = true;
    private boolean checkingForUpdate;
    private final Runnable scheduledUpdateCheck = () -> checkForUpdate(false, null);
    public void checkForUpdate(boolean force, Runnable whenDone) {
        if (checkingForUpdate) return;

        if (firstCheck) {
            force = true;
        }
        if (!force && System.currentTimeMillis() - lastCheck < (ApplicationLoader.mainInterfacePaused ? CHECK_INTERVAL_PAUSED : (BuildVars.DEBUG_PRIVATE_VERSION ? CHECK_INTERVAL_PRIVATE : CHECK_INTERVAL))) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }

        final String url = UPDATE_URL;
        checkingForUpdate = true;
        firstCheck = false;
        new HttpGetTask(str -> AndroidUtilities.runOnUIThread(() -> {
            checkingForUpdate = false;
            try {
                final JSONObject json = new JSONObject(str);
                final String newVersion = json.getString("version");
                final int newVersionCode = json.getInt("version_code");
                final String fileUrl = trustedApkUrl(json.getString("file_url"));
                if (fileUrl == null) {
                    throw new IllegalArgumentException("file_url is not an APK on " + APK_ORIGIN + "/apk/");
                }
                final String changelog = json.optString("changelog", null);

                final int oldVersionCode = this.versionCode;

                if (
                    (version == null || SharedConfig.versionBiggerOrEqual(newVersion, version) && newVersionCode > versionCode) &&
                    SharedConfig.versionBiggerOrEqual(newVersion, getCurrentVersion()) && newVersionCode > getCurrentVersionCode()
                ) { // received newer version
                    if (!TextUtils.isEmpty(path)) {
                        final File file = new File(path);
                        try {
                            file.delete();
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                    path = null;
                    version = newVersion;
                    versionCode = newVersionCode;
                    this.fileUrl = fileUrl;
                    this.changelog = changelog;
                } else if (
                    version != null && versionCode != 0 && SharedConfig.versionBiggerOrEqual(version, newVersion) && versionCode == newVersionCode
                ) { // received the same version
                    this.fileUrl = fileUrl;
                    this.changelog = changelog;
                } else { // received lower version: remove update
                    if (!TextUtils.isEmpty(path)) {
                        final File file = new File(path);
                        try {
                            file.delete();
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                    path = null;
                    if (SharedConfig.versionBiggerOrEqual(getCurrentVersion(), newVersion) && getCurrentVersionCode() < newVersionCode) {
                        // remote version is still newer than current installed, even though local downloaded was higher
                        version = newVersion;
                        versionCode = newVersionCode;
                        this.fileUrl = fileUrl;
                        this.changelog = changelog;
                    } else {
                        // remove version is the same or less than current installed
                        version = null;
                        versionCode = 0;
                        this.fileUrl = null;
                        this.changelog = null;
                    }
                }

                this.lastCheck = System.currentTimeMillis();
                save();

                if (this.versionCode != oldVersionCode) {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                }

                AndroidUtilities.cancelRunOnUIThread(this.scheduledUpdateCheck);
                AndroidUtilities.runOnUIThread(this.scheduledUpdateCheck, BuildVars.DEBUG_PRIVATE_VERSION ? CHECK_INTERVAL_PRIVATE : CHECK_INTERVAL);
                if (whenDone != null) {
                    whenDone.run();
                } else if (this.versionCode != oldVersionCode && !ApplicationLoader.mainInterfacePaused) {
                    final Context context = LaunchActivity.instance != null ? LaunchActivity.instance : ApplicationLoader.applicationContext;
                    final BetaUpdate pendingUpdate = getUpdate();
                    if (context != null && pendingUpdate != null) {
                        ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(context, pendingUpdate, UserConfig.selectedAccount);
                    }
                }
            } catch (Exception e) {
                FileLog.e("Failed to check for DealsGram update at " + url, e);
            }
        })).execute(url);
    }

    public BetaUpdate getUpdate() {
        if (version == null || versionCode == 0) {
            return null;
        }
        return new BetaUpdate(version, versionCode, changelog);
    }

    private boolean downloading;
    private float downloadingProgress;
    private HttpGetFileTask downloadingTask;
    public void downloadUpdate() {
        downloadUpdate(false);
    }
    private void downloadUpdate(boolean triedGettingFileUrl) {
        if (downloading || !TextUtils.isEmpty(path)) return;

        downloading = true;
        downloadingProgress = 0.0f;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);

        if (TextUtils.isEmpty(fileUrl)) {
            if (!triedGettingFileUrl) {
                checkForUpdate(true, () -> downloadUpdate(true));
            } else {
                downloading = false;
            }
            return;
        }

        downloadingTask = new HttpGetFileTask(
            downloadedFile -> {
                if (downloadedFile == null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        downloading = false;
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                    });
                    return;
                }
                // Reading the APK's certificates hashes the whole file: off the UI thread.
                Utilities.globalQueue.postRunnable(() -> {
                    final boolean trusted = isTrustedApk(downloadedFile);
                    if (!trusted) {
                        FileLog.e("DealsGram update rejected: the downloaded APK is not this app signed with its key");
                        try {
                            downloadedFile.delete();
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        if (trusted) {
                            if (!TextUtils.isEmpty(path)) {
                                final File file = new File(path);
                                try {
                                    file.delete();
                                } catch (Exception e) {
                                    FileLog.e(e);
                                }
                            }
                            path = downloadedFile.getAbsolutePath();
                            save();
                            downloadingProgress = 1.0f;
                        }
                        downloading = false;
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                    });
                });
            },
            progress -> {
                downloadingProgress = progress;
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
            }
        ).setOverrideExtension("apk");
        downloadingTask.execute(fileUrl);
    }
    public void cancelDownloadingUpdate() {
        if (!downloading) return;
        if (downloadingTask != null) {
            downloadingTask.cancel(false);
        }
        downloading = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    public boolean isDownloading() {
        return downloading;
    }

    public float getDownloadingProgress() {
        return downloadingProgress;
    }

    public File getDownloadedFile() {
        if (path == null)
            return null;
        final File file = new File(path);
        if (!file.exists()) {
            path = null;
            save();
            return null;
        }
        return file;
    }

    /**
     * The download address rebuilt from its parts, or null unless it is an APK under https://dealsgram.app/apk/
     * (the same rule as the site's Download button). Rebuilding avoids any difference between how this check and
     * the downloader would read an unusual address (user info, ports, encoded characters).
     */
    static String trustedApkUrl(String value) {
        if (value == null) {
            return null;
        }
        try {
            final URI uri = new URI(value.trim());
            final String path = uri.getRawPath();
            if (!"https".equals(uri.getScheme()) || !"dealsgram.app".equals(uri.getHost()) || uri.getPort() != -1
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || path == null || !APK_PATH.matcher(path).matches()) {
                return null;
            }
            return APK_ORIGIN + path;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True when the downloaded file is this app's package signed with the same key as the installed app. Android
     * already refuses an update of the same package signed with another key; what it would accept is an APK with a
     * different package, installed next to DealsGram as a new app. When a device cannot read the archive's
     * certificates, the package check alone remains and Android enforces the key on install.
     */
    private static boolean isTrustedApk(File file) {
        try {
            final Context context = ApplicationLoader.applicationContext;
            final PackageManager pm = context.getPackageManager();
            final String packageName = context.getPackageName();
            final String apk = file.getAbsolutePath();
            Signature[] downloaded = null;
            Signature[] installed = null;
            PackageInfo archive;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archive = pm.getPackageArchiveInfo(apk, PackageManager.GET_SIGNING_CERTIFICATES);
                final PackageInfo current = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                if (archive != null && archive.signingInfo != null && current.signingInfo != null) {
                    downloaded = archive.signingInfo.getApkContentsSigners();
                    installed = current.signingInfo.getApkContentsSigners();
                }
            } else {
                archive = pm.getPackageArchiveInfo(apk, PackageManager.GET_SIGNATURES);
                if (archive != null) {
                    downloaded = archive.signatures;
                    installed = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures;
                }
            }
            if (archive == null || !packageName.equals(archive.packageName)) {
                return false;
            }
            if (downloaded == null || downloaded.length == 0 || installed == null || installed.length == 0) {
                return true;
            }
            return new HashSet<>(Arrays.asList(downloaded)).equals(new HashSet<>(Arrays.asList(installed)));
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private String getCurrentVersion() {
        try {
            return ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionName;
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }

    private int getCurrentVersionCode() {
        try {
            return ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

}
