import { AlertTriangle, CheckCircle2, HelpCircle } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"
import type { ConsistencyStatus, ScenarioResultResponse } from "@/types/scenario"

interface Props {
  consistencyStatus: ConsistencyStatus
  results: ScenarioResultResponse[]
}

export function ConsistencyBadge({ consistencyStatus, results }: Props) {
  const successful = results.filter((r) => r.status === "SUCCESS" && r.scenarioResultHash)
  const distinctHashes = new Set(successful.map((r) => r.scenarioResultHash))

  if (consistencyStatus === "MATCH" || (successful.length > 1 && distinctHashes.size === 1)) {
    return (
      <Badge className={cn("inline-flex items-center gap-1.5 px-3 py-1 text-xs font-medium border-0",
        "bg-status-success-bg text-status-success-text")}>
        <CheckCircle2 className="h-3.5 w-3.5" />
        Results match across {successful.length} DB{successful.length === 1 ? "" : "s"}
      </Badge>
    )
  }
  if (consistencyStatus === "MISMATCH" || distinctHashes.size > 1) {
    return (
      <Badge className="inline-flex items-center gap-1.5 px-3 py-1 text-xs font-medium border-0 bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-300">
        <AlertTriangle className="h-3.5 w-3.5" />
        Mismatch: {distinctHashes.size} distinct results
      </Badge>
    )
  }
  return (
    <Badge className="inline-flex items-center gap-1.5 px-3 py-1 text-xs font-medium border-0 bg-muted text-muted-foreground">
      <HelpCircle className="h-3.5 w-3.5" />
      {successful.length === 0 ? "Awaiting results" : "Incomplete"}
    </Badge>
  )
}
