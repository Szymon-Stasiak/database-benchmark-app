import { useEffect, useMemo, useState } from "react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Loader2, Search } from "lucide-react"
import { cn } from "@/lib/utils"
import type { DatabaseResponse } from "@/types/benchmark"
import type { EntityChoice } from "@/types/insert"
import type { StartReadRunRequest } from "@/types/read"
import type { OperationMode } from "@/types/delete"
import type { RegistrySummaryEntry } from "@/types/preview"

interface Props {
  entities: EntityChoice[]
  databases: DatabaseResponse[]
  loading: boolean
  registry?: RegistrySummaryEntry[]
  onSubmit: (request: StartReadRunRequest) => Promise<void>
}

export function ReadRunForm({ entities, databases, loading, registry, onSubmit }: Props) {
  const runnableDatabases = useMemo(
    () => databases.filter((d) => d.status === "RUNNING"),
    [databases],
  )

  const [entityName, setEntityName] = useState<string>(entities[0]?.name ?? "")
  const [sampleSize, setSampleSize] = useState<number>(100)
  const [iterations, setIterations] = useState<number>(1)
  const [readDepth, setReadDepth] = useState<"NONE" | "ONE_HOP" | "FULL_CASCADE">("ONE_HOP")
  const [mode, setMode] = useState<OperationMode>("SINGLE")
  const [selectedDbIds, setSelectedDbIds] = useState<Set<string>>(new Set())
  const [submitError, setSubmitError] = useState<string | null>(null)

  useEffect(() => {
    if (!entityName && entities.length > 0) setEntityName(entities[0].name)
  }, [entities, entityName])

  useEffect(() => {
    setSelectedDbIds((prev) => {
      const next = new Set<string>()
      for (const db of runnableDatabases) {
        if (prev.has(db.id) || prev.size === 0) next.add(db.id)
      }
      return next
    })
  }, [runnableDatabases])

  const toggleDb = (id: string) =>
    setSelectedDbIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const validate = (): string | null => {
    if (!entityName) return "Pick an entity to read."
    if (sampleSize < 1 || sampleSize > 1_000_000)
      return "Sample size must be between 1 and 1,000,000."
    if (iterations < 1 || iterations > 50)
      return "Iterations must be between 1 and 50."
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
    await onSubmit({
      entityName,
      sampleSize,
      includeChildren: readDepth !== "NONE",
      readDepth,
      mode,
      iterations,
      databaseIds: Array.from(selectedDbIds),
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">Configure read run</CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          <div className="space-y-1.5">
            <Label htmlFor="entity-name">Entity</Label>
            <select
              id="entity-name"
              value={entityName}
              onChange={(e) => setEntityName(e.target.value)}
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
            >
              {entities.map((e) => (
                <option key={e.name} value={e.name}>
                  {e.name}
                </option>
              ))}
            </select>
            <p className="text-[11px] text-muted-foreground">
              {(() => {
                const pool = registry?.find((r) => r.entityName === entityName)?.availableIds
                if (pool === undefined) return "Same logical IDs picked once, applied to every database."
                return `Pool: ${pool.toLocaleString()} IDs · same set read on every DB.`
              })()}
            </p>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="sample-size">Sample size (N)</Label>
            <Input
              id="sample-size"
              type="number"
              min={1}
              max={1_000_000}
              value={sampleSize}
              onChange={(e) => setSampleSize(Math.max(1, Number(e.target.value)))}
            />
            {iterations > 1 && (
              <p className="text-[11px] text-muted-foreground">
                Effective samples: {(sampleSize * iterations).toLocaleString()} ({iterations} × {sampleSize.toLocaleString()})
              </p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="iterations">Iterations</Label>
            <Input
              id="iterations"
              type="number"
              min={1}
              max={50}
              value={iterations}
              onChange={(e) => setIterations(Math.max(1, Math.min(50, Number(e.target.value))))}
            />
            <p className="text-[11px] text-muted-foreground">
              Re-run the read N times. All samples merged → tighter percentiles.
            </p>
          </div>

          <div className="space-y-1.5">
            <Label>Read depth</Label>
            <div className="grid grid-cols-1 gap-1">
              {([
                { value: "NONE", title: "PK only", desc: "Just the entity by primary key" },
                { value: "ONE_HOP", title: "+ 1 hop", desc: "Entity + direct children (single FK hop)" },
                { value: "FULL_CASCADE", title: "+ full cascade", desc: "Entity + all descendants through schema (up to 5 levels)" },
              ] as const).map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  onClick={() => setReadDepth(opt.value)}
                  className={cn(
                    "rounded-md border px-3 py-1.5 text-xs text-left transition-all",
                    readDepth === opt.value
                      ? "border-primary bg-primary/5 ring-1 ring-primary"
                      : "border-border hover:border-foreground/30",
                  )}
                >
                  <div className="font-medium">{opt.title}</div>
                  <div className="text-[10px] text-muted-foreground leading-tight">{opt.desc}</div>
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="space-y-1.5">
          <Label>Execution mode</Label>
          <div className="grid grid-cols-3 gap-2">
            {(["SINGLE", "BATCH", "BULK"] as const).map((opt) => (
              <button
                key={opt}
                type="button"
                onClick={() => setMode(opt)}
                className={cn(
                  "rounded-md border px-3 py-2 text-sm text-left transition-all",
                  mode === opt
                    ? "border-primary bg-primary/5 ring-1 ring-primary"
                    : "border-border hover:border-foreground/30",
                )}
              >
                <div className="font-medium">{opt}</div>
                <div className="text-[11px] text-muted-foreground">
                  {opt === "SINGLE" && "1 SELECT per row"}
                  {opt === "BATCH" && "1 SELECT per row, reused conn"}
                  {opt === "BULK" && "1 SELECT: WHERE pk IN (...)"}
                </div>
              </button>
            ))}
          </div>
        </div>

        <div className="space-y-2">
          <Label>Run against ({selectedDbIds.size} selected)</Label>
          {runnableDatabases.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No databases are RUNNING right now.
            </p>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
              {runnableDatabases.map((db) => {
                const active = selectedDbIds.has(db.id)
                return (
                  <button
                    key={db.id}
                    type="button"
                    onClick={() => toggleDb(db.id)}
                    className={cn(
                      "flex items-center justify-between rounded-lg border px-3 py-2 text-left transition-all",
                      active
                        ? "border-primary bg-primary/5 ring-1 ring-primary"
                        : "border-border hover:border-foreground/30",
                    )}
                  >
                    <div>
                      <div className="text-sm font-medium capitalize">{db.dbName}</div>
                      <div className="text-xs text-muted-foreground">v{db.dbVersion}</div>
                    </div>
                    <span
                      className={cn(
                        "h-4 w-4 rounded-sm border",
                        active ? "border-primary bg-primary" : "border-border",
                      )}
                    />
                  </button>
                )
              })}
            </div>
          )}
        </div>

        {submitError && <p className="text-sm text-destructive">{submitError}</p>}

        <div className="flex justify-end">
          <Button
            type="button"
            onClick={submit}
            disabled={loading || runnableDatabases.length === 0 || entities.length === 0}
            className="bg-primary text-primary-foreground"
          >
            {loading ? (
              <Loader2 className="h-4 w-4 mr-2 animate-spin" />
            ) : (
              <Search className="h-4 w-4 mr-2" />
            )}
            Review read plan
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
