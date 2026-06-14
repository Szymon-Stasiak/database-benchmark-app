import { useMemo } from "react"
import { motion } from "framer-motion"
import { AlertTriangle, ChevronRight, Database, Layers, Loader2 } from "lucide-react"
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
import type { RunPreview, CascadeImpact } from "@/types/preview"

interface RunPlanPreviewProps {
  open: boolean
  operation: "READ" | "DELETE"
  preview: RunPreview | null
  databaseCount: number
  submitting: boolean
  loadingPreview: boolean
  onConfirm: () => void
  onCancel: () => void
}

export function RunPlanPreview({
  open,
  operation,
  preview,
  databaseCount,
  submitting,
  loadingPreview,
  onConfirm,
  onCancel,
}: RunPlanPreviewProps) {
  const isDelete = operation === "DELETE"
  const accent = isDelete ? "text-destructive" : "text-primary"
  const verb = isDelete ? "deleted" : "read"

  const grouped = useMemo(() => {
    if (!preview) return new Map<number, CascadeImpact[]>()
    const m = new Map<number, CascadeImpact[]>()
    for (const c of preview.cascade) {
      if (!m.has(c.depth)) m.set(c.depth, [])
      m.get(c.depth)!.push(c)
    }
    return m
  }, [preview])

  const totalImpacted = useMemo(() => {
    if (!preview) return 0
    return (
      preview.sampleSize +
      preview.cascade.reduce((acc, c) => acc + c.estimatedRowsAffected, 0)
    )
  }, [preview])

  return (
    <Dialog open={open} onOpenChange={(v) => !v && onCancel()}>
      <DialogContent className="max-w-2xl max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Layers className={`h-5 w-5 ${accent}`} />
            Confirm {operation.toLowerCase()} plan
          </DialogTitle>
          <DialogDescription>
            Review what will be {verb} on each of the {databaseCount} benchmarked
            database(s) before running.
          </DialogDescription>
        </DialogHeader>

        {loadingPreview && (
          <div className="flex items-center justify-center py-12 text-muted-foreground">
            <Loader2 className="h-5 w-5 animate-spin mr-2" />
            Preparing plan…
          </div>
        )}

        {!loadingPreview && preview && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.2 }}
            className="space-y-4"
          >
            <div className="rounded-md border bg-muted/30 p-4 space-y-2">
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">Root entity</span>
                <span className="font-semibold">{preview.rootEntity}</span>
              </div>
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">IDs selected from pool</span>
                <span className="font-semibold">
                  {preview.sampleSize.toLocaleString()} of{" "}
                  {preview.availablePool.toLocaleString()}
                </span>
              </div>
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">Will run on</span>
                <span className="font-semibold inline-flex items-center gap-1">
                  <Database className="h-3.5 w-3.5" />
                  {databaseCount} database(s)
                </span>
              </div>
              {isDelete && (
                <div className="flex items-center justify-between text-sm pt-2 border-t">
                  <span className="text-muted-foreground">Estimated total rows touched</span>
                  <span className="font-semibold text-destructive">
                    ~{totalImpacted.toLocaleString()}
                  </span>
                </div>
              )}
            </div>

            {preview.cascade.length === 0 ? (
              <div className="text-sm text-muted-foreground italic px-2">
                No cascading children — operation hits root entity only.
              </div>
            ) : (
              <div className="space-y-3">
                <div className="text-sm font-medium flex items-center gap-2">
                  <ChevronRight className="h-4 w-4" />
                  Cascade impact (estimated)
                </div>
                <div className="rounded-md border divide-y">
                  <div className="px-3 py-2 bg-muted/40 text-xs font-medium uppercase tracking-wide text-muted-foreground grid grid-cols-12 gap-2">
                    <div className="col-span-4">Entity</div>
                    <div className="col-span-3">Via FK</div>
                    <div className="col-span-3">Cardinality</div>
                    <div className="col-span-2 text-right">Est. rows</div>
                  </div>
                  {[...grouped.keys()]
                    .sort((a, b) => a - b)
                    .flatMap((depth) =>
                      grouped.get(depth)!.map((impact, idx) => (
                        <div
                          key={`${impact.entity}-${impact.parentEntity}-${idx}`}
                          className="px-3 py-2 grid grid-cols-12 gap-2 items-center text-sm"
                          style={{ paddingLeft: `${0.75 + (depth - 1) * 1}rem` }}
                        >
                          <div className="col-span-4 font-medium">
                            <span className="text-muted-foreground mr-1">{"›".repeat(depth)}</span>
                            {impact.entity}
                          </div>
                          <div className="col-span-3 text-muted-foreground text-xs">
                            {impact.parentEntity}
                            {impact.fkColumn ? `.${impact.fkColumn}` : ""}
                          </div>
                          <div className="col-span-3">
                            <Badge variant="outline" className="text-[10px]">
                              {impact.cardinality.replace(/_/g, ":")}
                            </Badge>
                          </div>
                          <div className="col-span-2 text-right tabular-nums">
                            ~{impact.estimatedRowsAffected.toLocaleString()}
                          </div>
                        </div>
                      )),
                    )}
                </div>
                <div className="text-xs text-muted-foreground italic px-1 flex gap-1.5 items-start">
                  <AlertTriangle className="h-3.5 w-3.5 mt-0.5 flex-shrink-0" />
                  <span>
                    Estimates use default cardinality ratios (1:1=1, 1:N=5, M:N=3). Actual
                    counts depend on real data in each database.
                  </span>
                </div>
              </div>
            )}
          </motion.div>
        )}

        <DialogFooter className="gap-2 pt-4">
          <Button variant="outline" onClick={onCancel} disabled={submitting}>
            Cancel
          </Button>
          <Button
            variant={isDelete ? "destructive" : "default"}
            onClick={onConfirm}
            disabled={submitting || loadingPreview || !preview}
          >
            {submitting ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin mr-2" />
                Starting…
              </>
            ) : isDelete ? (
              "Confirm & Delete"
            ) : (
              "Confirm & Read"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
