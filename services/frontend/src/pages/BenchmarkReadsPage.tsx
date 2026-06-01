import { useCallback, useEffect, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { ArrowLeft, Search } from "lucide-react"
import { motion } from "framer-motion"
import { AppLayout } from "@/components/AppLayout"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { benchmarkApi, insertApi, readApi, ApiError } from "@/lib/api"
import { ReadRunForm } from "@/components/read/ReadRunForm"
import { ActiveReadRunPanel } from "@/components/read/ActiveReadRunPanel"
import { ReadRunHistory } from "@/components/read/ReadRunHistory"
import type { BenchmarkResponse } from "@/types/benchmark"
import type { EntityChoice } from "@/types/insert"
import type {
  ReadResultResponse,
  ReadRunResponse,
  ReadStatus,
  StartReadRunRequest,
} from "@/types/read"

export default function BenchmarkReadsPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const [benchmark, setBenchmark] = useState<BenchmarkResponse | null>(null)
  const [entities, setEntities] = useState<EntityChoice[]>([])
  const [runs, setRuns] = useState<ReadRunResponse[]>([])
  const [pinnedRunId, setPinnedRunId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!id) return
    Promise.all([
      benchmarkApi.get(id),
      insertApi.listEntities(id).catch(() => [] as EntityChoice[]),
      readApi.listRuns(id).catch(() => [] as ReadRunResponse[]),
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

  const handleSubmit = useCallback(
    async (request: StartReadRunRequest) => {
      if (!id) return
      setSubmitting(true)
      setError(null)
      try {
        const created = await readApi.startRun(id, request)
        setRuns((prev) => [created, ...prev])
        setPinnedRunId(created.id)
      } catch (e) {
        const msg = e instanceof ApiError ? e.message : (e as Error).message
        setError(msg || "Failed to start read run")
      } finally {
        setSubmitting(false)
      }
    },
    [id],
  )

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
              <Search className="h-6 w-6 text-primary" />
              Read benchmark
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
          <ReadRunForm
            entities={entities}
            databases={benchmark.databases}
            loading={submitting}
            onSubmit={handleSubmit}
          />
        )}

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
