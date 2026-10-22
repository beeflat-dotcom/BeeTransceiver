# BeeTransceiver（びー・トランシーバー）

びーふらっとのAndroid端末同士で、同じWi-Fi内またはインターネット経由で音声通話するプロジェクトです。

## 構成

- `android/` — Android NET版アプリ（PTT、画面OFF受信、録音、6桁ペアリング）
- `windows/` — 既存Windows FastAPI（ポート8769）に追加するWebSocket中継サーバーとペアリングツール
- `.github/workflows/` — Androidのビルド検証
- `docs/` — 導入方法・更新運用・安全上の注意

## 現在の運用

* 2026-10-22時点の動作実績：Windows中継経由でネットワークをまたいだ音声通話を確認。
* **このリポジトリを作っただけでは、インストール済みのAPKやPCサーバーは切り替わりません。** 既存環境の停止・再認証は不要です。
* Android NET v0.7.0の接続先発見は、引き続き従来の `mywatch-home2` にある `bee-audio/audio_external_api.json` を参照します。変更する際は稼働中の端末との互換性を確認してください。
* Windowsの `main.py` は社内システムの一部です。直接移植・上書きはせず、追加モジュールだけを管理します。

## 開発・リリース方針

GitHub Actionsのビルド成功は、上書き更新に必要な同一署名を保証しません。デバッグAPKは検証用です。アプリ内更新は、将来の継続署名鍵の設定とGitHub Releasesへの署名済みAPK公開が完了してから有効にします。

録音、実際のAPI認証トークン、端末ペアリングトークン、署名秘密鍵、keystore、社内情報は公開リポジトリへコミットしません。

既存ソース移行元：`beeflat-dotcom/mywatch-home2` の `bee-ptt-v0.7-internet` ブランチ。
