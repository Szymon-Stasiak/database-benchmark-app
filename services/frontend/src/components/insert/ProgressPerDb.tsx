import { useMemo } from "react"
import { motion } from "framer-motion"
import { Badge } from "@/components/ui/badge"
import { AlertTriangle, CheckCircle2, Clock, Loader2, MinusCircle, XCircle } from "lucide-react"
import { cn } from "@/lib/utils"
import type { BatchProgressEvent, InsertRunResponse, InsertStatus } from "@/types/insert"

interface Props {
  run: InsertRunResponse
  progress: Map<string, BatchProgressEvent>
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

function aggregateStatus(rows: InsertRunResponse["results"]): InsertStatus {
  if (rows.length === 0) return "PENDING"
  let hasFailed = false
  let hasSkipped = false
  let hasSuccess = false
  let hasRunning = false
  let hasPending = false
  for (const r of rows) {
    if (r.status === "FAILED") hasFailed = true
    else if (r.status === "SKIPPED") hasSkipped = true
    else if (r.status === "SUCCESS") hasSuccess = true
    else if (r.status === "RUNNING") hasRunning = true
    else if (r.status === "PENDING") hasPending = true
  }
  if (hasRunning) return "RUNNING"
  if (hasPending) return "PENDING"
  if (hasFailed && hasSuccess) return "PARTIAL"
  if (hasFailed) return "FAILED"
  if (hasSuccess) return "SUCCESS"
  if (hasSkipped) return "SKIPPED"
  return "PENDING"
}

interface DbBucket {
  databaseId: string
  dbName: string
  results: InsertRunResponse["results"]
  entityNames: string[]
  totalMs: number
  totalRecords: number
}

export function ProgressPerDb({ run, progress }: Props) {
  const cascadeEntityNames = useMemo(() => parseCascadeEntityNames(run.cascadeJson), [run.cascadeJson])

  const byDb = useMemo(() => {
    const map = new Map<string, DbBucket>()
    for (const result of run.results) {
      let bucket = map.get(result.databaseId)
      if (!bucket) {
        bucket = {
          databaseId: result.databaseId,
          dbName: result.dbName,
          results: [],
          entityNames: [...cascadeEntityNames],
          totalMs: 0,
          totalRecords: 0,
        }
        map.set(result.databaseId, bucket)
      }
      bucket.results.push(result)
      if (result.entityName && !bucket.entityNames.includes(result.entityName)) {
        bucket.entityNames.push(result.entityName)
      }
      bucket.totalMs += result.durationMs ?? 0
      bucket.totalRecords += result.recordsInserted ?? 0
    }
    for (const evt of progress.values()) {
      const bucket = map.get(evt.databaseId)
      if (!bucket) continue
      if (!bucket.entityNames.includes(evt.entityName)) {
        bucket.entityNames.push(evt.entityName)
      }
    }
    return Array.from(map.values())
  }, [run, progress, cascadeEntityNames])

  return (
    <div className="space-y-3">
      {byDb.map((bucket) => {
        const status = aggregateStatus(bucket.results)
        const cfg = STATUS_CONFIG[status] ?? FALLBACK_STATUS_CFG
        const throughput = bucket.totalMs > 0
          ? Math.round((bucket.totalRecords / bucket.totalMs) * 1000)
          : null
        const errorMsg = bucket.results.find((r) => r.status === "FAILED")?.errorMessage
        const dbStatus: InsertStatus = bucket.results[0]?.status ?? "PENDING"
        return (
          <div
            key={bucket.databaseId}
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
              {bucket.entityNames.length === 0 ? (
                <p className="text-xs text-muted-foreground">Waiting for first batch…</p>
              ) : (
                bucket.entityNames.map((entityName) => {
                  const evt = progress.get(progressKey(bucket.databaseId, entityName))
                  const row = rowForEntity(entityName, evt, dbStatus)
                  return <ProgressRow key={entityName} row={row} />
                })
              )}
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

function rowForEntity(
  entityName: string,
  evt: BatchProgressEvent | undefined,
  dbStatus: InsertStatus,
): ProgressRowData {
  const done = evt?.recordsDone ?? 0
  const total = evt?.recordsTotal ?? 0
  return {
    key: entityName,
    entityName,
    done,
    total: total > 0 ? total : Math.max(done, 1),
    status: resolveEntityStatus(evt, dbStatus, done, total),
  }
}

function resolveEntityStatus(
  evt: BatchProgressEvent | undefined,
  dbStatus: InsertStatus,
  done: number,
  total: number,
): InsertStatus {
  if (dbStatus === "FAILED") return "FAILED"
  if (dbStatus === "SKIPPED") return "SKIPPED"
  if (!evt) return dbStatus === "SUCCESS" ? "SUCCESS" : "PENDING"
  if (total > 0 && done >= total) return "SUCCESS"
  if (done > 0) return "RUNNING"
  return "PENDING"
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

function parseCascadeEntityNames(cascadeJson: string | null | undefined): string[] {
  if (!cascadeJson) return []
  try {
    const parsed = JSON.parse(cascadeJson) as { nodesInInsertOrder?: Array<{ entityName?: string }> }
    const nodes = parsed?.nodesInInsertOrder
    if (!Array.isArray(nodes)) return []
    const names: string[] = []
    for (const node of nodes) {
      const name = node?.entityName
      if (typeof name === "string" && !names.includes(name)) names.push(name)
    }
    return names
  } catch {
    return []
  }
}
