import { useMemo, useRef } from "react"
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Activity, MemoryStick } from "lucide-react"
import { DownloadChartButton } from "@/components/benchmark/DownloadChartButton"
import type { ContainerStatsEvent } from "@/types/resource"

interface Props {
  events: ContainerStatsEvent[]
  windowSeconds?: number
}

const PALETTE = [
  "#2563eb",
  "#dc2626",
  "#16a34a",
  "#9333ea",
  "#0891b2",
  "#ca8a04",
  "#db2777",
  "#475569",
]

function colorForDb(dbName: string): string {
  let hash = 0
  for (let i = 0; i < dbName.length; i++) {
    hash = (hash * 31 + dbName.charCodeAt(i)) | 0
  }
  return PALETTE[Math.abs(hash) % PALETTE.length]
}

interface DbSeries {
  databaseId: string
  dbName: string
  color: string
}

interface ChartRow {
  timestamp: number
  label: string
  [seriesKey: string]: number | string
}

export function ResourceMetricsChart({ events, windowSeconds }: Props) {
  const cpuRef = useRef<HTMLDivElement | null>(null)
  const memRef = useRef<HTMLDivElement | null>(null)

  const { series, cpuData, memData, hasData } = useMemo(
    () => buildChartData(events, windowSeconds),
    [events, windowSeconds],
  )

  return (
    <div className="grid gap-3 lg:grid-cols-2">
      <Card>
        <CardHeader className="pb-3">
          <div className="flex items-center justify-between gap-3">
            <CardTitle className="text-base inline-flex items-center gap-2">
              <Activity className="h-4 w-4" />
              CPU usage (%)
            </CardTitle>
            <DownloadChartButton containerRef={cpuRef} chartName="resource-cpu" />
          </div>
        </CardHeader>
        <CardContent>
          {!hasData ? (
            <p className="text-sm text-muted-foreground">Waiting for first sample…</p>
          ) : (
            <div ref={cpuRef} className="h-64 bg-white rounded-sm">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={cpuData} margin={{ top: 8, right: 8, bottom: 28, left: 8 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} className="opacity-30" />
                  <XAxis
                    dataKey="label"
                    tick={{ fontSize: 11 }}
                    label={{ value: "Time since start (mm:ss)", position: "insideBottom", offset: -16, style: { fontSize: 11, fill: "#6b7280" } }}
                  />
                  <YAxis
                    tickFormatter={(v) => `${Math.round(Number(v))}%`}
                    tick={{ fontSize: 11 }}
                    width={50}
                    domain={[0, "auto"]}
                  />
                  <Tooltip content={<CpuTooltip series={series} />} />
                  <Legend verticalAlign="top" align="right" wrapperStyle={{ fontSize: 12, paddingBottom: 4 }} />
                  {series.map((s) => (
                    <Line
                      key={s.databaseId}
                      type="monotone"
                      dataKey={s.databaseId}
                      name={s.dbName}
                      stroke={s.color}
                      strokeWidth={2}
                      dot={cpuData.length <= 12 ? { r: 3 } : false}
                      isAnimationActive={false}
                      connectNulls
                    />
                  ))}
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-3">
          <div className="flex items-center justify-between gap-3">
            <CardTitle className="text-base inline-flex items-center gap-2">
              <MemoryStick className="h-4 w-4" />
              Memory (MB)
            </CardTitle>
            <DownloadChartButton containerRef={memRef} chartName="resource-memory" />
          </div>
        </CardHeader>
        <CardContent>
          {!hasData ? (
            <p className="text-sm text-muted-foreground">Waiting for first sample…</p>
          ) : (
            <div ref={memRef} className="h-64 bg-white rounded-sm">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={memData} margin={{ top: 8, right: 8, bottom: 28, left: 8 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} className="opacity-30" />
                  <XAxis
                    dataKey="label"
                    tick={{ fontSize: 11 }}
                    label={{ value: "Time since start (mm:ss)", position: "insideBottom", offset: -16, style: { fontSize: 11, fill: "#6b7280" } }}
                  />
                  <YAxis
                    tickFormatter={(v) => `${Math.round(Number(v))} MB`}
                    tick={{ fontSize: 11 }}
                    width={70}
                    domain={[0, "auto"]}
                  />
                  <Tooltip content={<MemoryTooltip series={series} />} />
                  <Legend verticalAlign="top" align="right" wrapperStyle={{ fontSize: 12, paddingBottom: 4 }} />
                  {series.map((s) => (
                    <Line
                      key={s.databaseId}
                      type="monotone"
                      dataKey={s.databaseId}
                      name={s.dbName}
                      stroke={s.color}
                      strokeWidth={2}
                      dot={memData.length <= 12 ? { r: 3 } : false}
                      isAnimationActive={false}
                      connectNulls
                    />
                  ))}
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

const BUCKET_MS = 500

function buildChartData(events: ContainerStatsEvent[], windowSeconds?: number) {
  if (events.length === 0) {
    return { series: [] as DbSeries[], cpuData: [], memData: [], hasData: false }
  }

  const filtered = applyWindow(events, windowSeconds)
  const dbMap = new Map<string, DbSeries>()
  const dbOrder: string[] = []
  for (const e of filtered) {
    if (!dbMap.has(e.databaseId)) {
      dbMap.set(e.databaseId, {
        databaseId: e.databaseId,
        dbName: e.dbName,
        color: colorForDb(e.dbName),
      })
      dbOrder.push(e.databaseId)
    }
  }
  dbOrder.sort((a, b) => dbMap.get(a)!.dbName.localeCompare(dbMap.get(b)!.dbName))

  const startTs = filtered[0].timestamp
  const buckets = new Map<number, { cpu: ChartRow; mem: ChartRow }>()
  for (const e of filtered) {
    const relativeMs = e.timestamp - startTs
    const bucketKey = Math.floor(relativeMs / BUCKET_MS) * BUCKET_MS
    let entry = buckets.get(bucketKey)
    if (!entry) {
      const label = formatElapsed(bucketKey)
      entry = {
        cpu: { timestamp: bucketKey, label },
        mem: { timestamp: bucketKey, label },
      }
      buckets.set(bucketKey, entry)
    }
    entry.cpu[e.databaseId] = round(e.cpuPercent, 2)
    entry.mem[e.databaseId] = round(e.memoryBytes / (1024 * 1024), 2)
  }

  const sortedBuckets = [...buckets.values()].sort((a, b) =>
    (a.cpu.timestamp as number) - (b.cpu.timestamp as number),
  )
  const series = dbOrder.map((id) => dbMap.get(id)!)
  return {
    series,
    cpuData: sortedBuckets.map((b) => b.cpu),
    memData: sortedBuckets.map((b) => b.mem),
    hasData: true,
  }
}

function applyWindow(events: ContainerStatsEvent[], windowSeconds?: number): ContainerStatsEvent[] {
  if (!windowSeconds || windowSeconds <= 0 || events.length === 0) return events
  const last = events[events.length - 1].timestamp
  const cutoff = last - windowSeconds * 1000
  return events.filter((e) => e.timestamp >= cutoff)
}

function formatElapsed(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000)
  const mm = String(Math.floor(totalSeconds / 60)).padStart(2, "0")
  const ss = String(totalSeconds % 60).padStart(2, "0")
  return `${mm}:${ss}`
}

function round(v: number, digits: number): number {
  const f = 10 ** digits
  return Math.round(v * f) / f
}

interface TooltipPayloadEntry {
  dataKey?: string
  value?: number
  color?: string
}

function CpuTooltip({
  active,
  payload,
  label,
  series,
}: {
  active?: boolean
  payload?: TooltipPayloadEntry[]
  label?: string
  series: DbSeries[]
}) {
  if (!active || !payload || payload.length === 0) return null
  return (
    <div className="rounded-md border border-border bg-background p-2 shadow-md text-xs space-y-1">
      <div className="font-semibold mb-1">{label}</div>
      {payload.map((row) => {
        const s = series.find((x) => x.databaseId === row.dataKey)
        if (!s || row.value == null) return null
        return (
          <div key={s.databaseId} className="flex items-center gap-2">
            <span className="h-2 w-2 rounded-sm inline-block" style={{ backgroundColor: s.color }} />
            <span className="capitalize">{s.dbName}:</span>
            <span className="font-mono">{Number(row.value).toFixed(2)} %</span>
          </div>
        )
      })}
    </div>
  )
}

function MemoryTooltip({
  active,
  payload,
  label,
  series,
}: {
  active?: boolean
  payload?: TooltipPayloadEntry[]
  label?: string
  series: DbSeries[]
}) {
  if (!active || !payload || payload.length === 0) return null
  return (
    <div className="rounded-md border border-border bg-background p-2 shadow-md text-xs space-y-1">
      <div className="font-semibold mb-1">{label}</div>
      {payload.map((row) => {
        const s = series.find((x) => x.databaseId === row.dataKey)
        if (!s || row.value == null) return null
        return (
          <div key={s.databaseId} className="flex items-center gap-2">
            <span className="h-2 w-2 rounded-sm inline-block" style={{ backgroundColor: s.color }} />
            <span className="capitalize">{s.dbName}:</span>
            <span className="font-mono">{Number(row.value).toFixed(1)} MB</span>
          </div>
        )
      })}
    </div>
  )
}
