import { useState } from "react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { AlertTriangle, CheckCircle2, Clock, Loader2, MinusCircle, XCircle } from "lucide-react"
import { cn } from "@/lib/utils"
import { useInsertRunEvents } from "@/hooks/useInsertRunEvents"
import { ProgressPerDb, progressKey } from "@/components/insert/ProgressPerDb"
import type {
  BatchProgressEvent,
  InsertResultResponse,
  InsertRunResponse,
  InsertStatus,
} from "@/types/insert"

interface Props {
  benchmarkId: string
  run: InsertRunResponse
  onRunStatusChange: (runId: string, status: InsertStatus) => void
  onResultUpdate: (runId: string, result: InsertResultResponse) => void
}

const STATUS_CONFIG: Record<
  InsertStatus,
  { label: string; bg: string; text: string; Icon: React.ComponentType<{ className?: string }>; spin?: boolean }
> = {
  PENDING: { label: "Pending", bg: "bg-status-info-bg", text: "text-status-info-text", Icon: Clock },
  RUNNING: { label: "Running", bg: "bg-status-info-bg", text: "text-status-info-text", Icon: Loader2, spin: true },
  SUCCESS: { label: "Success", bg: "bg-status-success-bg", text: "text-status-success-text", Icon: CheckCircle2 },
  PARTIAL: { label: "Partial", bg: "bg-amber-100 dark:bg-amber-900/30", text: "text-amber-700 dark:text-amber-300", Icon: AlertTriangle },
  FAILED: { label: "Failed", bg: "bg-destructive/10", text: "text-destructive", Icon: XCircle },
  SKIPPED: { label: "Skipped", bg: "bg-muted", text: "text-muted-foreground", Icon: MinusCircle },
}

const FALLBACK_STATUS_CFG = STATUS_CONFIG.PENDING

export function ActiveInsertRunPanel({ benchmarkId, run, onRunStatusChange, onResultUpdate }: Props) {
  const [progress, setProgress] = useState<Map<string, BatchProgressEvent>>(new Map())

  useInsertRunEvents(benchmarkId, run.id, {
    onRunStatus: (status) => onRunStatusChange(run.id, status),
    onResultUpdate: (result) => onResultUpdate(run.id, result),
    onBatchProgress: (evt) => {
      setProgress((prev) => {
        const next = new Map(prev)
        next.set(progressKey(evt.databaseId, evt.entityName), evt)
        return next
      })
    },
  })

  const cfg = STATUS_CONFIG[run.status] ?? FALLBACK_STATUS_CFG

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-lg">{run.entityName}</CardTitle>
            <p className="text-xs text-muted-foreground mt-0.5">
              {run.recordCount.toLocaleString()} total records · {run.mode}
              {run.mode === "BATCH" && run.batchSize ? ` (size ${run.batchSize})` : ""}
              {run.workerCount ? ` · ${run.workerCount} workers` : ""}
            </p>
          </div>
          <Badge className={cn("rounded-full px-3 py-0.5 text-xs font-medium border-0 inline-flex items-center gap-1.5", cfg.bg, cfg.text)}>
            <cfg.Icon className={cn("h-3.5 w-3.5", cfg.spin && "animate-spin")} />
            {cfg.label}
          </Badge>
        </div>
      </CardHeader>
      <CardContent>
        <ProgressPerDb run={run} progress={progress} />
      </CardContent>
    </Card>
  )
}
