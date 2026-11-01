# 開発とテスト

利用者向けの導入・操作は[README](../README.md)、辞書の設計と再生成は[DICTIONARY](../DICTIONARY.md)を参照してください。

## ビルド

Java 25を使用します。最初のビルドではGradle・Minecraft・Fabric等の依存取得が必要です。通常のビルドは同梱辞書を検証して使い、外部翻訳APIへ送信しません。

```powershell
.\gradlew.bat offlineTest build -x test "-PupdateRepository=bibi09ix/skyblock-jp"
python -m unittest discover -s scripts/tests
```

依存が取得済みならGradleに`--offline`を追加できます。`offlineTest`は実API試験クラスを除外し、Minecraftクライアントは起動しません。実API用テストには別の明示的な有効化条件があります。通常の変更確認でAPIキーを設定する必要はありません。

成果物は`build/libs/`です。リリース用更新情報の生成は`scripts/prepare_release.py`、公開手順は`.github/workflows/release.yml`が担当します。版番号は`build.gradle`・`ModVersion.java`・`fabric.mod.json`を一致させます。

## コードを読む順番

| 入口 | 追う処理 |
|---|---|
| `SkyBlockJp`と`mixin/` | クライアントの受信・入力・描画と設定の反映 |
| `TooltipContext` → `LoreParser` → `TooltipPlan` | 用途の分類、段落境界、表示と要求で共有する計画 |
| `Translator` | 要求の重複排除、再試行、手動再翻訳、結果を公開する状態遷移 |
| `DictionaryTranslationResolver` → `BundledDictionary` | 同期的な辞書照合と意味・native Style検証、その結果の有界キャッシュ |
| `TranslationProvider` | HTTP要求・応答形式とリクエストごとの不変なNPC文脈。キューや会話履歴を保持しない |
| `TranslationCacheStore` | API成功訳のLRUと永続化。ファイルIOは要求公開用のmonitorを保持しない |
| `DictionaryMatching`・`ReviewedDictionary`等 | 別索引、型、承認済み判断と文脈条件 |
| `StyledParagraph`・`Quantity*`・`TypedDictionaryTokens` | 原文の意味・数値・名前とnative Styleの対応 |
| `DictionaryAuditTrail` → `TranslationAuditLog` | 辞書判断・NPC traceの集約と独立した有界FIFOへの記録 |
| `TooltipAudit`・`DisplayLog`・`SharedLog*` | 各段階の診断、表示観測、共有時の機密除外 |

クラス名が同じDictionary系でも、照合候補を探す処理・採用判断・色の対応・共有診断は異なる責務です。APIの検証失敗による暫定表示と、辞書の安全な採用も区別してください。

`TranslationContentChecks`はAPI訳の条件・操作の欠落を調べる補助検査です。日本語化の到達率を測るクラスでも、意味の同等性を証明するクラスでもありません。

プロンプトは`src/main/resources/assets/skyblock_jp/prompts/`の版付きUTF-8 JSONで管理します。`segments`を区切り文字なしで連結した値が送信されます。v1は0.7.12のJava文字列と完全に同一で、SHA-256の回帰テストがあります。プロンプト変更時は版・評価例を更新し、HTTP処理の変更とは分けて検証してください。

翻訳futureの完了は、診断ログの永続化完了を意味しません。診断は独立した256件の有界FIFOで処理し、過負荷では翻訳を止めず省略件数を記録します。ログを検査するコードテストは`Translator.awaitDiagnostics()`で先行記録の完了を待ちます。`close()`は待機中の要求と診断を止め、進行中の診断書き込み・キャッシュ保存の終了を待ちます。そのIO待ちで要求公開用のmonitorを保持しません。

## 変更を検証する基準

- 同じ原文・用途・話者・文脈で、既存の訳と数値・名前・RGB・装飾が保持されることを確認します。
- 不一致の例だけでなく、近い別文・値の対象違い・コマンド違い等の反例を含めます。
- 出力例の表はデータ化できますが、非同期、保存、機密除外、更新、境界のテストは独立した責務を持ちます。
- 「読んだ」「コードで実行した」「保存ログを再生した」「クライアントで確認した」を分けて報告します。
- 整形・移動と挙動変更は分けます。辞書の採用率だけを上げるために保持対象や対象外を増やさないでください。

Javaの整形はSpotlessで管理します。変更前後で`spotlessCheck`を確認し、整形が必要な場合は`spotlessApply`を使います。

PRとmainのCIでは、Pythonの検証、公開編集元からの辞書再生成、英語保持判断の監査、Java整形・テスト・固定表示コホート・ビルドを実行します。リリース時にも同じ検査を行います。

```powershell
python scripts/verify_dictionary_reproduction.py
python scripts/dictionary_audit.py src/main/resources/assets/skyblock_jp/dictionary/candidate.json --check
```

再生成検証は5つの実行資源すべてをバイト単位で比較します。保持判断の監査は、名称として維持された長い動作説明をレビュー待ちとして検出する補助です。警告0件をもって、すべての保持理由が正しいという証明にはしません。

```powershell
.\gradlew.bat spotlessCheck
.\gradlew.bat dictionaryCoverage
python scripts/coverage_report.py build/reports/dictionary-coverage/current.json --baseline src/test/resources/dictionary-coverage-baseline.json --output build/reports/dictionary-coverage/summary.json
```

到達率レポートは本体のJava照合・復元経路を使います。固定コホートの前後を比較し、保持・不一致・復元失敗を区別します。新しい難しい観測を増やした結果と、同じ観測での後退を分けて評価してください。

分母の決め方、段階別の出力、基準値のレビュー、共有ZIPのローカル再生は[到達率の計測](coverage.md)を参照してください。

過去の件数・実機確認は[変更履歴](../CHANGELOG.md)と[辞書の過去記録](archive/dictionary-history.md)に保存しています。その数字を現行版の試験結果として使わないでください。
