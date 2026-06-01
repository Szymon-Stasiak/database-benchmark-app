import { useEffect, useMemo, useState } from "react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Loader2, Trash2 } from "lucide-react"
import { cn } from "@/lib/utils"
import type { DatabaseResponse } from "@/types/benchmark"
import type { EntityChoice } from "@/types/insert"
import type { StartDeleteRunRequest } from "@/types/delete"

interface Props {
  entities: EntityChoice[]
  databases: DatabaseResponse[]
  loading: boolean
  onPreview: (request: StartDeleteRunRequest) => void
}

export function DeleteRunForm({ entities, databases, loading, onPreview }: Props) {
  const runnableDatabases = useMemo(
    () => databases.filter((d) => d.status === "RUNNING"),
    [databases],
  )

  const [entityName, setEntityName] = useState<string>(entities[0]?.name ?? "")
  const [sampleSize, setSampleSize] = useState<number>(50)
  const [includeChildren, setIncludeChildren] = useState<boolean>(true)
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
    if (sampleSize < 1 || sampleSize > 100_000)
      return "Sample size must be between 1 and 100,000."
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
      includeChildren,
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
              Random PKs sampled from the registry per database.
            </p>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="sample-size">Sample size (N)</Label>
            <Input
              id="sample-size"
              type="number"
              min={1}
              max={100_000}
              value={sampleSize}
              onChange={(e) => setSampleSize(Math.max(1, Number(e.target.value)))}
            />
          </div>

          <div className="space-y-1.5">
            <Label>Cascade children</Label>
            <button
              type="button"
              onClick={() => setIncludeChildren((v) => !v)}
              className={cn(
                "w-full rounded-md border px-3 py-2 text-sm text-left transition-all",
                includeChildren
                  ? "border-primary bg-primary/5 ring-1 ring-primary"
                  : "border-border hover:border-foreground/30",
              )}
            >
              {includeChildren
                ? "Cascade via FK / embedded refs"
                : "Drop primary node only"}
            </button>
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
