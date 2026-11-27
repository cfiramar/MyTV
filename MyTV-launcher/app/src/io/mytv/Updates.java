package io.mytv;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.PersistableBundle;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Self-Updating OTA Manager for MyTV.
 *
 * <p><b>Why:</b> Supports automatic self-updates directly from a configured GitHub repository
 * without requiring a computer to be turned on.
 *
 * <p><b>What:</b> Periodically (every 12 hours) and on-demand checks {@code /repos/{owner}/{repo}/releases/latest}
 * using an optional Fine-Grained Personal Access Token ({@code Contents: Read-only}) stored encrypted in
 * {@link Secrets} for private repositories, downloads {@code MyTV.apk} via the GitHub Release Asset API,
 * verifies SHA-256 digest and signing certificate continuity against the installed APK, and triggers installation.
 */
final class Updates {
    private Updates() {}

    static final String LOCKED_GITHUB_REPO = "";
    private static final String ASSET_PRIMARY = "MyTV.apk";
    private static final long MAX_SIZE = 40L << 20;   // up to 40 MB
    private static final long CHECK_INTERVAL_MS = 12 * 3600_000L; // every 12 hours
    static final int JOB = 2900;
    static final String ACTION = "io.mytv.UPDATE_STATUS";

    /** Validates an {@code owner/repo} slug to block path traversal or arbitrary URL injection. */
    static boolean isValidRepoSlug(String repo) {
        if (repo == null) return false;
        String clean = repo.trim();
        return clean.matches("^[A-Za-z0-9_.-]{1,64}/[A-Za-z0-9_.-]{1,100}$")
                && !clean.contains("..");
    }

    static final String CHECKING = "checking", CURRENT = "current", BUILD = "build", DOWNLOADING = "downloading",
            READY = "ready", FAILED = "failed";
    private static final String OFFLINE = "offline", OTHER = "other";

    /** All MyTV builds signed with the user's keystore can receive self-updates signed by the same keystore. */
    static boolean takesReleases(Context c) {
        return true;
    }

