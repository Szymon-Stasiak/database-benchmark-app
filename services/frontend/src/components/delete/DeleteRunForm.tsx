import { useEffect, useMemo, useState } from "react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Loader2, Trash2 } from "lucide-react"
import { cn } from "@/lib/utils"
import type { DatabaseResponse } from "@/types/benchmark"
import type { EntityChoice } from "@/types/insert"
import type { DeletionMode, OperationMode, StartDeleteRunRequest } from "@/types/delete"
import type { RegistrySummaryEntry } from "@/types/preview"

interface Props {
  entities: EntityChoice[]
  databases: DatabaseResponse[]
  loading: boolean
  registry?: RegistrySummaryEntry[]
  onPreview: (request: StartDeleteRunRequest) => void
}

export function DeleteRunForm({ entities, databases, loading, registry, onPreview }: Props) {
  const runnableDatabases = useMemo(
    () => databases.filter((d) => d.status === "RUNNING"),
    [databases],
  )

  const [entityName, setEntityName] = useState<string>(entities[0]?.name ?? "")
  const [sampleSize, setSampleSize] = useState<number>(50)
  const [deletionMode, setDeletionMode] = useState<DeletionMode>("WITH_CHILDREN")
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
    if (!entityName) return "Pick an entity to delete from."
    if (sampleSize < 1 || sampleSize > 1_000_000)
      return "Sample size must be between 1 and 1,000,000."
    if (selectedDbIds.size === 0) return "Select at least one running database."
    return null
  }

  const review = () => {
    const err = validate()
    if (err) {
      setSubmitError(err)
      return
    }
    setSubmitError(null)
    onPreview({
      entityName,
      sampleSize,
      includeChildren: deletionMode === "WITH_CHILDREN",
      deletionMode,
      mode,
      databaseIds: Array.from(selectedDbIds),
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">Configure delete run</CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
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
                return `Pool: ${pool.toLocaleString()} IDs · same set deleted on every DB.`
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
          </div>

          <div className="space-y-1.5">
            <Label>Deletion strategy</Label>
            <div className="grid grid-cols-1 gap-1.5">
              {(
                [
                  {
                    value: "NATIVE",
                    title: "Native",
                    desc: "Each engine does its own thing — PG may fail on FK, Mongo deletes only doc, Neo4j DETACH only edges",
                  },
                  {
                    value: "WITH_CHILDREN",
                    title: "Cascade children",
                    desc: "Walk FK chain and delete all descendants (PG/MySQL/Mongo/Neo4j)",
                  },
                  {
                    value: "ROOT_ONLY",
                    title: "Root only (orphan)",
                    desc: "Force delete root, leave children as orphans (PG: disable FK check, MySQL: SET FOREIGN_KEY_CHECKS=0)",
                  },
                ] as const
              ).map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  onClick={() => setDeletionMode(opt.value)}
                  className={cn(
                    "rounded-md border px-3 py-1.5 text-sm text-left transition-all",
                    deletionMode === opt.value
                      ? "border-primary bg-primary/5 ring-1 ring-primary"
                      : "border-border hover:border-foreground/30",
                  )}
                >
                  <div className="font-medium text-xs">{opt.title}</div>
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
                  {opt === "SINGLE" && "1 stmt per row"}
                  {opt === "BATCH" && "addBatch / executeBatch"}
                  {opt === "BULK" && "1 stmt: WHERE pk IN (...)"}
                </div>
              </button>
            ))}
          </div>
          {deletionMode === "WITH_CHILDREN" && mode !== "SINGLE" && (
            <p className="text-[11px] text-amber-600 dark:text-amber-400">
              Cascade is per-row by design — root delete uses {mode}, but child cleanup runs SINGLE.
            </p>
          )}
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
            onClick={review}
            disabled={loading || runnableDatabases.length === 0 || entities.length === 0}
            variant="destructive"
          >
            {loading ? (
              <Loader2 className="h-4 w-4 mr-2 animate-spin" />
            ) : (
              <Trash2 className="h-4 w-4 mr-2" />
            )}
            Review delete plan
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
