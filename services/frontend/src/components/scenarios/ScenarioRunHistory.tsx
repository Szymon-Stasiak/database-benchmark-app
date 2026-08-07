import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { AlertTriangle, Beaker, CheckCircle2, Clock, History, Loader2, MinusCircle, XCircle } from "lucide-react"
import { cn, relativeTime } from "@/lib/utils"
import { EmptyState } from "@/components/shared/EmptyState"
import type { ScenarioRunResponse, ScenarioStatus } from "@/types/scenario"

interface Props {
  runs: ScenarioRunResponse[]
  selectedRunId: string | null
  onSelect: (run: ScenarioRunResponse) => void
}

const STATUS_ICON: Record<ScenarioStatus, React.ComponentType<{ className?: string }>> = {
  PENDING: Clock,
  RUNNING: Loader2,
  SUCCESS: CheckCircle2,
  PARTIAL: AlertTriangle,
  FAILED: XCircle,
  SKIPPED: MinusCircle,
}

export function ScenarioRunHistory({ runs, selectedRunId, onSelect }: Props) {
  if (runs.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-base inline-flex items-center gap-2">
            <History className="h-4 w-4" />
            Scenario history
          </CardTitle>
        </CardHeader>
        <CardContent>
          <EmptyState
            compact
            icon={Beaker}
            title="No scenario runs yet"
            description="Configure a scenario above and start it to see cross-DB results."
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
          Scenario history ({runs.length})
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="space-y-1">
          {runs.map((run) => {
            const Icon = STATUS_ICON[run.status]
            const isSelected = run.id === selectedRunId
            return (
              <Button
                key={run.id}
                variant="ghost"
                onClick={() => onSelect(run)}
                className={cn(
                  "w-full justify-start text-left h-auto py-2 px-3",
                  isSelected && "bg-accent",
                )}
              >
                <div className="flex items-center gap-3 w-full">
                  <Icon className={cn("h-4 w-4 shrink-0",
                    run.status === "RUNNING" && "animate-spin",
                    run.status === "SUCCESS" && "text-green-600 dark:text-green-400",
                    run.status === "FAILED" && "text-destructive",
                  )} />
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-medium truncate">{run.scenarioType}</div>
                    <div className="text-[11px] text-muted-foreground">
                      <span title={new Date(run.createdAt).toLocaleString()}>{relativeTime(run.createdAt)}</span> ·{" "}
                      {run.iterations ?? 1} iter · {run.results.length} DB
                    </div>
                  </div>
                  <Badge variant="outline" className="text-[10px] shrink-0">
                    {run.consistencyStatus || "—"}
                  </Badge>
                </div>
              </Button>
            )
          })}
        </div>
      </CardContent>
    </Card>
  )
}