    /** Schedules the periodic 12-hour background update check job. */
    static void schedule(Context c) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(JOB) != null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("action", ModeJob.UPDATE);
        scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(c, ModeJob.class)).setExtras(extras)
                .setPeriodic(CHECK_INTERVAL_MS).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setBackoffCriteria(30 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }

    // MARK: - State

    private static File stateFile(Context c) {
        return new File(c.getFilesDir(), "update.json");
    }

    private static File dir(Context c) {
        return new File(c.getNoBackupFilesDir(), "update");
    }

    static JSONObject state(Context c) {
        try {
            return new JSONObject(text(stateFile(c)));
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    private static void save(Context c, JSONObject o) {
        File f = stateFile(c), tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    private static void set(Context c, String state, String version, String error) {
        set(c, state, version, error, null, null);
    }

    private static void set(Context c, String state, String version, String error, String key, String value) {
        JSONObject o = state(c);
        try {
            o.put("state", state).put("version", version).put("error", error).put("at", System.currentTimeMillis());
            if (key != null) o.put(key, value);
        } catch (org.json.JSONException ignored) {
        }
        save(c, o);
    }

    static boolean busy(JSONObject o) {
        String state = o.optString("state");
        return (CHECKING.equals(state) || DOWNLOADING.equals(state)) && System.currentTimeMillis() - o.optLong("at") < 2 * 60_000L;
    }

    static boolean ready(JSONObject o, String installed) {
        return READY.equals(o.optString("state")) && compare(o.optString("version"), installed) > 0;
    }

    static String summary(JSONObject o, String installed) {
        String v = o.optString("version");
        if (READY.equals(o.optString("state")) && !ready(o, installed)) return Ui.tr("Up to date");
        switch (o.optString("state")) {
            case CHECKING: return busy(o) ? Ui.tr("Checking…") : Ui.tr("Check now");
            case DOWNLOADING: return busy(o) ? Ui.tr("Downloading…") : Ui.tr("Check now");
            case CURRENT: return System.currentTimeMillis() - o.optLong("at") < CHECK_INTERVAL_MS + 3600_000L ? Ui.tr("Up to date") : Ui.tr("Check now");
            case BUILD: return Ui.trf("{version} is out: build it again", "version", v);
            case READY: return Ui.trf("Install {version}", "version", v);
            case FAILED: return OFFLINE.equals(o.optString("error")) ? Ui.tr("No connection") : Ui.tr("Couldn't update");
            default: return Ui.tr("Check now");
        }
    }

    private static long seen;

    static void tellOnce(Activity a) {
        long changed = stateFile(a).lastModified();
        if (changed == seen) return;
        seen = changed;
        JSONObject o = state(a);
        String v = o.optString("version");
        File told = new File(a.getFilesDir(), "update-told");
        if (!ready(o, installedVersion(a)) || v.equals(text(told))) return;
        try (OutputStream out = new FileOutputStream(told)) {
            out.write(v.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        Toast.makeText(a, Ui.trf("MyTV {version} is ready: Home Settings > Updates.", "version", v), Toast.LENGTH_LONG).show();
    }

    /**
     * Reads the GitHub PAT from {@link Secrets} first, auto-migrating any legacy {@link Prefs#githubToken()}
     * value into hardware-encrypted storage and clearing it from plaintext SharedPreferences.
     */
    static String resolveGithubToken(Context c) {
        String secretToken = Secrets.get(c, Secrets.GITHUB_TOKEN);
        if (secretToken != null && !secretToken.trim().isEmpty()) {
            return secretToken.trim();
        }
        try {
            if (c != null) {
                Prefs.init(c);
                String legacy = Prefs.githubToken();
                if (legacy != null && !legacy.trim().isEmpty()) {
                    String clean = legacy.trim();
                    if (Secrets.putSecret(c, Secrets.GITHUB_TOKEN, clean)) {
                        Prefs.setGithubToken("");
                    }
                    return clean;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    // MARK: - Check and download (Supports Private & Public GitHub Repos)

    static synchronized boolean check(Context c) {
        Prefs.init(c);
        JSONObject before = state(c);
        String version = "";
        try {
            set(c, CHECKING, "", "");
            String repo = Prefs.githubRepo();
            if (!isValidRepoSlug(repo)) {
                set(c, CURRENT, installedVersion(c), "");
                return false;
            }
            String token = resolveGithubToken(c);
            String latestUrl = "https://api.github.com/repos/" + repo.trim() + "/releases/latest";
            JSONObject release = new JSONObject(getJson(latestUrl, token));
            version = release.optString("tag_name").replaceFirst("^[vV]", "");
            if (version.isEmpty()) throw new IOException("the latest release has no tag");
            String installed = installedVersion(c);
            AppLog.w("update: latest release " + version + ", installed " + installed);
            if (compare(version, installed) <= 0) {
                set(c, CURRENT, version, "");
                return false;
            }
            JSONObject asset = null;
            JSONArray assets = release.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject cand = assets.getJSONObject(i);
                String assetName = cand.optString("name");
                if (ASSET_PRIMARY.equals(assetName) || assetName.endsWith(".apk")) {
                    asset = cand;
                    if (ASSET_PRIMARY.equals(assetName)) break;
                }
            }
            if (asset == null) throw new IOException("No APK asset in release " + version);
            // For private GitHub repos, asset.optString("url") with Accept: application/octet-stream streams the binary;
            // for public repos without a token, browser_download_url works directly.
            String apiAssetUrl = asset.optString("url");
            String browserUrl = asset.optString("browser_download_url");
            String downloadUrl = (!token.isEmpty() && !apiAssetUrl.isEmpty()) ? apiAssetUrl : browserUrl;
            String digest = asset.optString("digest");
            long size = asset.optLong("size");
            if (downloadUrl.isEmpty() || size <= 0 || size > MAX_SIZE) {
                throw new IOException("unexpected APK asset: " + downloadUrl + ", " + size + " bytes");
            }
            String name = "MyTV-" + version + "-" + Integer.toHexString((digest + size).hashCode()) + ".apk";
            if (name.equals(before.optString("refused"))) {
                set(c, FAILED, version, OTHER);
                return false;
            }
            File apk = new File(dir(c), name);
            if (!apk.exists()) {
                set(c, DOWNLOADING, version, "");
                download(c, version, downloadUrl, token, digest, size, apk);
                AppLog.w("update: downloaded " + version + " (" + apk.length() / 1024 + " KB)");
            }
            String wrong = misfit(c, apk);
            if (wrong != null) {
                AppLog.w("update: " + version + " APK " + wrong);
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                set(c, FAILED, version, OTHER, "refused", name);
                return false;
            }
            deleteDownloads(c, apk);
            set(c, READY, version, "", "file", name);
            AppLog.w("update: " + version + " ready — triggering auto-install");
            install(c);
            return false;
        } catch (Exception e) {
            AppLog.w("update: " + e);
            if (ready(before, installedVersion(c)) && new File(dir(c), before.optString("file")).isFile()) {
                save(c, before);
            } else {
                set(c, FAILED, version, offline(e) ? OFFLINE : OTHER);
            }
            return true;
        }
    }

    private static boolean offline(Exception e) {
        return e instanceof java.net.UnknownHostException || e instanceof java.net.SocketException
                || e instanceof java.net.SocketTimeoutException || e instanceof javax.net.ssl.SSLException;
    }

    private static void deleteDownloads(Context c, File keep) {
        File[] files = dir(c).listFiles();
        for (int i = 0; files != null && i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (!files[i].equals(keep)) files[i].delete();
        }
    }

    static synchronized void cleanUp(Context c) {
        deleteDownloads(c, null);
        JSONObject o = state(c);
        String installed = installedVersion(c);
        if (READY.equals(o.optString("state")) && compare(o.optString("version"), installed) <= 0) set(c, CURRENT, installed, "");
        else save(c, new JSONObject());
    }

    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static int compare(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Downloads the release APK asset, supporting both public URLs and private GitHub Release Asset API URLs
     * (following 302 redirects to pre-signed storage URLs without forwarding the GitHub Authorization header).
     */
    private static void download(Context c, String version, String url, String token, String digest, long size, File to) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        to.getParentFile().mkdirs();
        File part = new File(to.getPath() + ".part");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long total = 0, said = System.currentTimeMillis();
        HttpURLConnection h = openAssetConnection(url, token);
        try {
            if (h.getResponseCode() != 200) throw new IOException("GitHub returned HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > size) throw new IOException("the download is larger than GitHub says");
                    sha.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                    if (System.currentTimeMillis() - said > 5000) {
                        said = System.currentTimeMillis();
                        set(c, DOWNLOADING, version, "");
                    }
                }
            }
            if (total != size || digest.startsWith("sha256:") && !digest.substring(7).equalsIgnoreCase(hex(sha.digest()))) {
                throw new IOException("the download doesn't match GitHub's size or SHA-256");
            }
            if (!part.renameTo(to)) throw new IOException("couldn't save the update");
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            h.disconnect();
        }
    }

    /**
     * Verifies the downloaded APK matches our package name, is newer than the installed version,
     * and is signed with the same certificate as the currently installed MyTV APK.
     */
    @SuppressWarnings("deprecation")
    private static String misfit(Context c, File apk) throws PackageManager.NameNotFoundException {
        PackageManager pm = c.getPackageManager();
        PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        PackageInfo ours = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
        if (theirs == null) return "can't be read";
        if (!c.getPackageName().equals(theirs.packageName)) return "is another app, " + theirs.packageName;
        String name = theirs.versionName == null ? "" : theirs.versionName;
        if (theirs.versionCode < ours.versionCode || compare(name, ours.versionName) <= 0) {
            return "isn't newer than this app: " + name + " (" + theirs.versionCode + ")";
        }
        String theirCert = cert(theirs);
        String ourCert = cert(ours);
        if (theirCert != null && ourCert != null && !ourCert.equals(theirCert)) {
            return "isn't signed with the same key as this installed MyTV app";
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static String cert(PackageInfo p) {
        Signature[] s = p == null ? null : p.signatures;
        if (s == null || s.length != 1) return null;
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s[0].toByteArray()));
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    // MARK: - Install

    static synchronized void install(Context c) {
        JSONObject o = state(c);
        String version = o.optString("version"), name = o.optString("file");
        if (!ready(o, installedVersion(c))) return;
        File apk = new File(dir(c), name);
        if (name.isEmpty() || !apk.isFile()) {
            check(c);
            return;
        }
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        try {
            for (PackageInstaller.SessionInfo s : installer.getMySessions()) installer.abandonSession(s.getSessionId());
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());
            params.setSize(apk.length());
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite(ASSET_PRIMARY, 0, apk.length())) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    session.fsync(out);
                }
                Intent done = new Intent(c, HomeReceiver.class).setAction(ACTION).putExtra("version", version);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                session.commit(PendingIntent.getBroadcast(c, id, done, flags).getIntentSender());
            }
            AppLog.w("update: installing " + version);
        } catch (Exception e) {
            AppLog.w("update install: " + e);
            set(c, FAILED, version, OTHER);
        }
    }

    static void status(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String version = intent.getStringExtra("version");
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                AppLog.w("update: Android's installer didn't open: " + e);
                failed(c, version);
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            AppLog.w("update: not installed (" + status + "): " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) failed(c, version);
        }
    }

    private static synchronized void failed(Context c, String version) {
        set(c, FAILED, version == null ? "" : version, OTHER);
    }

    // MARK: - HTTP (Authenticated Private & Public GitHub API)

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "MyTV");
        return h;
    }

    private static HttpURLConnection openAssetConnection(String url, String token) throws IOException {
        HttpURLConnection h = open(url);
        h.setInstanceFollowRedirects(false);
        h.setRequestProperty("Accept", "application/octet-stream");
        if (token != null && !token.isEmpty()) {
            h.setRequestProperty("Authorization", "Bearer " + token);
        }
        int code = h.getResponseCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            String location = h.getHeaderField("Location");
            h.disconnect();
            if (location == null || location.isEmpty()) throw new IOException("Missing redirect Location from GitHub");
            HttpURLConnection redirected = open(location);
            redirected.setInstanceFollowRedirects(true);
            return redirected;
        }
        return h;
    }

    private static String getJson(String url, String token) throws IOException {
        HttpURLConnection h = open(url);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        if (token != null && !token.isEmpty()) {
            h.setRequestProperty("Authorization", "Bearer " + token);
        }
        try {
            if (h.getResponseCode() != 200) throw new IOException("GitHub returned HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream()) {
                return read(in);
            }
        } finally {
            h.disconnect();
        }
    }

    private static String text(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return read(in);
        } catch (IOException e) {
            return "";
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
