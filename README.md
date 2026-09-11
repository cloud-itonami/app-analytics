# app-analytics

**analytics —— ダッシュボード・集計メトリクス・レポートを扱うサービスの
appview（公開面）。** 集計と保存そのものはここには無く、この repo が持つのは
XRPC を MCP router へ中継する thin edge と、その面を説明するページである。

`etzhayyim/root` の `60-apps/etzhayyim-project-analytics` からの抽出物で、
**2026-08-18 に appview を TypeScript/Svelte から ClojureScript へ移行した**
（`docs/adr/0001`）。以下の数字はすべて `scripts/verify-docs-claims.cljs` が
tree から再計算して検査する —— 散文が先に古くなることを許さない。

## deploy されるものは、いま読んでいるソースである

```
src/analytics/route.cljc    判断（どの handler が答えるか）  ← 純 .cljc、テスト対象
src/analytics/view.cljc     ページ（jp-go-dds の hiccup）    ← 純 .cljc、テスト対象
src/analytics/worker.cljs   Request/Response に触る唯一の層
        ↓ shadow-cljs :target :esm
dist/worker.js              ← wrangler.jsonc の "main" が指すもの
```

移行前の `main` は `svelte/.svelte-kit/cloudflare/_worker.js` —— **この tree に
存在しないパス**（`git ls-files | grep -c svelte-kit` → `0`）を指しており、
読み手が開く `appview/analytics-mcp-component/src/app.ts` は**どの bundle にも
入っていなかった**（どの `package.json` の script からも参照されていない。
実測は `docs/operator-quickstart.md` §1）。いまは `main` が指す bundle が上の
ソースからコンパイルされたものなので、その形は構造的に起こり得ない。
`scripts/verify-docs-claims.cljs` が **shadow の出力先と wrangler の `main` と
export の ns 名の 3 つが噛み合っていること**を検査し、噛み合わなくなれば落ちる。

判断を `.cljc` に置いてあるのは、ブラウザもビルドも無しにテストするためであり、
ingress capability が qualify した時に **最初に `.kotoba` へ移る部分**だからで
ある（入口を当面 cljs に置くのは ADR-2606290000 の判断）。

## 公開ルート

| METHOD | PATH | 何をするか |
|---|---|---|
| GET | `/` | この appview の説明ページ |
| GET | `/health` | 生存確認。deploy された面が答えることを外から確かめられる |
| POST | `/xrpc/:nsid` | XRPC を MCP router へ中継する |
| OPTIONS | `/xrpc/:nsid` | CORS preflight |

**この表の出所は `analytics.route/routes` で、ページもそこから描く。** 移行前の
ページは `routeCount: 0` / `routes: []` / `vars: []` を literal で持っていた時期が
あり、隣の `wrangler.jsonc` が route 1 個と var 8 個を宣言していることに気づけ
ないまま『No public route is declared next to this app surface.』と印字して
いた。いまは route 表を渡す側が持ち、ページは描くだけなので、両者がずれる余地が
無い。

`/xrpc/` の nsid は**区切りより後ろを丸ごと**取る。移行前に deploy されていた
SvelteKit の route が `[...path]`（rest param）だったので、`/xrpc/a/b` は
`a/b` という 1 つの nsid として中継されていた —— その意味論をそのまま移して
ある（percent-encoding も SvelteKit と同じく decode する）。

### 移行前の面との差は 1 つだけ

`GET /health` は**移植ではなく追加**である。移行前に deploy されていた面では
`/health` は `not_found_handling: "none"` により**ハード 404** で、監視を
そこに向けても存在しないパスを見ていた（`docs/operator-quickstart.md` §3 が
測って記録した）。上流も binding も要らない経路なので足した。それ以外の
status code・ヘッダ・中継の封筒の形は移行前と同じである。

## いま在るもの — 38 ファイル

| 面 | ファイル |
|---|---|
| 判断・描画・edge | `src/analytics/{route.cljc, view.cljc, worker.cljs}` |
| テスト | `test/analytics/*_test.cljc`（7 本・70 tests / 364 assertions、2026-09-03 実測） |
| ビルド | `deps.edn` / `shadow-cljs.edn` / `.gitignore` |
| Worker 設定 | `appview/analytics-mcp-component/wrangler.jsonc` |
| actor 記述子 | `appview/analytics-mcp-component/kotodama.jsonld` |
| 検査 | `scripts/{smoke-worker.cljs, verify-docs-claims.cljs}` |
| 参照実装（移行対象外） | `kotoba/`（TypeScript 5 本、下記） |
| プロセス定義 | `bpmn/analytics.bpmn` |
| 由来・権利・識別 | `NOTICE` / `README.edn` / `migration.edn` / `MIGRATION-TODO.md` |
| 文書 | `README.md` / `docs/operator-quickstart.md` / `docs/adr/*.edn` |

**appview の TypeScript / Svelte / JavaScript は 0 本、正本言語（`.cljs`/`.cljc`）
が `src/` + `test/` に 16 本**（加えて `scripts/` に検査が 2 本）。移行前は
**production source が 3 対 0**（`src/app.ts`・
`svelte/src/routes/xrpc/[...path]/+server.ts`・`svelte/src/routes/+page.svelte`）、
`appview/` 配下の `.ts`/`.svelte`/`.js` を全部数えても **5 対 0** だった
（上の 3 本 + `svelte.config.js` + `vite.config.ts`）。この 2 つの数は検証器の
claim なので、TS が戻れば落ちる —— 撤去したパスに戻る場合
（`removed-by-migration-absent`）も、別名で入る場合
（`appview-ts-or-svelte-files`）も、別々の claim が捕まえる。

