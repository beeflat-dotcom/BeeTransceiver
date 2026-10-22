package jp.beeflat.beetransceiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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

    static void check(Activity activity, Button button) {
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
                                download(activity, button, apkUrl, expectedHash))
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

    private static void download(Activity activity, Button button, String url, String expectedHash) {
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
