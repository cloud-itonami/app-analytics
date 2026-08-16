# Operator quickstart — app-analytics

23 tracked files: dashboards, aggregate metric datapoints and reports for an
analytics service, plus a Cloudflare edge surface. Three things to know before
reading them.

**Two implementations of the same eight methods live here, and the one that looks
like the entry point is not the one that ships.** `wrangler.jsonc`'s `main` is the
SvelteKit build; `appview/analytics-mcp-component/src/app.ts` is not deployed. They
disagree about routing (§3).

**Nothing this repository addresses resolves.** All four hostnames it names —
its own route, its DID, the MCP router it forwards to, the dispatcher `src/app.ts`
proxies to — are `NXDOMAIN` (§7).

**This is not one of the nine `app-air-*` repositories, and their findings do not
transfer.** The sharpest one does not: there `APP_CAPABILITIES` holds the first
three of eight methods, a truncation. Here it holds all eight, in order, byte-identical
to the handler's own list (§3). Check, don't assume.

Steps marked ✅ were run on 2026-08-16. §9 says what was not walked.

---

## §0 Environment traps

1. **the remote is not `origin`** — west names remotes after the org, so it is
   `cloud-itonami`; `git fetch origin` fails on access rights.
2. **`error: could not read IPC response` is the fsmonitor daemon**, not your
   command. `-c core.fsmonitor=false` silences it.
3. **npm 11.16 cannot install `kotoba/`'s git dependencies** — §5 has a workaround
   that runs the suite.
4. **there is no `.gitignore`** (`ls -a | grep -c gitignore` → `0`). Building in the
   checkout leaves `node_modules/`, `.svelte-kit/` and any `page.html` untracked.
   Build in a worktree, or clean up (§10).

`cloud-itonami/app-air-crew/docs/operator-quickstart.md` §0 documents the same traps
at greater length; they are properties of the fleet, not of this repository.

## §1 ✅ What this repository actually contains

```bash
wc -l appview/analytics-mcp-component/src/app.ts \
      appview/analytics-mcp-component/svelte/src/routes/xrpc/'[...path]'/+server.ts \
      kotoba/src/registry.ts kotoba/src/types.ts kotoba/test/analytics.test.ts
#    75 appview/.../src/app.ts                  (not deployed — §3)
#    60 appview/.../routes/xrpc/[...path]/+server.ts   (deployed)
#   251 kotoba/src/registry.ts                  (the domain logic)
#   233 kotoba/src/types.ts
#    68 kotoba/test/analytics.test.ts
grep -cE '\b(it|test)\(' kotoba/test/analytics.test.ts   # 4
git -c core.fsmonitor=false rev-list --count HEAD        # 4
```

The layout differs from the `app-air-*` family: there `src/` and `svelte/` sit at the
repository root, here they are nested under `appview/analytics-mcp-component/`, while
`kotoba/` and `bpmn/` stay at the root. That nesting is not cosmetic — it is what
makes one file invisible to the fleet instrument (§8).

`kotoba/src/registry.ts` is the only place a reader learns what the app does:
`createDashboard` / `getDashboard` / `listDashboards`, `recordMetric` / `listMetrics` /
`getMetrics` (an app-layer sum/count/min/max rollup, because AT PDS has no `GROUP BY`),
`createReport` / `publishReport` / `listReports`, and `coverage`. Reports may
FK-reference a dashboard; metric values are integers only and carry no per-user rows.

## §2 ✅ The provenance claim is byte-exact

`migration.edn` states the repository is a verbatim extraction of
`etzhayyim/root@f9432ab5` `60-apps/etzhayyim-project-analytics` — 21 files, 44077
bytes — plus exactly two additions. That is checkable, and it holds:

```bash
node -e '
const {execSync}=require("child_process");
const added=new Set(["README.edn","migration.edn"]);
let n=0,bytes=0;
for(const line of execSync("git ls-tree -r -l c2836a6",{encoding:"utf8"}).trim().split("\n")){
  const m=line.match(/^\S+\s+blob\s+\S+\s+(\d+)\t(.+)$/);
  if(m && !added.has(m[2])){ n++; bytes+=parseInt(m[1],10); }
}
console.log(n, bytes);'
#   21 44077     ← exactly what migration.edn declares
```

