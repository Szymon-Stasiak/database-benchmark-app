import { useEffect, useMemo, useState } from "react"
import { DatabaseSelector } from "@/components/shared/DatabaseSelector"
import { OperationModeSelector } from "@/components/shared/OperationModeSelector"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Loader2, Play } from "lucide-react"
import { cn } from "@/lib/utils"
import { EntityCascadePicker } from "@/components/insert/EntityCascadePicker"
import type { DatabaseResponse } from "@/types/benchmark"
import type {
  EntityCascadeChoice,
  EntityChoice,
  InsertMode,
  StartInsertRunRequest,
} from "@/types/insert"
import { INSERT_MODE_LABELS } from "@/types/insert"

interface Props {
  entities: EntityChoice[]
  databases: DatabaseResponse[]
  benchmarkId: string
  loading: boolean
  /** One cascade-aware insert run per submit. */
  onSubmit: (request: StartInsertRunRequest) => Promise<void>
}

const MODE_OPTIONS: InsertMode[] = ["SINGLE", "BATCH", "BULK"]

/**
 * Top-level form: wraps {@link EntityCascadePicker} with run-wide controls (mode, batchSize,
 * workerCount, DB selection) and the submit button.
 */
export function InsertRunForm({ entities, databases, benchmarkId, loading, onSubmit }: Props) {
  const runnableDatabases = useMemo(
    () => databases.filter((d) => d.status === "RUNNING"),
    [databases],
  )

  const [cascadeChoices, setCascadeChoices] = useState<EntityCascadeChoice[]>([])
  const [mode, setMode] = useState<InsertMode>("BATCH")
  const [batchSize, setBatchSize] = useState<number>(100)
  const [workerCount, setWorkerCount] = useState<number>(4)
  const [selectedDbIds, setSelectedDbIds] = useState<Set<string>>(new Set())
  const [submitError, setSubmitError] = useState<string | null>(null)

  useEffect(() => {
    setSelectedDbIds((prev) => {
      const next = new Set<string>()
      for (const db of runnableDatabases) {
        if (prev.has(db.id) || prev.size === 0) next.add(db.id)
      }
      return next
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runnableDatabases.map((d) => d.id).join("|")])

  const toggleDb = (id: string) =>
    setSelectedDbIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const leafTotalRecords = useMemo(
    () => cascadeChoices.reduce((sum, c) => sum + c.recordCount, 0),
    [cascadeChoices],
  )

  const validate = (): string | null => {
    if (cascadeChoices.length === 0) return "Pick at least one leaf entity to insert."
    if (mode === "BATCH" && (batchSize < 1 || batchSize > 1_000_000))
      return "Batch size must be between 1 and 1,000,000."
    if (workerCount < 1 || workerCount > 64) return "Workers must be between 1 and 64."
    if (selectedDbIds.size === 0) return "Select at least one running database."
    return null
  }

  const submit = async () => {
    const err = validate()
    if (err) {
      setSubmitError(err)
      return
    }
    setSubmitError(null)
    const request: StartInsertRunRequest = {
      entities: cascadeChoices,
      mode,
      batchSize: mode === "BATCH" ? batchSize : null,
      workerCount,
      databaseIds: Array.from(selectedDbIds),
    }
    await onSubmit(request)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">Configure insert run</CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        <EntityCascadePicker
          entities={entities}
          benchmarkId={benchmarkId}
          onCascadeReady={setCascadeChoices}
        />

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="space-y-1.5">
            <Label htmlFor="worker-count">Workers (virtual threads)</Label>
            <Input
              id="worker-count"
              type="number"
              min={1}
              max={64}
              value={workerCount}
              onChange={(e) => setWorkerCount(Math.max(1, Number(e.target.value)))}
            />
            <p className="text-[11px] text-muted-foreground">
              Each worker holds one DB connection and pulls batches from a queue.
            </p>
          </div>
          {mode === "BATCH" && (
            <div className="space-y-1.5">
              <Label htmlFor="batch-size">Batch size</Label>
              <Input
                id="batch-size"
                type="number"
                min={1}
                max={1_000_000}
                value={batchSize}
                onChange={(e) => setBatchSize(Math.max(1, Number(e.target.value)))}
              />
            </div>
          )}
          <div className="text-xs text-muted-foreground self-end">
            Leaf entities total: <span className="font-mono">{leafTotalRecords.toLocaleString()}</span> rows
          </div>
        </div>

        <OperationModeSelector
          label="Insertion mode"
          value={mode}
          onChange={setMode}
          options={MODE_OPTIONS.map((m) => ({
            value: m,
            label: m,
            description: INSERT_MODE_LABELS[m],
          }))}
        />

        <DatabaseSelector
          runnableDatabases={runnableDatabases}
          selectedDbIds={selectedDbIds}
          onToggle={toggleDb}
          emptyText="No databases are RUNNING right now. Start containers from the benchmark page first."
        />

        {submitError && (
          <p className="text-sm text-destructive">{submitError}</p>
        )}

        <div className="flex justify-end">
          <Button
            type="button"
            onClick={submit}
            disabled={loading || runnableDatabases.length === 0 || entities.length === 0 || cascadeChoices.length === 0}
            className="bg-primary text-primary-foreground"
          >
            {loading ? (
              <Loader2 className="h-4 w-4 mr-2 animate-spin" />
            ) : (
              <Play className="h-4 w-4 mr-2" />
            )}
            Run cascade insert
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
