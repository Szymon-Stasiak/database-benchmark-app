import { useCallback, useEffect, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { Trash2 } from "lucide-react"
import { BackButton } from "@/components/shared/BackButton"
import { PageHeader } from "@/components/shared/PageHeader"
import { motion } from "framer-motion"
import { AppLayout } from "@/components/AppLayout"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { benchmarkApi, deleteApi, insertApi, registryApi, ApiError } from "@/lib/api"
import { DeleteRunForm } from "@/components/delete/DeleteRunForm"
import { RunPlanPreview } from "@/components/benchmark/RunPlanPreview"
import { ActiveDeleteRunPanel } from "@/components/delete/ActiveDeleteRunPanel"
import { DeleteRunHistory } from "@/components/delete/DeleteRunHistory"
import { DatabaseSizeChart } from "@/components/insert/DatabaseSizeChart"
import type { BenchmarkResponse } from "@/types/benchmark"
import type { EntityChoice } from "@/types/insert"
import type {
  DeleteResultResponse,
  DeleteRunResponse,
  DeleteStatus,
  StartDeleteRunRequest,
} from "@/types/delete"
import type { PreparedRunResponse, RegistrySummaryEntry } from "@/types/preview"

export default function BenchmarkDeletesPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const [benchmark, setBenchmark] = useState<BenchmarkResponse | null>(null)
  const [entities, setEntities] = useState<EntityChoice[]>([])
  const [runs, setRuns] = useState<DeleteRunResponse[]>([])
  const [registry, setRegistry] = useState<RegistrySummaryEntry[]>([])
  const [pinnedRunId, setPinnedRunId] = useState<string | null>(null)
  const [prepared, setPrepared] = useState<PreparedRunResponse | null>(null)
  const [previewOpen, setPreviewOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [preparing, setPreparing] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!id) return
    Promise.all([
      benchmarkApi.get(id),
      insertApi.listEntities(id).catch(() => [] as EntityChoice[]),
      deleteApi.listRuns(id).catch(() => [] as DeleteRunResponse[]),
      registryApi.getSummary(id).catch(() => [] as RegistrySummaryEntry[]),
    ])
      .then(([b, e, r, reg]) => {
        setBenchmark(b)
        setEntities(e)
        setRuns(r)
        setRegistry(reg)
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

  const handlePreview = useCallback(
    async (request: StartDeleteRunRequest) => {
      if (!id) return
      setPreparing(true)
      setError(null)
      setPreviewOpen(true)
      try {
        const prep = await deleteApi.prepareRun(id, request)
        setPrepared(prep)
      } catch (e) {
        const msg = e instanceof ApiError ? e.message : (e as Error).message
        setError(msg || "Failed to prepare delete run")
        setPreviewOpen(false)
      } finally {
        setPreparing(false)
      }
    },
    [id],
  )

  const handleConfirm = useCallback(async () => {
    if (!id || !prepared) return
    setSubmitting(true)
    setError(null)
    try {
      await deleteApi.confirmRun(prepared.runId)
      const fresh = await deleteApi.listRuns(id)
      setRuns(fresh)
      setPinnedRunId(prepared.runId)
      setPreviewOpen(false)
      setPrepared(null)
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : (e as Error).message
      setError(msg || "Failed to confirm delete run")
    } finally {
      setSubmitting(false)
    }
  }, [id, prepared])

  const handleCancel = useCallback(() => {
    setPreviewOpen(false)
    setPrepared(null)
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
    <AppLayout
      breadcrumbs={[
        { label: "Dashboard", to: "/dashboard" },
        { label: benchmark?.topic ?? "Benchmark", to: id ? `/benchmarks/${id}` : undefined },
        { label: "Deletes" },
      ]}
    >
      <BackButton to={id ? `/benchmarks/${id}` : "/dashboard"} label="Back to benchmark" />
      <PageHeader
        icon={Trash2}
        title="Delete benchmark"
        subtitle={
          benchmark ? (
            <>
              Benchmark: <span className="font-medium text-foreground">{benchmark.topic}</span> ·{" "}
              {benchmark.databases.length} database(s) · same IDs deleted on every DB
            </>
          ) : (
            "Loading benchmark…"
          )
        }
      />

      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
        className="space-y-6"
      >
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
            loading={preparing || submitting}
            registry={registry}
            onPreview={handlePreview}
          />
        )}

        {benchmark && <DatabaseSizeChart benchmarkId={benchmark.id} />}

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

      <RunPlanPreview
        open={previewOpen}
        operation="DELETE"
        preview={prepared?.preview ?? null}
        databaseCount={benchmark?.databases.length ?? 0}
        submitting={submitting}
        loadingPreview={preparing}
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
