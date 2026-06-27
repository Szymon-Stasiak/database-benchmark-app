import { useCallback, useEffect, useMemo, useState } from "react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Loader2, Play, Shuffle } from "lucide-react"
import { cn } from "@/lib/utils"
import { scenarioApi } from "@/lib/api"
import type { DatabaseResponse } from "@/types/benchmark"
import type { EntityChoice } from "@/types/insert"
import type { RegistrySummaryEntry } from "@/types/preview"
import type {
  ScenarioApplicabilityMap,
  ScenarioParams,
  ScenarioType,
  SchemaRelationship,
  StartScenarioRunRequest,
} from "@/types/scenario"

interface Props {
  benchmarkId: string
  entities: EntityChoice[]
  databases: DatabaseResponse[]
  loading: boolean
  applicability: ScenarioApplicabilityMap | null
  relationships: SchemaRelationship[]
  registry?: RegistrySummaryEntry[]
  onSubmit: (request: StartScenarioRunRequest) => Promise<void>
}

interface ScenarioMeta {
  value: ScenarioType
  title: string
  desc: string
  hint: string
}

const SCENARIOS: ScenarioMeta[] = [
  {
    value: "AGGREGATE_GROUP_COUNT",
    title: "Aggregate",
    desc: "COUNT(child) GROUP BY parent FK",
    hint: "Pick a parent→child relationship. The query counts how many children each parent has. Result: a map {parent_id: count}.",
  },
  {
    value: "RANGE_FILTER",
    title: "Range filter",
    desc: "WHERE attribute BETWEEN min AND max",
    hint: "Pick an entity and a numeric attribute, then a range. The query counts rows that fall in the range. Result: a single number.",
  },
  {
    value: "GRAPH_TRAVERSAL",
    title: "Graph traversal",
    desc: "K-hop reachability from start node",
    hint: "Pick a parent entity (must have children in the schema) + random start ID + depth 1–5. Returns the list of all reachable IDs.",
  },
  {
    value: "VECTOR_KNN",
    title: "Vector KNN",
    desc: "top-K nearest neighbors of query vector",
    hint: "Pick an entity with a VECTOR attribute. The form generates a random vector — you can also paste your own. Returns the top-K nearest IDs + scores.",
  },
]

