package jp.beeflat.beetransceiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Button;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Checks the latest signed GitHub release, verifies APK hash, then hands
 * installation to Android. Never silently installs or removes existing apps.
 */
final class AppUpdater {
    private static final String REPO = "beeflat-dotcom/BeeTransceiver";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String RELEASE_PREFIX =
            "https://github.com/" + REPO + "/releases/download/";
    private static final long MAX_APK_BYTES = 90L * 1024L * 1024L;
    private static final String LEGACY_API =
            "https://api.github.com/repos/beeflat-dotcom/mywatch-home2/releases?per_page=40";
    private static final String LEGACY_RELEASE_PREFIX =
            "https://github.com/beeflat-dotcom/mywatch-home2/releases/download/";
    private static final String LEGACY_TAG_PREFIX = "bee-ptt-net-v";
    private static final String LEGACY_ASSET = "BeeTransceiver-NET.apk";

    static void check(Activity activity, Button button) {
        if (BuildConfig.BEE_LEGACY_NET_CHANNEL) {
            checkLegacy(activity, button);
            return;
        }
        button.setEnabled(false);
        button.setText("更新情報を確認中…");
        new Thread(() -> {
            try {
                JSONObject release = getJson(API);
                JSONArray assets = release.getJSONArray("assets");
                String manifestUrl = findAsset(assets, "update.json");
                if (manifestUrl == null) throw new Exception("更新情報がまだ公開されていません");
                JSONObject manifest = getJson(manifestUrl);
                int code = manifest.getInt("versionCode");
                String pkg = manifest.getString("packageName");
                String versionName = manifest.getString("versionName");
                String expectedHash = manifest.getString("sha256").toLowerCase(Locale.ROOT);
                String apkName = manifest.getString("apkAsset");

                if (!pkg.equals(activity.getPackageName())) {
                    throw new Exception("このアプリ版用の更新はまだありません");
                }
                if (!expectedHash.matches("[0-9a-f]{64}")) throw new Exception("更新ファイルの検証情報が不正です");
                if (!apkName.matches("[a-zA-Z0-9._-]+\\.apk")) throw new Exception("更新ファイル名が不正です");

                String apkUrl = findAsset(assets, apkName);
                if (apkUrl == null) throw new Exception("更新APKが見つかりません");
                if (code <= BuildConfig.VERSION_CODE) {
                    activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                            .setTitle("更新はありません")
                            .setMessage("現在の版は " + BuildConfig.VERSION_NAME + " です。")
                            .setPositiveButton("OK", null).show());
                    return;
                }

                activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                        .setTitle("新しいバージョン " + versionName)
                        .setMessage("最新版をダウンロードして更新しますか？\n"
                                + "現在の端末名やペアリング情報は、同一アプリへの上書き更新なら引き継がれます。")
                        .setNegativeButton("あとで", null)
                        .setPositiveButton("ダウンロード", (dialog, which) ->
                                download(activity, button, apkUrl, expectedHash, code))
                        .show());
            } catch (Exception e) {
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                        .setTitle("更新情報")
                        .setMessage(detail).setPositiveButton("OK", null).show());
            } finally {
                activity.runOnUiThread(() -> {
                    button.setEnabled(true);
                    button.setText("更新を確認");
                });
            }
        }, "BeeUpdateCheck").start();
    }

    /** Original NET channel; the on-device updater already checks this location. */
    private static void checkLegacy(Activity activity, Button button) {
        button.setEnabled(false);
        button.setText("更新情報を確認中…");
        new Thread(() -> {
            try {
                JSONArray releases = getJsonArray(LEGACY_API);
                String newestVersion = null;
                String newestUrl = null;
                String newestHash = null;
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject rel = releases.getJSONObject(i);
                    if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue;
                    String tag = rel.optString("tag_name", "");
                    if (!tag.startsWith(LEGACY_TAG_PREFIX)) continue;
                    String version = tag.substring(LEGACY_TAG_PREFIX.length());
                    if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) continue;
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets == null) continue;
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject asset = assets.getJSONObject(j);
                        if (!LEGACY_ASSET.equals(asset.optString("name"))) continue;
                        String url = asset.optString("browser_download_url");
                        String digest = asset.optString("digest", "").toLowerCase(Locale.ROOT);
                        if (!url.startsWith(LEGACY_RELEASE_PREFIX)
                                || !digest.matches("sha256:[0-9a-f]{64}")) {
                            throw new Exception("旧通常版の更新情報が不正です");
                        }
                        if (newestVersion == null || compareVersions(version, newestVersion) > 0) {
                            newestVersion = version;
                            newestUrl = url;
                            newestHash = digest.substring(7);
                        }
                    }
                }
                if (newestVersion == null) {
                    throw new Exception("現在の通常版に対応した更新APKは未公開です");
                }
                if (compareVersions(newestVersion, BuildConfig.VERSION_NAME) <= 0) {
                    activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                            .setTitle("更新はありません")
                            .setMessage("現在の版は " + BuildConfig.VERSION_NAME + " です。")
                            .setPositiveButton("OK", null).show());
                    return;
                }
                final String version = newestVersion;
                final String url = newestUrl;
                final String hash = newestHash;
                activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                        .setTitle("通常版の更新 " + version)
                        .setMessage("同じアプリID・署名の更新APKだけをインストールします。")
                        .setNegativeButton("あとで", null)
                        .setPositiveButton("更新する", (d,w) ->
                                download(activity, button, url, hash, -1))
                        .show());
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                        .setTitle("通常版の更新情報")
                        .setMessage(reason).setPositiveButton("OK", null).show());
            } finally {
                activity.runOnUiThread(() -> {
                    button.setEnabled(true);
                    button.setText("更新を確認");
                });
            }
        }, "BeeLegacyNetUpdate").start();
    }

    private static int compareVersions(String a, String b) {
        String[] aa = a.split("\\.");
        String[] bb = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int x = i < aa.length ? Integer.parseInt(aa[i]) : 0;
            int y = i < bb.length ? Integer.parseInt(bb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static JSONArray getJsonArray(String address) throws Exception {
        HttpURLConnection c = connection(address);
        try {
            if (c.getResponseCode() != 200)
                throw new Exception("更新一覧の取得に失敗しました (HTTP " + c.getResponseCode() + ")");
            if (c.getContentLengthLong() > 1024 * 1024L)
                throw new Exception("更新一覧のサイズが不正です");
            try (InputStream input = c.getInputStream()) {
                byte[] bytes = new byte[1024 * 1024 + 1];
                int used = 0, n;
                while (used < bytes.length
                        && (n = input.read(bytes, used, bytes.length - used)) > 0) used += n;
                if (used >= bytes.length) throw new Exception("更新一覧が大きすぎます");
                return new JSONArray(new String(bytes, 0, used, StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    private static void download(Activity activity, Button button, String url, String expectedHash,
                                 int expectedVersionCode) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("インストールの許可")
                    .setMessage("Androidの設定で、このアプリからの更新インストールを許可してください。"
                            + "設定後、もう一度「更新を確認」を押してください。")
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("設定を開く", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(intent);
                    }).show();
            return;
        }
        button.setEnabled(false);
        button.setText("APKをダウンロード中…");
        new Thread(() -> {
            File apk = null;
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("保存フォルダを作成できません");
                apk = new File(dir, "BeeTransceiver-update.apk");
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                HttpURLConnection conn = connection(url);
                try {
                    if (conn.getResponseCode() != 200) throw new Exception("APKダウンロード失敗 HTTP " + conn.getResponseCode());
                    long advertised = conn.getContentLengthLong();
                    if (advertised > MAX_APK_BYTES) throw new Exception("更新ファイルが大きすぎます");
                    try (InputStream input = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(apk)) {
                        byte[] buffer = new byte[16384]; int n; long total=0;
                        while ((n = input.read(buffer)) != -1) {
                            total += n;
                            if (total > MAX_APK_BYTES) throw new Exception("更新ファイルが大きすぎます");
                            sha.update(buffer, 0, n);
                            out.write(buffer, 0, n);
                        }
                        if (total < 1024) throw new Exception("APKが正常に取得できませんでした");
                    }
                } finally {
                    conn.disconnect();
                }
                StringBuilder hex = new StringBuilder(64);
                for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
                if (!expectedHash.equals(hex.toString())) throw new Exception("APKのSHA-256が一致しません。更新を中止しました");
                // Check the actual APK identity before opening Android installer.
                // A matching filename/hash is not enough to preserve an installed app.
                verifyUpdateIdentity(activity, apk, expectedVersionCode);

                File readyApk=apk;
                activity.runOnUiThread(() -> {
                    try {
                        Uri uri = FileProvider.getUriForFile(activity,
                                activity.getPackageName() + ".fileprovider", readyApk);
                        Intent install = new Intent(Intent.ACTION_VIEW);
                        install.setDataAndType(uri, "application/vnd.android.package-archive");
                        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        activity.startActivity(install);
                    } catch (Exception ex) {
                        new AlertDialog.Builder(activity).setTitle("更新を開始できません")
                                .setMessage(ex.toString()).setPositiveButton("OK", null).show();
                    }
                });
            } catch (Exception ex) {
                if (apk != null) apk.delete();
                String message=ex.getMessage()==null?ex.toString():ex.getMessage();
                activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                        .setTitle("更新できませんでした")
                        .setMessage(message).setPositiveButton("OK", null).show());
            } finally {
                activity.runOnUiThread(() -> {
                    button.setEnabled(true);
                    button.setText("更新を確認");
                });
            }
        }, "BeeUpdateDownload").start();
    }

    /**
     * Never send an incompatible APK to Android's installer. Both the package
     * ID and the signing certificate must match the installed app.
     * This does not bypass Android's own signature verification.
     */
    private static void verifyUpdateIdentity(Activity activity, File apk,
                                             int expectedVersionCode) throws Exception {
        PackageManager manager = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = manager.getPackageInfo(activity.getPackageName(), flags);
        PackageInfo proposed = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (proposed == null) {
            throw new Exception("更新APKを解析できません。端末のアプリは変更していません");
        }
        if (!activity.getPackageName().equals(proposed.packageName)) {
            throw new Exception("更新APKが別アプリです。既存の登録を維持するため更新を中止しました");
        }
        long version = Build.VERSION.SDK_INT >= 28
                ? proposed.getLongVersionCode() : proposed.versionCode;
        if (expectedVersionCode > 0 && version != expectedVersionCode) {
            throw new Exception("更新情報とAPKのバージョンが一致しません");
        }
        long installedVersion = Build.VERSION.SDK_INT >= 28
                ? installed.getLongVersionCode() : installed.versionCode;
        if (version <= installedVersion) {
            throw new Exception("APKのバージョンが現在のアプリより新しくありません");
        }

        Signature[] oldSigners;
        Signature[] newSigners;
        if (Build.VERSION.SDK_INT >= 28) {
            oldSigners = installed.signingInfo == null ? null
                    : installed.signingInfo.getApkContentsSigners();
            newSigners = proposed.signingInfo == null ? null
                    : proposed.signingInfo.getApkContentsSigners();
        } else {
            oldSigners = installed.signatures;
            newSigners = proposed.signatures;
        }
        if (oldSigners == null || newSigners == null
                || oldSigners.length == 0 || oldSigners.length != newSigners.length) {
            throw new Exception("更新APKの署名を検証できません。更新を中止しました");
        }
        for (int i = 0; i < oldSigners.length; i++) {
            if (!MessageDigest.isEqual(oldSigners[i].toByteArray(), newSigners[i].toByteArray())) {
                throw new Exception("この更新APKは現在のアプリと署名が異なります。削除せず管理者に連絡してください");
            }
        }
    }

    private static String findAsset(JSONArray assets, String name) throws Exception {
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (name.equals(asset.optString("name"))) {
                String url = asset.getString("browser_download_url");
                if (!url.startsWith(RELEASE_PREFIX)) throw new Exception("GitHub以外の更新URLです");
                return url;
            }
        }
        return null;
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = connection(url);
        try {
            int status = c.getResponseCode();
            if (status == 404 && url.equals(API)) throw new Exception("正式版の更新はまだ公開されていません");
            if (status != 200) throw new Exception("更新情報の取得に失敗しました (HTTP " + status + ")");
            if (c.getContentLengthLong() > 1024 * 1024L) throw new Exception("更新情報のサイズが不正です");
            try (InputStream input = c.getInputStream()) {
                byte[] bytes = new byte[1024 * 1024 + 1];
                int used=0,n;
                while (used < bytes.length && (n=input.read(bytes,used,bytes.length-used))>0)used+=n;
                if (used >= bytes.length) throw new Exception("更新情報が大きすぎます");
                return new JSONObject(new String(bytes,0,used,StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection connection(String value) throws Exception {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new Exception("HTTPS以外は更新に使えません");
        if (!(url.getHost().equals("github.com") || url.getHost().equals("api.github.com"))) {
            throw new Exception("許可されていない配布元です");
        }
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "BeeTransceiver-Android");
        return c;
    }
    private AppUpdater() {}
}
