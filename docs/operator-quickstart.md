# Operator quickstart — app-analytics

26 tracked files: analytics（ダッシュボード・集計メトリクス・レポート）の
**appview**、その参照実装スライス、プロセス定義。読む前に 3 つ。

**deploy される Worker は、いま読んでいるソースである。** 2026-08-18 の移行前は
そうではなかった —— `wrangler.jsonc` の `main` は tree に存在しないパス
（SvelteKit のビルド出力）を指し、いちばんアプリケーションらしく読める
`src/app.ts` はどの bundle にも入っていなかった（§3・`docs/adr/0001`）。

**この repo が名指しするホストは 1 つも解決しない。** route も DID も中継先も
NXDOMAIN（§9）。移行はそれを直さない。

**`kotoba/` は appview ではなく、移行の対象外である。** TypeScript のまま
1 バイトも触っていない（§8）。

✅ が付いた節は 2026-08-18 に実際に走らせた。走らせていないものは §11 に書く。

---

## §0 環境の罠

1. **remote は `origin` ではない** —— west は remote を org 名で持つので
   `cloud-itonami`。`git fetch origin` は「そんな repo は無い」で落ちる。
2. **`error: could not read IPC response` は fsmonitor daemon** であって
   あなたのコマンドではない。`-c core.fsmonitor=false` で黙る。
3. **npm 11.x は `kotoba/` の git 依存を install できない** —— §8 に、実際に
   suite を通した回避手順がある。
4. **`.gitignore` は移行で足した。** 移行前は無く、checkout の中でビルドすると
   `node_modules/` と `.svelte-kit/` が未追跡で残った。いまは `dist/` /
   `.shadow-cljs/` / `node_modules/` / `.cpcache/` / `.wrangler/` /
   `package-lock.json` を無視する。

## §0.1 前提 ✅

| 要るもの | 確認 | この walk で使った版 |
|---|---|---|
| git | `git --version` | 2.51.0 |
| node | `node --version` | v26.3.0 |
| nbb | `npx --yes nbb --version` | v1.4.210 |
| clojure | `clojure --version` | §6 のビルド時のみ |

## §1 ✅ 何が入っているか

```bash
git -c core.fsmonitor=false ls-files | wc -l          # 26
wc -l src/analytics/*.cljc src/analytics/*.cljs test/analytics/*.cljc
#   168 src/analytics/route.cljc      判断（テスト対象）
#    93 src/analytics/view.cljc       ページ（テスト対象）
#   146 src/analytics/worker.cljs     Request/Response に触る唯一の層
#    95 test/analytics/route_test.cljc
wc -l kotoba/src/registry.ts kotoba/src/types.ts kotoba/test/analytics.test.ts
#   251 / 233 / 68                    移行対象外（§8）
```

**移行の副作用が 1 つ測れる。** 移行前、`git ls-files | awk -F/ 'NF>7'` は
1 件を返した ——
`appview/analytics-mcp-component/svelte/src/routes/xrpc/[...path]/+server.ts`。
fleet の成熟度スキャナ `scripts/itonami-maturity-scan.cljs` は
`(walk-files root 6 6000)` で歩き、**エントリ上限には truncation フラグを立てるが
深さ上限には立てない**ので、この 1 件（= この app が実際に deploy していた
唯一の route）が黙って落ちていた。移行後は:

```bash
git -c core.fsmonitor=false ls-files | awk -F/ 'NF>7' | wc -l   # 0
git -c core.fsmonitor=false ls-files | awk -F/ 'NF<=7' | wc -l  # 26
```

深さ上限の欠陥自体は直っていない（スキャナ側の話）。ここで消えたのはそれを
踏む形の方である。

## §2 ✅ deploy されるものは、いま読んでいるソースである

```bash
grep '"main"' appview/analytics-mcp-component/wrangler.jsonc
#   "main": "../../dist/worker.js",
git -c core.fsmonitor=false ls-files | grep -c svelte-kit     # 0
```

