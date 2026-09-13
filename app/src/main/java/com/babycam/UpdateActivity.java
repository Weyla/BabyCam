package com.babycam;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-initiated GitHub updates. Only same-signer, newer BabyCam APKs reach the installer. */
public final class UpdateActivity extends Activity {
    private static final String LATEST = "https://api.github.com/repos/Weyla/BabyCam/releases/latest";
    private static final long MAX_APK_BYTES = 100 * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button action;
    private JSONObject asset;
    private File verifiedApk;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        status = new TextView(this);
        status.setTextSize(18);
        layout.addView(status);
        action = new Button(this);
        layout.addView(action);
        TextView help = new TextView(this);
        help.setText(R.string.update_help);
        layout.addView(help);
        setContentView(layout);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(layout, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            view.setPadding(padding + bars.left, padding + bars.top,
                    padding + bars.right, padding + bars.bottom);
            return insets;
        });
        check();
    }

    private void show(String message, String label, Runnable click) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            status.setText(message);
            action.setText(label);
            action.setEnabled(click != null);
            action.setOnClickListener(view -> { if (click != null) click.run(); });
        });
    }

    @SuppressWarnings("deprecation") // API 26 compatibility; exact signer matching is intentional.
    private PackageInfo installed() throws PackageManager.NameNotFoundException {
        return getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
    }

    private void check() {
        show("Checking GitHub…", "Checking…", null);
        worker.execute(() -> {
            try {
                HttpURLConnection connection = open(LATEST);
                JSONObject release;
                try (InputStream input = connection.getInputStream();
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > 1024 * 1024) throw new IOException("Release response too large");
                        output.write(buffer, 0, count);
                    }
                    release = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
                } finally { connection.disconnect(); }
                String current = installed().versionName;
                String next = release.getString("tag_name");
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                    throw new IOException("No stable release available");
                }
                if (!UpdateVersion.isNewer(next, current)) {
                    show("BabyCam " + current + " is up to date.", "Check again", this::check);
                    return;
                }
                JSONArray assets = release.getJSONArray("assets");
                JSONObject selected = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject item = assets.getJSONObject(i);
                    if (item.getString("name").matches("BabyCam-v[0-9.]+\\.apk")
                            && "uploaded".equals(item.optString("state"))) {
                        if (selected != null) throw new IOException("Release has multiple APKs");
                        selected = item;
                    }
                }
                if (selected == null) throw new IOException("Release APK is not available yet");
                asset = selected;
                show("BabyCam " + next + " is available. Installed: " + current,
                        "Download update", this::download);
            } catch (Exception error) { fail(error); }
        });
    }

    private void download() {
        show("Downloading update… Keep this screen open.", "Downloading…", null);
        worker.execute(() -> {
            File file = null;
            try {
                String address = asset.getString("browser_download_url");
                if (!address.startsWith("https://github.com/Weyla/BabyCam/releases/download/")) {
                    throw new IOException("Unexpected download address");
                }
                long expected = asset.getLong("size");
                if (expected <= 0 || expected > MAX_APK_BYTES) throw new IOException("Invalid APK size");
                String digest = asset.optString("digest");
                if (!digest.matches("sha256:[0-9a-f]{64}")) throw new IOException("Release checksum unavailable");
                File directory = new File(getCacheDir(), "updates");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create download folder");
                file = File.createTempFile("update-", ".apk", directory);
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                HttpURLConnection connection = open(address);
                long total = 0;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled");
                        total += count;
                        if (total > expected) throw new IOException("APK exceeds expected size");
                        output.write(buffer, 0, count);
                        sha.update(buffer, 0, count);
                    }
                } finally { connection.disconnect(); }
                StringBuilder actual = new StringBuilder("sha256:");
                for (byte value : sha.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                if (total != expected || !digest.equals(actual.toString())) throw new IOException("Download checksum mismatch");
                verify(file);
                verifiedApk = file;
                show("Update verified. Installation will interrupt monitoring.", "Install update", this::install);
            } catch (Exception error) {
                if (file != null && file.exists() && !file.delete()) {
                    android.util.Log.w("BabyCamUpdater", "Could not remove incomplete update");
                }
                fail(error);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void verify(File file) throws Exception {
        PackageInfo candidate = getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        PackageInfo current = installed();
        if (candidate == null || !getPackageName().equals(candidate.packageName)
                || candidate.versionCode <= current.versionCode
                || candidate.signatures == null || current.signatures == null
                || !Arrays.equals(candidate.signatures, current.signatures)) {
            throw new IOException("APK is incompatible: it must be a newer BabyCam build signed with the same key. Development builds cannot update to release builds.");
        }
    }

    private void install() {
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
                show("Allow updates from BabyCam, then return and tap Install update.", "Install update", this::install);
                return;
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", verifiedApk);
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                    "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception error) { fail(error); }
    }

    private void fail(Exception error) {
        show("Update failed: " + error.getMessage(),
                "Try again", this::check);
    }

    private static HttpURLConnection open(String address) throws IOException {
        for (int redirects = 0; redirects < 6; redirects++) {
            URL url = new URL(address);
            String host = url.getHost();
            if (!"https".equals(url.getProtocol()) || !(host.equals("api.github.com")
                    || host.equals("github.com") || host.equals("release-assets.githubusercontent.com")
                    || host.equals("objects.githubusercontent.com"))) throw new IOException("Untrusted update host");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "BabyCam-Updater");
            int code;
            try { code = connection.getResponseCode(); }
            catch (IOException error) { connection.disconnect(); throw error; }
            if (code == 200) return connection;
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (code >= 300 && code < 400 && location != null) address = new URL(url, location).toString();
            else throw new IOException("GitHub returned HTTP " + code);
        }
        throw new IOException("Too many download redirects");
    }

    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
