# 辞書訳が表示へ届くまでの計測

辞書の登録件数や照合成功数だけでは、実際に日本語が表示された割合は分かりません。この計測は本体のJava処理をそのまま呼び、用途の分類、段落化、辞書照合、意味・数値の保護、色復元、最終コンポーネント置換までを再生します。Python側には辞書照合を実装していません。

対象はツールチップのmod処理直後です。Minecraftのフォント・画面幅による折り返し、その後に動く別mod、チャット・NPC・他HUD、Hypixel全体の網羅率を保証するものではありません。

## 固定コホートの実行

リポジトリのルートで、Java 25とPython 3.10以上を使います。Minecraftや外部翻訳APIは起動しません。

```powershell
.\gradlew.bat dictionaryCoverage
python scripts/coverage_report.py build/reports/dictionary-coverage/current.json --baseline src/test/resources/dictionary-coverage-baseline.json --output build/reports/dictionary-coverage/summary.json
```

LinuxのCIでは`bash gradlew dictionaryCoverage`を使用します。公開用fixtureと基準値は以下です。

- `src/test/resources/dictionary-coverage-fixtures.json`: 明示的に選別した固定ゲーム文、既存の公開テストにある色付き効果文、未分類・未登録の合成例。生の共有ログ、プレイヤー名、Co-opの名簿、個人の進捗・入手日等は含みません。
- `src/test/resources/dictionary-coverage-baseline.json`: 同じ入力について、既に表示できた訳の文字列・RGB・5種の装飾と、保持行の結果をハッシュで記録した基準値。
- `DictionaryCoverageReplayTest`: 実際の表示経路と基準値をJUnitでも検証します。通常の`offlineTest`に含まれます。

初期のコホートは34ケース・157原文行です。翻訳対象の90行と、名称・数値ステータス・区切り等の保持67行を、パーサへ渡す前に区別しています。Pets・Collections・Rewards等の一般的なメニュー見出しは翻訳対象です。頻度集計の挙動も確認するため、2ケースには人工的な重み2を設定しています。これは実際のプレイ時間や画面描画回数ではありません。

## 分母をパーサに決めさせない

fixtureの各原文行には、事前にレビューした次の`intent`があります。

| intent | 意味 | 翻訳対象の分母 |
|---|---|---|
| `TRANSLATE` | 日本語にしたい説明・操作案内 | 常に含む。未分類・パーサの除外・辞書の保持判断でも除かない |
| `PRESERVE` | 英語の名称、数値ステータス、レア度、空行等 | 含めず、理由と件数を別に出す |
| `UNREVIEWED` | 翻訳対象か未精査 | 別枠。名称だと推定して自動的に免除しない |

率は`TRANSLATE`行のうち、訳文に日本語があり、原文と異なり、本体がnativeコンポーネントとして実際に置換できた行の割合です。`KEEP_ORIGINAL`や、日本語を含まない採用結果を成功の分子には含めません。

同じ原文の物理的な行番号を固定するため、説明段落を細分化したり出力を折り返したりしても、この比較の分母は増減しません。1段落を複数のRecipe行へ安全に分離する場合も、元の原文範囲へ結果を戻して数えます。

## 出力の読み方

コンソールには全体・チャンネル別・画面グループ別の表が出ます。JSONにはさらに次を記録します。

| 項目 | 用途 |
|---|---|
| `translationTargets` / `translatedTargets` | 事前に決めた対象の分母・最終表示に届いた分子 |
| `dictionaryTranslatedSourceLines` | 対象意図の有無にかかわらず、辞書で日本語へ置換できた原文行数 |
| `dictionaryKeptSourceLines` | 辞書が意図して原文を保持した行数。訳表示の分子とは別 |
| `intentionalPreservation` / `unreviewedIntent` | 事前に承認した保持と、未精査を区別 |
| `uniqueTranslatedEveryVariant` / `uniqueTranslatedAnyVariant` | 同じ原文の全スタイル等で成功したか、一部だけ成功したかを区別 |
| `failureStages` | 翻訳対象の失敗を分類・段落化・辞書・意味保護・色復元・表示等に分ける |
| `pipelineStages` | 保持・未精査を含む全行の経路別集計。意図不明の取得ログでも使える |
| `translatedMatchModes` | 完全一致・正規化・テンプレート・レビュー済み等の取得経路 |

