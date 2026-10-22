# アプリ内更新の運用方針

## 2026-10-22現在

- Android v0.8.0に**「更新を確認」ボタン実装済み**。GitHub Releasesの最新公開版（signed）とversionCodeを比較し、SHA-256一致確認後、Android標準インストーラーを表示します。
- v0.8.0はGitHub ActionsのデバッグAPKとしてビルド検証済み（プレビュー版は別パッケージ）。
- 正式APKは、Windowsで一度きりの固定署名鍵設定とGitHub Actionsの手動リリースが必要。**初回正式版の公開はまだ行っていません**。
- APKの自動無人インストールはしません。インストール確認・提供元の許可はAndroid側のユーザー操作が必要です。

## 更新の構成

1. Windowsの `tools/Setup_Signed_Updates.cmd` で署名秘密鍵を安全に生成・保存し、4つのGitHub Actions Secretsを設定。
2. `Actions > Publish signed BeeTransceiver APK > Run workflow` を手動で実行。
3. GitHub Releasesに固定署名APKと `update.json` を公開（versionCode、versionName、packageName、SHA-256、APK asset name）。
4. Androidの「更新を確認」で最新のReleaseを取得。新しいversionCodeだけを対象にする。
5. HTTPS経由でダウンロードしSHA-256を検証。Android標準のインストール確認で上書き更新する。

## v0.7利用者の移行

- 現行v0.7 NET (`jp.beeflat.beetransceiver.net`) とv0.8正式版 (`jp.beeflat.beetransceiver.net.stable`) は**別パッケージ**。
- 最初の正式版導入時には新規に6桁ペアリングが必要。旧アプリの録音等は自動移行されません。
- 安定運用を確認するまで旧版を削除せず、両方の受信サービスを同時に起動しないよう注意。
- 正式版同士では、次回以降同じ署名鍵で上書き更新するため、端末名・認証の保存が維持されます。
- Windowsサーバーの発話権更新は `tools/Apply_Floor_Control.cmd`。モジュールバックアップ・エラー時の復元に対応。

接続先の発見は旧リポジトリ `mywatch-home2` のDropbox/GitHub連携を継続利用。通信先の切り替えは別の段階で検証して実施する。
