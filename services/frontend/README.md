# frontend

React 19 + Vite 8 + TypeScript 6 + Tailwind 4 dashboard. Streams benchmark telemetry live and renders comparison reports across every paradigm.

The frontend is a projection of the backend's SSE event stream — it doesn't own state, it just visualizes what the engine reports. **All timing shown here is measured server-side at driver level** (see `services/backend/README.md`); this app only formats and charts it.

---

## What lives where

```
src/
  App.tsx                       — router + providers (Auth, Confirm, Toaster, BackendReadyGate)
  main.tsx                      — bootstrap
  index.css                     — Tailwind 4 + OKLch design tokens (light + dark)

  pages/
    LoginPage.tsx               — Google OAuth (JWT in localStorage)
    DashboardPage.tsx           — list of benchmarks + stats
    NewBenchmarkPage.tsx        — create from topic/depth/targets, or import bundle
    BenchmarkDetailPage.tsx     — action grid, progress timeline, DB cards
    BenchmarkInsertsPage.tsx    — insert runs + latency + storage growth
    BenchmarkReadsPage.tsx      — read runs + latency distribution
    BenchmarkDeletesPage.tsx    — delete runs + cascade breakdown
    BenchmarkScenariosPage.tsx  — cross-paradigm scenario workloads
    BenchmarkComparisonPage.tsx — comparison report (radar, tables, resource charts)
    BenchmarkTestsPage.tsx      — schema entities + ERD diagram (mermaid, PNG/SVG export)

  components/
    AppLayout.tsx               — sticky glass header, breadcrumbs, dark toggle, user menu
    BackendReadyGate.tsx        — polls /api/user during warm-up, gates children
    ProtectedRoute.tsx          — JWT guard
    benchmark/                  — detail page pieces (ProgressTimeline, ActionCards, ...)
    insert/, read/, delete/     — per-operation forms, history, live panels, latency charts
    scenarios/                  — scenario form, active run panel, result preview
    comparison/                 — ParadigmRadarChart, ComparisonSummaryTables
    shared/                     — PageHeader, BackButton, EmptyState, DatabaseSelector,
                                  OperationModeSelector, DarkModeToggle, ResourceMetricsChart
    ui/                         — shadcn-on-base-ui primitives (Button, Card, Dialog,
                                  AlertDialog, Skeleton, ...)

  hooks/
    useBenchmarkData.ts         — Promise.all fetch + 404 redirect
    useBenchmarkEvents.ts       — top-level SSE hook (connect + reconnect)
    use{Insert,Read,Delete,Scenario}RunEvents.ts  — per-op SSE hooks
    useConfirm.tsx              — imperative confirm (AlertDialog wrapper)
    useDarkMode.ts              — theme state + .dark class + localStorage
    useArchivedResourceTimeline.ts — historical CPU/RAM samples

  lib/
    api.ts                      — apiFetch<T>() + ApiError + endpoint modules
    utils.ts                    — cn, relativeTime, getBenchmarkStatusConfig, ...
```

---

## Realtime data flow

Everything interesting happens over Server-Sent Events (`/api/events/{benchmarkId}`):

1. On page mount, `useBenchmarkData(id)` runs `Promise.all` over the REST snapshots (benchmark, entities, runs, registry, applicability).
2. `useBenchmarkEvents(id)` opens an `EventSource` and dispatches events by type into per-operation hooks.
3. Each op hook (`useInsertRunEvents`, ...) merges live events into the corresponding React state — new results, per-batch progress, status changes, resource-usage snapshots.
4. Charts re-render on state change; framer-motion handles enter/exit animations.

If the backend dies mid-run, the SSE hook reconnects with exponential backoff and pulls a snapshot to catch up. If the backend is still warming up, `BackendReadyGate` shows an overlay until `/api/user` answers with anything other than 503.

The Vite dev-server proxy (`vite.config.ts`) silences `ECONNREFUSED` during startup and returns a 503 JSON so the app can distinguish "backend down" from real API errors.

---

## Timing surfaces

All values coming from the backend are in nanoseconds; the frontend converts to milliseconds for display but keeps precision for tooltips.

Displayed everywhere latency shows up:

- **Engine time (dbTimeNs)** — how long the DB itself took (charted as the primary series).
- **Wire time (wireTimeNs)** — total wall-clock including all overhead.
- **Overhead (wireTimeNs − dbTimeNs)** — the delta.
- **p50 / p95 / p99 / mean** — per-DB percentiles interpolated by the backend (`LatencyStats`).
- **Rows / conflicts** — from `TimedOperation.rowsAffected` and `conflictsSkipped`.

Charts:

- `LatencyDistributionChart` (read + delete) — grouped bars per DB for p50/p95/p99/mean.
- `DatabaseSizeChart` — stacked bars, engine baseline vs inserted data (bytes), polls `/database-size` and refreshes on `database_size_dirty` SSE event.
- `ResourceMetricsChart` — CPU % and memory MB over run duration, one series per DB, click a legend entry to toggle its visibility.
- `ParadigmRadarChart` — normalized 0..100 scores across five dimensions (insert speed, read speed, delete speed, size efficiency, consistency), clickable legend.
- `ProgressTimeline` — 6-step motion timeline for benchmark lifecycle.

---

## Design system

- **shadcn on @base-ui/react** — accessible primitives, matched Tailwind 4 tokens.
- **OKLch color tokens** — full light + dark palettes in `index.css` (background, foreground, primary, muted, destructive, accent, status-\*).
- **Dark mode** — `useDarkMode()` toggles `.dark` on `document.documentElement`; state persists in localStorage.
- **framer-motion** — page fade-ins, staggered timelines, checkbox animations.
- **sonner** — toast library for all mutations (`toast.success`, `toast.error`, `toast.promise` for hard reset).
- **AlertDialog + `useConfirm()`** — imperative API for destructive confirmations. Returns `Promise<boolean>`.
- **mermaid.js** — ERD diagram rendering with 2× scale PNG export and SVG download.
- **recharts** — every chart.

---

## Auth

Google OAuth via `@react-oauth/google`. On successful sign-in the backend returns a JWT; the frontend stores it in `localStorage` and attaches it to every `apiFetch()` call as `Authorization: Bearer ...`.

`AuthProvider` reads the token **synchronously** on mount (`useState(() => readInitialUser())`) so a hard refresh on any protected route doesn't briefly redirect to `/dashboard`. `ProtectedRoute` gates the rest.

---

## Scripts

```bash
npm install
npm run dev        # Vite dev server on :5173 (proxies /api to :8080)
npm run build      # TypeScript strict build + Vite bundle
npm run preview    # serve the production build locally
npx tsc --noEmit   # type-check only (used in CI)
```

---

## Adding a new page

1. Add the route in `App.tsx`.
2. Wrap the page in `<AppLayout breadcrumbs={[...]}>`.
3. Header: `<PageHeader icon={LucideIcon} title="..." subtitle="..." actions={...} />`.
4. Data: `useBenchmarkData(id)` for the base fetch, then a per-op SSE hook for live updates.
5. Empty states: `<EmptyState icon={...} title="..." description="..." />` — never inline `<p>No X yet</p>`.
6. Mutations: wrap in `toast.promise(promise, {loading, success, error})` or `toast.success` / `toast.error`.
7. Destructive actions: `await useConfirm()({ title, description, variant: "destructive" })` — no native `window.confirm`.
