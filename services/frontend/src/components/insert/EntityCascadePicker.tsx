import { useEffect, useMemo, useRef, useState } from "react"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { ArrowRight, GitBranch, Loader2 } from "lucide-react"
import { cn } from "@/lib/utils"
import { insertApi, ApiError } from "@/lib/api"
import type {
  CascadePreviewResponse,
  EntityCascadeChoice,
  EntityChoice,
} from "@/types/insert"

interface EntityConfig {
  /** Original entity name; display uses this. */
  entityName: string
  recordCount: number
  /** True for entities the user explicitly ticked; false for ones auto-pulled in via cascade. */
  pickedDirectly: boolean
}

interface Props {
  entities: EntityChoice[]
  benchmarkId: string
  /** Fires whenever the cascade resolves AND every selected entity has a valid count (≥1). The
   *  parent form keeps the latest value and disables submit when this fires with an empty array. */
  onCascadeReady: (entities: EntityCascadeChoice[]) => void
}

const DEFAULT_RECORDS = 100

/**
 * Cascade-aware entity picker.
 *
 * Flow:
 *   1. User clicks anywhere on an entity card to tick it. Default record count = 100.
 *   2. The picker debounces a {@code /cascade-preview} call to the backend with the picked
 *      entities. The response lists every required ancestor (e.g. picking {@code Order} also
 *      pulls in {@code User → Address}).
 *   3. Ancestors get added to the local selection automatically and their cards visibly light up
 *      in the grid. Each gets its own record-count input — editable independently.
 *   4. Any count below 1 marks the selection as invalid; the parent form disables the submit
 *      button until everything is ≥ 1.
 */
