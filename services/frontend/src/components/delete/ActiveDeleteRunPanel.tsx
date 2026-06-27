import { useState } from "react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { CheckCircle2, Clock, Loader2, XCircle, SkipForward } from "lucide-react"
import { cn } from "@/lib/utils"
import { useDeleteRunEvents } from "@/hooks/useDeleteRunEvents"
import { useArchivedResourceTimeline } from "@/hooks/useArchivedResourceTimeline"
import { LatencyDistributionChart } from "@/components/delete/LatencyDistributionChart"
import { ResourceMetricsChart } from "@/components/shared/ResourceMetricsChart"
import { ResourceSummaryTable } from "@/components/shared/ResourceSummaryTable"
import { deleteApi } from "@/lib/api"
import type { DeleteResultResponse, DeleteRunResponse, DeleteStatus } from "@/types/delete"
import type { ContainerStatsEvent } from "@/types/resource"

const MAX_LIVE_SAMPLES = 4000

interface Props {
  benchmarkId: string
  run: DeleteRunResponse
  onRunStatusChange: (runId: string, status: DeleteStatus) => void
  onResultUpdate: (runId: string, result: DeleteResultResponse) => void
}

const STATUS_CONFIG: Record<
  DeleteStatus,
  { label: string; bg: string; text: string; Icon: React.ComponentType<{ className?: string }>; spin?: boolean }
> = {
  PENDING: { label: "Pending", bg: "bg-status-info-bg", text: "text-status-info-text", Icon: Clock },
  RUNNING: { label: "Running", bg: "bg-status-info-bg", text: "text-status-info-text", Icon: Loader2, spin: true },
  SUCCESS: { label: "Success", bg: "bg-status-success-bg", text: "text-status-success-text", Icon: CheckCircle2 },
  PARTIAL: { label: "Partial", bg: "bg-amber-100 dark:bg-amber-900/30", text: "text-amber-700 dark:text-amber-300", Icon: CheckCircle2 },
  FAILED: { label: "Failed", bg: "bg-destructive/10", text: "text-destructive", Icon: XCircle },
  SKIPPED: { label: "Skipped", bg: "bg-muted", text: "text-muted-foreground", Icon: SkipForward },
}

export function ActiveDeleteRunPanel({ benchmarkId, run, onRunStatusChange, onResultUpdate }: Props) {
  const [statsEvents, setStatsEvents] = useState<ContainerStatsEvent[]>([])

  useDeleteRunEvents(benchmarkId, run.id, {
    onRunStatus: (status) => onRunStatusChange(run.id, status),
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
    loadTimeline: deleteApi.getResourceTimeline,
    enabled: statsEvents.length === 0,
  })
  const chartEvents = statsEvents.length > 0 ? statsEvents : archivedEvents

  const cfg = STATUS_CONFIG[run.status]
  const isFinished = run.status === "SUCCESS" || run.status === "FAILED" || run.status === "PARTIAL"

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-lg">{run.entityName ?? "—"}</CardTitle>
            <p className="text-xs text-muted-foreground mt-0.5">
              N = {run.sampleSize?.toLocaleString() ?? "—"} ·{" "}
              {run.includeChildren ? "cascade children" : "primary node only"}
            </p>
          </div>
          <Badge
            className={cn(
              "rounded-full px-3 py-0.5 text-xs font-medium border-0 inline-flex items-center gap-1.5",
              cfg.bg,
              cfg.text,
            )}
          >
            <cfg.Icon className={cn("h-3.5 w-3.5", cfg.spin && "animate-spin")} />
            {cfg.label}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <ResultsTable results={run.results} />
        <LatencyDistributionChart results={run.results} />
        <ResourceMetricsChart events={chartEvents} />
        {isFinished && <ResourceSummaryTable results={run.results} />}
      </CardContent>
    </Card>
  )
}

function ResultsTable({ results }: { results: DeleteResultResponse[] }) {
  if (results.length === 0) {
    return <p className="text-sm text-muted-foreground">Waiting for results…</p>
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="text-xs text-muted-foreground border-b">
          <tr>
            <th className="text-left py-2 px-2">Database</th>
            <th className="text-right py-2 px-2">Status</th>
            <th className="text-right py-2 px-2">Deleted</th>
            <th className="text-right py-2 px-2">Samples</th>
            <th className="text-right py-2 px-2">p50 (ms)</th>
            <th className="text-right py-2 px-2">p95 (ms)</th>
            <th className="text-right py-2 px-2">p99 (ms)</th>
            <th className="text-right py-2 px-2">Mean (ms)</th>
            <th className="text-right py-2 px-2">Wire (ms)</th>
            <th className="text-right py-2 px-2">Δ Size</th>
          </tr>
        </thead>
        <tbody>
          {results.map((r) => (
            <tr key={r.id} className="border-b last:border-0">
              <td className="py-1.5 px-2 capitalize font-medium">{r.dbName}</td>
              <td className="py-1.5 px-2 text-right">
                <Badge variant="outline" className="text-[10px]">
                  {r.status}
                </Badge>
              </td>
              <td className="py-1.5 px-2 text-right font-mono">
                <DeletedCell root={r.rowsDeleted} cascade={r.cascadeRowsDeleted} breakdown={r.cascadeBreakdown} />
              </td>
              <td className="py-1.5 px-2 text-right font-mono">{r.samplesRecorded ?? "—"}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtMs(r.p50DbTimeUs)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtMs(r.p95DbTimeUs)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtMs(r.p99DbTimeUs)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtMs(r.meanDbTimeUs)}</td>
              <td className="py-1.5 px-2 text-right font-mono text-muted-foreground">
                {r.wireTimeMs?.toFixed(0) ?? "—"}
              </td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtDelta(r.dataSizeDelta)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function DeletedCell({
  root,
  cascade,
  breakdown,
}: {
  root: number | null
  cascade: number | null
  breakdown: Record<string, number> | null
}) {
  if (root == null) return <>—</>
  const hasCascade = cascade != null && cascade > 0
  const breakdownText =
    breakdown && Object.keys(breakdown).length > 0
      ? Object.entries(breakdown)
          .map(([entity, n]) => `${entity}: ${n.toLocaleString()}`)
          .join("\n")
      : ""
  return (
    <span
      title={hasCascade ? `Root: ${root}\nCascade total: ${cascade}\n${breakdownText}` : `Root: ${root}`}
      className="inline-flex items-baseline gap-1"
    >
      <span>{root.toLocaleString()}</span>
      {hasCascade && (
        <span className="text-[10px] text-muted-foreground">
          (+{cascade.toLocaleString()})
        </span>
      )}
    </span>
  )
}

function fmtMs(us: number | null): string {
  if (us == null) return "—"
  return (us / 1000).toFixed(3)
}

function fmtDelta(bytes: number | null): string {
  if (bytes == null) return "—"
  const sign = bytes > 0 ? "+" : bytes < 0 ? "−" : ""
  const abs = Math.abs(bytes)
  if (abs >= 1_048_576) return `${sign}${(abs / 1_048_576).toFixed(2)} MB`
  if (abs >= 1024) return `${sign}${(abs / 1024).toFixed(2)} KB`
  return `${sign}${abs} B`
}
