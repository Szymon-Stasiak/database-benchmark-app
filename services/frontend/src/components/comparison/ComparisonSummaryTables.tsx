import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Database, FlaskConical, Search, Trash2 } from "lucide-react"
import type {
  DeleteSummary,
  InsertSummary,
  ReadSummary,
} from "@/types/comparison"

export function InsertSummaryTable({ rows }: { rows: InsertSummary[] }) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base inline-flex items-center gap-2">
          <FlaskConical className="h-4 w-4 text-primary" />
          Insert summary
        </CardTitle>
      </CardHeader>
      <CardContent>
        <TableShell empty={rows.length === 0 || rows.every((r) => r.totalRuns === 0)}>
          <thead className="text-xs text-muted-foreground border-b">
            <tr>
              <th className="text-left py-2 px-2">Database</th>
              <th className="text-right py-2 px-2">Runs</th>
              <th className="text-right py-2 px-2">Rows inserted</th>
              <th className="text-right py-2 px-2">Throughput (rps)</th>
              <th className="text-right py-2 px-2">Avg DB (ms)</th>
              <th className="text-right py-2 px-2">Avg wire (ms)</th>
              <th className="text-right py-2 px-2">Avg overhead (ms)</th>
              <th className="text-right py-2 px-2">Conflicts</th>
              <th className="text-right py-2 px-2">✓ / ✗</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.databaseId} className="border-b last:border-0">
                <td className="py-1.5 px-2 capitalize font-medium inline-flex items-center gap-1.5">
                  <Database className="h-3.5 w-3.5 text-muted-foreground" />
                  {r.dbName}
                </td>
                <td className="py-1.5 px-2 text-right font-mono">{r.totalRuns}</td>
                <td className="py-1.5 px-2 text-right font-mono">{r.totalRowsInserted.toLocaleString()}</td>
                <td className="py-1.5 px-2 text-right font-mono">{fmt(r.avgThroughputRps, 0)}</td>
                <td className="py-1.5 px-2 text-right font-mono">{fmt(r.avgDbTimeMs, 2)}</td>
                <td className="py-1.5 px-2 text-right font-mono text-muted-foreground">{fmt(r.avgWireTimeMs, 2)}</td>
                <td className="py-1.5 px-2 text-right font-mono text-muted-foreground">{fmt(r.avgOverheadMs, 2)}</td>
                <td className="py-1.5 px-2 text-right font-mono">{r.totalConflicts}</td>
                <td className="py-1.5 px-2 text-right font-mono text-xs">
                  <span className="text-status-success-text">{r.successCount}</span>
                  {" / "}
                  <span className="text-destructive">{r.failedCount}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </TableShell>
      </CardContent>
    </Card>
  )
}

export function ReadSummaryTable({ rows }: { rows: ReadSummary[] }) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base inline-flex items-center gap-2">
          <Search className="h-4 w-4 text-primary" />
          Read summary (server-side latency per record)
        </CardTitle>
      </CardHeader>
      <CardContent>
        <TableShell empty={rows.length === 0 || rows.every((r) => r.totalRuns === 0)}>
          <thead className="text-xs text-muted-foreground border-b">
            <tr>
              <th className="text-left py-2 px-2">Database</th>
              <th className="text-right py-2 px-2">Runs</th>
              <th className="text-right py-2 px-2">Samples</th>
              <th className="text-right py-2 px-2">p50 (ms)</th>
              <th className="text-right py-2 px-2">p95 (ms)</th>
              <th className="text-right py-2 px-2">p99 (ms)</th>
              <th className="text-right py-2 px-2">Mean (ms)</th>
              <th className="text-right py-2 px-2">Wire (ms)</th>
              <th className="text-right py-2 px-2">✓ / ✗</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.databaseId} className="border-b last:border-0">
                <td className="py-1.5 px-2 capitalize font-medium inline-flex items-center gap-1.5">
                  <Database className="h-3.5 w-3.5 text-muted-foreground" />
                  {r.dbName}
                </td>
                <td className="py-1.5 px-2 text-right font-mono">{r.totalRuns}</td>
                <td className="py-1.5 px-2 text-right font-mono">{r.totalSamples.toLocaleString()}</td>
                <td className="py-1.5 px-2 text-right font-mono">{fmtUsAsMs(r.avgP50DbTimeUs)}</td>
                <td className="py-1.5 px-2 text-right font-mono">{fmtUsAsMs(r.avgP95DbTimeUs)}</td>
                <td className="py-1.5 px-2 text-right font-mono">{fmtUsAsMs(r.avgP99DbTimeUs)}</td>
                <td className="py-1.5 px-2 text-right font-mono">{fmtUsAsMs(r.avgMeanDbTimeUs)}</td>
                <td className="py-1.5 px-2 text-right font-mono text-muted-foreground">{fmt(r.avgWireTimeMs, 1)}</td>
                <td className="py-1.5 px-2 text-right font-mono text-xs">
                  <span className="text-status-success-text">{r.successCount}</span>
                  {" / "}
                  <span className="text-destructive">{r.failedCount}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </TableShell>
      </CardContent>
    </Card>
  )
}

