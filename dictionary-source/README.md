# 辞書の編集元と出典

`snapshot.zip` は125,003原資料・2,013翻訳パックと用語方針の編集用スナップショットです。各文章に原文、訳文、採用状態、文脈、出典URL・Wiki revision等を残しています。ログ、APIキー、翻訳キャッシュ、作業履歴は含めません。`snapshot.manifest.json` はZIPと全メンバーのSHA-256・サイズを記録します。

生成済みの実行用辞書も残しています。通常のJARビルドにPythonやZIPの展開は必要ありません。辞書の編集と再生成はPython 3.10以上・標準ライブラリのみで行え、ネットワークや翻訳APIは使いません。

## 編集と再生成

リポジトリのルートで実行します。展開先は空のフォルダを指定してください。

```powershell
python scripts/dictionary_source.py unpack --snapshot dictionary-source/snapshot.zip --output ../dictionary-edit
```

`../dictionary-edit/translations/` 内のJSONの `ja`、`status`、`note` を編集します。原文・ID・出典・ゲーム内検証フラグを翻訳しただけで書き換えないでください。`translation-policy.json` は英語を維持する名称の出典付き一覧です。実行時の用語・別名は `src/main/resources/assets/skyblock_jp/terminology.json` にあります。文法・数量の検証はJava側に残しています。

```powershell
python scripts/dictionary_source.py export --source-root ../dictionary-edit --output dictionary-source/snapshot.zip
python scripts/compile_dictionary.py --source-snapshot dictionary-source/snapshot.zip --candidate --output src/main/resources/assets/skyblock_jp/dictionary/candidate.json --report src/main/resources/assets/skyblock_jp/dictionary/candidate.manifest.json
python -m unittest discover -s scripts/tests
.\gradlew.bat offlineTest dictionaryCoverage build -x test
```

ZIP・sidecar・実行用辞書・マニフェスト・照合補助データをまとめてレビューしてください。元ZIPに何も編集せず再exportした場合は同じバイト列になります。破損、古いhash、パス逸脱、シンボリックリンク、Windowsで衝突する名前を拒否します。

`editorial-corrections.json` は最初の公開スナップショットで修正した3原資料のレビュー記録です。通常説明を名称と誤分類していた保持理由を訂正しました。既にZIPに反映されているため、通常の再exportで再適用する必要はありません。

## 素材の条件

コードのMITライセンスは辞書素材に対する権利を置き換えません。スナップショット内の `source_refs` が元資料を特定します。複数の出典を持つ文は、それらの条件を確認してください。

- NotEnoughUpdates-REPO: 固定commitの出典を保存し、上流MITライセンスは実行資源の `dictionary/LICENSE-NEU.txt` に同梱します。
- Hypixel SkyBlock Wikiとその寄稿者: Wiki由来の翻案には保存時のCC BY-NC-SA 3.0等の素材条件が及びます。各文のURLとrevisionを参照してください。変更点は抽出・段落化・日本語訳・英語名の保持です。
- Hypixel Public APIおよびゲーム原文: Hypixelの資料です。上記のコードや上流リポジトリのライセンスをもって、ゲーム原文への権利が与えられるという扱いはしません。

各素材の条件、翻案と独立したコードの区別を明示するための資料です。全文章の権利関係について法的な保証を行うものではありません。