**Run it against `c2836a6`, not the working tree.** The commit that added this
document also changed `+page.svelte` (§4), so the number moved. A provenance check
that reads `HEAD` will start failing for a reason that has nothing to do with
provenance.

## §3 ✅ Two implementations, one deployed, and they disagree

```bash
grep '"main"' appview/analytics-mcp-component/wrangler.jsonc
#   "main": "svelte/.svelte-kit/cloudflare/_worker.js",
```

So `src/app.ts` — the file with the readable dispatcher, the DID and the health
endpoint — never runs. Built and served locally (`npm run build && vite preview`), the
deployed worker answers:

| path | deployed (SvelteKit) | what `src/app.ts` would do |
|---|---|---|
| `GET /` | **200** landing page | 404 `NotFound` |
| `GET /health` | **404** | 200 `{ok, actor, nanoid, methods…}` |
| `GET /_app/meta` | **404** | 200, same payload |
| `GET /xrpc/<nsid>` | **405** | proxied — it accepts GET and POST |
| `POST /xrpc/<nsid>` | 500 (§7) | proxies to `dispatcher.etzhayyim.com` |
| `OPTIONS /xrpc/<nsid>` | **204** CORS | 404 |

Three consequences worth writing down. **A monitor pointed at `/health` is watching a
path that does not exist** — `not_found_handling: "none"` makes it a hard 404. **A
client that sends `GET /xrpc/…`** — which the undeployed handler explicitly supports —
**gets 405.** And the health payload advertises `bpmn:
"60-apps/etzhayyim-project-analytics/bpmn"`, a path in the monorepo this was extracted
from; here the file is `bpmn/analytics.bpmn`.

The two also route to different upstreams: the deployed route forwards to
`AGENTGATEWAY_MCP_ROUTER_URL` as a JSON-RPC `tools/call`, `src/app.ts` forwards to
`DISPATCHER_URL` as plain JSON. Neither host resolves (§7).

**`APP_CAPABILITIES` is complete here.** Verified rather than assumed, because the
sibling family's is not:

```bash
node -e '
const fs=require("fs");
const w=fs.readFileSync("appview/analytics-mcp-component/wrangler.jsonc","utf8");
const caps=JSON.parse(JSON.parse(w.match(/"APP_CAPABILITIES":\s*("(?:[^"\\]|\\.)*")/)[1]));
const src=fs.readFileSync("appview/analytics-mcp-component/src/app.ts","utf8");
const m=src.match(/methods:\s*\[([\s\S]*?)\]/)[1].match(/"([A-Za-z]+)"/g).map(s=>s.slice(1,-1));
console.log(caps.length, m.length, JSON.stringify(caps)===JSON.stringify(m));'
#   8 8 true
```

It is still documentation rather than enforcement — `grep -c 'analytics'` on the
deployed route returns **0**, because it forwards whatever NSID it is given and the
method list lives upstream in the MCP router.

## §4 ✅ The landing page told visitors it had no routes and no vars — fixed, and the render was checked

`appview/analytics-mcp-component/svelte/src/routes/+page.svelte` embeds a summary
object and renders it. Before this commit it said `routeCount: 0, routes: [], vars: []`,
with a `relativePath` pointing into the monorepo this repository was extracted from —
so the page printed two sentences that its own `wrangler.jsonc` contradicts.

**Rendered over HTTP, before and after.** `vite preview` serves the built worker, so
the page can be fetched rather than reasoned about:

```bash
cd appview/analytics-mcp-component/svelte
npm install --no-audit --no-fund && npm run build
npx vite preview --port 4412 &
curl -s http://localhost:4412/ > page.html
grep -o 'No public route is declared\|No public vars are declared' page.html | wc -l
grep -o 'pbhsahxt\.etzhayyim\.com/\*' page.html | wc -l
grep -oE 'Routes</span><strong[^>]*>[0-9]+' page.html
```

| in the rendered HTML | before | after |
|---|---|---|
| false sentences present | 2 | **0** |
| names its own route pattern | 0 | **1** |
| var names listed | 0 | **8** |
| the `Routes` figure | `0` | **`1`** |
| var **values** leaked | 0 | **0** |

The two sentences it used to print were:

> No public route is declared next to this app surface.
> No public vars are declared in the nearest wrangler config.

