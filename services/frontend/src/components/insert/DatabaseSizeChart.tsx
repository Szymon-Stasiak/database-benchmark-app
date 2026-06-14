import { useCallback, useEffect, useRef, useState } from "react"
import { DownloadChartButton } from "@/components/benchmark/DownloadChartButton"
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { BarChart3, Loader2, RefreshCw } from "lucide-react"
import { ApiError, insertApi } from "@/lib/api"
import type { DatabaseSizeResponse } from "@/types/insert"
import { useBenchmarkEvents } from "@/hooks/useBenchmarkEvents"

interface Props {
  benchmarkId: string
  /** Refresh interval in ms; pass 0 to disable auto-refresh. The backend also pushes a
   *  `database_size_dirty` SSE event after each insert phase, so the chart updates
   *  immediately on actual user activity even with a relaxed poll interval. */
  refreshMs?: number
}

/**
 * Vertical stacked bar chart — one bar per database, dark segment = engine baseline,
 * accent segment = data inserted after deployment. Built with recharts so we get hover
 * tooltips and a clean legend for free.
 */
export function DatabaseSizeChart({ benchmarkId, refreshMs = 30000 }: Props) {
  const [sizes, setSizes] = useState<DatabaseSizeResponse[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null)
  const stoppedRef = useRef(false)
  const chartRef = useRef<HTMLDivElement | null>(null)

  const refresh = useCallback(async () => {
    if (stoppedRef.current) return
    setLoading(true)
    setError(null)
    try {
      const data = await insertApi.getDatabaseSizes(benchmarkId)
      // Merge: if backend says a DB is currently unavailable (probe timed out and we have no
      // server-side cache for it yet), preserve the last good values we already had so we don't
      // visually zero-out a busy DB just because its `du` was slower than the others this round.
      setSizes((prev) => mergePreservingLastKnown(prev, data))
      setLastRefreshed(new Date())
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        stoppedRef.current = true
        setError("Benchmark no longer exists — polling stopped.")
        return
      }
      setError(e instanceof Error ? e.message : "Failed to load sizes")
    } finally {
      setLoading(false)
    }
  }, [benchmarkId])

  useEffect(() => {
    stoppedRef.current = false
    void refresh()
    if (refreshMs > 0) {
      const t = setInterval(() => {
        if (stoppedRef.current) {
          clearInterval(t)
          return
        }
        void refresh()
      }, refreshMs)
      return () => clearInterval(t)
    }
  }, [refresh, refreshMs])

  // The orchestrator fires `database_size_dirty` per (db × entity) phase as soon as the strategy
  // returns — gives us instant chart updates without dropping the poll interval to 5s.
  useBenchmarkEvents(benchmarkId, (event) => {
    if (event.type === "database_size_dirty") {
      void refresh()
    }
  })

  const chartData = sizes.map((s) => {
    const baselineKnown = s.baselineBytes != null
    const baseline = s.baselineBytes ?? 0
    const data = baselineKnown
      ? (s.dataBytes ?? 0)
      : (s.sizeBytes ?? 0)
    return {
      name: s.dbName,
      baseline,
      data,
      baselinePending: !baselineKnown,
      raw: s,
    }
  })

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between gap-3">
          <CardTitle className="text-base inline-flex items-center gap-2">
            <BarChart3 className="h-4 w-4" />
            On-disk size — engine vs data
          </CardTitle>
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            {lastRefreshed && <span>updated {formatRelative(lastRefreshed)}</span>}
            <DownloadChartButton containerRef={chartRef} chartName="database-sizes" />
            <Button variant="ghost" size="sm" onClick={refresh} disabled={loading} className="h-7 px-2">
              {loading ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <RefreshCw className="h-3.5 w-3.5" />
              )}
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent>
        {error && <p className="text-sm text-destructive mb-3">{error}</p>}
        {chartData.length === 0 ? (
          <p className="text-sm text-muted-foreground">No databases yet.</p>
        ) : (
          <div ref={chartRef} className="h-72">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={chartData} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} className="opacity-30" />
                <XAxis dataKey="name" tick={{ fontSize: 12 }} />
                <YAxis tickFormatter={formatBytes} tick={{ fontSize: 11 }} />
                <Tooltip content={<SizeTooltip />} cursor={{ fill: "var(--muted)", fillOpacity: 0.2 }} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="baseline" name="DB engine (baseline)" stackId="size" fill="#3b82f6" radius={[0, 0, 0, 0]}>
                  {chartData.map((_, i) => <Cell key={i} />)}
                </Bar>
                <Bar dataKey="data" name="Inserted data" stackId="size" fill="#ec4899" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

interface TooltipRow {
  baseline: number
  data: number
  baselinePending: boolean
  raw: DatabaseSizeResponse
}

function SizeTooltip({ active, payload, label }: { active?: boolean; payload?: Array<{ payload: TooltipRow }>; label?: string }) {
  if (!active || !payload || payload.length === 0) return null
  const row = payload[0].payload
  return (
    <div className="rounded-md border border-border bg-background p-2 shadow-md text-xs">
      <div className="font-semibold capitalize mb-1">{label}</div>
      <div className="flex items-center gap-2">
        <span className="h-2 w-2 rounded-sm inline-block" style={{ backgroundColor: "#3b82f6" }} />
        baseline: <span className="font-mono">{formatBytes(row.baseline)}</span>
      </div>
      <div className="flex items-center gap-2">
        <span className="h-2 w-2 rounded-sm inline-block" style={{ backgroundColor: "#ec4899" }} />
        data: <span className="font-mono">{formatBytes(row.data)}</span>
      </div>
      <div className="text-muted-foreground mt-1">total: {row.raw.sizeHuman}</div>
      {row.baselinePending && (
        <div className="text-amber-600 dark:text-amber-400 mt-1">baseline pending — showing total</div>
      )}
    </div>
  )
}

function mergePreservingLastKnown(
  prev: DatabaseSizeResponse[],
  next: DatabaseSizeResponse[],
): DatabaseSizeResponse[] {
  const prevById = new Map(prev.map((s) => [s.databaseId, s]))
  return next.map((s) => {
    if (s.available) return s
    const prior = prevById.get(s.databaseId)
    if (!prior || !prior.available) return s
    // Keep the previous size figures, but adopt the latest baseline if it changed (e.g. hard reset
    // just recomputed it). The chart will keep showing the most recent "real" data segment until
    // the next successful probe replaces it.
    return {
      ...prior,
      baselineBytes: s.baselineBytes ?? prior.baselineBytes,
    }
  })
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  let v = n
  const units = ["KB", "MB", "GB", "TB"]
  let i = -1
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(v >= 10 ? 0 : 1)} ${units[i]}`
}

function formatRelative(date: Date): string {
  const diffSec = Math.max(1, Math.floor((Date.now() - date.getTime()) / 1000))
  if (diffSec < 60) return `${diffSec}s ago`
  return `${Math.floor(diffSec / 60)}m ago`
}
