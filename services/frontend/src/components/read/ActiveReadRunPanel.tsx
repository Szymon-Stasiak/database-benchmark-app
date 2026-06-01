import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { CheckCircle2, Clock, Loader2, XCircle, SkipForward } from "lucide-react"
import { cn } from "@/lib/utils"
import { useReadRunEvents } from "@/hooks/useReadRunEvents"
import { LatencyDistributionChart } from "@/components/read/LatencyDistributionChart"
import type { ReadResultResponse, ReadRunResponse, ReadStatus } from "@/types/read"

interface Props {
  benchmarkId: string
  run: ReadRunResponse
  onRunStatusChange: (runId: string, status: ReadStatus) => void
  onResultUpdate: (runId: string, result: ReadResultResponse) => void
}

const STATUS_CONFIG: Record<
  ReadStatus,
  { label: string; bg: string; text: string; Icon: React.ComponentType<{ className?: string }>; spin?: boolean }
> = {
  PENDING: { label: "Pending", bg: "bg-status-info-bg", text: "text-status-info-text", Icon: Clock },
  RUNNING: { label: "Running", bg: "bg-status-info-bg", text: "text-status-info-text", Icon: Loader2, spin: true },
  SUCCESS: { label: "Success", bg: "bg-status-success-bg", text: "text-status-success-text", Icon: CheckCircle2 },
  PARTIAL: { label: "Partial", bg: "bg-amber-100 dark:bg-amber-900/30", text: "text-amber-700 dark:text-amber-300", Icon: CheckCircle2 },
  FAILED: { label: "Failed", bg: "bg-destructive/10", text: "text-destructive", Icon: XCircle },
  SKIPPED: { label: "Skipped", bg: "bg-muted", text: "text-muted-foreground", Icon: SkipForward },
}

export function ActiveReadRunPanel({ benchmarkId, run, onRunStatusChange, onResultUpdate }: Props) {
  useReadRunEvents(benchmarkId, run.id, {
    onRunStatus: (status) => onRunStatusChange(run.id, status),
    onResultUpdate: (result) => onResultUpdate(run.id, result),
  })

  const cfg = STATUS_CONFIG[run.status]

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-lg">{run.entityName ?? "—"}</CardTitle>
            <p className="text-xs text-muted-foreground mt-0.5">
              N = {run.sampleSize?.toLocaleString() ?? "—"} · {run.includeChildren ? "with 1-hop children" : "PK lookup only"}
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
      </CardContent>
    </Card>
  )
}

function ResultsTable({ results }: { results: ReadResultResponse[] }) {
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
            <th className="text-right py-2 px-2">Records</th>
            <th className="text-right py-2 px-2">Samples</th>
            <th className="text-right py-2 px-2">p50 (ms)</th>
            <th className="text-right py-2 px-2">p95 (ms)</th>
            <th className="text-right py-2 px-2">p99 (ms)</th>
            <th className="text-right py-2 px-2">Mean (ms)</th>
            <th className="text-right py-2 px-2">Wire (ms)</th>
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
              <td className="py-1.5 px-2 text-right font-mono">{r.recordsRead ?? "—"}</td>
              <td className="py-1.5 px-2 text-right font-mono">{r.samplesRecorded ?? "—"}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtMs(r.p50DbTimeUs)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtMs(r.p95DbTimeUs)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtMs(r.p99DbTimeUs)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtMs(r.meanDbTimeUs)}</td>
              <td className="py-1.5 px-2 text-right font-mono text-muted-foreground">
                {r.wireTimeMs?.toFixed(0) ?? "—"}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function fmtMs(us: number | null): string {
  if (us == null) return "—"
  return (us / 1000).toFixed(3)
}
