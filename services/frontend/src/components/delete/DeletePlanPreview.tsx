import { useMemo } from "react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { AlertTriangle, Loader2, Trash2 } from "lucide-react"
import type { DatabaseResponse } from "@/types/benchmark"
import type { StartDeleteRunRequest } from "@/types/delete"

interface Props {
  open: boolean
  request: StartDeleteRunRequest | null
  databases: DatabaseResponse[]
  submitting: boolean
  onConfirm: () => Promise<void>
  onCancel: () => void
}

export function DeletePlanPreview({
  open,
  request,
  databases,
  submitting,
  onConfirm,
  onCancel,
}: Props) {
  const selected = useMemo(() => {
    if (!request) return []
    const lookup = new Map(databases.map((d) => [d.id, d]))
    return request.databaseIds
      .map((id) => lookup.get(id))
      .filter((db): db is DatabaseResponse => Boolean(db))
  }, [request, databases])

  if (!request) return null

  return (
    <Dialog open={open} onOpenChange={(v) => !v && onCancel()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="inline-flex items-center gap-2">
            <AlertTriangle className="h-5 w-5 text-amber-500" />
            Confirm delete benchmark
          </DialogTitle>
          <DialogDescription>
            This will permanently remove records from the selected live databases.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-3 text-sm">
          <div className="rounded-md border border-border bg-muted/30 p-3 space-y-1">
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">Entity</span>
              <span className="font-medium">{request.entityName}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">Sample size</span>
              <span className="font-mono">{request.sampleSize ?? "—"}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">Cascade children</span>
              <Badge variant="outline">
                {request.includeChildren ? "yes (FK / embedded refs)" : "no"}
              </Badge>
            </div>
          </div>

          <div>
            <p className="text-xs uppercase text-muted-foreground mb-1.5">
              Affected databases ({selected.length})
            </p>
            <div className="flex flex-wrap gap-1.5">
              {selected.map((db) => (
                <Badge key={db.id} variant="secondary" className="capitalize">
                  {db.dbName}:{db.dbVersion}
                </Badge>
              ))}
            </div>
          </div>

          <p className="text-xs text-amber-700 dark:text-amber-400">
            Per-DB samples may diverge: each database picks its own random PKs from
            the local registry. Sizes before/after will be captured for the comparison
            report.
          </p>
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={onCancel} disabled={submitting}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={() => {
              void onConfirm()
            }}
            disabled={submitting}
          >
            {submitting ? (
              <Loader2 className="h-4 w-4 mr-2 animate-spin" />
            ) : (
              <Trash2 className="h-4 w-4 mr-2" />
            )}
            Run delete benchmark
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
