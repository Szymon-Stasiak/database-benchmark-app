import { useState, useEffect, useCallback } from "react"
import { useParams, useNavigate } from "react-router-dom"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardHeader, CardTitle } from "@/components/ui/card"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Skeleton } from "@/components/ui/skeleton"
import { RefreshCw, Loader2, Trash2, Eraser, Package } from "lucide-react"
import { toast } from "sonner"
import { benchmarkApi } from "@/lib/api"
import { useBenchmarkEvents } from "@/hooks/useBenchmarkEvents"
import { useConfirm } from "@/hooks/useConfirm"
import { SchemaRelationships, SchemaEntities } from "@/components/benchmark/LogicalSchemaPanel"
import { SchemaErdDiagram } from "@/components/benchmark/SchemaErdDiagram"
import { ProgressTimeline } from "@/components/benchmark/ProgressTimeline"
import { InfoBanner } from "@/components/benchmark/InfoBanner"
import { BenchmarkActionCards } from "@/components/benchmark/BenchmarkActionCards"
import { DatabaseCardsGrid } from "@/components/benchmark/DatabaseCardsGrid"
import { AppLayout } from "@/components/AppLayout"
import { BackButton } from "@/components/shared/BackButton"
import { getBenchmarkStatusConfig, cn, relativeTime } from "@/lib/utils"
import type {
  BenchmarkResponse,
  BenchmarkStatusEvent,
  DatabaseStatusEvent,
  ScriptGeneratedEvent,
} from "@/types/benchmark"