移行前の `main` は `svelte/.svelte-kit/cloudflare/_worker.js` で、これは
**tree に存在しない**（上の `grep -c` が 0 を返すのがその測定である）。同時に
`appview/analytics-mcp-component/src/app.ts` はどの `package.json` の script
からも参照されておらず、どの bundle にも入らなかった。読み手が正本を決められ
ない —— それが `docs/adr/0001` の Context である。

いまは `main` が指す `dist/worker.js` が `src/analytics/worker.cljs` を
shadow-cljs でコンパイルしたものである。**その 3 つ（shadow の出力先・
wrangler の main・export の ns 名）が噛み合っていることを §7 の検証器が
検査する**ので、噛み合わなくなれば落ちる。

## §3 ✅ 移した面と、移さなかった面

移したのは **deploy されていた 2 つ**だけ:

| METHOD | PATH | 移行前（SvelteKit） | 移行後（cljs） |
|---|---|---|---|
| GET | `/` | 200 ページ | 200 ページ |
| POST | `/xrpc/<nsid>` | MCP router へ JSON-RPC 中継 | 同じ |
| OPTIONS | `/xrpc/<nsid>` | 204 CORS | 同じ |
| GET | `/xrpc/<nsid>` | 405 | 405 |
| GET | `/health` | **404** | **200** ← 唯一の意図的な差 |
| その他 | | 404 | 404 |

`/health` は移植ではなく**追加**である。移行前は `not_found_handling: "none"`
によりハード 404 で、監視をそこに向けても存在しないパスを見ていた。上流も
binding も要らない経路なので足した。

**移していないもの**（`src/app.ts` にあり、どこにも deploy されていなかった）:
`DISPATCHER_URL` 中継（宛先 NXDOMAIN、かつ binding が `wrangler.jsonc` に無い）、
`DISPATCHER_INTERNAL_SECRET`（同じく binding が無い）、`/_app/meta`、
health payload の `bpmn:`（抽出元モノレポのパスで、ここでは偽）。理由は
README の「持ち越さなかったもの」に測定つきで書いてある。

## §4 ✅ テストを走らせる（ビルド不要・ブラウザ不要）

