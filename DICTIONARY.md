# 同梱辞書の設計と確認方法

この文書は現在の照合・編集・診断の入口です。過去の件数や段階的な修正記録は[履歴](docs/archive/dictionary-history.md)へ分離しました。現在の同梱件数・ハッシュ・除外理由の正本は、資源と同じ場所の`candidate.manifest.json`です。

## 利用設定

新規設定では`candidateDictionary=true`と`dictionaryAutoDisplay=true`です。既存JSONに明示された`false`は保持します。辞書はAPIキーなしで使え、`apiTranslation=false`でも登録済みの訳を表示します。F8の「同梱辞書」から変更できます。

即表示はツールチップの描画前に同期的に行います。未収録の段落は原文を残し、残りをGで手動翻訳できます。Ctrl＋Gは辞書を迂回してAPIへ再送し、そのサービスの存続中は再翻訳結果を優先します。設定の再読込・再起動では辞書優先に戻ります。

`dictionaryPlainFallback`は、対応表で普通の本文・降格許可まで確認した範囲だけに適用する設定です。現在の同梱対応表では許可spanは0で、設定画面に「対象なし」と表示します。設定値は互換性のため保持します。数値・名前・意味の不一致を通す機能ではありません。

## 照合から表示まで

1. 画面・スロット・SkyBlock識別情報から、実アイテム、メニュー案内、対象外・未判定を分類します。
2. 名称・ステータス等を区別し、折り返しを含む説明を段落にまとめます。独立したRecipeの分割は文脈と各行の登録済み訳が揃う場合だけ行います。
3. 承認済みの採用判断、全文、共通範囲、正規化、型付きテンプレート等を調べます。NPCは話者と既知のプレイヤー名など、承認した文脈条件を使います。
4. 原文と訳文の値・単位・対象・名前・コマンドを検証し、対応できる範囲へ元のnative Styleを復元します。対応表は原文・訳文のハッシュと範囲も検証します。
5. 辞書訳を実際の表示へ適用します。採用できない部分は原文を保持します。アイテムはAPIがONでG要求された場合、NPCは自動翻訳設定に従いAPI補助へ進みます。

空白・折り返し・Minecraft書式コード・ASCII大小文字・末尾の単一ピリオド・確認済みのステータス記号差を扱います。任意の数値や名前を一律のワイルドカードへ変えて近い文を当てる仕組みではありません。

プレイヤーチャットにNPC辞書を流用しません。名前・ステータス・レア度等の意図した英語維持と、未収録・復元失敗による原文表示は別の結果として扱います。

## 主な資源と責務

資源は`src/main/resources/assets/skyblock_jp/dictionary/`にあります。

| 資源 | 役割 |
|---|---|
| `candidate.json` | 固定訳、英語維持、未解決競合の墓標と原文ID |
| `candidate-matching.json` | 正規化の別索引と型付きテンプレート |
| `candidate-reviewed.json` | 競合等に対する承認済みの採用判断と文脈条件 |
| `candidate-scoped-descriptions.json` | 画面・段落役割・スロット条件を確認した説明の適用先 |
| `alignment.json` | 意味区間・本文役割とnative Styleの対応 |
| `ordinary-tokens.json` | 個別原文で普通語と確認した英語の役割 |
| `candidate.manifest.json` | 入力と出力のハッシュ、件数、出典ID、採用・除外理由 |

`blocked_keys`の数は、そのまま実行時に使えないキー数ではありません。承認済みのレビューが優先され、承認資源が欠ける場合の誤採用防止として墓標を残すことがあります。異なる話者や用途に同じ短文がある場合は、無条件に一方を選びません。

## 編集と再生成

公開リポジトリは生成済みスナップショットを同梱するため、通常のビルドに元コーパスやPythonは不要です。`verifyBundledDictionary`が資源とマニフェストのハッシュ・サイズ等を検証します。生成物を単独で直接編集しないでください。

出典付き翻訳パック・マニフェスト・用語方針は、`dictionary-source/snapshot.zip`と`dictionary-source/snapshot.manifest.json`に同梱します。[編集元の説明](dictionary-source/README.md)も参照してください。元の個人作業フォルダーを参照せず、次のスナップショットから再生成できます。

まずスナップショットから資源を生成し、PythonとJavaのテストを実行します。

```powershell
python scripts/compile_dictionary.py --source-snapshot dictionary-source/snapshot.zip --candidate --output src/main/resources/assets/skyblock_jp/dictionary/candidate.json --report src/main/resources/assets/skyblock_jp/dictionary/candidate.manifest.json
python -m unittest discover -s scripts/tests
.\gradlew.bat offlineTest build -x test
```

人が編集する場合は空のフォルダーへ検証付きで展開し、原文・ID・用途・話者・出典を保ちながら訳や保持理由を変更します。編集後にスナップショットを再出力し、上の生成とテストを実行します。

```powershell
python scripts/dictionary_source.py unpack --snapshot dictionary-source/snapshot.zip --output ../dictionary-edit
# ../dictionary-edit/translations/ のJSONを編集
python scripts/dictionary_source.py export --source-root ../dictionary-edit --output dictionary-source/snapshot.zip
```

出力ZIPと同時に、その内容とハッシュを記録するマニフェストを更新します。展開時は両方の一致と全メンバーを検証します。生成済み資源と編集元を同じ変更としてレビューしてください。

一度見た文専用のJava分岐を増やす前に、既存の型・用途・語句対応データで表せるか確認します。固有のNPC台詞等は個別の辞書登録が必要です。数値差のある型を追加する場合も、量・単位・対象・コマンドの不変条件と反例を検証します。

## 診断と計測上の注意

共有ログはF8からZIPとして出力できます。Tooltip schema 3では`MATCHED_ORIGINAL`が取得元を特定できた原文、`UNMATCHED_DISPLAY`が取得元未確定の表示観測です。`displayStyledLines`は翻訳後のRGBと書式です。nativeのclick/hover/insertionは共有しません。

`STATE_SUMMARY.observationCount`は対応する`STATE_CHANGE`を含む累積値なので両方を加算しません。同じログを含む複数のZIPも重複排除が必要です。`NPC_TRANSLATION_STATUS`のAPI処理完了はHUDへの表示完了を意味しません。

辞書全件の単色テスト、保存済み入力の再生、実クライアントの表示確認は異なる検証です。辞書一致数・原文保持数・最終表示成功数を区別してください。段落分割や折り返しで分母が変わるため、前後比較には固定した原文範囲を使います。キー数からゲーム全体の日本語化率を算出することはできません。

全コーパスのゲーム内検証は完了していません。少数の実機確認を理由に元資料の`game_validated`や`runtime_eligible`、全体の`actual_game_validation_performed`を一括で真にしないでください。

## 出典と権利

[SOURCES.md](src/main/resources/assets/skyblock_jp/dictionary/SOURCES.md)と[公開編集元](dictionary-source/README.md)を参照してください。コードのMITは辞書素材の条件を置き換えません。スナップショット内に個別出典の参照を保ちます。通常のビルドは生成済み資源を利用するので、辞書コンパイラや出典サイトの状態へ依存せず実行できます。
