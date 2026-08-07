import { useEffect, useMemo, useRef, useState } from "react"
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Badge } from "@/components/ui/badge"
import { cn, relativeTime } from "@/lib/utils"
import { DownloadChartButton } from "@/components/benchmark/DownloadChartButton"
import { ResourceMetricsChart } from "@/components/shared/ResourceMetricsChart"
import { ResourceSummaryTable } from "@/components/shared/ResourceSummaryTable"
import { insertApi } from "@/lib/api"
import type { InsertResultResponse, InsertRunResponse } from "@/types/insert"
import type { ContainerStatsEvent } from "@/types/resource"

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

  const chartRef = useRef<HTMLDivElement | null>(null)
  const resourceEvents = useResourceTimeline(run, open)

  if (!run) return null

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>Insert run · {run.entityName}</DialogTitle>
          <DialogDescription className="text-xs">
            <span title={new Date(run.createdAt).toLocaleString()}>{relativeTime(run.createdAt)}</span> · mode {run.mode}
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
              <div className="flex items-center justify-between mb-1 px-2">
                <div className="text-xs font-medium text-muted-foreground">
                  Throughput per database (records / second, summed across entities)
                </div>
                <DownloadChartButton containerRef={chartRef} chartName="insert-throughput" />
              </div>
              <div ref={chartRef} className="h-[90%]">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={throughputData} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} className="opacity-30" />
                  <XAxis dataKey="dbName" tick={{ fontSize: 11 }} />
                  <YAxis tick={{ fontSize: 11 }} />
                  <Tooltip cursor={{ fill: "var(--muted)", fillOpacity: 0.2 }} />
                  <Bar dataKey="throughput" name="r/s" fill="hsl(var(--primary, 217 91% 60%))" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
              </div>
            </div>
          )}

          {resourceEvents.length > 0 && (
            <div className="space-y-3">
              <ResourceMetricsChart events={resourceEvents} />
              <ResourceSummaryTable results={run.results} />
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

function useResourceTimeline(run: InsertRunResponse | null, open: boolean): ContainerStatsEvent[] {
  const [events, setEvents] = useState<ContainerStatsEvent[]>([])

  useEffect(() => {
    if (!run || !open) {
      setEvents([])
      return
    }
    const resultsWithSamples = run.results.filter((r) => (r.resourceSampleCount ?? 0) > 0)
    if (resultsWithSamples.length === 0) {
      setEvents([])
      return
    }
    let cancelled = false
    Promise.all(
      resultsWithSamples.map(async (result) => {
        try {
          const timeline = await insertApi.getResourceTimeline(run.id, result.id)
          return timeline.map<ContainerStatsEvent>((sample) => ({
            runId: run.id,
            resultId: result.id,
            databaseId: result.databaseId,
            dbName: result.dbName,
            operation: "insert",
            timestamp: sample.tMs,
            cpuPercent: sample.cpuPercent,
            memoryBytes: sample.memoryBytes,
            memoryLimitBytes: sample.memoryLimitBytes,
          }))
        } catch {
          return []
        }
      }),
    ).then((batches) => {
      if (cancelled) return
      const flattened = batches.flat().sort((a, b) => a.timestamp - b.timestamp)
      setEvents(flattened)
    })
    return () => {
      cancelled = true
    }
  }, [run, open])

  return events
}

function formatMs(ms: number): string {
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)} s`
  const min = Math.floor(ms / 60_000)
  const sec = Math.floor((ms % 60_000) / 1000)
  return `${min}m ${sec}s`
}
