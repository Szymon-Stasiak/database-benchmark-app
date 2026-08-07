import { useRef } from "react"
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { BarChart3, Timer } from "lucide-react"
import { DownloadChartButton } from "@/components/benchmark/DownloadChartButton"
import { EmptyState } from "@/components/shared/EmptyState"
import type { ReadResultResponse } from "@/types/read"

interface Props {
  results: ReadResultResponse[]
}

export function LatencyDistributionChart({ results }: Props) {
  const chartRef = useRef<HTMLDivElement | null>(null)
  const data = results
    .filter((r) => r.p50DbTimeUs != null || r.meanDbTimeUs != null)
    .map((r) => ({
      name: r.dbName,
      p50: usToMs(r.p50DbTimeUs),
      p95: usToMs(r.p95DbTimeUs),
      p99: usToMs(r.p99DbTimeUs),
      mean: usToMs(r.meanDbTimeUs),
      samples: r.samplesRecorded ?? 0,
      records: r.recordsRead ?? 0,
    }))

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between gap-3">
          <CardTitle className="text-base inline-flex items-center gap-2">
            <BarChart3 className="h-4 w-4" />
            Engine-side latency per DB — p50 / p95 / p99 (ms)
          </CardTitle>
          <DownloadChartButton containerRef={chartRef} chartName="read-latency" />
        </div>
      </CardHeader>
      <CardContent>
        {data.length === 0 ? (
          <EmptyState
            compact
            icon={Timer}
            title="No latency samples yet"
            description="Read latency will appear here as the run progresses."
          />
        ) : (
          <div ref={chartRef} className="h-72 bg-white rounded-sm">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={data} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} className="opacity-30" />
                <XAxis dataKey="name" tick={{ fontSize: 12 }} />
                <YAxis tickFormatter={(v) => `${v.toFixed(2)} ms`} tick={{ fontSize: 11 }} width={60} />
                <Tooltip content={<LatencyTooltip />} cursor={{ fill: "var(--muted)", fillOpacity: 0.2 }} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="p50" name="p50" fill="#06b6d4" radius={[3, 3, 0, 0]} />
                <Bar dataKey="p95" name="p95" fill="#a855f7" radius={[3, 3, 0, 0]} />
                <Bar dataKey="p99" name="p99" fill="#ec4899" radius={[3, 3, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

interface ChartRow {
  name: string
  p50: number
  p95: number
  p99: number
  mean: number
  samples: number
  records: number
}

function LatencyTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean
  payload?: Array<{ payload: ChartRow }>
  label?: string
}) {
  if (!active || !payload || payload.length === 0) return null
  const r = payload[0].payload
  return (
    <div className="rounded-md border border-border bg-background p-2 shadow-md text-xs space-y-0.5">
      <div className="font-semibold capitalize mb-1">{label}</div>
      <div>p50: <span className="font-mono">{r.p50.toFixed(3)} ms</span></div>
      <div>p95: <span className="font-mono">{r.p95.toFixed(3)} ms</span></div>
      <div>p99: <span className="font-mono">{r.p99.toFixed(3)} ms</span></div>
      <div>mean: <span className="font-mono">{r.mean.toFixed(3)} ms</span></div>
      <div className="text-muted-foreground mt-1">
        {r.samples} samples · {r.records} records
      </div>
    </div>
  )
}

function usToMs(us: number | null): number {
  if (us == null) return 0
  return us / 1000
}
