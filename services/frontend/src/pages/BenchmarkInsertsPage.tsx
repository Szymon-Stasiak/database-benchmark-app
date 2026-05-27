import { useCallback, useEffect, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { ArrowLeft, FlaskConical } from "lucide-react"
import { motion } from "framer-motion"
import { AppLayout } from "@/components/AppLayout"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { benchmarkApi, insertApi, ApiError } from "@/lib/api"
import { InsertRunForm } from "@/components/insert/InsertRunForm"
import { ActiveInsertRunPanel } from "@/components/insert/ActiveInsertRunPanel"
import { InsertRunHistory } from "@/components/insert/InsertRunHistory"
import { DatabaseSizeChart } from "@/components/insert/DatabaseSizeChart"
import { RunDetailDialog } from "@/components/insert/RunDetailDialog"
import type { BenchmarkResponse } from "@/types/benchmark"
import type {
  EntityChoice,
  InsertResultResponse,
  InsertRunResponse,
  InsertStatus,
  StartInsertRunRequest,
} from "@/types/insert"

export default function BenchmarkInsertsPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const [benchmark, setBenchmark] = useState<BenchmarkResponse | null>(null)
  const [entities, setEntities] = useState<EntityChoice[]>([])
  const [runs, setRuns] = useState<InsertRunResponse[]>([])
  /** Run IDs shown in the live panel section (multiple when the user kicked off several at once). */
  const [pinnedRunIds, setPinnedRunIds] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [detailRunId, setDetailRunId] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    Promise.all([
      benchmarkApi.get(id),
      insertApi.listEntities(id).catch(() => [] as EntityChoice[]),
      insertApi.listRuns(id).catch(() => [] as InsertRunResponse[]),
    ])
      .then(([b, e, r]) => {
        setBenchmark(b)
        setEntities(e)
        setRuns(r)
        if (r.length > 0) setPinnedRunIds([r[0].id])
      })
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 404) {
          navigate("/dashboard")
          return
        }
        setError(e instanceof Error ? e.message : "Failed to load benchmark")
      })
  }, [id, navigate])

  const handleSubmit = useCallback(
    async (request: StartInsertRunRequest) => {
      if (!id) return
      setSubmitting(true)
      setError(null)
      try {
        const created = await insertApi.startRun(id, request)
        setRuns((prev) => [created, ...prev])
        setPinnedRunIds([created.id])
      } catch (e) {
        const msg = e instanceof ApiError ? e.message : (e as Error).message
        setError(msg || "Failed to start insert run")
      } finally {
        setSubmitting(false)
      }
    },
    [id],
  )

  const handleRunStatusChange = useCallback((runId: string, status: InsertStatus) => {
    setRuns((prev) => prev.map((r) => (r.id === runId ? { ...r, status } : r)))
  }, [])

  const handleResultUpdate = useCallback((runId: string, data: InsertResultResponse) => {
    setRuns((prev) =>
      prev.map((r) =>
        r.id === runId
          ? {
              ...r,
              results: r.results.map((rr) => (rr.id === data.id ? { ...rr, ...data } : rr)),
            }
          : r,
      ),
    )
  }, [])

  const handleSelectFromHistory = useCallback((run: InsertRunResponse) => {
    setDetailRunId(run.id)
  }, [])

  const pinnedRuns = pinnedRunIds
    .map((rid) => runs.find((r) => r.id === rid))
    .filter((r): r is InsertRunResponse => Boolean(r))

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
              <FlaskConical className="h-6 w-6 text-primary" />
              Insert benchmark
            </h1>
            <p className="text-sm text-muted-foreground mt-1">
              {benchmark ? (
                <>
                  Benchmark: <span className="font-medium">{benchmark.topic}</span> · {benchmark.databases.length} database(s)
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
              No logical schema is attached to this benchmark yet. Wait for script generation to finish.
            </AlertDescription>
          </Alert>
        )}

        {benchmark && <DatabaseSizeChart benchmarkId={benchmark.id} />}

        {benchmark && (
          <InsertRunForm
            entities={entities}
            databases={benchmark.databases}
            benchmarkId={benchmark.id}
            loading={submitting}
            onSubmit={handleSubmit}
          />
        )}

        {pinnedRuns.map((run) => (
          <ActiveInsertRunPanel
            key={run.id}
            run={run}
            onRunStatusChange={handleRunStatusChange}
            onResultUpdate={handleResultUpdate}
          />
        ))}

        <InsertRunHistory
          runs={runs}
          selectedRunId={pinnedRunIds[0] ?? null}
          onSelect={handleSelectFromHistory}
        />

        <RunDetailDialog
          run={detailRunId ? runs.find((r) => r.id === detailRunId) ?? null : null}
          open={detailRunId !== null}
          onClose={() => setDetailRunId(null)}
        />
      </motion.div>
    </AppLayout>
  )
}
