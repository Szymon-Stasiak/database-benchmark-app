import { useCallback, useMemo, useRef, useState } from "react"
import {
  Legend,
  PolarAngleAxis,
  PolarGrid,
  PolarRadiusAxis,
  Radar,
  RadarChart,
  ResponsiveContainer,
  Tooltip,
} from "recharts"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Radar as RadarIcon } from "lucide-react"
import { DownloadChartButton } from "@/components/benchmark/DownloadChartButton"
import type { RadarScore } from "@/types/comparison"

interface Props {
  scores: RadarScore[]
}

const PALETTE = [
  "#3b82f6",
  "#10b981",
  "#ef4444",
  "#a855f7",
  "#f97316",
  "#14b8a6",
  "#eab308",
  "#ec4899",
]

const AXES: { key: keyof RadarScore; label: string }[] = [
  { key: "insertSpeed", label: "Insert speed" },
  { key: "readSpeed", label: "Read speed" },
  { key: "deleteSpeed", label: "Delete speed" },
  { key: "sizeEfficiency", label: "Size freed" },
  { key: "consistency", label: "Consistency" },
]

/**
 * Recharts uses `dataKey` as a property lookup on each row, so any character
 * that clashes with the object-path syntax (dots, brackets, quotes) silently
 * breaks the series. dbNames can also collide across duplicated engines, so we
 * derive a stable unique key per series and keep the pretty label for legends.
 */
interface Series {
  score: RadarScore
  key: string
  label: string
  color: string
}

function toDataKey(id: string, i: number): string {
  return `series_${i}_${id.replace(/[^A-Za-z0-9_]/g, "_")}`
}

/**
 * Coerces radar values into a safe [0..100] range so a single NaN / null from
 * the backend can't collapse the whole polygon.
 */
function safeScore(raw: unknown): number {
  const n = typeof raw === "number" ? raw : Number(raw)
  if (!Number.isFinite(n)) return 0
  return Math.max(0, Math.min(100, Math.round(n * 10) / 10))
}

export function ParadigmRadarChart({ scores }: Props) {
  // Hooks always run — hoisting them above any early return prevents the
  // "rendered fewer hooks than expected" mismatch that made the chart fail to
  // paint after data arrived.
  const chartRef = useRef<HTMLDivElement | null>(null)
  const [hiddenSeries, setHiddenSeries] = useState<Set<string>>(new Set())

  const toggleSeries = useCallback((key: string) => {
    setHiddenSeries((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }, [])

  const series = useMemo<Series[]>(
    () =>
      scores.map((s, i) => ({
        score: s,
        key: toDataKey(s.databaseId ?? String(i), i),
        label: s.dbName || `Series ${i + 1}`,
        color: PALETTE[i % PALETTE.length],
      })),
    [scores],
  )

  const data = useMemo(() => {
    return AXES.map((axis) => {
      const row: Record<string, number | string> = { axis: axis.label }
      for (const s of series) {
        row[s.key] = safeScore(s.score[axis.key])
      }
      return row
    })
  }, [series])

  const hasAnyValue = useMemo(
    () =>
      data.some((row) =>
        Object.entries(row).some(([k, v]) => k !== "axis" && typeof v === "number" && v > 0),
      ),
    [data],
  )

  if (scores.length === 0) {
    return (
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base inline-flex items-center gap-2">
            <RadarIcon className="h-4 w-4" />
            Paradigm positioning
          </CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">No runs yet.</p>
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between gap-3">
          <CardTitle className="text-base inline-flex items-center gap-2">
            <RadarIcon className="h-4 w-4" />
            Paradigm positioning (0..100 normalized)
          </CardTitle>
          <DownloadChartButton containerRef={chartRef} chartName="paradigm-radar" />
        </div>
      </CardHeader>
      <CardContent>
        <div ref={chartRef} className="h-96 w-full bg-white rounded-sm">
          {hasAnyValue ? (
            <ResponsiveContainer width="100%" height="100%" debounce={50}>
              <RadarChart data={data} outerRadius="75%">
                <PolarGrid gridType="polygon" />
                <PolarAngleAxis dataKey="axis" tick={{ fontSize: 12 }} />
                <PolarRadiusAxis
                  angle={30}
                  domain={[0, 100]}
                  tickCount={5}
                  tick={{ fontSize: 10 }}
                />
                {series.map((s) => (
                  <Radar
                    key={s.key}
                    name={s.label}
                    dataKey={s.key}
                    stroke={s.color}
                    fill={s.color}
                    fillOpacity={0.18}
                    isAnimationActive={false}
                    hide={hiddenSeries.has(s.key)}
                  />
                ))}
                <Tooltip
                  formatter={(value: number, name: string) => [value.toFixed(1), name]}
                />
                <Legend
                  wrapperStyle={{ fontSize: 12, cursor: "pointer" }}
                  onClick={(entry: { dataKey?: string | number }) => {
                    if (typeof entry.dataKey === "string") toggleSeries(entry.dataKey)
                  }}
                  formatter={(value: string, entry: { dataKey?: string | number }) => {
                    const key = typeof entry.dataKey === "string" ? entry.dataKey : ""
                    const hidden = hiddenSeries.has(key)
                    return (
                      <span style={{ opacity: hidden ? 0.4 : 1, textDecoration: hidden ? "line-through" : "none" }}>
                        {value}
                      </span>
                    )
                  }}
                />
              </RadarChart>
            </ResponsiveContainer>
          ) : (
            <div className="flex h-full flex-col items-center justify-center gap-2 text-center">
              <RadarIcon className="h-6 w-6 text-muted-foreground" />
              <p className="text-sm text-muted-foreground">
                Not enough measurements yet to score the paradigms.
              </p>
              <p className="text-xs text-muted-foreground/70">
                Run at least one insert / read / delete to populate the chart.
              </p>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
