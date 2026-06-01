import { useCallback, useEffect, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { ArrowLeft, Trash2 } from "lucide-react"
import { motion } from "framer-motion"
import { AppLayout } from "@/components/AppLayout"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { benchmarkApi, deleteApi, insertApi, ApiError } from "@/lib/api"
import { DeleteRunForm } from "@/components/delete/DeleteRunForm"
import { DeletePlanPreview } from "@/components/delete/DeletePlanPreview"
import { ActiveDeleteRunPanel } from "@/components/delete/ActiveDeleteRunPanel"
import { DeleteRunHistory } from "@/components/delete/DeleteRunHistory"
import type { BenchmarkResponse } from "@/types/benchmark"
import type { EntityChoice } from "@/types/insert"
import type {
  DeleteResultResponse,
  DeleteRunResponse,
  DeleteStatus,
  StartDeleteRunRequest,
} from "@/types/delete"

export default function BenchmarkDeletesPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const [benchmark, setBenchmark] = useState<BenchmarkResponse | null>(null)
  const [entities, setEntities] = useState<EntityChoice[]>([])
  const [runs, setRuns] = useState<DeleteRunResponse[]>([])
  const [pinnedRunId, setPinnedRunId] = useState<string | null>(null)
  const [pendingRequest, setPendingRequest] = useState<StartDeleteRunRequest | null>(null)
  const [previewOpen, setPreviewOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!id) return
    Promise.all([
      benchmarkApi.get(id),
      insertApi.listEntities(id).catch(() => [] as EntityChoice[]),
      deleteApi.listRuns(id).catch(() => [] as DeleteRunResponse[]),
    ])
      .then(([b, e, r]) => {
        setBenchmark(b)
        setEntities(e)
        setRuns(r)
        if (r.length > 0) setPinnedRunId(r[0].id)
      })
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 404) {
          navigate("/dashboard")
          return
        }
        setError(e instanceof Error ? e.message : "Failed to load benchmark")
      })
  }, [id, navigate])

  const handlePreview = useCallback((request: StartDeleteRunRequest) => {
    setPendingRequest(request)
    setPreviewOpen(true)
  }, [])

  const handleConfirm = useCallback(async () => {
    if (!id || !pendingRequest) return
    setSubmitting(true)
    setError(null)
    try {
      const created = await deleteApi.startRun(id, pendingRequest)
      setRuns((prev) => [created, ...prev])
      setPinnedRunId(created.id)
      setPreviewOpen(false)
      setPendingRequest(null)
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : (e as Error).message
      setError(msg || "Failed to start delete run")
    } finally {
      setSubmitting(false)
    }
  }, [id, pendingRequest])

  const handleCancel = useCallback(() => {
    setPreviewOpen(false)
    setPendingRequest(null)
  }, [])

  const handleRunStatusChange = useCallback((runId: string, status: DeleteStatus) => {
    setRuns((prev) => prev.map((r) => (r.id === runId ? { ...r, status } : r)))
  }, [])

  const handleResultUpdate = useCallback((runId: string, data: DeleteResultResponse) => {
    setRuns((prev) =>
      prev.map((r) =>
        r.id === runId
          ? {
              ...r,
              results: upsertResult(r.results, data),
            }
          : r,
      ),
    )
  }, [])

  const handleSelectFromHistory = useCallback((run: DeleteRunResponse) => {
    setPinnedRunId(run.id)
  }, [])

  const pinnedRun = pinnedRunId ? runs.find((r) => r.id === pinnedRunId) ?? null : null

  return (
    <AppLayout>
      <Button
        variant="ghost"
        size="sm"
        onClick={() => navigate(id ? `/benchmarks/${id}` : "/dashboard")}
        className="mb-4"
      >
        <ArrowLeft className="h-4 w-4 mr-2" />
        Back to benchmark
      </Button>

      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
        className="space-y-6"
      >
        <div className="flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold inline-flex items-center gap-2">
              <Trash2 className="h-6 w-6 text-destructive" />
              Delete benchmark
            </h1>
            <p className="text-sm text-muted-foreground mt-1">
              {benchmark ? (
                <>
                  Benchmark: <span className="font-medium">{benchmark.topic}</span> ·{" "}
                  {benchmark.databases.length} database(s)
                </>
              ) : (
                "Loading benchmark…"
              )}
            </p>
          </div>
        </div>

        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        {benchmark && entities.length === 0 && (
          <Alert>
            <AlertDescription>
              No logical schema is attached to this benchmark yet.
            </AlertDescription>
          </Alert>
        )}

        {benchmark && (
          <DeleteRunForm
            entities={entities}
            databases={benchmark.databases}
            loading={submitting}
            onPreview={handlePreview}
          />
        )}

        {benchmark && pinnedRun && (
          <ActiveDeleteRunPanel
            benchmarkId={benchmark.id}
            run={pinnedRun}
            onRunStatusChange={handleRunStatusChange}
            onResultUpdate={handleResultUpdate}
          />
        )}

        <DeleteRunHistory
          runs={runs}
          selectedRunId={pinnedRunId}
          onSelect={handleSelectFromHistory}
        />
      </motion.div>

      <DeletePlanPreview
        open={previewOpen}
        request={pendingRequest}
        databases={benchmark?.databases ?? []}
        submitting={submitting}
        onConfirm={handleConfirm}
        onCancel={handleCancel}
      />
    </AppLayout>
  )
}

function upsertResult(
  results: DeleteResultResponse[],
  incoming: DeleteResultResponse,
): DeleteResultResponse[] {
  const idx = results.findIndex((r) => r.id === incoming.id)
  if (idx === -1) return [...results, incoming]
  const next = [...results]
  next[idx] = { ...next[idx], ...incoming }
  return next
}
