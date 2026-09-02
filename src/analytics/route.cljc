(ns analytics.route
  "どのハンドラが答えるか — データと純関数だけで決める。

  これが `.cljs` ではなく `.cljc` なのは意図的である。edge worker のうち
  検査する価値があるのは経路の判断で、それはブラウザもビルドもネットワークも
  無しにここで検査できる。`analytics.worker` はこの repo で唯一 Request /
  Response に触る名前空間で、このファイルが既に決めたこと以外は何もしない。

  ingress capability が qualify したときに最初に `.kotoba` へ移るのもここで
  ある（今日は `:native-aot` / `:wasm-aot` とも pending —— ADR-2606290000）。
  route 表はスカラと文字列に対する判断で、まさにその移行を生き延びる形をして
  いる。

  ## 何を移し、何を移さなかったか

  移行前に **実際に deploy されていたのは SvelteKit のビルド出力**
  （`wrangler.jsonc` の `main` が `svelte/.svelte-kit/cloudflare/_worker.js` を
  指していた）で、それが答えていたのは `GET /`（ページ）と
  `POST|OPTIONS /xrpc/[...path]` の 2 つだけである。ここはその 2 つを移した。

  `appview/analytics-mcp-component/src/app.ts` はどの bundle にも入っておらず、
  そこにあった `/health` / `/_app/meta` / `GET /xrpc/…` / dispatcher 中継は
  **deploy されたことが無い**。dispatcher 経路は宛先
  `dispatcher.etzhayyim.com` が NXDOMAIN なので持ち越していない（README の
  「持ち越さなかったもの」）。`/health` だけは上流も binding も要らないので
  **移植ではなく追加**として足した —— それが唯一の意図的な振る舞いの変更で
  ある。"
  (:require [clojure.string :as str]))

(def actor-did
  "この appview の DID。kotodama.jsonld の `@id` と同じ値。"
  "did:web:analytics.etzhayyim.com")

(def default-mcp-router-url
  "`AGENTGATEWAY_MCP_ROUTER_URL` も `MCP_ROUTER_URL` も無いときの中継先。

  既定をここに焼くのは「設定が無ければ黙って何処かへ POST してよい」からでは
  なく、**どこへ行くのかを 1 箇所で読めるようにする**ためである。移行前の
  SvelteKit の route も同じ値を持っていた。"
  "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message")

(def routes
  "公開されている面を、データとして持つ。ランディングページは **これ** を
  描くので、実際に答える route とページが宣伝する route がずれる余地が無い。

  移行前のページは `routeCount: 0` / `routes: []` / `vars: []` を literal で
  持っており、隣の `wrangler.jsonc` が route 1 個と var 8 個を宣言している
  ことに気づけなかった。この移行はその欠陥を消すために在る。"
  [{:route/path "/"            :route/method :get     :route/kind :page
    :route/doc "この appview の説明ページ"}
   {:route/path "/health"      :route/method :get     :route/kind :json
    :route/doc "生存確認。deploy された面が答えることを外から確かめられる"}
   {:route/path "/xrpc/:nsid"  :route/method :post    :route/kind :proxy
    :route/doc "XRPC を MCP router へ中継する"}
   {:route/path "/xrpc/:nsid"  :route/method :options :route/kind :cors
    :route/doc "CORS preflight"}
   {:route/path "/observations/window-refresh" :route/method :get :route/kind :json
    :route/doc "window-refresh-observation/v1 の読み出し面。設定された観測レコードをそのまま返す。無ければ 404 — 不在は 0 に着替えない"}])

(defn- url-decode
  "percent-encoding を解く。SvelteKit は route param を decode 済みで渡すので、
  `[...path]` の忠実な移植にはここが要る（`/xrpc/a%2Fb` と `/xrpc/a/b` が
  同じ nsid になる）。壊れた encoding は元の文字列のまま返す —— 500 にして
  隠さない。"
  [s]
  #?(:cljs (try (js/decodeURIComponent s) (catch :default _ s))
     :clj (try (java.net.URLDecoder/decode s "UTF-8") (catch Exception _ s))))