export default function BenchmarkDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const confirm = useConfirm()
  const [benchmark, setBenchmark] = useState<BenchmarkResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [redeployLoading, setRedeployLoading] = useState(false)
  const [hardResetLoading, setHardResetLoading] = useState(false)
  const [deleteLoading, setDeleteLoading] = useState(false)
  const [bundleLoading, setBundleLoading] = useState(false)
  const [scriptPreviews, setScriptPreviews] = useState<Record<string, string>>({})

  useEffect(() => {
    if (!id) return
    benchmarkApi
      .get(id)
      .then(setBenchmark)
      .catch((e) => setError(e.message))
  }, [id])

  useEffect(() => {
    if (!id || !benchmark) return
    const terminal =
      benchmark.status === "RUNNING" ||
      benchmark.status === "STOPPED" ||
      benchmark.status === "FAILED" ||
      benchmark.status === "READY_TO_RUN"
    if (terminal) return
    const interval = setInterval(() => {
      benchmarkApi
        .get(id)
        .then(setBenchmark)
        .catch(() => {})
    }, 5000)
    return () => clearInterval(interval)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, benchmark?.status])

  const handleEvent = useCallback((event: { type: string; data: unknown }) => {
    if (event.type === "benchmark_status") {
      const data = event.data as BenchmarkStatusEvent
      setBenchmark((prev) => (prev ? { ...prev, status: data.status } : prev))
    } else if (event.type === "database_status") {
      const data = event.data as DatabaseStatusEvent
      setBenchmark((prev) => {
        if (!prev) return prev
        return {
          ...prev,
          databases: prev.databases.map((db) =>
            db.id === data.databaseId
              ? { ...db, status: data.status, errorMessage: data.errorMessage || db.errorMessage }
              : db,
          ),
        }
      })
    } else if (event.type === "database_port_assigned") {
      const data = event.data as { databaseId: string; hostPort: number }
      setBenchmark((prev) => {
        if (!prev) return prev
        return {
          ...prev,
          databases: prev.databases.map((db) =>
            db.id === data.databaseId ? { ...db, hostPort: data.hostPort } : db,
          ),
        }
      })
    } else if (event.type === "script_generated") {
      const data = event.data as ScriptGeneratedEvent
      setScriptPreviews((prev) => ({ ...prev, [data.databaseId]: data.scriptPreview }))
    }
  }, [])

  useBenchmarkEvents(id || null, handleEvent)

  const handleDatabaseStatusChange = useCallback(
    (databaseId: string, status: DatabaseStatusEvent["status"]) => {
      setBenchmark((prev) => {
        if (!prev) return prev
        const updated = {
          ...prev,
          databases: prev.databases.map((db) => (db.id === databaseId ? { ...db, status } : db)),
        }
        const anyRunning = updated.databases.some((db) => db.status === "RUNNING")
        const allFailed = updated.databases.every((db) => db.status === "FAILED")
        const anyReadyOrStopped = updated.databases.some(
          (db) => db.status === "SCRIPT_READY" || db.status === "STOPPED",
        )
        let benchmarkStatus = prev.status
        if (allFailed) benchmarkStatus = "FAILED"
        else if (anyRunning) benchmarkStatus = "RUNNING"
        else if (anyReadyOrStopped) benchmarkStatus = "READY_TO_RUN"
        return { ...updated, status: benchmarkStatus }
      })
    },
    [],
  )

  const handleDeleteDatabase = useCallback(
    (databaseId: string) => {
      setBenchmark((prev) => {
        if (!prev) return prev
        const remaining = prev.databases.filter((db) => db.id !== databaseId)
        if (remaining.length === 0) {
          navigate("/dashboard")
          return prev
        }
        return { ...prev, databases: remaining }
      })
    },
    [navigate],
  )

  const handleRedeployAll = async () => {
    if (!id) return
    setRedeployLoading(true)
    try {
      await benchmarkApi.redeployBenchmark(id)
      toast.success("Redeploy started")
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Redeploy failed")
    } finally {
      setRedeployLoading(false)
    }
  }

  const handleHardReset = async () => {
    if (!id) return
    const ok = await confirm({
      title: "Hard reset this benchmark?",
      description:
        "This will FORCE-KILL every database container, DELETE its data volume, and redeploy fresh from the init script. All inserted benchmark data will be lost.",
      confirmLabel: "Reset & wipe",
      variant: "destructive",
    })
    if (!ok) return
    setHardResetLoading(true)
    try {
      await toast.promise(benchmarkApi.hardResetBenchmark(id), {
        loading: "Force-killing containers…",
        success: "Hard reset in progress",
        error: (e) => (e instanceof Error ? e.message : "Reset failed"),
      })
    } finally {
      setHardResetLoading(false)
    }
  }

  const handleDelete = async () => {
    if (!id) return
    const ok = await confirm({
      title: "Delete this benchmark?",
      description: "This will stop and remove all containers. This action cannot be undone.",
      confirmLabel: "Delete benchmark",
      variant: "destructive",
    })
    if (!ok) return
    setDeleteLoading(true)
    try {
      await benchmarkApi.deleteBenchmark(id)
      toast.success("Benchmark deleted")
      navigate("/dashboard")
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Failed to delete benchmark"
      setError(msg)
      toast.error(msg)
      setDeleteLoading(false)
    }
  }

  const handleDownloadBundle = async () => {
    if (!id) return
    setBundleLoading(true)
    try {
      await benchmarkApi.downloadBundle(id)
      toast.success("Bundle downloaded")
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Failed to download bundle"
      setError(msg)
      toast.error(msg)
    } finally {
      setBundleLoading(false)
    }
  }

  const hasInactiveDatabases = benchmark?.databases.some((db) => db.status !== "RUNNING") ?? false
  const hasReadyScripts =
    benchmark?.databases.some(
      (db) => db.status !== "PENDING" && db.status !== "SCRIPT_GENERATING" && db.status !== "FAILED",
    ) ?? false
  const anyRunning = benchmark?.databases.some((db) => db.status === "RUNNING") ?? false

  if (error) {
    return (
      <AppLayout breadcrumbs={[{ label: "Dashboard", to: "/dashboard" }, { label: "Error" }]}>
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      </AppLayout>
    )
  }

  if (!benchmark) {
    return (
      <AppLayout breadcrumbs={[{ label: "Dashboard", to: "/dashboard" }, { label: "Loading…" }]}>
        <div className="space-y-6">
          <Skeleton className="h-8 w-40" />
          <Skeleton className="h-32 w-full" />
          <div className="flex items-center justify-between px-4">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="flex flex-col items-center gap-1.5">
                <Skeleton className="h-8 w-8 rounded-full" />
                <Skeleton className="h-3 w-12" />
              </div>
            ))}
          </div>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-40 w-full" />
            ))}
          </div>
        </div>
      </AppLayout>
    )
  }

  const statusConfig = getBenchmarkStatusConfig(benchmark.status)

  return (
    <AppLayout
      breadcrumbs={[
        { label: "Dashboard", to: "/dashboard" },
        { label: benchmark.topic },
      ]}
    >
      <BackButton to="/dashboard" label="Back to Dashboard" />

      <Card className="mb-6 overflow-hidden">
        <CardHeader>
          <div className="flex items-start justify-between gap-4">
            <div className="min-w-0">
              <CardTitle className="truncate text-xl">{benchmark.topic}</CardTitle>
              <p
                className="mt-1 text-sm text-muted-foreground"
                title={new Date(benchmark.createdAt).toLocaleString()}
              >
                Created {relativeTime(benchmark.createdAt)}
              </p>
            </div>
            <Badge
              className={cn(
                "shrink-0 rounded-full border-0 px-3 py-0.5 text-xs font-medium",
                statusConfig.bgClass,
                statusConfig.textClass,
              )}
            >
              {statusConfig.animate && <Loader2 className="mr-1 h-3 w-3 animate-spin" />}
              {statusConfig.label}
            </Badge>
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            {hasInactiveDatabases && (
              <Button size="sm" onClick={handleRedeployAll} disabled={redeployLoading}>
                {redeployLoading ? (
                  <Loader2 className="mr-1 h-4 w-4 animate-spin" />
                ) : (
                  <RefreshCw className="mr-1 h-4 w-4" />
                )}
                Redeploy All
              </Button>
            )}
            <Button
              size="sm"
              variant="outline"
              onClick={handleHardReset}
              disabled={hardResetLoading}
              title="Force-kill containers, wipe data volumes, redeploy fresh"
              className="border-amber-500/50 text-amber-700 hover:bg-amber-50 dark:text-amber-400 dark:hover:bg-amber-950/30"
            >
              {hardResetLoading ? (
                <Loader2 className="mr-1 h-4 w-4 animate-spin" />
              ) : (
                <Eraser className="mr-1 h-4 w-4" />
              )}
              Restart (wipe data)
            </Button>
            {hasReadyScripts && (
              <Button
                size="sm"
                variant="outline"
                onClick={handleDownloadBundle}
                disabled={bundleLoading}
                title="Download a ZIP with logical schema, embedding mappings and all init scripts"
              >
                {bundleLoading ? (
                  <Loader2 className="mr-1 h-4 w-4 animate-spin" />
                ) : (
                  <Package className="mr-1 h-4 w-4" />
                )}
                Download Bundle
              </Button>
            )}
            <Button
              size="sm"
              variant="outline"
              onClick={handleDelete}
              disabled={deleteLoading}
              className="text-destructive hover:text-destructive"
            >
              {deleteLoading ? (
                <Loader2 className="mr-1 h-4 w-4 animate-spin" />
              ) : (
                <Trash2 className="mr-1 h-4 w-4" />
              )}
              Delete
            </Button>
          </div>
        </CardHeader>
      </Card>

      <ProgressTimeline status={benchmark.status} />
      <InfoBanner status={benchmark.status} />

      <div className="mb-3 flex items-baseline justify-between">
        <h2 className="text-lg font-semibold tracking-tight">What next?</h2>
        <p className="text-xs text-muted-foreground">
          {anyRunning ? "All actions available" : "Start a database to unlock run actions"}
        </p>
      </div>
      <BenchmarkActionCards benchmarkId={benchmark.id} anyRunning={anyRunning} />

      <h3 className="mb-3 text-lg font-semibold tracking-tight">
        Databases ({benchmark.databases.length})
      </h3>
      <DatabaseCardsGrid
        benchmark={benchmark}
        scriptPreviews={scriptPreviews}
        onStatusChange={handleDatabaseStatusChange}
        onDelete={handleDeleteDatabase}
      />

      {benchmark.logicalSchema && (
        <>
          <SchemaRelationships logicalSchemaJson={benchmark.logicalSchema} />
          <SchemaEntities logicalSchemaJson={benchmark.logicalSchema} />
          <SchemaErdDiagram logicalSchemaJson={benchmark.logicalSchema} />
        </>
      )}
    </AppLayout>
  )
}