export function ScenarioRunForm({ benchmarkId, entities, databases, loading, applicability, relationships, registry, onSubmit }: Props) {
  const runnableDatabases = useMemo(
    () => databases.filter((d) => d.status === "RUNNING"),
    [databases],
  )

  const [scenarioType, setScenarioType] = useState<ScenarioType>("AGGREGATE_GROUP_COUNT")
  const [iterations, setIterations] = useState<number>(10)
  const [selectedDbIds, setSelectedDbIds] = useState<Set<string>>(new Set())
  const [submitError, setSubmitError] = useState<string | null>(null)

  const [aggRelationshipIdx, setAggRelationshipIdx] = useState<number>(0)
  const [rangeEntity, setRangeEntity] = useState<string>("")
  const [rangeAttribute, setRangeAttribute] = useState<string>("")
  const [rangeMin, setRangeMin] = useState<number>(0)
  const [rangeMax, setRangeMax] = useState<number>(1000)
  const [travStart, setTravStart] = useState<string>("")
  const [travId, setTravId] = useState<string>("")
  const [travDepth, setTravDepth] = useState<number>(3)
  const [knnEntity, setKnnEntity] = useState<string>("")
  const [knnAttr, setKnnAttr] = useState<string>("")
  const [knnVector, setKnnVector] = useState<string>("[]")
  const [knnTopK, setKnnTopK] = useState<number>(10)

  const parentEntities = useMemo(() => {
    const parents = new Set(relationships.map((r) => r.parentEntity.toLowerCase()))
    return entities.filter((e) => parents.has(e.name.toLowerCase()))
  }, [entities, relationships])

  useEffect(() => {
    if (entities.length > 0) {
      if (!rangeEntity) setRangeEntity(entities[0].name)
      if (!knnEntity) setKnnEntity(entities[0].name)
    }
    if (parentEntities.length > 0 && !travStart) {
      setTravStart(parentEntities[0].name)
    }
  }, [entities, parentEntities, rangeEntity, travStart, knnEntity])

  useEffect(() => {
    const applicableIds = new Set(applicability?.[scenarioType] ?? [])
    setSelectedDbIds(() => {
      const next = new Set<string>()
      for (const db of runnableDatabases) {
        if (applicableIds.has(db.id)) next.add(db.id)
      }
      return next
    })
  }, [runnableDatabases, applicability, scenarioType])

  const pickRandomTraversalId = useCallback(async () => {
    if (!travStart) return
    try {
      const res = await scenarioApi.sampleEntityId(benchmarkId, travStart, true)
      if (res.logicalId) setTravId(res.logicalId)
    } catch {
      // ignore — user can still paste manually
    }
  }, [benchmarkId, travStart])

  useEffect(() => {
    if (scenarioType !== "GRAPH_TRAVERSAL" || !travStart) return
    if (travId) return
    void pickRandomTraversalId()
  }, [scenarioType, travStart, travId, pickRandomTraversalId])

  const generateRandomVector = useCallback((dim: number) => {
    const arr = Array.from({ length: dim }, () => Math.round((Math.random() * 2 - 1) * 1000) / 1000)
    setKnnVector(JSON.stringify(arr))
  }, [])

  useEffect(() => {
    if (scenarioType !== "VECTOR_KNN") return
    if (knnVector && knnVector !== "[]") return
    generateRandomVector(384)
  }, [scenarioType, knnVector, generateRandomVector])

  const numericAttributes = useMemo(() => {
    const entity = entities.find((e) => e.name === rangeEntity)
    if (!entity) return [] as string[]
    return entity.attributes
      .filter((a) => /int|float|double|decimal|bigint|date|timestamp/i.test(a.dataType))
      .map((a) => a.name)
  }, [entities, rangeEntity])

  useEffect(() => {
    if (numericAttributes.length > 0 && !numericAttributes.includes(rangeAttribute)) {
      setRangeAttribute(numericAttributes[0])
    }
  }, [numericAttributes, rangeAttribute])

  const vectorAttributes = useMemo(() => {
    const entity = entities.find((e) => e.name === knnEntity)
    if (!entity) return [] as string[]
    return entity.attributes.filter((a) => /vector/i.test(a.dataType)).map((a) => a.name)
  }, [entities, knnEntity])

  useEffect(() => {
    if (vectorAttributes.length > 0 && !vectorAttributes.includes(knnAttr)) {
      setKnnAttr(vectorAttributes[0])
    }
  }, [vectorAttributes, knnAttr])

  const buildParams = (): ScenarioParams | null => {
    if (scenarioType === "AGGREGATE_GROUP_COUNT") {
      const rel = relationships[aggRelationshipIdx]
      if (!rel) return null
      return {
        type: "AGGREGATE_GROUP_COUNT",
        childEntity: rel.childEntity,
        parentEntity: rel.parentEntity,
      }
    }
    if (scenarioType === "RANGE_FILTER") {
      if (!rangeEntity || !rangeAttribute) return null
      return {
        type: "RANGE_FILTER",
        entityName: rangeEntity,
        attribute: rangeAttribute,
        min: rangeMin,
        max: rangeMax,
      }
    }
    if (scenarioType === "GRAPH_TRAVERSAL") {
      if (!travStart || !travId) return null
      return {
        type: "GRAPH_TRAVERSAL",
        startEntity: travStart,
        startLogicalId: travId,
        depth: travDepth,
      }
    }
    if (scenarioType === "VECTOR_KNN") {
      let vec: number[]
      try {
        vec = JSON.parse(knnVector)
        if (!Array.isArray(vec) || vec.length === 0) return null
      } catch {
        return null
      }
      return {
        type: "VECTOR_KNN",
        entityName: knnEntity,
        vectorAttribute: knnAttr,
        queryVector: vec,
        topK: knnTopK,
      }
    }
    return null
  }

  const validate = (): string | null => {
    if (!buildParams()) return "Fill all required scenario parameters."
    if (scenarioType === "RANGE_FILTER" && rangeMin > rangeMax) return "Range min must be <= max."
    if (scenarioType === "GRAPH_TRAVERSAL" && (travDepth < 1 || travDepth > 5))
      return "Traversal depth must be between 1 and 5."
    if (scenarioType === "VECTOR_KNN") {
      try {
        const arr = JSON.parse(knnVector)
        if (!Array.isArray(arr) || arr.length === 0) return "queryVector must be a non-empty JSON array."
      } catch {
        return "queryVector must be valid JSON array."
      }
    }
    if (iterations < 1 || iterations > 50) return "Iterations must be between 1 and 50."
    if (selectedDbIds.size === 0) return "Select at least one applicable database."
    return null
  }

  const submit = async () => {
    const err = validate()
    if (err) {
      setSubmitError(err)
      return
    }
    setSubmitError(null)
    const params = buildParams()!
    await onSubmit({ params, iterations, databaseIds: Array.from(selectedDbIds) })
  }

  const toggleDb = (id: string) =>
    setSelectedDbIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const applicableIds = new Set(applicability?.[scenarioType] ?? [])

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">Configure scenario run</CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="space-y-1.5">
          <Label>Scenario</Label>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
            {SCENARIOS.map((opt) => (
              <button
                key={opt.value}
                type="button"
                onClick={() => setScenarioType(opt.value)}
                className={cn(
                  "rounded-md border px-3 py-2 text-sm text-left transition-all",
                  scenarioType === opt.value
                    ? "border-primary bg-primary/5 ring-1 ring-primary"
                    : "border-border hover:border-foreground/30",
                )}
              >
                <div className="font-medium">{opt.title}</div>
                <div className="text-[11px] text-muted-foreground leading-tight">{opt.desc}</div>
              </button>
            ))}
          </div>
        </div>

        {SCENARIOS.filter((s) => s.value === scenarioType).map((s) => (
          <div key={s.value} className="rounded-md border border-blue-200 dark:border-blue-900/40 bg-blue-50 dark:bg-blue-950/20 px-3 py-2 text-xs">
            <span className="font-semibold">How to use: </span>
            {s.hint}
          </div>
        ))}

        {scenarioType === "AGGREGATE_GROUP_COUNT" && (
          <div className="space-y-1.5">
            <Label>Relationship (parent → child)</Label>
            {relationships.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No relationships defined in schema. AGGREGATE requires parent/child FK link.
              </p>
            ) : (
              <>
                <select
                  value={aggRelationshipIdx}
                  onChange={(e) => setAggRelationshipIdx(Number(e.target.value))}
                  className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
                >
                  {relationships.map((r, i) => (
                    <option key={i} value={i}>
                      {r.parentEntity} → {r.childEntity}
                      {r.cardinality ? ` (${r.cardinality})` : ""}
                    </option>
                  ))}
                </select>
                {relationships[aggRelationshipIdx] && (
                  <p className="text-[11px] text-muted-foreground">
                    COUNT({relationships[aggRelationshipIdx].childEntity}) GROUP BY {relationships[aggRelationshipIdx].fkColumnInChild}
                  </p>
                )}
              </>
            )}
          </div>
        )}

        {scenarioType === "RANGE_FILTER" && (
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <div className="space-y-1.5">
              <Label>Entity</Label>
              <EntitySelect value={rangeEntity} onChange={setRangeEntity} entities={entities} />
            </div>
            <div className="space-y-1.5">
              <Label>Numeric attribute</Label>
              <select
                value={rangeAttribute}
                onChange={(e) => setRangeAttribute(e.target.value)}
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
              >
                {numericAttributes.map((a) => (
                  <option key={a} value={a}>{a}</option>
                ))}
                {numericAttributes.length === 0 && <option>(no numeric attrs)</option>}
              </select>
            </div>
            <div className="space-y-1.5">
              <Label>Min</Label>
              <Input type="number" value={rangeMin} onChange={(e) => setRangeMin(Number(e.target.value))} />
            </div>
            <div className="space-y-1.5">
              <Label>Max</Label>
              <Input type="number" value={rangeMax} onChange={(e) => setRangeMax(Number(e.target.value))} />
            </div>
          </div>
        )}

        {scenarioType === "GRAPH_TRAVERSAL" && (
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="space-y-1.5">
              <Label>Start entity (must be a parent in schema)</Label>
              {parentEntities.length === 0 ? (
                <p className="text-sm text-destructive">
                  Schema has no relationships — TRAVERSAL not applicable.
                </p>
              ) : (
                <EntitySelect value={travStart} onChange={setTravStart} entities={parentEntities} />
              )}
              <p className="text-[11px] text-muted-foreground">
                Pool: {registry?.find((r) => r.entityName === travStart)?.availableIds?.toLocaleString() ?? "—"} IDs
              </p>
            </div>
            <div className="space-y-1.5">
              <Label>Start logical ID</Label>
              <div className="flex gap-2">
                <Input value={travId} onChange={(e) => setTravId(e.target.value)} placeholder="UUID" />
                <Button type="button" variant="outline" size="sm" onClick={pickRandomTraversalId} title="Pick random ID from registry">
                  <Shuffle className="h-3.5 w-3.5" />
                </Button>
              </div>
              <p className="text-[11px] text-muted-foreground">
                Auto-picked from rows that have descendants (guaranteed &gt; 0 results). Click shuffle for another.
              </p>
            </div>
            <div className="space-y-1.5">
              <Label>Depth (1–5)</Label>
              <Input
                type="number"
                min={1}
                max={5}
                value={travDepth}
                onChange={(e) => setTravDepth(Math.max(1, Math.min(5, Number(e.target.value))))}
              />
            </div>
          </div>
        )}

        {scenarioType === "VECTOR_KNN" && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="space-y-1.5">
              <Label>Entity with VECTOR</Label>
              <EntitySelect value={knnEntity} onChange={setKnnEntity} entities={entities} />
            </div>
            <div className="space-y-1.5">
              <Label>Vector attribute</Label>
              <select
                value={knnAttr}
                onChange={(e) => setKnnAttr(e.target.value)}
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
              >
                {vectorAttributes.map((a) => (
                  <option key={a} value={a}>{a}</option>
                ))}
                {vectorAttributes.length === 0 && <option>(no VECTOR attrs)</option>}
              </select>
            </div>
            <div className="space-y-1.5 md:col-span-2">
              <Label>Query vector (JSON array)</Label>
              <div className="flex gap-2">
                <Input
                  value={knnVector}
                  onChange={(e) => setKnnVector(e.target.value)}
                  placeholder="[0.12, 0.34, ...]"
                />
                <Button type="button" variant="outline" size="sm" onClick={() => generateRandomVector(parseDim(knnVector) || 384)} title="Generate random vector">
                  <Shuffle className="h-3.5 w-3.5" />
                </Button>
              </div>
              <p className="text-[11px] text-muted-foreground">
                Auto-generated (dim {parseDim(knnVector) || 384}); click shuffle to regenerate.
              </p>
            </div>
            <div className="space-y-1.5">
              <Label>Top K</Label>
              <Input
                type="number"
                min={1}
                max={1000}
                value={knnTopK}
                onChange={(e) => setKnnTopK(Math.max(1, Number(e.target.value)))}
              />
            </div>
          </div>
        )}

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="space-y-1.5">
            <Label>Iterations</Label>
            <Input
              type="number"
              min={1}
              max={50}
              value={iterations}
              onChange={(e) => setIterations(Math.max(1, Math.min(50, Number(e.target.value))))}
            />
            <p className="text-[11px] text-muted-foreground">
              Re-run scenario N times for stable latency percentiles.
            </p>
          </div>
        </div>

        <div className="space-y-2">
          <Label>Run against ({selectedDbIds.size} selected)</Label>
          {runnableDatabases.length === 0 ? (
            <p className="text-sm text-muted-foreground">No databases are RUNNING right now.</p>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
              {runnableDatabases.map((db) => {
                const supported = applicableIds.has(db.id)
                const active = selectedDbIds.has(db.id)
                return (
                  <button
                    key={db.id}
                    type="button"
                    onClick={() => supported && toggleDb(db.id)}
                    disabled={!supported}
                    title={supported ? "" : `${db.dbName} does not support ${scenarioType}`}
                    className={cn(
                      "flex items-center justify-between rounded-lg border px-3 py-2 text-left transition-all",
                      !supported && "opacity-40 cursor-not-allowed",
                      supported && active
                        ? "border-primary bg-primary/5 ring-1 ring-primary"
                        : "border-border",
                      supported && !active && "hover:border-foreground/30",
                    )}
                  >
                    <div>
                      <div className="text-sm font-medium capitalize">{db.dbName}</div>
                      <div className="text-xs text-muted-foreground">
                        v{db.dbVersion}{!supported && " · N/A"}
                      </div>
                    </div>
                    <span
                      className={cn(
                        "h-4 w-4 rounded-sm border",
                        supported && active ? "border-primary bg-primary" : "border-border",
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
          <Button type="button" onClick={submit} disabled={loading} className="bg-primary text-primary-foreground">
            {loading ? <Loader2 className="h-4 w-4 mr-2 animate-spin" /> : <Play className="h-4 w-4 mr-2" />}
            Review scenario plan
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

function parseDim(json: string): number {
  try {
    const arr = JSON.parse(json)
    return Array.isArray(arr) ? arr.length : 0
  } catch {
    return 0
  }
}

function EntitySelect({
  value,
  onChange,
  entities,
}: {
  value: string
  onChange: (v: string) => void
  entities: EntityChoice[]
}) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
    >
      {entities.map((e) => (
        <option key={e.name} value={e.name}>{e.name}</option>
      ))}
    </select>
  )
}
