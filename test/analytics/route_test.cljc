(ns analytics.route-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [analytics.route :as route]
            [analytics.view :as view]))

(deftest dispatch-page-and-health
  (is (= :page (:action (route/dispatch "GET" "/"))))
  (is (= :method-not-allowed (:action (route/dispatch "POST" "/"))))
  (is (= :health (:action (route/dispatch "GET" "/health"))))
  (is (= :method-not-allowed (:action (route/dispatch "POST" "/health"))))
  (testing "移行前に deploy されていた面と同じく、他は全部 404"
    (is (= :not-found (:action (route/dispatch "GET" "/nope"))))
    (is (= :not-found (:action (route/dispatch "GET" "/_app/meta"))))
    (is (= :not-found (:action (route/dispatch "GET" "/xrp"))))))

(deftest dispatch-xrpc
  (testing "nsid はそのまま通る"
    (is (= {:action :xrpc :nsid "com.etzhayyim.apps.analytics.listDashboards"}
           (route/dispatch "POST" "/xrpc/com.etzhayyim.apps.analytics.listDashboards"))))
  (testing "[...path] の忠実な移植 —— 多段も 1 つの nsid として中継する"
    (is (= {:action :xrpc :nsid "a/b"} (route/dispatch "POST" "/xrpc/a/b")))
    (is (= {:action :xrpc :nsid "a/b"} (route/dispatch "POST" "/xrpc/a%2Fb"))))
  (testing "nsid が無ければ 400。前方一致で素通ししない"
    (is (= :bad-request (:action (route/dispatch "POST" "/xrpc/"))))
    (is (= :bad-request (:action (route/dispatch "POST" "/xrpc"))))
    (is (= "Missing XRPC method" (:reason (route/dispatch "POST" "/xrpc/")))))
  (testing "preflight と method"
    (is (= :cors-preflight (:action (route/dispatch "OPTIONS" "/xrpc/x"))))
    (is (= :cors-preflight (:action (route/dispatch "OPTIONS" "/xrpc/"))))
    (is (= :method-not-allowed (:action (route/dispatch "GET" "/xrpc/x"))))
    (is (= "POST, OPTIONS" (:allow (route/dispatch "GET" "/xrpc/x")))))
  (testing "method は大小文字を問わない"
    (is (= :xrpc (:action (route/dispatch "post" "/xrpc/x"))))))

(deftest mcp-url-resolution
  (is (= "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"
         (route/mcp-router-url {})))
  (is (= "https://a.example/x"
         (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "https://a.example/x/"})))
  (testing "空白だけの設定は未設定として扱う（移行前の .trim() と同じ判断）"
    (is (= "https://b.example"
           (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "   "
                                  :MCP_ROUTER_URL "https://b.example"})))))

(deftest header-forwarding
  (testing "body を包み直すので長さと符号化は転送しない"
    (is (false? (route/forward-header? "Content-Length")))
    (is (false? (route/forward-header? "host")))
    (is (false? (route/forward-header? "Transfer-Encoding"))))
  (testing "呼び手の認証は上流へ渡す（移行前もそうしていた）"
    (is (true? (route/forward-header? "authorization")))
    (is (true? (route/forward-header? "x-request-id")))))

(deftest unwrap
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:structuredContent {:a 1}}})))
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:a 1}})))
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:a 1})))
  (testing "nil は {} になる（移行前の `structured ?? {}`）"
    (is (= {:ok? true :value {}} (route/unwrap-mcp {:result nil}))))
  (testing "error は result より先に見る"
    (is (false? (:ok? (route/unwrap-mcp {:error {:message "boom"} :result {:a 1}}))))
    (is (= "boom" (:error (route/unwrap-mcp {:error {:message "boom"}}))))))

(deftest page-shows-the-real-routes
  (testing "ページは route 表から描く。数を焼かない（移行前の欠陥）"
    (let [html (view/render {:css "/*x*/"
                             :routes route/routes
                             :vars [:APP_NANOID :APP_UI_TYPE]
                             :mcp-url "https://mcp.example/x"
                             :actor-did route/actor-did})]
      (doseq [r route/routes]
        (is (str/includes? html (:route/path r))
            (str (:route/path r) " がページに出ていない")))
      (is (str/includes? html "APP_NANOID"))
      (is (str/includes? html "https://mcp.example/x"))
      (is (str/includes? html route/actor-did))
      (testing "移行前のページが印字していた 2 つの偽りの文が無いこと"
        (is (not (str/includes? html "No public route is declared")))
        (is (not (str/includes? html "No public vars are declared")))))))

(deftest page-renders-what-it-is-handed
  (testing "route 表を差し替えれば表示も変わる（固定値なら変わらない）"
    (let [html (view/render {:css "/*x*/"
                             :routes [{:route/path "/only" :route/method :get
                                       :route/kind :page :route/doc "just this"}]
                             :vars [:ONE_KEY]
                             :mcp-url "https://elsewhere.example"
                             :actor-did "did:web:example.test"})]
      (is (str/includes? html "/only"))
      (is (str/includes? html "just this"))
      (is (str/includes? html "ONE_KEY"))
      (is (str/includes? html "https://elsewhere.example"))
      (testing "他の route 表の path が漏れ込んでいない"
        (is (not (str/includes? html "/health")))))))

(deftest dispatch-window-refresh-readback
  (is (= {:action :window-refresh-observation}
         (route/dispatch "GET" "/observations/window-refresh")))
  (is (= {:action :method-not-allowed :allow "GET"}
         (route/dispatch "POST" "/observations/window-refresh")))
  (testing "the surface is declared in the route table the page renders"
    (is (some #(= "/observations/window-refresh" (:route/path %)) route/routes))))

(deftest dispatch-retraction-readback
  (is (= {:action :retraction-observation}
         (route/dispatch "GET" "/observations/retraction")))
  (is (= {:action :method-not-allowed :allow "GET"}
         (route/dispatch "POST" "/observations/retraction")))
  (testing "the surface is declared in the route table the page renders"
    (is (some #(= "/observations/retraction" (:route/path %)) route/routes))))