Both name the wrangler config and both were false — it declares one route pattern and
eight vars.

**Why fetching the page is the gate and grepping the bundle is not.** Svelte compiles
**both** branches of an `{#if}` into the component, so the false sentences remain in
the build output even when they cannot render. A grep over the bundle shows the data
entering the build and nothing about which branch wins. The sibling `app-air-sched`
was verified that way and the check did not settle it.

The summary is now populated from `wrangler.jsonc`: `routeCount: 1`, the one pattern,
the eight var **names** (the page prints keys only — the last row above is the check
that no value escaped), and a `relativePath` inside this repository.

**There is no generator for this object**, in this repository or in the root's
`scripts/`, so it is hand-maintained: change routes or vars in `wrangler.jsonc` and
this object will not follow.

## §5 ✅ Run the tests

`npm install` in `kotoba/` fails — both dependencies are git URLs whose preparation
runs a nested install that npm 11.16 refuses (`EALLOWSCRIPTS`), and an `allowScripts`
field does not help because the rejection happens inside the nested install.

The workaround rests on two facts you can check:

```bash
grep -n '@etzhayyim/sdk' kotoba/src/registry.ts kotoba/test/analytics.test.ts
#   registry.ts:7:      import type { Etzhayyim } from "@etzhayyim/sdk";   ← type-only, erased
#   analytics.test.ts:2: import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
```

The real SDK is needed only for types; the mock is standalone. Install the mock from
disk with its unused dependency removed, **in a copy, never in the checkout**:

```bash
rm -rf /tmp/analytics-sdk /tmp/analytics-build
mkdir -p /tmp/analytics-sdk && cd /tmp/analytics-sdk
git clone -q https://github.com/etzhayyim/com-etzhayyim-sdk-mock.git sdk-mock
git -C sdk-mock checkout -q c857ff9be5310bf433bfe1e8d3c0f677e213d667   # the pinned SHA

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
#   Test Files  1 passed (1)
#         Tests  4 passed (4)
```

## §6 ✅ Do those four tests discriminate? Eight mutants say yes

Four green tests prove nothing until you have seen them go red. Each mutation below
was required to match **exactly once** in `kotoba/src/registry.ts` before being
applied — a replacement that silently matches nothing produces a red-free run that
looks exactly like a surviving mutant, which is the failure mode this whole check
exists to avoid.

| mutation | result | tests |
|---|---|---|
| M1 drop the integer-only guard on metric values | RED | 1 failed / 3 passed |
| M2 rollup `min` computed with `Math.max` | RED | 1 failed / 3 passed |
| M3 drop `listMetrics`'s `since` filter | RED | 1 failed / 3 passed |
| M4 drop the report → dashboard FK check | RED | 1 failed / 3 passed |
| M5 drop the double-publish guard | RED | 1 failed / 3 passed |
| M6 drop dashboard widget validation | RED | 1 failed / 3 passed |
| M7 rollup `sum` accumulates 0 | RED | 1 failed / 3 passed |
| M8 coverage stops tallying reports by status | RED | 1 failed / 3 passed |

Eight of eight killed; `registry.ts` restored byte-identical afterwards and the suite
green again. Four tests are few, but they are not decoration: every invariant the
registry states in prose — integers only, aggregate rollup arithmetic, the optional
FK, publish-once — is actually held down by one of them.

## §7 ✅ Nothing it addresses resolves

```bash
for h in pbhsahxt.etzhayyim.com analytics.etzhayyim.com \
         mcp.etzhayyim.com dispatcher.etzhayyim.com; do
  printf '%-28s ' "$h"; dig @1.1.1.1 +noall +comment "$h" A | grep -oE 'status: [A-Z]+'
done
#   pbhsahxt.etzhayyim.com       status: NXDOMAIN   ← its only wrangler route
#   analytics.etzhayyim.com      status: NXDOMAIN   ← its DID and the src/app.ts header
#   mcp.etzhayyim.com            status: NXDOMAIN   ← upstream of the deployed route
#   dispatcher.etzhayyim.com     status: NXDOMAIN   ← upstream of src/app.ts
dig +short @1.1.1.1 NS etzhayyim.com    # everton/vivienne.ns.cloudflare.com — the zone exists
```

