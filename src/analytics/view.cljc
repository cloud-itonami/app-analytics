(ns analytics.view
  "この appview の説明ページ。純 hiccup。

  基盤は `jp-go-dds`（デジタル庁デザインシステム）—— superproject の skill
  `kotoba-uiux` が定める新規 UI の base。色・寸法は `--hig-*` トークン契約で
  書き、raw hex も px フォントサイズも置かない。

  **表示する事実は引数で受け取る。ページの中に焼かない。**
  これは装飾の都合ではない —— 移行前の `+page.svelte` はページの中に
  `routeCount` と `vars` を literal で持っていて、隣の `wrangler.jsonc` が
  route 1 個と var 8 個を宣言していることに気づけないまま
  『No public route is declared next to this app surface.』と印字していた。
  ここでは route 表も env のキーも渡す側が持ち、ページは描くだけなので、
  両者がずれる余地が無い。"
  (:require [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [clojure.string :as str]))

(def app-css
  "app 固有の最小 CSS。`--hig-*` 契約だけを使う（bridge が DADS の上に再定義
  する）。DADS を base にした app の下には `shitsuke.hig` が居ないので、bridge
  が運んでいないトークンは何にも解決しない —— 使うのは運ばれている範囲だけ。"
  (str/join
   "\n"
   [".an-lede { color: var(--hig-color-secondary-label); max-width: 42rem; }"
    ".an-note { color: var(--hig-color-secondary-label); font-size: var(--hig-text-footnote-font-size); }"
    ".an-mono { font-family: var(--hig-font-mono); overflow-wrap: anywhere; }"]))

(defn- route-rows [routes]
  (mapv (fn [r]
          [(str/upper-case (name (:route/method r)))
           [:span {:class "an-mono"} (:route/path r)]
           (:route/doc r)])
        routes))

(defn body
  "opts:
   :routes    analytics.route/routes（この Worker が実際に答えるもの）
   :vars      wrangler が渡した env のキー（**値は出さない**）
   :mcp-url   XRPC の中継先（route/mcp-router-url の戻り値）
   :actor-did この appview の DID
   :built-at  bundle のビルド時刻（不明なら nil）"
  [{:keys [routes vars mcp-url actor-did built-at]}]
  (dds/container
   (dds/section
    {}
    (dds/heading 1 "Analytics")
    [:p {:class "an-lede"}
     "ダッシュボード・集計メトリクス・レポートを扱う analytics サービスの"
     "公開面（appview）。集計と保存そのものはここには無く、この面は XRPC を"
     "MCP router へ中継するだけの thin edge である。"])

   (dds/section
    {:title "この面が答えるもの"}
    (dds/table {:caption "公開ルート"
                :headers ["METHOD" "PATH" "何をするか"]
                :rows (route-rows routes)})
    [:p {:class "an-note"}
     "この表は Worker の route 表そのものから描いている。ページに焼いた値では"
     "ないので、実際に答えるものと表示がずれない。"])

   (dds/section
    {:title "実行時の設定"}
    (if (seq vars)
      [:div
       (into [:p] (interpose " " (map (fn [k] (dds/chip-label (name k))) vars)))
       [:p {:class "an-note"} "キー名のみ。値は出さない。"]]
      [:p {:class "an-note"} "env が渡されていない（ローカル描画）。"])
    [:p {:class "an-note"} "XRPC の中継先: "
     [:span {:class "an-mono"} mcp-url]]
    (when actor-did
      [:p {:class "an-note"} "actor DID: " [:span {:class "an-mono"} actor-did]]))

   (dds/section
    {:title "現在地"}
    [:p {:class "an-lede"}
     "この appview は TypeScript/Svelte から ClojureScript へ移行済み。"
     "deploy される bundle は、いま読んでいるソースからコンパイルされたもので"
     "ある（docs/adr/0001）。"]
    (when built-at
      [:p {:class "an-note"} "bundle build: " built-at]))))

(defn render
  "完全な HTML 文書。`css` は呼び出し側が渡す（ライブラリは I/O を持たない）。"
  [{:keys [css] :as opts}]
  (page/->page
   {:title "Analytics"
    :description "ダッシュボード・集計メトリクス・レポートを扱う analytics サービスの公開面。"
    :lang "ja"
    :css css
    :app-css (str tokens/bridge-css "\n" app-css)}
   (body opts)))
