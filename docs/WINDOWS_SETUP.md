# Windows中継サーバー

PCのFastAPI/uvicorn（TCP 8769）でPTT用WebSocketを中継します。トンネルはCloudflare Tunnelを使います。

## 既存稼働環境
本リポジトリへの移行で**稼働中のPCサーバーを再インストール・再起動しないでください**。2026-10-22時点のv0.7.2修復版＋v0.7.3ペアリング修正版をベースにソースを収録しています。

## モジュール構成
- `ptt_relay_api.py`: `/ptt/ws`, `/ptt/pair`, `/ptt/health`をFastAPIへ追加
- `ptt_pair_code.py`: 6桁コード発行（サーバーヘルスで認証の有無を確認）
- `audio_upload_api.py`: 録音M4AのアップロードAPI（既存APIから供給する認証トークンを共有）
- `Install_Bee_PTT_Relay.ps1`: 既存`main.py`をバックアップしてPTT処理を追記（重複追記防止）
- `Restart_Bee_PTT_Server.ps1`: 日本語BATを使わずにPowerShellで8769のサーバーを再起動

初回・修復時はWindows用のファイルをすべて同じフォルダに置き、`Bee_PTT_Install.cmd`を実行します。インストーラーは社内PCにある既存の `main.py` と音声アップロードAPIの導入を前提としています。

設定確認: PCのブラウザで `http://127.0.0.1:8769/ptt/health` を開く。
端末追加: PC側で `Bee_PTT_Pair.cmd` を起動し、表示された6桁コードを新規Android端末に登録。通常、同じ端末での再登録は不要です。

**秘密情報をGitHubへ保存しないこと**：音声APIトークン、ペアリングコードJSON、端末トークン、録音データ、端末情報を含むログ。
