import { useState, useEffect, useCallback } from "react"
import { useParams, useNavigate } from "react-router-dom"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardHeader, CardTitle } from "@/components/ui/card"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { ArrowLeft, RefreshCw, Loader2, Info, Trash2 } from "lucide-react"
import { motion, AnimatePresence } from "framer-motion"
import { benchmarkApi } from "@/lib/api"
import { useBenchmarkEvents } from "@/hooks/useBenchmarkEvents"
import { DatabaseCard } from "@/components/benchmark/DatabaseCard"
import { AppLayout } from "@/components/AppLayout"
import { getBenchmarkStatusConfig, cn } from "@/lib/utils"
import type { BenchmarkResponse, BenchmarkStatus, BenchmarkStatusEvent, DatabaseStatusEvent, ScriptGeneratedEvent } from "@/types/benchmark"

const TIMELINE_STEPS: { key: BenchmarkStatus; label: string }[] = [
  { key: "PENDING", label: "Pending" },
  { key: "GENERATING_SCRIPTS", label: "Scripts" },
  { key: "READY_TO_RUN", label: "Ready" },
  { key: "STARTING_CONTAINERS", label: "Containers" },
  { key: "INITIALIZING", label: "Init" },
  { key: "RUNNING", label: "Running" },
]

const STEP_ORDER: Record<string, number> = {
  PENDING: 0,
  GENERATING_SCRIPTS: 1,
  READY_TO_RUN: 2,
  STARTING_CONTAINERS: 3,
  INITIALIZING: 4,
  RUNNING: 5,
  STOPPED: 5,
  FAILED: -1,
}

const INFO_MESSAGES: Partial<Record<BenchmarkStatus, string>> = {
  GENERATING_SCRIPTS: "Script generation typically takes 3-5 minutes per database. All databases are processed in parallel.",
  READY_TO_RUN: "Scripts are generated and ready. Use Redeploy to start Docker containers.",
  STARTING_CONTAINERS: "Docker containers are being pulled and started. This usually takes 1-2 minutes.",
  INITIALIZING: "Initialization scripts are being executed on the database containers.",
}

