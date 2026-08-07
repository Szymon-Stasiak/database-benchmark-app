import { useCallback, useEffect, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { FlaskConical } from "lucide-react"
import { BackButton } from "@/components/shared/BackButton"
import { PageHeader } from "@/components/shared/PageHeader"
import { motion } from "framer-motion"
import { AppLayout } from "@/components/AppLayout"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { benchmarkApi, insertApi, registryApi, scenarioApi, ApiError } from "@/lib/api"
import { ScenarioRunForm } from "@/components/scenarios/ScenarioRunForm"
import { ActiveScenarioRunPanel } from "@/components/scenarios/ActiveScenarioRunPanel"
import { ScenarioRunHistory } from "@/components/scenarios/ScenarioRunHistory"
import type { BenchmarkResponse } from "@/types/benchmark"
import type { EntityChoice } from "@/types/insert"
import type { RegistrySummaryEntry } from "@/types/preview"
import type {
  ConsistencyStatus,
  PreparedScenarioRunResponse,
  ScenarioApplicabilityMap,
  ScenarioResultResponse,
  ScenarioRunResponse,
  ScenarioStatus,
  SchemaRelationship,
  StartScenarioRunRequest,
} from "@/types/scenario"

export default function BenchmarkScenariosPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const [benchmark, setBenchmark] = useState<BenchmarkResponse | null>(null)
  const [entities, setEntities] = useState<EntityChoice[]>([])
  const [runs, setRuns] = useState<ScenarioRunResponse[]>([])
  const [registry, setRegistry] = useState<RegistrySummaryEntry[]>([])
  const [applicability, setApplicability] = useState<ScenarioApplicabilityMap | null>(null)
  const [relationships, setRelationships] = useState<SchemaRelationship[]>([])
  const [pinnedRunId, setPinnedRunId] = useState<string | null>(null)
  const [prepared, setPrepared] = useState<PreparedScenarioRunResponse | null>(null)
  const [previewOpen, setPreviewOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [preparing, setPreparing] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!id) return
    Promise.all([
      benchmarkApi.get(id),
      insertApi.listEntities(id).catch(() => [] as EntityChoice[]),
      scenarioApi.listRuns(id).catch(() => [] as ScenarioRunResponse[]),
      registryApi.getSummary(id).catch(() => [] as RegistrySummaryEntry[]),
      scenarioApi.getApplicability(id).catch(() => null),
      scenarioApi.listRelationships(id).catch(() => [] as SchemaRelationship[]),
    ])
      .then(([b, e, r, reg, app, rels]) => {
        setBenchmark(b)
        setEntities(e)
        setRuns(r)
        setRegistry(reg)
        setApplicability(app)
        setRelationships(rels)
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
    async (request: StartScenarioRunRequest) => {
      if (!id) return
      setPreparing(true)
      setError(null)
      setPreviewOpen(true)
      try {
        const prep = await scenarioApi.prepareRun(id, request)
        setPrepared(prep)
      } catch (e) {
        const msg = e instanceof ApiError ? e.message : (e as Error).message
        setError(msg || "Failed to prepare scenario run")
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
      await scenarioApi.confirmRun(prepared.runId)
      const fresh = await scenarioApi.listRuns(id)
      setRuns(fresh)
      setPinnedRunId(prepared.runId)
      setPreviewOpen(false)
      setPrepared(null)
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : (e as Error).message
      setError(msg || "Failed to confirm scenario run")
    } finally {
      setSubmitting(false)
    }
  }, [id, prepared])

  const handleCancel = useCallback(() => {
    setPreviewOpen(false)
    setPrepared(null)
  }, [])

  const handleRunStatusChange = useCallback((runId: string, status: ScenarioStatus, consistencyStatus: string) => {
    setRuns((prev) =>
      prev.map((r) =>
        r.id === runId
          ? { ...r, status, consistencyStatus: (consistencyStatus || r.consistencyStatus) as ConsistencyStatus }
          : r,
      ),
    )
  }, [])

  const handleResultUpdate = useCallback((runId: string, data: ScenarioResultResponse) => {
    setRuns((prev) =>
      prev.map((r) =>
        r.id === runId
          ? { ...r, results: upsertResult(r.results, data) }
          : r,
      ),
    )
  }, [])

  const handleSelectFromHistory = useCallback((run: ScenarioRunResponse) => {
    setPinnedRunId(run.id)
  }, [])

  const pinnedRun = pinnedRunId ? runs.find((r) => r.id === pinnedRunId) ?? null : null

  return (
    <AppLayout
      breadcrumbs={[
        { label: "Dashboard", to: "/dashboard" },
        { label: benchmark?.topic ?? "Benchmark", to: id ? `/benchmarks/${id}` : undefined },
        { label: "Scenarios" },
      ]}
    >
      <BackButton to={id ? `/benchmarks/${id}` : "/dashboard"} label="Back to benchmark" />
      <PageHeader
        icon={FlaskConical}
        title="Query scenarios"
        subtitle={
          benchmark ? (
            <>
              Benchmark: <span className="font-medium text-foreground">{benchmark.topic}</span> ·{" "}
              {benchmark.databases.length} database(s) · results cross-checked across DBs
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
            <AlertDescription>No logical schema is attached to this benchmark yet.</AlertDescription>
          </Alert>
        )}

        {benchmark && (
          <ScenarioRunForm
            benchmarkId={benchmark.id}
            entities={entities}
            databases={benchmark.databases}
            loading={preparing || submitting}
            applicability={applicability}
            relationships={relationships}
            registry={registry}
            onSubmit={handlePreview}
          />
        )}

        {benchmark && pinnedRun && (
          <ActiveScenarioRunPanel
            benchmarkId={benchmark.id}
            run={pinnedRun}
            onRunStatusChange={handleRunStatusChange}
            onResultUpdate={handleResultUpdate}
          />
        )}

        <ScenarioRunHistory
          runs={runs}
          selectedRunId={pinnedRunId}
          onSelect={handleSelectFromHistory}
        />
      </motion.div>

      <Dialog open={previewOpen} onOpenChange={(o) => { if (!o) handleCancel() }}>
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle>Confirm scenario run</DialogTitle>
            <DialogDescription className="text-xs">
              {prepared
                ? `Scenario ${prepared.scenarioType} — ${prepared.applicability.filter((a) => a.applicable).length} applicable DB(s)`
                : preparing ? "Preparing…" : ""}
            </DialogDescription>
          </DialogHeader>
          {prepared && (
            <div className="space-y-2">
              <div className="text-sm">Applicability per database:</div>
              <ul className="text-xs space-y-1">
                {prepared.applicability.map((a) => (
                  <li key={a.databaseId} className="flex items-center gap-2">
                    <span className={a.applicable ? "text-green-600 dark:text-green-400" : "text-muted-foreground"}>
                      {a.applicable ? "✓" : "✗"}
                    </span>
                    <span className="capitalize">{a.dbName}</span>
                    {a.reason && <span className="text-muted-foreground">— {a.reason}</span>}
                  </li>
                ))}
              </ul>
              <div className="flex justify-end gap-2 pt-3">
                <Button variant="ghost" onClick={handleCancel} disabled={submitting}>Cancel</Button>
                <Button onClick={handleConfirm} disabled={submitting}>
                  {submitting ? "Submitting…" : "Confirm and run"}
                </Button>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </AppLayout>
  )
}

function upsertResult(
  results: ScenarioResultResponse[],
  incoming: ScenarioResultResponse,
): ScenarioResultResponse[] {
  const idx = results.findIndex((r) => r.id === incoming.id)
  if (idx === -1) return [...results, incoming]
  const next = [...results]
  next[idx] = { ...next[idx], ...incoming }
  return next
}
