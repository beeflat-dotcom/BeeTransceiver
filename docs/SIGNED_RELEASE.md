# 固定署名・アプリ更新の初回設定

Windows PCにGitHub CLI (gh)とJDK17 (keytool) を用意し、gh auth loginでログインします。
不足している場合はSetup_Signed_Updates.ps1がインストールコマンドを表示します。

tools/Setup_Signed_Updates.cmd と Setup_Signed_Updates.ps1 を同じフォルダに保存してCMDを実行します。
初回のみ署名鍵をLOCALAPPDATA/BeeTransceiverSigning/beetransceiver-release.p12へ作成し、GitHub Secrets 4項目を設定します。
鍵は再生成せず安全な場所にバックアップしてください。署名鍵とパスワードの紛失は上書き更新不能につながります。

専用リポジトリの Actions > Publish signed BeeTransceiver APK > Run workflow で正式APKを発行します。
ReleasesにはAPKとupdate.json (SHA-256、パッケージ名、versionCode) が公開されます。
Androidの「更新を確認」から最新版を取得し、Androidの標準インストール画面で承認してください。

注意: 現在のNET v0.7と新しいstable版は別パッケージです。初回は6桁コードで新しいstable版を登録する必要があります。
プレビュー版は .stable.preview パッケージで、正式版とは別の検証アプリです。
録音・認証情報がある旧版を先に削除しないでください。

詳細: docs/UPDATES.md