export default function BenchmarkDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [benchmark, setBenchmark] = useState<BenchmarkResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [redeployLoading, setRedeployLoading] = useState(false)
  const [deleteLoading, setDeleteLoading] = useState(false)
  const [scriptPreviews, setScriptPreviews] = useState<Record<string, string>>({})

  useEffect(() => {
    if (!id) return
    benchmarkApi.get(id).then(setBenchmark).catch((e) => setError(e.message))
  }, [id])

  useEffect(() => {
    if (!id || !benchmark) return
    const terminal = benchmark.status === "RUNNING" || benchmark.status === "STOPPED" || benchmark.status === "FAILED" || benchmark.status === "READY_TO_RUN"
    if (terminal) return
    const interval = setInterval(() => {
      benchmarkApi.get(id).then(setBenchmark).catch(() => {})
    }, 5000)
    return () => clearInterval(interval)
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

  const handleDatabaseStatusChange = useCallback((databaseId: string, status: DatabaseStatusEvent["status"]) => {
    setBenchmark((prev) => {
      if (!prev) return prev
      const updated = {
        ...prev,
        databases: prev.databases.map((db) =>
          db.id === databaseId ? { ...db, status } : db,
        ),
      }
      const anyRunning = updated.databases.some((db) => db.status === "RUNNING")
      const allFailed = updated.databases.every((db) => db.status === "FAILED")
      const anyReadyOrStopped = updated.databases.some((db) => db.status === "SCRIPT_READY" || db.status === "STOPPED")
      let benchmarkStatus = prev.status
      if (allFailed) benchmarkStatus = "FAILED"
      else if (anyRunning) benchmarkStatus = "RUNNING"
      else if (anyReadyOrStopped) benchmarkStatus = "READY_TO_RUN"
      return { ...updated, status: benchmarkStatus }
    })
  }, [])

  const handleRedeployAll = async () => {
    if (!id) return
    setRedeployLoading(true)
    try {
      await benchmarkApi.redeployBenchmark(id)
    } finally {
      setRedeployLoading(false)
    }
  }

  const handleDelete = async () => {
    if (!id || !confirm("Are you sure you want to delete this benchmark? This will stop and remove all containers.")) return
    setDeleteLoading(true)
    try {
      await benchmarkApi.deleteBenchmark(id)
      navigate("/dashboard")
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to delete benchmark")
      setDeleteLoading(false)
    }
  }

  const hasFailedDatabases = benchmark?.databases.some((db) => db.status === "FAILED") ?? false

  if (error) {
    return (
      <AppLayout>
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      </AppLayout>
    )
  }

  if (!benchmark) {
    return (
      <AppLayout>
        <p className="text-muted-foreground">Loading...</p>
      </AppLayout>
    )
  }

  const currentStepIndex = STEP_ORDER[benchmark.status] ?? 0
  const isFailed = benchmark.status === "FAILED"
  const statusConfig = getBenchmarkStatusConfig(benchmark.status)
  const infoMessage = INFO_MESSAGES[benchmark.status]

  return (
    <AppLayout>
      <Button variant="ghost" size="sm" onClick={() => navigate("/dashboard")} className="mb-4">
        <ArrowLeft className="h-4 w-4 mr-2" />
        Back to Dashboard
      </Button>

      <Card className="mb-6">
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle className="text-xl">{benchmark.topic}</CardTitle>
            <Badge className={cn("rounded-full px-3 py-0.5 text-xs font-medium border-0", statusConfig.bgClass, statusConfig.textClass)}>
              {statusConfig.animate && <Loader2 className="h-3 w-3 animate-spin mr-1" />}
              {statusConfig.label}
            </Badge>
          </div>
          <div className="flex items-center justify-between mt-2">
            <p className="text-sm text-muted-foreground">
              Created {new Date(benchmark.createdAt).toLocaleString()}
            </p>
            <div className="flex items-center gap-2">
              {hasFailedDatabases && (
                <Button size="sm" onClick={handleRedeployAll} disabled={redeployLoading}>
                  {redeployLoading ? <Loader2 className="h-4 w-4 animate-spin mr-1" /> : <RefreshCw className="h-4 w-4 mr-1" />}
                  Redeploy All Failed
                </Button>
              )}
              <Button size="sm" variant="outline" onClick={handleDelete} disabled={deleteLoading} className="text-destructive hover:text-destructive">
                {deleteLoading ? <Loader2 className="h-4 w-4 animate-spin mr-1" /> : <Trash2 className="h-4 w-4 mr-1" />}
                Delete
              </Button>
            </div>
          </div>
        </CardHeader>
      </Card>

      {/* Progress Timeline */}
      <div className="mb-6 px-4">
        <div className="flex items-center justify-between">
          {TIMELINE_STEPS.map((step, i) => {
            const isComplete = !isFailed && currentStepIndex > i
            const isCurrent = !isFailed && currentStepIndex === i
            const isFailedStep = isFailed && i === 0

            return (
              <div key={step.key} className="flex items-center flex-1 last:flex-none">
                <div className="flex flex-col items-center gap-1.5">
                  <div
                    className={cn(
                      "h-8 w-8 rounded-full flex items-center justify-center text-xs font-medium transition-all duration-500",
                      isComplete && "bg-primary text-primary-foreground",
                      isCurrent && "bg-primary/20 text-primary ring-2 ring-primary/40",
                      isFailedStep && "bg-destructive/20 text-destructive ring-2 ring-destructive/40",
                      !isComplete && !isCurrent && !isFailedStep && "bg-muted text-muted-foreground",
                    )}
                  >
                    {isCurrent && (
                      <motion.div
                        className="absolute h-8 w-8 rounded-full bg-primary/10"
                        animate={{ scale: [1, 1.4, 1], opacity: [0.5, 0, 0.5] }}
                        transition={{ duration: 2, repeat: Infinity }}
                      />
                    )}
                    {i + 1}
                  </div>
                  <span className={cn(
                    "text-xs whitespace-nowrap",
                    (isComplete || isCurrent) ? "text-foreground font-medium" : "text-muted-foreground"
                  )}>
                    {step.label}
                  </span>
                </div>
                {i < TIMELINE_STEPS.length - 1 && (
                  <div className="flex-1 mx-2 mt-[-1.5rem]">
                    <div className="h-0.5 w-full rounded-full bg-muted overflow-hidden">
                      <motion.div
                        className="h-full bg-primary"
                        initial={{ width: "0%" }}
                        animate={{ width: isComplete ? "100%" : isCurrent ? "50%" : "0%" }}
                        transition={{ duration: 0.6, ease: "easeOut" }}
                      />
                    </div>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>

      {/* Info Banner */}
      <AnimatePresence mode="wait">
        {infoMessage && (
          <motion.div
            key={benchmark.status}
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.3 }}
            className="mb-6 overflow-hidden"
          >
            <div className="flex items-start gap-3 rounded-lg bg-status-info-bg p-4">
              <Info className="h-5 w-5 text-status-info-text shrink-0 mt-0.5" />
              <p className="text-sm text-status-info-text">{infoMessage}</p>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <h3 className="text-lg font-semibold mb-4">
        Databases ({benchmark.databases.length})
      </h3>

      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {benchmark.databases.map((db, i) => (
          <motion.div
            key={db.id}
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.08, duration: 0.35 }}
          >
            <DatabaseCard
              database={db}
              benchmarkId={benchmark.id}
              scriptPreview={scriptPreviews[db.id]}
              onStatusChange={handleDatabaseStatusChange}
            />
          </motion.div>
        ))}
      </div>
    </AppLayout>
  )
}
