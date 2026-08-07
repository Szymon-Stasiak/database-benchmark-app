import { useRef, useState } from "react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { DownloadTableButton } from "@/components/benchmark/DownloadTableButton"
import { CheckCircle2, Clock, Loader2, XCircle, SkipForward, AlertTriangle } from "lucide-react"
import { cn } from "@/lib/utils"
import { useScenarioRunEvents } from "@/hooks/useScenarioRunEvents"
import { useArchivedResourceTimeline } from "@/hooks/useArchivedResourceTimeline"
import { ResourceMetricsChart } from "@/components/shared/ResourceMetricsChart"
import { ResourceSummaryTable } from "@/components/shared/ResourceSummaryTable"
import { ConsistencyBadge } from "@/components/scenarios/ConsistencyBadge"
import { ScenarioResultPreview } from "@/components/scenarios/ScenarioResultPreview"
import { scenarioApi } from "@/lib/api"
import type {
  ConsistencyStatus,
  ScenarioResultResponse,
  ScenarioRunResponse,
  ScenarioStatus,
} from "@/types/scenario"
import type { ContainerStatsEvent } from "@/types/resource"

interface Props {
  benchmarkId: string
  run: ScenarioRunResponse
  onRunStatusChange: (runId: string, status: ScenarioStatus, consistencyStatus: string) => void
  onResultUpdate: (runId: string, result: ScenarioResultResponse) => void
}

const STATUS_CONFIG: Record<ScenarioStatus, {
  label: string; bg: string; text: string; Icon: React.ComponentType<{ className?: string }>; spin?: boolean
}> = {
  PENDING: { label: "Pending", bg: "bg-status-info-bg", text: "text-status-info-text", Icon: Clock },
  RUNNING: { label: "Running", bg: "bg-status-info-bg", text: "text-status-info-text", Icon: Loader2, spin: true },
  SUCCESS: { label: "Success", bg: "bg-status-success-bg", text: "text-status-success-text", Icon: CheckCircle2 },
  PARTIAL: { label: "Partial", bg: "bg-amber-100 dark:bg-amber-900/30", text: "text-amber-700 dark:text-amber-300", Icon: AlertTriangle },
  FAILED: { label: "Failed", bg: "bg-destructive/10", text: "text-destructive", Icon: XCircle },
  SKIPPED: { label: "Skipped", bg: "bg-muted", text: "text-muted-foreground", Icon: SkipForward },
}

const MAX_LIVE_SAMPLES = 4000

export function ActiveScenarioRunPanel({ benchmarkId, run, onRunStatusChange, onResultUpdate }: Props) {
  const [statsEvents, setStatsEvents] = useState<ContainerStatsEvent[]>([])

  useScenarioRunEvents(benchmarkId, run.id, {
    onRunStatus: (status, consistencyStatus) => onRunStatusChange(run.id, status, consistencyStatus),
    onResultUpdate: (result) => onResultUpdate(run.id, result),
    onContainerStats: (evt) => {
      setStatsEvents((prev) => {
        const next = prev.length >= MAX_LIVE_SAMPLES ? prev.slice(prev.length - MAX_LIVE_SAMPLES + 1) : prev
        return [...next, evt]
      })
    },
  })

  const archivedEvents = useArchivedResourceTimeline({
    runId: run.id,
    status: run.status,
    results: run.results,
    operation: "delete",
    loadTimeline: scenarioApi.getResourceTimeline,
    enabled: statsEvents.length === 0,
  })
  const chartEvents = statsEvents.length > 0 ? statsEvents : archivedEvents

  const cfg = STATUS_CONFIG[run.status]
  const isFinished = run.status === "SUCCESS" || run.status === "FAILED" || run.status === "PARTIAL"
  const consistency: ConsistencyStatus = run.consistencyStatus ?? null

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between flex-wrap gap-3">
          <div>
            <CardTitle className="text-lg">{run.scenarioType}</CardTitle>
            <p className="text-xs text-muted-foreground mt-0.5">
              {run.iterations ?? 1} iteration(s) · {run.results.length} target DB(s)
            </p>
          </div>
          <div className="flex items-center gap-2">
            {isFinished && <ConsistencyBadge consistencyStatus={consistency} results={run.results} />}
            <Badge
              className={cn("rounded-full px-3 py-0.5 text-xs font-medium border-0 inline-flex items-center gap-1.5", cfg.bg, cfg.text)}
            >
              <cfg.Icon className={cn("h-3.5 w-3.5", cfg.spin && "animate-spin")} />
              {cfg.label}
            </Badge>
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <ResultsTable results={run.results} />
        <ScenarioResultPreview scenarioType={run.scenarioType} results={run.results} />
        <ResourceMetricsChart events={chartEvents} />
        {isFinished && <ResourceSummaryTable results={run.results} />}
      </CardContent>
    </Card>
  )
}

function ResultsTable({ results }: { results: ScenarioResultResponse[] }) {
  const tableRef = useRef<HTMLDivElement | null>(null)
  if (results.length === 0) return <p className="text-sm text-muted-foreground">Waiting for results…</p>
  return (
    <div className="space-y-1">
      <div className="flex justify-end">
        <DownloadTableButton containerRef={tableRef} tableName="scenario-results" />
      </div>
      <div ref={tableRef} className="overflow-x-auto">
        <table className="w-full text-sm">
        <thead className="text-xs text-muted-foreground border-b">
          <tr>
            <th className="text-left py-2 px-2">Database</th>
            <th className="text-right py-2 px-2">Status</th>
            <th className="text-right py-2 px-2">Rows</th>
            <th className="text-right py-2 px-2">Samples</th>
            <th className="text-right py-2 px-2">Total</th>
            <th className="text-right py-2 px-2">p50 (ms)</th>
            <th className="text-right py-2 px-2">p95 (ms)</th>
            <th className="text-right py-2 px-2">p99 (ms)</th>
            <th className="text-right py-2 px-2">Mean (ms)</th>
            <th className="text-right py-2 px-2">Hash</th>
          </tr>
        </thead>
        <tbody>
          {results.map((r) => (
            <tr key={r.id} className="border-b last:border-0">
              <td className="py-1.5 px-2 capitalize font-medium">{r.dbName}</td>
              <td className="py-1.5 px-2 text-right">
                <Badge variant="outline" className="text-[10px]">{r.status}</Badge>
              </td>
              <td className="py-1.5 px-2 text-right font-mono">{r.scenarioRowsReturned ?? "—"}</td>
              <td className="py-1.5 px-2 text-right font-mono">{r.samplesRecorded ?? "—"}</td>
              <td className="py-1.5 px-2 text-right font-mono font-semibold">{fmtDuration(r.durationMs)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtMs(r.p50DbTimeUs)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtMs(r.p95DbTimeUs)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtMs(r.p99DbTimeUs)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtMs(r.meanDbTimeUs)}</td>
              <td className="py-1.5 px-2 text-right font-mono text-[10px] text-muted-foreground">
                {r.scenarioResultHash?.slice(0, 8) ?? "—"}
              </td>
            </tr>
          ))}
        </tbody>
        </table>
      </div>
    </div>
  )
}

function fmtMs(us: number | null): string {
  if (us == null) return "—"
  return (us / 1000).toFixed(3)
}

function fmtDuration(ms: number | null | undefined): string {
  if (ms == null) return "—"
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)} s`
  const min = Math.floor(ms / 60_000)
  const sec = Math.round((ms % 60_000) / 1000)
  return `${min}m ${sec}s`
}
