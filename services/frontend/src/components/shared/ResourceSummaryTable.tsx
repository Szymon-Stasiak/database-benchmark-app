import { useRef } from "react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { BarChart3 } from "lucide-react"
import { DownloadTableButton } from "@/components/benchmark/DownloadTableButton"
import type { ResourceMetricsFields } from "@/types/resource"

interface ResultLike extends ResourceMetricsFields {
  databaseId: string
  dbName: string
}

interface Props {
  results: ResultLike[]
}

export function ResourceSummaryTable({ results }: Props) {
  const tableRef = useRef<HTMLDivElement | null>(null)
  const rows = results.filter((r) => (r.resourceSampleCount ?? 0) > 0)
  if (rows.length === 0) {
    return null
  }
  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between gap-3">
          <CardTitle className="text-base inline-flex items-center gap-2">
            <BarChart3 className="h-4 w-4" />
            Resource usage summary
          </CardTitle>
          <DownloadTableButton containerRef={tableRef} tableName="resource-summary" />
        </div>
      </CardHeader>
      <CardContent>
        <div ref={tableRef} className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left border-b border-border">
                <th className="py-2 pr-3 font-medium text-muted-foreground">Database</th>
                <th className="py-2 px-3 font-medium text-muted-foreground text-right">CPU max</th>
                <th className="py-2 px-3 font-medium text-muted-foreground text-right">CPU mean</th>
                <th className="py-2 px-3 font-medium text-muted-foreground text-right">CPU p95</th>
                <th className="py-2 px-3 font-medium text-muted-foreground text-right">RAM max</th>
                <th className="py-2 px-3 font-medium text-muted-foreground text-right">RAM mean</th>
                <th className="py-2 px-3 font-medium text-muted-foreground text-right">RAM p95</th>
                <th className="py-2 pl-3 font-medium text-muted-foreground text-right">Samples</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.databaseId} className="border-b border-border/50 last:border-b-0">
                  <td className="py-2 pr-3 capitalize font-medium">{r.dbName}</td>
                  <td className="py-2 px-3 font-mono text-right">{fmtPercent(r.cpuPercentMax)}</td>
                  <td className="py-2 px-3 font-mono text-right">{fmtPercent(r.cpuPercentMean)}</td>
                  <td className="py-2 px-3 font-mono text-right">{fmtPercent(r.cpuPercentP95)}</td>
                  <td className="py-2 px-3 font-mono text-right">{fmtBytes(r.memoryBytesMax)}</td>
                  <td className="py-2 px-3 font-mono text-right">{fmtBytes(r.memoryBytesMean)}</td>
                  <td className="py-2 px-3 font-mono text-right">{fmtBytes(r.memoryBytesP95)}</td>
                  <td className="py-2 pl-3 font-mono text-right text-muted-foreground">
                    {r.resourceSampleCount ?? 0}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </CardContent>
    </Card>
  )
}

function fmtPercent(value: number | null | undefined): string {
  if (value == null) return "—"
  return `${value.toFixed(1)} %`
}

function fmtBytes(value: number | null | undefined): string {
  if (value == null) return "—"
  if (value < 1024) return `${value} B`
  let v = value
  const units = ["KB", "MB", "GB", "TB"]
  let i = -1
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(v >= 10 ? 0 : 1)} ${units[i]}`
}
