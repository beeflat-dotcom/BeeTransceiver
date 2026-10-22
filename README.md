# BeeTransceiver（びー・トランシーバー）

びーふらっと専用のAndroid PTT（押して話す）アプリと、Windows FastAPI上の音声中継サーバーを管理するリポジトリ。

## v0.8.0（新アプリ）の変更点

- **発言者表示**：画面に「発言中：今店・天野」などを表示。画面OFF時の受信通知にも表示。
- **発話権**：新しい中継サーバーが「1人ずつ」を管理。ほかの職員が話している間は送信を許可しない。
- **端末名**：名前はいつでも変更・保存可能。認証のやり直しは不要（送信名はUTF-8で最大48バイト）。
- **更新確認**：GitHub Releasesの署名済みAPKを取得、SHA-256検証後にAndroid標準のインストール確認画面へ進む。
- **二重再生防止**：新しい中継サーバーへ接続済みの場合、同じ音声がLANとWebSocketから重複して再生されることを避ける。

## フォルダ

| パス | 内容 |
| --- | --- |
| `android/` | Androidアプリのソース、PTT、録音、更新機能 |
| `windows/` | PC上のWebSocket中継とペアリングスクリプト |
| `tools/` | Windowsの発話権更新・署名鍵初回設定用CMD/PowerShell |
| `tests/` | 発話権・旧版互換性の回帰テスト |
| `.github/workflows/` | ビルド検証と固定署名APKのRelease公開 |
| `docs/` | インストール・更新・運用 |

## 導入順序

1. 既存のv0.7が問題なく動いていることを確認し、**旧アプリは削除しない**。
2. [GitHub Actions](https://github.com/beeflat-dotcom/BeeTransceiver/actions/workflows/android-build.yml)からv0.8のプレビューAPKとWindows更新ツールを取得。
3. Windows PCで `Apply_Floor_Control.cmd` を実行。失敗した場合は旧モジュールにロールバックする。再起動を伴う。
4. PCの `http://127.0.0.1:8769/ptt/health` が `floor_control: true` と表示されることを確認。
5. プレビューアプリをテスト端末2台にインストールし、それぞれ**新しい6桁コードで登録**。端末名を設定し、異なる回線で「1人ずつ話す」動作を確認。
6. 動作確認後、`tools/Setup_Signed_Updates.cmd` で固定署名を一度だけ作成し、GitHub Actionsで正式版を発行する（[手順](docs/SIGNED_RELEASE.md)）。

## 現行版を壊さないための注意

- プレビューアプリは `jp.beeflat.beetransceiver.net.stable.preview`、正式版は `jp.beeflat.beetransceiver.net.stable`。
- 現在の旧v0.7は `jp.beeflat.beetransceiver.net`。**異なるアプリなので、既存アプリの録音・認証情報は自動では引き継がれない**。
- 同じスマホに旧版とプレビュー版を入れると両方の受信サービスが起動し得る。テスト時は旧版の受信を停止する。
- 正式版は同じ署名鍵を使った将来の更新で、端末名・ペアリング情報を維持して上書き更新できる。
- 現在の外部接続先発見情報は、旧 `mywatch-home2` リポジトリを参照したまま。通信先移行は別途検証してから実施する。
- **LANだけで使う古いアプリ**の送信まではインターネット中継サーバーで排他制御できない。全端末を新バージョンにそろえると確実。

### 秘密情報の管理

API認証トークン、ペアリング用コード、録音、keystore/署名秘密鍵をGitHubにコミットしない。
