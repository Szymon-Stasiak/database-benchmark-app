import { useMemo } from "react"
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"
import type { InsertResultResponse, InsertRunResponse } from "@/types/insert"

interface Props {
  run: InsertRunResponse | null
  open: boolean
  onClose: () => void
}

/**
 * Drill-down view from the run history: per-DB durations and per-entity timings, plus a small
 * recharts bar comparing throughput across databases. Read-only.
 */
export function RunDetailDialog({ run, open, onClose }: Props) {
  const byEntity = useMemo(() => {
    if (!run) return new Map<string, InsertResultResponse[]>()
    const map = new Map<string, InsertResultResponse[]>()
    for (const r of run.results) {
      const key = r.entityName ?? "(legacy)"
      const bucket = map.get(key) ?? []
      bucket.push(r)
      map.set(key, bucket)
    }
    return map
  }, [run])

  const throughputData = useMemo(() => {
    if (!run) return []
    const byDb = new Map<string, { dbName: string; durationMs: number; recordsInserted: number }>()
    for (const r of run.results) {
      const existing = byDb.get(r.dbName) ?? { dbName: r.dbName, durationMs: 0, recordsInserted: 0 }
      existing.durationMs += r.durationMs ?? 0
      existing.recordsInserted += r.recordsInserted ?? 0
      byDb.set(r.dbName, existing)
    }
    return Array.from(byDb.values()).map((r) => ({
      dbName: r.dbName,
      throughput: r.durationMs > 0 ? Math.round((r.recordsInserted / r.durationMs) * 1000) : 0,
    }))
  }, [run])

  if (!run) return null

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>Insert run · {run.entityName}</DialogTitle>
          <DialogDescription className="text-xs">
            {new Date(run.createdAt).toLocaleString()} · mode {run.mode}
            {run.batchSize ? ` (batch ${run.batchSize})` : ""}
            {run.workerCount ? ` · ${run.workerCount} workers` : ""}
            {" · "}
            <span className={cn(
              "font-medium",
              run.status === "SUCCESS" && "text-green-600 dark:text-green-400",
              run.status === "FAILED" && "text-destructive",
            )}>{run.status}</span>
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {throughputData.length > 0 && (
            <div className="h-48 -mx-2">
              <div className="text-xs font-medium mb-1 px-2 text-muted-foreground">
                Throughput per database (records / second, summed across entities)
              </div>
              <ResponsiveContainer width="100%" height="90%">
                <BarChart data={throughputData} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} className="opacity-30" />
                  <XAxis dataKey="dbName" tick={{ fontSize: 11 }} />
                  <YAxis tick={{ fontSize: 11 }} />
                  <Tooltip cursor={{ fill: "var(--muted)", fillOpacity: 0.2 }} />
                  <Bar dataKey="throughput" name="r/s" fill="hsl(var(--primary, 217 91% 60%))" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}

          <div className="space-y-3 max-h-[40vh] overflow-y-auto pr-1">
            {Array.from(byEntity.entries()).map(([entityName, rows]) => (
              <div key={entityName} className="rounded-lg border border-border">
                <div className="px-3 py-2 border-b border-border flex items-center justify-between">
                  <span className="font-medium text-sm">{entityName}</span>
                  <Badge variant="outline" className="text-[10px]">
                    {rows.length} DB{rows.length === 1 ? "" : "s"}
                  </Badge>
                </div>
                <div className="divide-y divide-border">
                  {rows.map((r) => (
                    <div key={r.id} className="px-3 py-2 grid grid-cols-4 gap-3 items-center text-xs">
                      <div className="font-medium capitalize">{r.dbName}</div>
                      <div className={cn(
                        "font-mono",
                        r.status === "SUCCESS" && "text-foreground",
                        r.status === "FAILED" && "text-destructive",
                      )}>{r.status}</div>
                      <div className="font-mono">
                        {r.durationMs != null ? formatMs(r.durationMs) : "—"}
                      </div>
                      <div className="font-mono text-right">
                        {r.throughputRps ? `${r.throughputRps.toFixed(0)} r/s` : "—"}
                      </div>
                      {r.errorMessage && (
                        <div className="col-span-4 text-[11px] text-destructive font-mono break-words">
                          {r.errorMessage}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}

function formatMs(ms: number): string {
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)} s`
  const min = Math.floor(ms / 60_000)
  const sec = Math.floor((ms % 60_000) / 1000)
  return `${min}m ${sec}s`
}