export function DeleteSummaryTable({ rows }: { rows: DeleteSummary[] }) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base inline-flex items-center gap-2">
          <Trash2 className="h-4 w-4 text-destructive" />
          Delete summary
        </CardTitle>
      </CardHeader>
      <CardContent>
        <TableShell empty={rows.length === 0 || rows.every((r) => r.totalRuns === 0)}>
          <thead className="text-xs text-muted-foreground border-b">
            <tr>
              <th className="text-left py-2 px-2">Database</th>
              <th className="text-right py-2 px-2">Runs</th>
              <th className="text-right py-2 px-2">Rows deleted</th>
              <th className="text-right py-2 px-2">p50 (ms)</th>
              <th className="text-right py-2 px-2">p95 (ms)</th>
              <th className="text-right py-2 px-2">p99 (ms)</th>
              <th className="text-right py-2 px-2">Size freed</th>
              <th className="text-right py-2 px-2">✓ / ✗</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.databaseId} className="border-b last:border-0">
                <td className="py-1.5 px-2 capitalize font-medium inline-flex items-center gap-1.5">
                  <Database className="h-3.5 w-3.5 text-muted-foreground" />
                  {r.dbName}
                </td>
                <td className="py-1.5 px-2 text-right font-mono">{r.totalRuns}</td>
                <td className="py-1.5 px-2 text-right font-mono">{r.totalRowsDeleted.toLocaleString()}</td>
                <td className="py-1.5 px-2 text-right font-mono">{fmtUsAsMs(r.avgP50DbTimeUs)}</td>
                <td className="py-1.5 px-2 text-right font-mono">{fmtUsAsMs(r.avgP95DbTimeUs)}</td>
                <td className="py-1.5 px-2 text-right font-mono">{fmtUsAsMs(r.avgP99DbTimeUs)}</td>
                <td className="py-1.5 px-2 text-right font-mono">{fmtBytes(r.totalSizeFreedBytes)}</td>
                <td className="py-1.5 px-2 text-right font-mono text-xs">
                  <span className="text-status-success-text">{r.successCount}</span>
                  {" / "}
                  <span className="text-destructive">{r.failedCount}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </TableShell>
      </CardContent>
    </Card>
  )
}

function TableShell({ children, empty }: { children: React.ReactNode; empty: boolean }) {
  if (empty) return <p className="text-sm text-muted-foreground">No runs recorded yet.</p>
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">{children}</table>
    </div>
  )
}

function fmt(value: number | null, digits: number): string {
  if (value == null) return "—"
  return value.toFixed(digits)
}

function fmtUsAsMs(us: number | null): string {
  if (us == null) return "—"
  return (us / 1000).toFixed(3)
}

function fmtBytes(bytes: number | null): string {
  if (bytes == null || bytes === 0) return "—"
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(2)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(2)} KB`
  return `${bytes} B`
}