## UI

基盤は `kotoba-lang/jp-go-digital-design-system`（デジタル庁デザインシステム）
—— superproject の skill `kotoba-uiux` が定める新規 UI の base。色・寸法は
`--hig-*` トークン契約だけで書き、raw hex も px フォントサイズも置かない。
app 固有 CSS は 3 行。CSS は外部リクエストゼロの方針どおり
`shadow.resource/inline` で bundle に焼く。

決定論的 audit（`kotoba-lang/design-quality`）で **100.00 / 100（gate 95）**。
**この gate が測っているのはアクセシビリティと応答性の 10 軸**（viewport meta /
safe-area / tap target / focus-visible / reduced-motion / 対比など）**であって
`--hig-*` トークン規律ではない** —— 実測で確かめた（app CSS に raw hex と px を
入れても 100.00 のまま、`transition` を足すと 87.64 で FAIL）。トークン規律は
コードレビューと skill `kotoba-uiux` の側で守る。

## 持ち越さなかったもの（黙って消していない）

移行前の `appview/analytics-mcp-component/src/app.ts` にあって、**どこにも
deploy されていなかった**経路のうち、次は**意図的に移していない**。

| 経路 | 移さなかった測定上の理由 |
|---|---|
| `GET|POST /xrpc/<nsid>` → `DISPATCHER_URL` 中継 | 既定の宛先 `dispatcher.etzhayyim.com` が **NXDOMAIN**（`dig +short` が 1 行も返さない）。かつ `DISPATCHER_URL` は `wrangler.jsonc` の `vars` に**無い** |
| `DISPATCHER_INTERNAL_SECRET` を載せる internal-secret ヘッダ | この binding（var / secret とも）が `wrangler.jsonc` に**無い**。空文字を送るだけの経路になる |
| `GET /_app/meta` | `/health` と同じ payload を返す別名。SvelteKit 面では 404 で、移行後も 404 |
| health payload の `bpmn: "60-apps/etzhayyim-project-analytics/bpmn"` | 抽出元モノレポのパス。この repo でのファイルは `bpmn/analytics.bpmn` であって、その値は誰にとっても偽 |
| `GET /xrpc/<nsid>`（GET での中継） | deploy されていた面では 405。移行後も 405 |

**動かない経路を移植して「移行済み」と言わないため**である。必要になった時点で
`route.cljc` に足し、テストと binding を伴って戻す。

## `kotoba/` は移していない（移行対象外）

`kotoba/` は analytics の公開カタログ（dashboard / aggregate metric / report）の
**参照実装スライス**で、`@etzhayyim/sdk` に依存する TypeScript 5 本（251 行の
`registry.ts` を含む）と 4 本のテストからなる。**appview ではない** ——
`wrangler.jsonc` はこれを bundle にも main にも含めず、appview のどのファイルも
require していない。この移行は appview を対象にしたので、ここは 1 バイトも触って
いない（sha256 を検証器に固定）。cljs へ移すなら `@etzhayyim/sdk` の型面ごと
別の決定が要る。

**測って分かった食い違いを 1 つ記録する。** appview が宣言する 8 つの capability
（`wrangler.jsonc` の `APP_CAPABILITIES` と `kotodama.jsonld` の
`profile.capabilities`。両者は完全一致することを検証器が検査する）と、`kotoba/`
が実際に export する 10 個は、**一致しない**:

- appview だけ: `recordEvent` / `listEvents`
- `kotoba/` だけ: `recordMetric` / `listMetrics` / `publishReport` / `coverage`
- 共通: `createDashboard` / `getDashboard` / `listDashboards` / `getMetrics` /
  `createReport` / `listReports`

**移行はこれを直さない**（どちらも移行前から在る）。そもそも edge は与えられた
nsid をそのまま中継するだけで、method 一覧は上流の MCP router 側にある ——
`APP_CAPABILITIES` は enforcement ではなく documentation である。

## 呼び先が 1 つも解決しない（移行では直らない）

| ホスト | 役割 | DNS（2026-08-18 実測） |
|---|---|---|
| `pbhsahxt.etzhayyim.com` | 唯一の wrangler route | **応答なし（NXDOMAIN）** |
| `analytics.etzhayyim.com` | この appview の DID | **応答なし（NXDOMAIN）** |
| `mcp.etzhayyim.com` | `/xrpc/:nsid` の中継先 | **応答なし（NXDOMAIN）** |
| `dispatcher.etzhayyim.com` | 移していない app.ts 経路の中継先 | **応答なし（NXDOMAIN）** |

`etzhayyim.com` の apex 自体は解決する（`104.21.51.111` と `172.67.179.128`。順序は毎回入れ替わる）ので、
zone は在ってこの 4 レコードだけが無い。deploy 先も中継先も、いま存在しない。

**到達できなかったことを不透明な 500 で隠さない。** 移行前の SvelteKit の route は
`fetch` を guard しておらず、運用者が見られるのは `{"message":"Internal Error"}` の
500 だけだった（どちらの上流が落ちたのかも分からない）。移行後は **502 と
`{"error":"MCP router unreachable", "detail": …, "url": …}`** を返す。
`scripts/smoke-worker.cljs` がこれを実際にビルドした bundle に対して確かめる。

## 検証

```bash
npx --yes kbb --backend sci scripts/verify-docs-claims.cljk .    # <dir> は先頭に置く
```

exit 0 = 全一致 / 1 = 食い違い / **2 = 判定できなかった**（0 と区別する）。
テスト・描画採点・ビルド・bundle の smoke は `docs/operator-quickstart.md`。
