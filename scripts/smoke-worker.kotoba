#!/usr/bin/env nbb
;; smoke-worker — 実際にビルドされた bundle を import して叩く。
;;
;; ここが「deploy される成果物」に触る唯一の検査である。テスト
;; (test/analytics/route_test.cljc) はソースの判断を固定するが、bundle が
;; 本当に Worker の形で答えるかは言えない —— export の形、shadow の
;; :advanced-optimization 下での env キー、`shadow.resource/inline` で焼いた
;; CSS は、どれもビルドを通って初めて存在する。
;;
;; Usage:  nbb scripts/smoke-worker.cljs [<dist/worker.js>]
;; Exit:   0 全て期待どおり · 1 期待と違う · 2 判定できなかった（bundle が無い等）

(require '["node:fs" :as fs] '["node:path" :as path] '["node:url" :as url]
         '[kotoba.lang.text :as str])

(def bundle
  "ESM の import は相対パスを package 名と読むので、必ず絶対パスに直してから
  file:// URL にする（`dist/worker.js` をそのまま渡すと『Cannot find package
  dist』になる。実測）。"
  (let [a (first (remove #(str/starts-with? % "--") *command-line-args*))]
    (.resolve path (or a "dist/worker.js"))))

(def failures (atom []))
(defn check! [label expected actual]
  (let [ok (= expected actual)]
    (println (str (if ok "PASS" "FAIL") "\t" label
                  "\texpected=" (pr-str expected) "\tactual=" (pr-str actual)))
    (when-not ok (swap! failures conj label))))

(when-not (.existsSync fs bundle)
  (println (str "UNDETERMINED\tno bundle at " bundle))
  (println "Refusing to report a pass: build it first (see docs/operator-quickstart.md §6).")
  (js/process.exit 2))

(def sentinel
  "env の VALUE がページに出ていないことを確かめるための印。実在しそうな値
  （\"yoro\" 等）だと二つの問題がある: 他の文言と偶然一致しうるし、引用符ごと
  探すと renderer が \" を &quot; に escape するので**決して一致しない** ——
  つまり検査が構造的に落ちなくなる。app-ongakuka の移行で実測してこれを踏んだ
  ので、こちらは最初から印を使う。"
  "SENTINEL-4c7e1b")

(def unreachable
  "中継先を『必ず届かない』先に固定する。DNS を引かないので offline でも
  決定論的で、しかも移行前の SvelteKit の route が不透明な 500 しか返せなかった
  ケースそのものである。"
  "http://127.0.0.1:1/x")

(def env
  ;; APP_UI_TYPE と APP_DESCRIPTION の**値**は印。ページに出たら漏れている。
  ;; AGENTGATEWAY_MCP_ROUTER_URL の値は中継先そのものなのでページに出る（出る
  ;; ことを別の check で確かめる）。
  #js {"APP_NANOID" "pbhsahxt"
       "APP_UI_TYPE" sentinel
       "APP_DESCRIPTION" sentinel
       "AGENTGATEWAY_MCP_ROUTER_URL" unreachable})

(defn- call
  ([h method p] (call h method p nil))
  ([h method p body]
   (let [init (cond-> #js {:method method}
                body (doto (aset "body" body)
                       (aset "headers" #js {"content-type" "application/json"})))
         req (js/Request. (str "https://pbhsahxt.etzhayyim.com" p) init)]
     (-> (js/Promise.resolve ((.-fetch h) req env #js {}))
         (.then (fn [res]
                  (-> (.text res)
                      (.then (fn [text]
                               {:status (.-status res)
                                :ct (.get (.-headers res) "content-type")
                                :allow (.get (.-headers res) "allow")
                                :cors (.get (.-headers res) "access-control-allow-methods")
                                :body text})))))))))

(-> (js/import (.-href (.pathToFileURL url bundle)))
    (.then
     (fn [m]
       (let [h (.-default m)]
         (check! "default export has fetch" true (fn? (.-fetch h)))
         (-> (js/Promise.all
              #js [(call h "GET" "/") (call h "GET" "/health")
                   (call h "POST" "/xrpc/") (call h "OPTIONS" "/xrpc/x")
                   (call h "GET" "/nope") (call h "POST" "/health")
                   (call h "GET" "/xrpc/x")
                   (call h "POST" "/xrpc/com.etzhayyim.apps.analytics.listDashboards" "{}")])
             (.then
              (fn [[page health bad pre nf mna wrong-xrpc proxied]]
                (check! "GET / status" 200 (:status page))
                (check! "GET / is html" true (str/includes? (or (:ct page) "") "text/html"))

                ;; ページは route 表から描かれる。表の 4 行が全部出ていること。
                ;; `"/"` そのものは HTML のどこにでも出るので検査にならない ——
                ;; 各行を**区別できる**文字列で見る（OPTIONS 行は path が POST 行と
                ;; 同じなので doc 文字列でしか区別できない）。
                (doseq [p ["/health" "/xrpc/:nsid" "この appview の説明ページ"
                           "CORS preflight"]]
                  (check! (str "page advertises " p) true (str/includes? (:body page) p)))

                ;; env のキーは出す、値は出さない
                (check! "page shows a var key" true (str/includes? (:body page) "APP_NANOID"))
                (check! "page shows every var key" true
                        (every? #(str/includes? (:body page) %)
                                ["APP_UI_TYPE" "APP_DESCRIPTION" "AGENTGATEWAY_MCP_ROUTER_URL"]))
                (check! "page hides var values" false (str/includes? (:body page) sentinel))

                ;; ページは渡された値を描く（焼いた定数ではない）
                (check! "page shows the resolved router url" true
                        (str/includes? (:body page) unreachable))
                (check! "page shows the actor did" true
                        (str/includes? (:body page) "did:web:analytics.etzhayyim.com"))
                (check! "page carries the design system" true
                        (str/includes? (:body page) "dads-table"))
                ;; 移行前のページが印字していた偽りの文
                (check! "page does not repeat the old false sentence" false
                        (str/includes? (:body page) "No public route is declared"))

                (check! "GET /health status" 200 (:status health))
                (check! "health names its routes" true
                        (str/includes? (:body health) "POST /xrpc/:nsid"))

                ;; nsid 無しの XRPC は 400。前方一致で素通ししない
                (check! "POST /xrpc/ status" 400 (:status bad))
                (check! "POST /xrpc/ says why" true
                        (str/includes? (:body bad) "Missing XRPC method"))

                (check! "OPTIONS preflight" 204 (:status pre))
                (check! "OPTIONS advertises methods" "POST,OPTIONS" (:cors pre))
                (check! "unknown path" 404 (:status nf))
                (check! "wrong method on /health" 405 (:status mna))
                (check! "405 carries allow" "GET" (:allow mna))
                (check! "GET on /xrpc is 405" 405 (:status wrong-xrpc))
                (check! "405 on xrpc allows POST, OPTIONS" "POST, OPTIONS" (:allow wrong-xrpc))

                ;; 到達できない上流は 502 と理由。移行前は不透明な 500 だった。
                (check! "unreachable upstream is 502" 502 (:status proxied))
                (check! "unreachable upstream says so" true
                        (str/includes? (:body proxied) "MCP router unreachable"))

                (let [f @failures]
                  (if (seq f)
                    (do (println (str "FAILED\t" (count f) " check(s): " (str/join ", " f)))
                        (js/process.exit 1))
                    (do (println "OK\tthe built bundle answers as the route table says")
                        (js/process.exit 0))))))))))
    (.catch (fn [e]
              (println (str "UNDETERMINED\tcould not exercise the bundle: " (.-message e)))
              (js/process.exit 2))))
