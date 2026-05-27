import { useMemo } from "react"
import { motion } from "framer-motion"
import { Badge } from "@/components/ui/badge"
import { CheckCircle2, Clock, Loader2, XCircle } from "lucide-react"
import { cn } from "@/lib/utils"
import type { BatchProgressEvent, InsertRunResponse, InsertStatus } from "@/types/insert"

interface Props {
  run: InsertRunResponse
  /** Most recent {@link BatchProgressEvent} per (databaseId, entityName) pair. */
  progress: Map<string, BatchProgressEvent>
}

const STATUS_CONFIG: Record<
  InsertStatus,
  { label: string; bg: string; text: string; Icon: React.ComponentType<{ className?: string }>; spin?: boolean }
> = {
  PENDING: { label: "Pending", bg: "bg-status-info-bg", text: "text-status-info-text", Icon: Clock },
  RUNNING: { label: "Running", bg: "bg-status-info-bg", text: "text-status-info-text", Icon: Loader2, spin: true },
  SUCCESS: { label: "Success", bg: "bg-status-success-bg", text: "text-status-success-text", Icon: CheckCircle2 },
  FAILED: { label: "Failed", bg: "bg-destructive/10", text: "text-destructive", Icon: XCircle },
}

/**
 * Aggregate the per-entity statuses for a database into one overall status:
 * any FAILED → FAILED, else any RUNNING/PENDING → RUNNING/PENDING, else SUCCESS.
 */
function aggregateStatus(rows: InsertRunResponse["results"]): InsertStatus {
  let hasFailed = false
  let hasPending = false
  let hasRunning = false
  for (const r of rows) {
    if (r.status === "FAILED") hasFailed = true
    else if (r.status === "RUNNING") hasRunning = true
    else if (r.status === "PENDING") hasPending = true
  }
  if (hasFailed) return "FAILED"
  if (hasRunning) return "RUNNING"
  if (hasPending) return "PENDING"
  return "SUCCESS"
}

/**
 * Renders one filling progress bar per (DB × entity) phase, fed by the
 * {@code insert_batch_progress} SSE stream. Stays smooth even at high throughputs because
 * we only render the latest event per key.
 */
export function ProgressPerDb({ run, progress }: Props) {
  const byDb = useMemo(() => {
    const map = new Map<string, {
      dbName: string
      results: InsertRunResponse["results"]
      rows: ReturnType<typeof rowFor>[]
      totalMs: number
      totalRecords: number
    }>()
    for (const result of run.results) {
      const evt = progress.get(progressKey(result.databaseId, result.entityName ?? ""))
      const row = rowFor(result, evt)
      let bucket = map.get(result.databaseId)
      if (!bucket) {
        bucket = { dbName: result.dbName, results: [], rows: [], totalMs: 0, totalRecords: 0 }
        map.set(result.databaseId, bucket)
      }
      bucket.results.push(result)
      bucket.rows.push(row)
      bucket.totalMs += result.durationMs ?? 0
      bucket.totalRecords += result.recordsInserted ?? 0
    }
    return Array.from(map.values())
  }, [run, progress])

  return (
    <div className="space-y-3">
      {byDb.map((bucket) => {
        const status = aggregateStatus(bucket.results)
        const cfg = STATUS_CONFIG[status]
        const throughput = bucket.totalMs > 0
          ? Math.round((bucket.totalRecords / bucket.totalMs) * 1000)
          : null
        const errorMsg = bucket.results.find((r) => r.status === "FAILED")?.errorMessage
        return (
          <div
            key={bucket.dbName}
            className={cn(
              "rounded-lg border-2 p-3",
              status === "SUCCESS" && "border-green-500/50 bg-green-500/5",
              status === "FAILED" && "border-destructive/60 bg-destructive/5",
              (status === "RUNNING" || status === "PENDING") && "border-primary/40 bg-primary/5",
            )}
          >
            <div className="flex items-center justify-between gap-3 mb-3">
              <div className="text-sm font-semibold capitalize">{bucket.dbName}</div>
              <div className="flex items-center gap-2">
                {status === "SUCCESS" && throughput !== null && (
                  <span className="text-xs font-mono text-muted-foreground">
                    {formatMs(bucket.totalMs)} · {throughput.toLocaleString()} r/s
                  </span>
                )}
                <Badge className={cn("rounded-full px-2.5 py-0.5 text-xs font-medium border-0 inline-flex items-center gap-1.5", cfg.bg, cfg.text)}>
                  <cfg.Icon className={cn("h-3.5 w-3.5", cfg.spin && "animate-spin")} />
                  {cfg.label}
                </Badge>
              </div>
            </div>
            <div className="space-y-2">
              {bucket.rows.map((row) => (
                <ProgressRow key={row.key} row={row} />
              ))}
            </div>
            {errorMsg && (
              <p className="mt-2 text-[11px] text-destructive font-mono break-words bg-destructive/5 rounded px-2 py-1">
                {errorMsg}
              </p>
            )}
          </div>
        )
      })}
    </div>
  )
}

function formatMs(ms: number): string {
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)} s`
  const min = Math.floor(ms / 60_000)
  const sec = Math.floor((ms % 60_000) / 1000)
  return `${min}m ${sec}s`
}

interface ProgressRowData {
  key: string
  entityName: string
  done: number
  total: number
  status: string
}

function rowFor(
  result: InsertRunResponse["results"][number],
  evt: BatchProgressEvent | undefined,
): ProgressRowData {
  return {
    key: result.id,
    entityName: result.entityName ?? "(legacy)",
    done: evt?.recordsDone ?? result.recordsInserted ?? 0,
    total: evt?.recordsTotal ?? (result.status === "SUCCESS" ? result.recordsInserted ?? 1 : 1),
    status: result.status,
  }
}

function ProgressRow({ row }: { row: ProgressRowData }) {
  const pct = row.total > 0 ? Math.min(100, (row.done / row.total) * 100) : 0
  return (
    <div>
      <div className="flex items-center justify-between text-xs mb-1">
        <span className="font-medium">{row.entityName}</span>
        <span className="font-mono text-muted-foreground">
          {row.done.toLocaleString()} / {row.total.toLocaleString()} · {pct.toFixed(0)}%
        </span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
        <motion.div
          className={cn(
            "h-full rounded-full",
            row.status === "SUCCESS" && "bg-green-500",
            row.status === "FAILED" && "bg-destructive",
            (row.status === "RUNNING" || row.status === "PENDING") && "bg-primary",
          )}
          initial={{ width: 0 }}
          animate={{ width: `${pct}%` }}
          transition={{ duration: 0.25, ease: "easeOut" }}
        />
      </div>
    </div>
  )
}

export function progressKey(databaseId: string, entityName: string): string {
  return `${databaseId}::${entityName.toLowerCase()}`
}