The zone is live on Cloudflare and the apex resolves; these four records simply are not
there. The repository declares a `did:web:analytics.etzhayyim.com` while shipping a
route for `pbhsahxt.etzhayyim.com`, and neither name exists.

**This surfaces as an opaque 500, not a diagnosable error.** The deployed route handles
upstream *errors* (non-2xx, JSON-RPC `error`) but not upstream *unreachability* — the
`fetch` is not guarded:

```bash
curl -s -X POST -H 'content-type: application/json' -d '{}' \
  http://localhost:4412/xrpc/com.etzhayyim.apps.analytics.listDashboards
#   {"message":"Internal Error"}          http 500
#   server log: TypeError: fetch failed
```

An operator seeing that 500 in production learns nothing about which of the two
possible upstreams failed, or why.

## §8 ✅ The fleet instrument cannot see one of the 23 files — and does not say so

`scripts/itonami-maturity-scan.cljs` walks each repository with
`(walk-files root 6 6000)` and reports `:repo/files-truncated?` when it hits the
**6000-entry** cap. It reports nothing when it hits the **depth-6** cap. This
repository's nesting crosses that line exactly once:

```bash
git -c core.fsmonitor=false ls-files | wc -l                 # 23
git -c core.fsmonitor=false ls-files | awk -F/ 'NF<=7' | wc -l  # 22  ← what the walk reaches
git -c core.fsmonitor=false ls-files | awk -F/ 'NF>7'
#   appview/analytics-mcp-component/svelte/src/routes/xrpc/[...path]/+server.ts
```

and the evidence row agrees: `:repo/file-count 22`, `:repo/files-truncated? false`.

The one file it drops is **the only route this app actually deploys** (§3). The scan's
own docstring says truncation "must not happen silently, because a truncated repo
collapses to `src`/`test` = 0 and becomes indistinguishable from a repo with no
implementation" — the entry cap honours that and the depth cap does not. It is the
shape CLAUDE.md names: a check that *could not look* returning the same value as a
check that *looked and found nothing*.

No score moves because of it here (§9 explains why), so this is a note for whoever
raises the depth or adds a depth-truncation flag, not a defect of this repository.

## §9 What the maturity instrument sees here, and what is not a gap ✅

```
· orgs/cloud-itonami/app-analytics  own=0.049  axis-docs=0bp → +2500bp
    ⚠ README が .md ではないので docs の README 成分は 0（README.edn 等が 1 件）
    ⚠ taxonomy に :repo/kind の行が無い → :default の重みで採点されている
```

Both warnings are about the instrument, and one more is invisible in that output:

- **`README.edn` declares `:canonical-metadata :edn`**, so EDN is deliberately
  canonical here while `:doc/readme-bytes` reads `README.md`. Adding a second README
  to move a number would be exactly the padding the loop forbids.
- **No row in `manifest/repo-taxonomy.edn`** (`grep -c` → `0`), so this is scored
  against the `:default` weight profile and its `own` is not comparable to a
  repository whose kind is known — even though `README.edn` states `:kind :app`.
- **`axis-substrate` and `axis-test` are structurally 0 and always will be.** The scan
  counts only `cljc`/`cljs`/`clj`/`kotoba` under `src/` and `test/`. Every source file
  here is TypeScript, so 251 lines of registry and 4 discriminating tests are counted
  as nothing — and they are not even reported under `:uncounted/*`, because that
  fallback filters on the same extension set. The tick is right to mark
  `axis-substrate` as not targetable; the honest reading is that for a TypeScript
  repository the instrument measures documentation, freshness and citations, and is
  blind to the code.

Recorded in ADR-2608052000. None of these are closed by adding files.

## §10 Leave the checkout clean

There is no `.gitignore` (§0.4), so §4 leaves five untracked artifacts, not the three
you would guess — `npm install` writes a `package-lock.json` the repository does not
track, and the Cloudflare adapter's preview writes `.wrangler/`:

```bash
cd appview/analytics-mcp-component/svelte
rm -rf node_modules .svelte-kit .wrangler page.html package-lock.json
cd - && git -c core.fsmonitor=false status --porcelain    # must print nothing
```

Verified by running it: the first three alone leave `.wrangler/` and
`package-lock.json` behind. §5 and §6 already run in `/tmp` and touch nothing here.