(defn xrpc-nsid
  "`/xrpc/<nsid>` の nsid。無ければ nil。

  **区切りより後ろを丸ごと nsid にする。** 移行前に deploy されていた
  SvelteKit の route は `[...path]`（rest param）だったので `/xrpc/a/b` は
  `a/b` という nsid として中継されていた。ここはその意味論をそのまま移して
  いる —— app-ongakuka は単一セグメントに絞る判断をしたが、あちらは移す元の
  route が別物だった。"
  [path]
  (let [p (or path "")
        rest' (cond
                (= p "/xrpc") ""
                (str/starts-with? p "/xrpc/") (subs p (count "/xrpc/"))
                :else nil)]
    (when (and rest' (seq (str/trim rest')))
      (url-decode rest'))))

(defn dispatch
  "method + path → 何をするか。Request も Response も知らない。

  返すのは `{:action …}` で、`:action` は `:page` / `:health` / `:xrpc` /
  `:cors-preflight` / `:bad-request` / `:method-not-allowed` / `:not-found`
  のいずれか。"
  [method path]
  (let [m (keyword (str/lower-case (or method "GET")))
        p (or path "")]
    (cond
      (or (= p "/xrpc") (str/starts-with? p "/xrpc/"))
      (case m
        :options {:action :cors-preflight}
        :post (if-let [nsid (xrpc-nsid p)]
                {:action :xrpc :nsid nsid}
                ;; 移行前の +server.ts と同じ文言。前方一致で素通ししない。
                {:action :bad-request :reason "Missing XRPC method"})
        {:action :method-not-allowed :allow "POST, OPTIONS"})

      (= p "/health") (if (= m :get)
                        {:action :health}
                        {:action :method-not-allowed :allow "GET"})

      (= p "/") (if (= m :get)
                  {:action :page}
                  {:action :method-not-allowed :allow "GET"})

      (= p "/observations/window-refresh")
      (if (= m :get)
        {:action :window-refresh-observation}
        {:action :method-not-allowed :allow "GET"})

      :else {:action :not-found})))

(defn mcp-router-url
  "env の設定 → MCP router の URL。末尾スラッシュは落とす。

  空白だけの設定は未設定として扱う（移行前の `+server.ts` の `.trim()` と
  同じ判断）。"
  [{:keys [AGENTGATEWAY_MCP_ROUTER_URL MCP_ROUTER_URL]}]
  (let [pick (fn [s] (when (and (string? s) (seq (str/trim s))) (str/trim s)))]
    (-> (or (pick AGENTGATEWAY_MCP_ROUTER_URL)
            (pick MCP_ROUTER_URL)
            default-mcp-router-url)
        (str/replace #"/+$" ""))))

(def ^:private dropped-headers
  "上流へ**転送しない**リクエストヘッダ（小文字）。

  `host` は移行前の `+server.ts` も落としていた。`content-length` /
  `content-encoding` / `transfer-encoding` を足したのは、中継の body を
  JSON-RPC の封筒に**包み直す**ので元の長さも符号化も嘘になるからである。
  移行前はこれを落としておらず、上流が生きていれば長さ不一致で失敗しえた
  （上流は NXDOMAIN なので誰も踏んでいない）。"
  #{"host" "content-length" "content-encoding" "transfer-encoding"})

(defn forward-header?
  "このヘッダを上流の MCP router へ転送してよいか。"
  [k]
  (not (contains? dropped-headers (str/lower-case (or k "")))))

(defn unwrap-mcp
  "MCP router の応答 → 呼び手に返す値。

  移行前の `+server.ts` と同じ順序で判定する:
  `error` を持てば 502、`result` を持てばそれを剥がし、`result` が
  `structuredContent` を持てばさらに剥がす。最後が nil なら `{}`。"
  [payload]
  (cond
    (and (map? payload) (contains? payload :error))
    {:ok? false
     :error (get-in payload [:error :message] "MCP router returned an error")
     :upstream payload}

    :else
    (let [result (if (and (map? payload) (contains? payload :result))
                   (:result payload)
                   payload)
          structured (if (and (map? result) (contains? result :structuredContent))
                       (:structuredContent result)
                       result)]
      {:ok? true :value (if (nil? structured) {} structured)})))

(defn cors-headers
  "移行前の `+server.ts` の OPTIONS が返していたものと同じ。"
  []
  {"access-control-allow-origin" "*"
   "access-control-allow-methods" "POST,OPTIONS"
   "access-control-allow-headers" "content-type,authorization"
   "access-control-max-age" "86400"})
