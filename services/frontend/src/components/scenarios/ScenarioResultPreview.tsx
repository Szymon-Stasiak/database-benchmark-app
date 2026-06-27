import { useState } from "react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { ChevronDown, ChevronRight } from "lucide-react"
import { cn } from "@/lib/utils"
import type { ScenarioResultResponse, ScenarioType } from "@/types/scenario"

interface Props {
  scenarioType: ScenarioType
  results: ScenarioResultResponse[]
}

export function ScenarioResultPreview({ scenarioType, results }: Props) {
  const successful = results.filter((r) => r.status === "SUCCESS")
  if (successful.length === 0) return null
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">Result preview per database</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {successful.map((r) => (
          <PerDbPreview key={r.id} result={r} scenarioType={scenarioType} />
        ))}
      </CardContent>
    </Card>
  )
}

function PerDbPreview({ result, scenarioType }: { result: ScenarioResultResponse; scenarioType: ScenarioType }) {
  const [expanded, setExpanded] = useState(false)
  return (
    <div className="rounded-lg border border-border p-3">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="flex w-full items-center justify-between gap-3 text-left"
      >
        <div className="flex items-center gap-2">
          {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          <span className="font-medium capitalize">{result.dbName}</span>
          <Badge variant="outline" className="text-[10px]">
            {(result.scenarioRowsReturned ?? 0).toLocaleString()} rows
          </Badge>
        </div>
        <span className="text-[10px] font-mono text-muted-foreground">
          hash {result.scenarioResultHash?.slice(0, 12) ?? "—"}
        </span>
      </button>
      {expanded && (
        <div className="mt-2 text-xs">
          <Renderer scenarioType={scenarioType} preview={result.scenarioResultPreview} />
        </div>
      )}
    </div>
  )
}

function Renderer({ scenarioType, preview }: { scenarioType: ScenarioType; preview: unknown }) {
  if (preview == null) return <p className="text-muted-foreground">No preview.</p>
  if (scenarioType === "AGGREGATE_GROUP_COUNT") return <KeyValueTable data={preview} />
  if (scenarioType === "RANGE_FILTER") return <SingleStat data={preview} label="count" />
  if (scenarioType === "GRAPH_TRAVERSAL") return <IdList data={preview} />
  if (scenarioType === "VECTOR_KNN") return <KnnList data={preview} />
  return <pre className="text-[10px] font-mono">{JSON.stringify(preview, null, 2)}</pre>
}

function KeyValueTable({ data }: { data: unknown }) {
  if (typeof data !== "object" || data === null) return <pre>{String(data)}</pre>
  const entries = Object.entries(data as Record<string, unknown>).slice(0, 50)
  return (
    <div className="overflow-x-auto max-h-60 overflow-y-auto">
      <table className="w-full text-xs">
        <thead className="text-muted-foreground">
          <tr><th className="text-left py-1">Group</th><th className="text-right py-1">Count</th></tr>
        </thead>
        <tbody>
          {entries.map(([k, v]) => (
            <tr key={k} className="border-b last:border-0 border-border/40">
              <td className="py-1 font-mono">{k}</td>
              <td className="py-1 text-right font-mono">{String(v)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function SingleStat({ data, label }: { data: unknown; label: string }) {
  if (typeof data === "object" && data !== null && "count" in data) {
    return (
      <div className={cn("text-2xl font-bold font-mono")}>
        {String((data as { count: unknown }).count)}{" "}
        <span className="text-xs text-muted-foreground font-normal">{label}</span>
      </div>
    )
  }
  return <pre className="text-[10px] font-mono">{JSON.stringify(data)}</pre>
}

function IdList({ data }: { data: unknown }) {
  if (!Array.isArray(data)) return <pre>{JSON.stringify(data)}</pre>
  const list = data.slice(0, 50)
  return (
    <div className="space-y-1 max-h-60 overflow-y-auto font-mono text-[11px]">
      <div className="text-muted-foreground mb-1">{data.length} reachable IDs (first 50 shown)</div>
      {list.map((id, i) => <div key={i}>{String(id)}</div>)}
    </div>
  )
}

function KnnList({ data }: { data: unknown }) {
  if (!Array.isArray(data)) return <pre>{JSON.stringify(data)}</pre>
  const list = data.slice(0, 50) as Array<{ id: string; score: number }>
  return (
    <div className="overflow-x-auto max-h-60 overflow-y-auto">
      <table className="w-full text-xs">
        <thead className="text-muted-foreground">
          <tr><th className="text-left py-1">ID</th><th className="text-right py-1">Score</th></tr>
        </thead>
        <tbody>
          {list.map((h, i) => (
            <tr key={i} className="border-b last:border-0 border-border/40">
              <td className="py-1 font-mono">{h.id}</td>
              <td className="py-1 text-right font-mono">{h.score?.toFixed(4)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