`dictionaryTranslatedSourceLines`と`japaneseDisplaySourceLines`は異なります。後者には最初から日本語だった文も含みうるため、外部ログの日本語表示数をそのまま翻訳成功率とは呼びません。

行ごとの結果には原文・表示文字・native Styleのハッシュ、役割、採用理由と段階を残します。プレイヤー名を含みうる原文、訳文、API応答、取得元の絶対パスやセッションIDは出力しません。

ハッシュだけになっていても匿名化を保証するものではありません。既知のプレイヤー名や短い原文から同じSHA-256を計算すれば、入力に含まれていたかを照合できる場合があります。取得ログから生成するcapture出力はローカル分析用です。公開・他人への共有に使う前には、個人情報を含む原文範囲や結果を別途レビューしてください。公開用の固定fixtureはこのcapture出力を自動で取り込まず、ゲーム文だけを個別に精査して作ります。

## 回帰判定と基準値の変更

既存の訳が表示されなくなった場合、既存の訳文やRGB・装飾が変わった場合、保持行が改変された場合は、CIとJUnitが失敗します。まだ訳せていない対象行も、新たな日本語訳が表示できるようになる場合以外は、元の文字・RGB・装飾を維持します。`KEEP_ORIGINAL`等へ判定を変えるだけで未訳原文を改変することは許可しません。未一致だった行を新たに訳せるようになる改善は許可します。

対象行を保持扱いに変えたり、ケースを削ったり、重みを変えたりする変更は自動採用しません。fixtureのハッシュ、各原文行とintentを固定して検出します。意図した訳の修正や新しいコホートの追加では、差分を確認したうえで明示的に基準値を更新します。

```powershell
python scripts/coverage_report.py build/reports/dictionary-coverage/current.json --write-baseline src/test/resources/dictionary-coverage-baseline.json
```

このコマンドを通常の検証・CIへ入れないでください。自動更新すると、検知したかった後退まで承認してしまいます。新しく観測した難しい文を追加したことで全体の率が下がった場合と、同じ文が以前より訳せなくなった場合を分けて評価します。

## 手元の共有ZIPを調べる

共有ZIPはローカルで任意に読み込めます。GitやCIへ追加する必要はありません。

`DictionaryCoverageRunner`は次のCLIに対応します。

```text
DictionaryCoverageRunner --zip output.json first-shared-log.zip second-shared-log.zip
```

Gradleの`dictionaryCoverage`タスクでは、任意の出力先とZIP一覧を`coverageOutput` / `coverageZips`プロパティで指定できます。ZIP一覧はセミコロン区切りです。

```powershell
.\gradlew.bat dictionaryCoverage "-PcoverageZips=C:/Reports/first.zip;C:/Reports/second.zip" "-PcoverageOutput=build/reports/dictionary-coverage/capture.json"
python scripts/coverage_report.py build/reports/dictionary-coverage/capture.json --output build/reports/dictionary-coverage/capture-summary.json
```

読み取るのは`logs/translation-display.jsonl`と`.1`の、原文native情報が完全な表示レコードだけです。同一内容のログpayloadを複数ZIPから二重加算せず、重複する観測イベントもまとめます。`STATE_SUMMARY`は累積件数なので加算しません。未対応・不完全な入力の件数も出力します。

処理できる入力には上限があります。1回の実行はZIP32個、ログ1本32MB、読み込んだ展開後ログの累積96MB、非空レコード50,000件、異なる表示状態10,000件までです。重複排除前の読み取り量・レコード数も上限を消費します。native本文は累積8,000,000 UTF-16文字・500,000 runs・100,000行（空行も含む）、1表示256行、1行256 runs・16,384 UTF-16文字までです。さらに各rawレコードのフィールド16,384個・JSON構造深さ32・値数、metadata文字数を投影前に検証します。上限違反は途中の結果を成功として返さず、処理全体をエラーにします。データ量が大きい場合は期間ごとに分けて分析してください。

取得ログの各行は事前の対象意図が未精査なので、`UNREVIEWED_CAPTURE`として扱い、対象日本語化率は`n/a`です。実際の辞書置換数、保持数、分類や復元で止まった理由は確認できます。個人データを除き、対象意図も精査したものだけを、別の変更として固定コホートへ追加します。

これはツールチップの保存観測に基づく分析です。取得できなかった原文や診断キューから省略された観測、未訪問の画面まで補って数えません。