判断（`route.cljc`）と描画（`view.cljc`）は純 `.cljc` なので nbb だけで回る。

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:test:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > /tmp/run.cljs <<'EOF'
(require '[cljs.test :refer [run-tests]] 'analytics.route-test)
(run-tests 'analytics.route-test)
EOF
npx --yes kbb --backend sci --classpath "$CP" /tmp/run.cljs
```

実際の出力:

```
Testing analytics.route-test

Ran 7 tests containing 46 assertions.
0 failures, 0 errors.
```

何を固定しているか: `/xrpc/` の nsid が **rest param**（`/xrpc/a/b` は `a/b`、
`%2F` も decode）であること、nsid が無ければ 400 で前方一致では素通ししない
こと、MCP router の URL 解決（空白だけの設定は未設定扱い）、`error` を
`result` より先に見ること、body を包み直すので `content-length` を転送しない
こと、そして **ページが route 表から描かれること**（固定値を焼いていたら落ちる）。

**この 4 つは実際に落とした**（各 mutation とそれが赤くした test は
`docs/adr/0001` の検証節）。

## §5 ✅ ページを描画して採点する

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > /tmp/render.cljs <<'EOF'
(require '["node:fs" :as fs] '[analytics.view :as view] '[analytics.route :as route])
(let [css (.readFileSync fs (str (.-DDS js/process.env) "/resources/jp_go_dds/dds.css") "utf8")]
  (.writeFileSync fs "/tmp/an-page.html"
    (view/render {:css css :routes route/routes
                  :vars [:AGENTGATEWAY_MCP_ROUTER_URL :APP_CAPABILITIES :APP_DESCRIPTION
                         :APP_DISPLAY_NAME :APP_FRAMEWORK :APP_NANOID
                         :APP_PERFORMER_TYPE :APP_UI_TYPE]
                  :mcp-url (route/mcp-router-url {})
                  :actor-did route/actor-did}))
  (println "rendered" (.-size (.statSync fs "/tmp/an-page.html")) "bytes"))
EOF
DDS="$K/jp-go-digital-design-system" npx --yes kbb --backend sci --classpath "$CP" /tmp/render.cljs
cd $K/design-quality && npx --yes kbb --backend sci -m design-quality.cli score /tmp/an-page.html --min 95
```

実際の出力:

```
rendered 82048 bytes
  100.00  /tmp/an-page.html
aggregate: 100.00
gate: aggregate 100.00 >= min 95.00 -> PASS      (exit 0)
```

**この gate が測っているものを取り違えないこと。** 10 軸は viewport meta /
safe-area / tap target / focus-visible / reduced-motion / 対比などの
**アクセシビリティと応答性**であって、`--hig-*` トークン規律ではない。実測で
確かめた: app CSS に raw hex と `11px` を入れても **100.00 のまま**だった。
一方 app CSS に `transition: color 0.2s ease;` を足すと（`prefers-reduced-motion`
が無いので）**87.64 に落ちて gate は FAIL（exit 1）**。つまりこの gate は
「DADS の上に建て、それを台無しにしていないこと」を証明する。トークン規律は
コードレビューと `kotoba-uiux` の側で守る。

## §6 bundle をビルドする ✅

**高負荷ビルドは workspace 全体で同時 1 本**に制限されている（superproject
`CLAUDE.md` の resource governor）。直接叩かず必ず guard 経由で:

```bash
node ~/github/com-junkawasaki/scripts/resource-guard.mjs run build -- \
  npx --yes amu compile --target wasm32-browser worker
ls -la dist/worker.js
```

lock を他セッションが持っていると **exit 2** で拒否される。
`resource-guard: build is already running (pid=…)` は**エラーではなく順番待ち**
なので、迂回せずに待つ。この walk では **45 秒間隔で 44 回 retry**（約 33 分、
別セッションの `cloud-murakumo` と `protocols-worker` のビルドが順に lock を
持っていた）してから通り、ビルド自体は 132.95 秒だった。

実際の出力（末尾）:

```
shadow-cljs - config: /private/tmp/app-analytics-cljs/shadow-cljs.edn
shadow-cljs - starting via "clojure"
[:worker] Compiling ...
[:worker] Build completed. (55 files, 12 compiled, 0 warnings, 132.95s)

-rw-r--r--  1 junkawasaki  wheel  246174  8月 18 18:48 dist/worker.js
```

`WARNING: shadow-cljs not installed in project` と `sun.misc.Unsafe` の警告が
先に出るが、どちらも致命ではない（前者は npm に入れず `clojure` 経由で回して
いるため、後者は protobuf-java が JDK の非推奨 API を呼ぶため）。

### 壊れた var はビルドを **落とす**（2026-08-18 実測）

`shadow-cljs.edn` の `:compiler-options` に `:warnings-as-errors true` を入れた。
入れる前は、存在しない var を参照しても shadow は **WARNING** を出して **exit 0**
し、最初のリクエストで `Cannot read properties of undefined` を投げる bundle を
書いていた ——「ビルドが通った」は検査ではなかった（**落ちようがなかった**）。

この repo で実際に落として確かめた。`src/analytics/worker.cljs:123` の
`route/dispatch` を、存在しない `route/dispatch-nonexistent` に改名して再ビルドする:

```
------ ERROR -------------------------------------------------------------------
 File: src/analytics/worker.cljs:123:44
```

| | exit | `dist/worker.js` sha256 | bytes |
|---|---|---|---|
| 改名前 | **0** | `34a1ade5…40321386` | 246174 |
| 改名後 | **1** | `34a1ade5…40321386`（**不変**） | 246174 |
| 戻して再ビルド | **0** | `34a1ade5…40321386` | 246174 |

**落ちたビルドは bundle を出荷しない** —— sha256 が 1 バイトも動いていないことが
それを言っている。

キーは `:build-options` ではなく **`:compiler-options`** に置く。shadow が読むのは
`[:compiler-options :warnings-as-errors]` で、置き場所を間違えると**黙って無視される**
—— この option が防ぐはずの失敗（落ちようのない検査）そのものになる。

## §7 ビルドした成果物を実際に叩く ✅

ここが **deploy されるものに触る唯一の検査**である。§4 のテストはソースの判断を
固定するが、bundle が Worker の形で答えるかは言えない —— export の形、
`:advanced-optimization` 下の env キー、`shadow.resource/inline` で焼いた CSS は、
どれもビルドを通って初めて存在する。

```bash
npx --yes kbb --backend sci scripts/smoke-worker.cljk dist/worker.js
```

実際の出力（27 項目すべて PASS、exit 0。抜粋）:

```
PASS	default export has fetch	expected=true	actual=true
PASS	GET / status	expected=200	actual=200
PASS	GET / is html	expected=true	actual=true
PASS	page advertises /health	expected=true	actual=true
PASS	page advertises /xrpc/:nsid	expected=true	actual=true
PASS	page advertises この appview の説明ページ	expected=true	actual=true
PASS	page advertises CORS preflight	expected=true	actual=true
PASS	page shows a var key	expected=true	actual=true
PASS	page shows every var key	expected=true	actual=true
PASS	page hides var values	expected=false	actual=false
PASS	page shows the resolved router url	expected=true	actual=true
PASS	page shows the actor did	expected=true	actual=true
PASS	page carries the design system	expected=true	actual=true
PASS	page does not repeat the old false sentence	expected=false	actual=false
PASS	GET /health status	expected=200	actual=200
PASS	health names its routes	expected=true	actual=true
PASS	POST /xrpc/ status	expected=400	actual=400
PASS	POST /xrpc/ says why	expected=true	actual=true
PASS	OPTIONS preflight	expected=204	actual=204
PASS	OPTIONS advertises methods	expected="POST,OPTIONS"	actual="POST,OPTIONS"
PASS	unknown path	expected=404	actual=404
PASS	wrong method on /health	expected=405	actual=405
PASS	405 carries allow	expected="GET"	actual="GET"
PASS	GET on /xrpc is 405	expected=405	actual=405
PASS	405 on xrpc allows POST, OPTIONS	expected="POST, OPTIONS"	actual="POST, OPTIONS"
PASS	unreachable upstream is 502	expected=502	actual=502
PASS	unreachable upstream says so	expected=true	actual=true
OK	the built bundle answers as the route table says
```

**bundle が無ければ exit 2**（「判定できなかった」であって合格ではない）:

```
UNDETERMINED	no bundle at /private/tmp/app-analytics-cljs/dist/worker.js
Refusing to report a pass: build it first (see docs/operator-quickstart.md §6).
```

env の**値**が漏れていないことは印（`SENTINEL-4c7e1b`）で見ている。実在しそうな
値（`"yoro"` 等）を引用符ごと探す形は、renderer が `"` を `&quot;` に escape
するので**構造的に落ちない** —— app-ongakuka の移行で実測して踏んだ罠なので、
こちらは最初から印を使っている。

## §7.5 ✅ 散文の数を tree から再計算する

```bash
npx --yes kbb --backend sci scripts/verify-docs-claims.cljk .     # <dir> は先頭に置く
```

実際の出力（末尾）:

```
SCANNED	26
PASS	tracked-files	expected=26	actual=26
...
OK	every claim in README.md and docs/operator-quickstart.md holds
```

18 claim すべて PASS、exit 0。**exit 2（UNDETERMINED）は 0 ではない** ——
tree を読み切れなかったという別の答えで、「検査して問題なし」と混ぜない
（実測: 追跡ファイル 0 件の tree に当てると `SCANNED 0` → exit 2）。

この検証器には移行の不変条件が入っている: 撤去した 9 パスが戻っていないこと、
appview に `.ts`/`.svelte` が 1 本も無いこと、`main` が shadow の出力先を
指していること、`assets` と `rules` が消えていること、移行が触っていない
13 ファイルが sha256 で同一であること、そして `wrangler.jsonc` と
`kotodama.jsonld` が同じ 8 capability を宣言していること。**4 通りの mutation で
実際に落とした**（`docs/adr/0001` の検証節）。

## §8 ✅ `kotoba/` は移行対象外 —— それでもテストは通る

`kotoba/` は analytics の公開カタログの参照実装スライスで、**appview ではない**
（`wrangler.jsonc` はこれを bundle にも main にも含めず、appview のどのファイル
も require していない）。移行は 1 バイトも触っていない。

`npm install` は失敗する —— 依存 2 つとも git URL で、その準備が入れ子の install
を走らせ、npm 11.x がそれを拒否する（`EALLOWSCRIPTS`）。実 SDK は**型のためだけ**
に要り、mock は自立しているので、**checkout の外**で mock をディスクから入れる:

```bash
rm -rf /tmp/analytics-sdk /tmp/analytics-build
mkdir -p /tmp/analytics-sdk && cd /tmp/analytics-sdk
git clone -q https://github.com/etzhayyim/com-etzhayyim-sdk-mock.git sdk-mock
git -C sdk-mock checkout -q c857ff9be5310bf433bfe1e8d3c0f677e213d667

mkdir -p /tmp/analytics-build && cp -R "$REPO/kotoba" /tmp/analytics-build/kotoba
cd /tmp/analytics-build/kotoba && node -e '
const fs=require("fs");
let f="/tmp/analytics-sdk/sdk-mock/package.json";
let p=JSON.parse(fs.readFileSync(f,"utf8")); delete p.dependencies;
fs.writeFileSync(f,JSON.stringify(p,null,2));
p=JSON.parse(fs.readFileSync("package.json","utf8")); delete p.dependencies;
p.devDependencies={"@etzhayyim/sdk-mock":"file:/tmp/analytics-sdk/sdk-mock","typescript":"^5.6.0","vitest":"^4.1.0"};
fs.writeFileSync("package.json",JSON.stringify(p,null,2));'
npm install --ignore-scripts --no-audit --no-fund
npx vitest run
```

実際の出力（2026-08-18 に再実行）:

```
added 47 packages in 49s

 RUN  v4.1.10 /private/tmp/analytics-build/kotoba

 Test Files  1 passed (1)
      Tests  4 passed (4)
   Duration  3.31s
```

この 4 本が実際に discriminate することは 2026-08-16 の walk が 8 mutant で
確かめている（8/8 kill、`git log` にその quickstart 版が残っている）。**今回の
walk では再実行していない** —— `kotoba/` は移行で 1 バイトも変わっておらず、
検証器が sha256 で固定しているため。

**測って分かった食い違いを 1 つ**: appview が宣言する 8 capability と `kotoba/`
が export する 10 は一致しない（appview だけ `recordEvent` / `listEvents`、
`kotoba/` だけ `recordMetric` / `listMetrics` / `publishReport` / `coverage`）。
移行はこれを直さない。edge は与えられた nsid をそのまま中継するだけで、
method 一覧は上流の MCP router 側にある。

## §9 ✅ 名指しするホストが 1 つも解決しない

```bash
for h in pbhsahxt.etzhayyim.com analytics.etzhayyim.com \
         mcp.etzhayyim.com dispatcher.etzhayyim.com etzhayyim.com; do
  printf '%-30s ' "$h"; dig +short "$h" | tr '\n' ' '; echo
done
#   pbhsahxt.etzhayyim.com         (空)   ← 唯一の wrangler route
#   analytics.etzhayyim.com        (空)   ← この appview の DID
#   mcp.etzhayyim.com              (空)   ← /xrpc の中継先
#   dispatcher.etzhayyim.com       (空)   ← 移していない app.ts 経路の中継先
#   etzhayyim.com                  104.21.51.111 172.67.179.128   ← 2 レコードの順序は毎回入れ替わる
```

zone 自体は生きていて、この 4 レコードだけが無い。

**移行はこれを直さないが、見え方は変えた。** 移行前の SvelteKit の route は
`fetch` を guard しておらず、運用者が見られるのは `{"message":"Internal Error"}`
の 500 だけで、どちらの上流が落ちたのかも分からなかった。移行後は **502 と
`{"error":"MCP router unreachable", "detail": …, "url": …}`** を返す。§7 の
smoke がそれを、届かない先（`http://127.0.0.1:1/x`、DNS を引かないので offline
でも決定論的）に対して実際に確かめる。

## §10 deploy — この walk では実行していない

```bash
cd appview/analytics-mcp-component
npx wrangler deploy
```

**route が指すホストは解決しない**（§9）ので、deploy が成功しても誰も到達でき
ない。中継先も同様なので、到達できたとしても中継は 502 を返す。superproject の
deploy guard は `origin/main` を包含した checkout からの deploy しか許さない点も
併せて注意（west checkout の remote は org 名なので、その guard は
`origin/main` を解決できず fail-open する —— superproject CLAUDE.md が
2026-08-13 に記録した既知の穴）。

## §11 この walk で走らせていないもの

- **`wrangler deploy`**（§10）。オーナー指示により deploy はしない。
- `kotoba/` の 8 mutant による discrimination 検査（§8。2026-08-16 の walk の
  結果を引用しており、今回は再実行していない）。
- `MIGRATION-TODO.md` の憲章適合レビュー 7 項目。移行前から未チェックのまま。
- `kotoba/` の cljs 移行（§8。この移行の対象ではない）。

## §11.5 ✅ compatibility_flags を外してよいことを実際に確かめた

移行前の `wrangler.jsonc` は `nodejs_compat` と `nodejs_als` を宣言していた。
これは SvelteKit の `adapter-cloudflare` が要求するもので（`getRequestEvent` が
`AsyncLocalStorage` を使う）、cljs の bundle には要らない。**外してから 2 通りで
確かめた。**

静的（bundle が何を要求しているか）:

```bash
grep -o 'from *"node:[a-z_]*"' dist/worker.js | sort -u | wc -l   # 0
grep -c 'require(' dist/worker.js                                 # 0
grep -c 'AsyncLocalStorage' dist/worker.js                        # 0
grep -c 'process\.' dist/worker.js                                # 0
grep -c '\bBuffer\b' dist/worker.js                               # 0
```

動的（フラグ無しの workerd で実際に動くか）:

```bash
cd appview/analytics-mcp-component && npx --yes wrangler@latest dev --port 8799
```

```
[wrangler:info] Ready on http://localhost:8799
```

`curl` で全 route を叩いた結果（**deploy はしていない。local workerd のみ**）:

| 叩いたもの | 返ってきたもの |
|---|---|
| `GET /` | 200 `text/html`、`/xrpc/:nsid` を 2 箇所で宣伝、`APP_NANOID` は出て `yoro` は出ない、`dads-*` class 71 個 |
| `GET /health` | 200 `{"ok":true,…,"routes":["GET /","GET /health","POST /xrpc/:nsid","OPTIONS /xrpc/:nsid"]}` |
| `POST /xrpc/` | 400 `{"error":"Missing XRPC method"}` |
| `OPTIONS /xrpc/x` | 204 + `access-control-allow-methods: POST,OPTIONS` |
| `GET /xrpc/x` | 405 |
| `GET /nope` | 404 |
| `POST /xrpc/<nsid>` | 502 `{"error":"MCP router unreachable",…}`（上流が NXDOMAIN） |

dev のログに `no such module` / `nodejs_compat` / `Uncaught` は **0 件**。
`rules` の CompiledWasm も外した —— tree に `.wasm` は 1 つも無く（検証器の
`no-wasm-in-tree` claim）、この bundle も 1 つも出さないので inert だった。

## §12 checkout を汚さない

```bash
rm -rf dist .shadow-cljs node_modules .cpcache .wrangler
git -c core.fsmonitor=false status --porcelain     # 何も出ないこと
```

移行で足した `.gitignore` がこの 5 つを無視するので、ビルドしても未追跡ファイル
は残らない（移行前は `.gitignore` が無く、5 個残った）。§8 は `/tmp` で走るので
ここには何も触らない。
