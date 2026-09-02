(ns analytics.worker
  "Cloudflare Worker の入口。**この repo で唯一 Request/Response に触る層。**

  ここには判断を置かない —— どのハンドラが答えるかは `analytics.route/dispatch`
  が決め、ページの中身は `analytics.view` が組む。どちらも `.cljc` なので
  ブラウザもビルドも無しにテストできる。

  `wrangler.jsonc` の `main` は `dist/worker.js` を指し、それはこの名前空間を
  コンパイルしたものである。移行前は SvelteKit のビルド出力（tree に存在しない
  パス）を指していて、読み手が開く `src/app.ts` はどの bundle にも入っていな
  かった（docs/adr/0001）。

  `aget` を使うのは `:advanced-optimization` 下で env のキーが潰れないため
  （先例 `listingops.edge.worker` と同じ約束）。"
  (:require [analytics.route :as route]
            [analytics.view :as view]
            [analytics.window-refresh :as wr]
            [analytics.retraction-observation :as ro]
            [analytics.attribution-observation :as ao]
            [shadow.resource :as rc]
            [clojure.string :as str]))

(def ^:private dds-css
  "DADS の CSS はビルド時に bundle へ焼く。外部リクエストゼロが design system
  の方針で、Worker から resource を読む経路も無い。"
  (rc/inline "jp_go_dds/dds.css"))

(defn- ->response [body {:keys [status content-type cache extra]}]
  (js/Response.
   body
   #js {:status status
        :headers (clj->js (merge {"content-type" content-type
                                  "cache-control" (or cache "no-store")}
                                 extra))}))

(defn- json
  ([body status] (json body status nil))
  ([body status extra]
   (->response (js/JSON.stringify (clj->js body))
               {:status status
                :content-type "application/json; charset=utf-8"
                :extra extra})))

(defn- env->keys
  "env の **キーだけ** を拾う。値はページにも応答にも出さない。"
  [env]
  (if env (vec (js/Object.keys env)) []))

(defn- env->map
  "route/mcp-router-url に渡すための keyword map。ここで読んだ値は
  **中継先の決定にだけ**使い、応答本文には出さない。"
  [env]
  (if env
    (into {} (map (fn [k] [(keyword k) (aget env k)])) (js/Object.keys env))
    {}))

(defn- upstream-headers
  "上流 MCP router へ送るヘッダ。転送してよいかの判断は route 側。"
  [req nsid]
  (let [h (js/Headers.)]
    (.forEach (.-headers req)
              (fn [v k] (when (route/forward-header? k) (.set h k v))))
    (.set h "content-type" "application/json")
    (.set h "x-etzhayyim-bff" "cljs-esm-worker")
    (.set h "x-etzhayyim-xrpc-method" nsid)
    h))

(defn- proxy-xrpc
  "XRPC を MCP router へ中継する。移行前に deploy されていた SvelteKit の
  route と同じ形（jsonrpc の封筒に包み、result/structuredContent を剥がす）。"
  [req env nsid]
  (let [url (route/mcp-router-url (env->map env))]
    (-> (.json req)
        (.catch (fn [_] #js {}))
        (.then
         (fn [input]
           (js/fetch url
                     #js {:method "POST"
                          :headers (upstream-headers req nsid)
                          :body (js/JSON.stringify
                                 #js {:jsonrpc "2.0"
                                      :id (.randomUUID js/crypto)
                                      :method "tools/call"
                                      :params #js {:name nsid :arguments input}})})))
        (.then
         (fn [resp]
           (-> (.text resp)
               (.then
                (fn [text]
                  (let [payload (try (when (seq text) (js/JSON.parse text))
                                     (catch :default _ text))
                        clj-payload (js->clj payload :keywordize-keys true)]
                    (if-not (.-ok resp)
                      (json {:error "MCP router request failed" :upstream clj-payload}
                            (.-status resp))
                      (let [{:keys [ok? value error upstream]} (route/unwrap-mcp clj-payload)]
                        (if ok?
                          (json value 200)
                          (json {:error error :upstream upstream} 502))))))))))
        (.catch
         (fn [e]
           ;; 到達できなかったことを 200 でも不透明な 500 でも隠さない。移行前の
           ;; SvelteKit の route は fetch を guard しておらず、mcp.etzhayyim.com が
           ;; NXDOMAIN なので運用者は `{"message":"Internal Error"}` の 500 しか
           ;; 見られなかった（docs/operator-quickstart.md が測って記録した）。
           (json {:error "MCP router unreachable"
                  :detail (str (.-message e))
                  :url url}
                 502))))))

(defn- page-response [env]
  (->response
   (view/render {:css dds-css
                 :routes route/routes
                 :vars (sort (env->keys env))
                 :mcp-url (route/mcp-router-url (env->map env))
                 :actor-did route/actor-did
                 :built-at nil})
   {:status 200
    :content-type "text/html; charset=utf-8"
    :cache "public, max-age=60"}))

(defn- observation-response
  "`GET /observations/window-refresh`. The decision is route-agnostic and
  lives in `analytics.window-refresh/configure-observation`; this layer only
  parses the deploy-time JSON and maps the tagged result to a status:
  :ok → 200 (record verbatim), :not-configured → 404 (absence is not a
  measurement, so no zero-shaped body), :invalid → 502 (something is
  configured but it is not this contract's record — do not guess)."
  ([env] (observation-response env "WINDOW_REFRESH_OBSERVATION_JSON" wr/configure-observation))
  ([env env-var configure-observation]
   (let [raw (when env (aget env env-var))
        parsed (when raw
                 (try (js->clj (js/JSON.parse raw) :keywordize-keys true)
                      (catch :default ::unparseable)))
        [tag payload] (configure-observation
                       (when-not (= ::unparseable parsed) parsed))]
    (case tag
      :ok (json payload 200)
      :not-configured (json {:error "observation-not-configured"
                             :note (:note payload)}
                            404)
      :invalid (json {:error "observation-invalid" :reason payload} 502)))))

(defn fetch-handler [req env _ctx]
  (let [url (js/URL. (.-url req))
        path (.-pathname url)
        {:keys [action nsid allow reason]} (route/dispatch (.-method req) path)]
    (case action
      :page   (page-response env)
      :health (json {:ok true
                     :app "analytics"
                     :runtime "cljs"
                     :actor route/actor-did
                     :routes (mapv (fn [r] (str (str/upper-case (name (:route/method r)))
                                                " " (:route/path r)))
                                   route/routes)}
                    200)
      :window-refresh-observation (observation-response env)
      :retraction-observation (observation-response
                               env "RETRACTION_OBSERVATION_JSON"
                               ro/configure-observation)
      :attribution-observation (observation-response
                                env "ATTRIBUTION_OBSERVATION_JSON"
                                ao/configure-observation)
      :xrpc   (proxy-xrpc req env nsid)
      :cors-preflight (->response nil {:status 204
                                       :content-type "text/plain"
                                       :extra (route/cors-headers)})
      :bad-request (json {:error reason} 400)
      :method-not-allowed (json {:error "Method Not Allowed"} 405 {"allow" allow})
      (json {:error "NotFound"
             :routes (mapv (fn [r] (str (str/upper-case (name (:route/method r)))
                                        " " (:route/path r)))
                           route/routes)}
            404))))

(def handler #js {:fetch fetch-handler})
