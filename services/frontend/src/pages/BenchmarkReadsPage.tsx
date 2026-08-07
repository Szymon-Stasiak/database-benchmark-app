import { useCallback, useEffect, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { Search } from "lucide-react"
import { motion } from "framer-motion"
import { AppLayout } from "@/components/AppLayout"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { BackButton } from "@/components/shared/BackButton"
import { PageHeader } from "@/components/shared/PageHeader"
import { benchmarkApi, insertApi, readApi, registryApi, ApiError } from "@/lib/api"
import { ReadRunForm } from "@/components/read/ReadRunForm"
import { RunPlanPreview } from "@/components/benchmark/RunPlanPreview"
import { ActiveReadRunPanel } from "@/components/read/ActiveReadRunPanel"
import { ReadRunHistory } from "@/components/read/ReadRunHistory"
import { DatabaseSizeChart } from "@/components/insert/DatabaseSizeChart"
import type { BenchmarkResponse } from "@/types/benchmark"
import type { EntityChoice } from "@/types/insert"
import type {
  ReadResultResponse,
  ReadRunResponse,
  ReadStatus,
  StartReadRunRequest,
} from "@/types/read"
import type { PreparedRunResponse, RegistrySummaryEntry } from "@/types/preview"

export default function BenchmarkReadsPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const [benchmark, setBenchmark] = useState<BenchmarkResponse | null>(null)
  const [entities, setEntities] = useState<EntityChoice[]>([])
  const [runs, setRuns] = useState<ReadRunResponse[]>([])
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
      readApi.listRuns(id).catch(() => [] as ReadRunResponse[]),
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
    async (request: StartReadRunRequest) => {
      if (!id) return
      setPreparing(true)
      setError(null)
      setPreviewOpen(true)
      try {
        const prep = await readApi.prepareRun(id, request)
        setPrepared(prep)
      } catch (e) {
        const msg = e instanceof ApiError ? e.message : (e as Error).message
        setError(msg || "Failed to prepare read run")
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
      await readApi.confirmRun(prepared.runId)
      const fresh = await readApi.listRuns(id)
      setRuns(fresh)
      setPinnedRunId(prepared.runId)
      setPreviewOpen(false)
      setPrepared(null)
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : (e as Error).message
      setError(msg || "Failed to confirm read run")
    } finally {
      setSubmitting(false)
    }
  }, [id, prepared])

  const handleCancel = useCallback(() => {
    setPreviewOpen(false)
    setPrepared(null)
  }, [])

  const handleRunStatusChange = useCallback((runId: string, status: ReadStatus) => {
    setRuns((prev) => prev.map((r) => (r.id === runId ? { ...r, status } : r)))
  }, [])

  const handleResultUpdate = useCallback((runId: string, data: ReadResultResponse) => {
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

  const handleSelectFromHistory = useCallback((run: ReadRunResponse) => {
    setPinnedRunId(run.id)
  }, [])

  const pinnedRun = pinnedRunId ? runs.find((r) => r.id === pinnedRunId) ?? null : null

  return (
    <AppLayout
      breadcrumbs={[
        { label: "Dashboard", to: "/dashboard" },
        { label: benchmark?.topic ?? "Benchmark", to: id ? `/benchmarks/${id}` : undefined },
        { label: "Reads" },
      ]}
    >
      <BackButton to={id ? `/benchmarks/${id}` : "/dashboard"} label="Back to benchmark" />
      <PageHeader
        icon={Search}
        title="Read benchmark"
        subtitle={
          benchmark ? (
            <>
              Benchmark: <span className="font-medium text-foreground">{benchmark.topic}</span> ·{" "}
              {benchmark.databases.length} database(s) · same IDs read on every DB
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
          <ReadRunForm
            entities={entities}
            databases={benchmark.databases}
            loading={preparing || submitting}
            registry={registry}
            onSubmit={handlePreview}
          />
        )}

        {benchmark && <DatabaseSizeChart benchmarkId={benchmark.id} />}

        {benchmark && pinnedRun && (
          <ActiveReadRunPanel
            benchmarkId={benchmark.id}
            run={pinnedRun}
            onRunStatusChange={handleRunStatusChange}
            onResultUpdate={handleResultUpdate}
          />
        )}

        <ReadRunHistory
          runs={runs}
          selectedRunId={pinnedRunId}
          onSelect={handleSelectFromHistory}
        />
      </motion.div>

      <RunPlanPreview
        open={previewOpen}
        operation="READ"
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
  results: ReadResultResponse[],
  incoming: ReadResultResponse,
): ReadResultResponse[] {
  const idx = results.findIndex((r) => r.id === incoming.id)
  if (idx === -1) return [...results, incoming]
  const next = [...results]
  next[idx] = { ...next[idx], ...incoming }
  return next
}
