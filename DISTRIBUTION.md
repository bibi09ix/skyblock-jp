# SkyBlock JP の配布と自動更新

## 利用者へ最初に渡すもの

GitHub Releasesにある自動更新対応版（0.6.4以降）のJARを、最初に一度だけ導入してもらいます。
以降はゲーム起動時に新版を確認し、終了後に差し替え、次の起動から新しい版になります。
F8 → 自動更新で停止／手動確認できます。APIキーや個人設定をこちらへ送ることはありません。

## 配布先

公開リポジトリ `bibi09ix/skyblock-jp` を使用します。GitHubアカウントを持たない利用者もダウンロードできます。

自動更新が読むファイル:
`https://github.com/bibi09ix/skyblock-jp/releases/latest/download/skyblock-jp-26.2.json`

## 新版を公開する

1. `build.gradle` と `src/main/resources/fabric.mod.json` のバージョンを同じ新しい値へ更新します。
2. 更新をコミットし、`v0.6.5` のようなタグをプッシュします。
3. `.github/workflows/release.yml` がテスト、ビルド、JARと更新情報の公開を実施します。
   Actionsの「Publish SkyBlock JP」→「Run workflow」でも実行できます。同じ版の公開は一度だけにします。

GitHubへ初めてソースをアップロードするときは、config、logs、build、.gradle、APIキーを含めません。
本プロジェクトの`.gitignore`がこれらを除外します。

## このPCでビルドして手動公開する場合

PowerShellでプロジェクトフォルダーを開きます。

```powershell
.\gradlew.bat build -PupdateRepository=bibi09ix/skyblock-jp
python scripts/prepare_release.py --repository bibi09ix/skyblock-jp
```

`release-assets`内のJARと`skyblock-jp-26.2.json`の2ファイルを、タグ`vバージョン`のGitHub Releaseに添付します。
JARに同梱される配布先とマニフェストの整合性はスクリプトが検証します。

## 差し替えの仕組み

- 稼働中のJARはその場で変更しません。独立したJavaヘルパーがMinecraftのプロセス終了を待ちます。
- SHA-256を適用直前にも確認します。手動でJARを更新していた場合はその変更を優先します。
- 旧版は`config/skyblock_jp/updates/backups`に保存します。
- 通信・検証・書込に失敗しても旧版のまま利用できます。ダウングレードや別Minecraft版への自動移行は行いません。
- 自動更新をOFFにすると、その起動中に準備した未適用の更新も取り消します。再開は次回起動からです。
- 更新元のGitHubアカウントを管理できる人はmodのコードを配布できます。更新元アカウントと公開リポジトリの管理を継続してください。