export function EntityCascadePicker({ entities, benchmarkId, onCascadeReady }: Props) {
  const entitiesByLower = useMemo(() => {
    const map = new Map<string, EntityChoice>()
    entities.forEach((e) => map.set(e.name.toLowerCase(), e))
    return map
  }, [entities])

  /** Selected entities keyed by lower-case name. Includes both user-picked and cascade-resolved. */
  const [selection, setSelection] = useState<Map<string, EntityConfig>>(new Map())
  /** Names the user clicked directly — these drive the cascade preview. */
  const [pickedNames, setPickedNames] = useState<Set<string>>(new Set())
  const [preview, setPreview] = useState<CascadePreviewResponse | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)

  const debounceRef = useRef<number | null>(null)
  const requestSeq = useRef(0)

  /* ============================ Selection actions ============================ */

  const togglePicked = (entityName: string) => {
    const key = entityName.toLowerCase()
    setPickedNames((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
    setSelection((prev) => {
      const next = new Map(prev)
      if (next.has(key) && pickedNames.has(key)) {
        // De-select — but only fully remove if it's not a cascade ancestor of another pick.
        // Simplest correct behaviour: rebuild from preview after we know the new picks.
        next.delete(key)
      } else if (!next.has(key)) {
        next.set(key, { entityName, recordCount: DEFAULT_RECORDS, pickedDirectly: true })
      } else {
        // Already in selection as ancestor — mark as directly picked too.
        const cfg = next.get(key)!
        next.set(key, { ...cfg, pickedDirectly: true })
      }
      return next
    })
  }

  const updateCount = (entityNameLower: string, count: number) => {
    setSelection((prev) => {
      const cur = prev.get(entityNameLower)
      if (!cur) return prev
      const next = new Map(prev)
      // Allow any value the user types so they can see the validation warning; clamp at submit.
      next.set(entityNameLower, { ...cur, recordCount: count })
      return next
    })
  }

  /* ===================== Debounced cascade preview lookup ===================== */

  useEffect(() => {
    if (pickedNames.size === 0) {
      setPreview(null)
      setPreviewError(null)
      // Drop any ancestor-only entries from selection.
      setSelection((prev) => {
        const next = new Map<string, EntityConfig>()
        for (const [k, v] of prev) if (v.pickedDirectly) next.set(k, v)
        return next
      })
      return
    }
    const pickedArray = Array.from(pickedNames)
    if (debounceRef.current) window.clearTimeout(debounceRef.current)
    debounceRef.current = window.setTimeout(() => {
      const seq = ++requestSeq.current
      setPreviewLoading(true)
      // Send the user's chosen counts as part of the preview so the backend's resolver can use
      // them when computing parent demand.
      const request: EntityCascadeChoice[] = pickedArray.map((key) => {
        const display = entitiesByLower.get(key)?.name ?? key
        const cur = selection.get(key)
        return {
          entityName: display,
          recordCount: Math.max(1, cur?.recordCount ?? DEFAULT_RECORDS),
        }
      })
      insertApi
        .cascadePreview(benchmarkId, { entities: request })
        .then((data) => {
          if (seq !== requestSeq.current) return
          setPreview(data)
          setPreviewError(null)
          // Merge cascade-resolved ancestors into selection (keep user-picked counts intact;
          // give newly-pulled ancestors the computed count as their starting value).
          setSelection((prev) => {
            const next = new Map<string, EntityConfig>()
            for (const e of data.entities) {
              const lower = e.name.toLowerCase()
              const existing = prev.get(lower)
              if (existing) {
                next.set(lower, existing)
              } else {
                next.set(lower, { entityName: e.name, recordCount: e.recordCount, pickedDirectly: false })
              }
            }
            // Carry forward any user-picked entry the preview didn't include (defensive).
            for (const [k, v] of prev) {
              if (!next.has(k) && v.pickedDirectly) next.set(k, v)
            }
            return next
          })
        })
        .catch((e) => {
          if (seq !== requestSeq.current) return
          const msg = e instanceof ApiError ? e.message : (e as Error).message
          setPreviewError(msg || "Failed to resolve cascade preview")
        })
        .finally(() => {
          if (seq === requestSeq.current) setPreviewLoading(false)
        })
    }, 220)
    return () => {
      if (debounceRef.current) window.clearTimeout(debounceRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pickedNames, benchmarkId])

  /* ============================ Validation + emit ============================ */

  useEffect(() => {
    if (selection.size === 0) {
      onCascadeReady([])
      return
    }
    const invalid = Array.from(selection.values()).some(
      (c) => !Number.isFinite(c.recordCount) || c.recordCount < 1 || c.recordCount > 1_000_000,
    )
    if (invalid) {
      onCascadeReady([])
      return
    }
    const out: EntityCascadeChoice[] = Array.from(selection.values()).map((cfg) => ({
      entityName: cfg.entityName,
      recordCount: Math.max(1, Math.floor(cfg.recordCount)),
    }))
    onCascadeReady(out)
    // onCascadeReady is intentionally omitted (parent passes a fresh ref each render).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selection])

  /* ================================ Render ================================ */

  const orderedNames = useMemo(() => {
    if (preview) return preview.entities.map((e) => e.name.toLowerCase())
    return Array.from(pickedNames)
  }, [preview, pickedNames])

  const MIN_RECORDS = 1
  const MAX_RECORDS = 1_000_000

  const invalidEntries = useMemo(() => {
    const out: { name: string; reason: string }[] = []
    for (const cfg of selection.values()) {
      if (!Number.isFinite(cfg.recordCount)) {
        out.push({ name: cfg.entityName, reason: "must be a number" })
      } else if (cfg.recordCount < MIN_RECORDS) {
        out.push({ name: cfg.entityName, reason: `must be ≥ ${MIN_RECORDS}` })
      } else if (cfg.recordCount > MAX_RECORDS) {
        out.push({ name: cfg.entityName, reason: `must be ≤ ${MAX_RECORDS.toLocaleString()}` })
      }
    }
    return out
  }, [selection])

  return (
    <div className="space-y-4">
      <div>
        <Label className="text-sm font-semibold">
          Pick entities to insert ({selection.size} selected)
        </Label>
        <p className="text-xs text-muted-foreground mt-0.5">
          Click an entity card to tick it. Dependent parents will be added automatically and shown lit up below.
        </p>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
        {entities.map((entity) => {
          const key = entity.name.toLowerCase()
          const config = selection.get(key)
          const directlyPicked = pickedNames.has(key)
          const auto = !!config && !directlyPicked
          return (
            <EntityCard
              key={entity.name}
              entity={entity}
              config={config}
              directlyPicked={directlyPicked}
              auto={auto}
              onToggle={() => togglePicked(entity.name)}
              onCountChange={(n) => updateCount(key, n)}
            />
          )
        })}
      </div>

      {(preview || previewLoading || previewError) && (
        <CascadeOrderPanel
          loading={previewLoading}
          error={previewError}
          orderedNames={orderedNames}
          selection={selection}
        />
      )}

      {invalidEntries.length > 0 && (
        <div className="rounded-lg border-2 border-destructive/50 bg-destructive/5 px-3 py-2.5 text-sm">
          <div className="font-semibold text-destructive mb-1">
            Fix {invalidEntries.length} entit{invalidEntries.length === 1 ? "y" : "ies"} before you can run:
          </div>
          <ul className="space-y-0.5 text-xs text-destructive/90">
            {invalidEntries.map((e) => (
              <li key={e.name}>
                <span className="font-mono font-semibold">{e.name}</span> — records {e.reason}
              </li>
            ))}
          </ul>
          <div className="mt-1.5 text-[11px] text-muted-foreground">
            Each entity must insert between {MIN_RECORDS} and {MAX_RECORDS.toLocaleString()} records.
          </div>
        </div>
      )}
    </div>
  )
}

/* ============================== Sub-components ============================== */

function EntityCard({
  entity,
  config,
  directlyPicked,
  auto,
  onToggle,
  onCountChange,
}: {
  entity: EntityChoice
  config: EntityConfig | undefined
  directlyPicked: boolean
  auto: boolean
  onToggle: () => void
  onCountChange: (n: number) => void
}) {
  const selected = directlyPicked || auto
  const invalidReason = !config
    ? null
    : !Number.isFinite(config.recordCount)
      ? "Type a number"
      : config.recordCount < 1
        ? "Must be ≥ 1"
        : config.recordCount > 1_000_000
          ? "Max is 100 000"
          : null
  const invalid = invalidReason !== null
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onToggle}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault()
          onToggle()
        }
      }}
      className={cn(
        "rounded-lg border p-3 cursor-pointer select-none transition-all outline-none",
        directlyPicked && "border-primary bg-primary/10 ring-2 ring-primary",
        auto && "border-pink-500/70 bg-pink-500/10 ring-1 ring-pink-500/40",
        !selected && "border-border hover:border-foreground/30 hover:bg-muted/30",
        invalid && "ring-2 ring-destructive",
        "focus:ring-2 focus:ring-primary",
      )}
    >
      <div className="flex items-center justify-between gap-2 mb-1">
        <span className="text-sm font-semibold truncate">{entity.name}</span>
        <div className="flex items-center gap-1.5 shrink-0">
          {auto && (
            <Badge variant="outline" className="text-[10px] border-pink-500/60 text-pink-600 dark:text-pink-400">
              auto
            </Badge>
          )}
          <span
            className={cn(
              "h-4 w-4 rounded-sm border-2",
              directlyPicked && "border-primary bg-primary",
              auto && "border-pink-500 bg-pink-500/70",
              !selected && "border-border",
            )}
          />
        </div>
      </div>
      <div className="text-[11px] text-muted-foreground">
        {entity.attributes.length} attribute(s)
      </div>
      {config && (
        <div className="mt-2 space-y-1" onClick={(e) => e.stopPropagation()}>
          <div className="flex items-center gap-2">
            <Input
              type="number"
              min={1}
              max={1_000_000}
              value={config.recordCount}
              onChange={(e) => onCountChange(Number(e.target.value))}
              className={cn(
                "h-9 text-sm font-mono font-semibold text-right",
                invalid && "border-destructive ring-1 ring-destructive",
              )}
            />
            <span className="text-[11px] text-muted-foreground whitespace-nowrap">records</span>
          </div>
          {invalidReason && (
            <p className="text-[11px] text-destructive font-medium">{invalidReason}</p>
          )}
        </div>
      )}
    </div>
  )
}

function CascadeOrderPanel({
  loading,
  error,
  orderedNames,
  selection,
}: {
  loading: boolean
  error: string | null
  orderedNames: string[]
  selection: Map<string, EntityConfig>
}) {
  return (
    <div className="rounded-lg border border-border bg-muted/20 p-3">
      <div className="flex items-center gap-2 text-xs font-medium text-muted-foreground mb-2">
        <GitBranch className="h-3.5 w-3.5" />
        Insert order (parents first)
        {loading && <Loader2 className="h-3 w-3 animate-spin" />}
      </div>
      {error && <p className="text-sm text-destructive mb-2">{error}</p>}
      <div className="flex flex-wrap items-center gap-1.5">
        {orderedNames.map((lower, i) => {
          const cfg = selection.get(lower)
          if (!cfg) return null
          return (
            <div key={lower} className="flex items-center gap-1.5">
              <span className="rounded-md border border-border bg-background px-2 py-0.5 text-xs font-mono">
                {cfg.entityName} <span className="text-muted-foreground">×{cfg.recordCount.toLocaleString()}</span>
              </span>
              {i < orderedNames.length - 1 && <ArrowRight className="h-3 w-3 text-muted-foreground" />}
            </div>
          )
        })}
      </div>
    </div>
  )
}
