import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { CheckCircle2, XCircle, Clock, Loader2, History, SkipForward, Eraser } from "lucide-react"
import { cn, relativeTime } from "@/lib/utils"
import { EmptyState } from "@/components/shared/EmptyState"
import type { DeleteRunResponse, DeleteStatus } from "@/types/delete"

interface Props {
  runs: DeleteRunResponse[]
  selectedRunId: string | null
  onSelect: (run: DeleteRunResponse) => void
}

const STATUS_ICON: Record<DeleteStatus, React.ComponentType<{ className?: string }>> = {
  PENDING: Clock,
  RUNNING: Loader2,
  SUCCESS: CheckCircle2,
  PARTIAL: CheckCircle2,
  FAILED: XCircle,
  SKIPPED: SkipForward,
}

export function DeleteRunHistory({ runs, selectedRunId, onSelect }: Props) {
  if (runs.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-base inline-flex items-center gap-2">
            <History className="h-4 w-4" />
            Run history
          </CardTitle>
        </CardHeader>
        <CardContent>
          <EmptyState
            compact
            icon={Eraser}
            title="No delete runs yet"
            description="Configure a run above to time root and cascade deletion."
          />
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base inline-flex items-center gap-2">
          <History className="h-4 w-4" />
          Run history ({runs.length})
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {runs.map((run) => {
          const Icon = STATUS_ICON[run.status]
          const isSelected = run.id === selectedRunId
          const succeeded = run.results.filter((r) => r.status === "SUCCESS").length
          const failed = run.results.filter((r) => r.status === "FAILED").length
          return (
            <button
              key={run.id}
              type="button"
              onClick={() => onSelect(run)}
              className={cn(
                "w-full text-left rounded-lg border px-3 py-2 transition-all flex items-center justify-between gap-3",
                isSelected ? "border-primary bg-primary/5" : "border-border hover:border-foreground/30",
              )}
            >
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <Icon className={cn("h-3.5 w-3.5", run.status === "RUNNING" && "animate-spin")} />
                  <span className="font-medium text-sm truncate">{run.entityName ?? "—"}</span>
                  <Badge variant="outline" className="text-[10px]">
                    N={run.sampleSize?.toLocaleString() ?? "—"}
                    {run.includeChildren ? " · cascade" : ""}
                  </Badge>
                </div>
                <div className="text-xs text-muted-foreground mt-0.5">
                  <span title={new Date(run.createdAt).toLocaleString()}>{relativeTime(run.createdAt)}</span> ·{" "}
                  <span className="text-green-600 dark:text-green-400">{succeeded} ok</span>
                  {failed > 0 ? <span className="text-destructive"> · {failed} failed</span> : null}
                </div>
              </div>
              <Button variant="ghost" size="sm" className="shrink-0">
                View
              </Button>
            </button>
          )
        })}
      </CardContent>
    </Card>
  )
}
