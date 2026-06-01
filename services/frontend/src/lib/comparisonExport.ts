import type { ComparisonReportResponse } from "@/types/comparison"

export function downloadJson(report: ComparisonReportResponse) {
  const blob = new Blob([JSON.stringify(report, null, 2)], { type: "application/json" })
  trigger(blob, `comparison-report-${report.benchmarkId}.json`)
}

export function downloadCsv(report: ComparisonReportResponse) {
  const lines: string[] = []
  lines.push("# Comparison report")
  lines.push(`# benchmark_id,${report.benchmarkId}`)
  lines.push(`# topic,${escape(report.topic)}`)
  lines.push(`# generated_at,${report.generatedAt}`)
  lines.push("")

  lines.push("section,database,db_version,engine,metric,value")

  for (const db of report.databases) {
    const tag = `${db.dbName},${db.dbVersion},${db.engineCategory}`
    const ins = report.insertSummary.find((s) => s.databaseId === db.databaseId)
    if (ins) {
      lines.push(`INSERT,${tag},total_runs,${ins.totalRuns}`)
      lines.push(`INSERT,${tag},rows_inserted,${ins.totalRowsInserted}`)
      lines.push(`INSERT,${tag},avg_throughput_rps,${num(ins.avgThroughputRps)}`)
      lines.push(`INSERT,${tag},avg_db_time_ms,${num(ins.avgDbTimeMs)}`)
      lines.push(`INSERT,${tag},avg_wire_time_ms,${num(ins.avgWireTimeMs)}`)
      lines.push(`INSERT,${tag},avg_overhead_ms,${num(ins.avgOverheadMs)}`)
      lines.push(`INSERT,${tag},conflicts_total,${ins.totalConflicts}`)
      lines.push(`INSERT,${tag},success_count,${ins.successCount}`)
      lines.push(`INSERT,${tag},failed_count,${ins.failedCount}`)
    }
    const rd = report.readSummary.find((s) => s.databaseId === db.databaseId)
    if (rd) {
      lines.push(`READ,${tag},total_runs,${rd.totalRuns}`)
      lines.push(`READ,${tag},total_samples,${rd.totalSamples}`)
      lines.push(`READ,${tag},avg_p50_db_time_us,${num(rd.avgP50DbTimeUs)}`)
      lines.push(`READ,${tag},avg_p95_db_time_us,${num(rd.avgP95DbTimeUs)}`)
      lines.push(`READ,${tag},avg_p99_db_time_us,${num(rd.avgP99DbTimeUs)}`)
      lines.push(`READ,${tag},avg_mean_db_time_us,${num(rd.avgMeanDbTimeUs)}`)
      lines.push(`READ,${tag},avg_wire_time_ms,${num(rd.avgWireTimeMs)}`)
      lines.push(`READ,${tag},success_count,${rd.successCount}`)
      lines.push(`READ,${tag},failed_count,${rd.failedCount}`)
    }
    const dl = report.deleteSummary.find((s) => s.databaseId === db.databaseId)
    if (dl) {
      lines.push(`DELETE,${tag},total_runs,${dl.totalRuns}`)
      lines.push(`DELETE,${tag},rows_deleted,${dl.totalRowsDeleted}`)
      lines.push(`DELETE,${tag},avg_p50_db_time_us,${num(dl.avgP50DbTimeUs)}`)
      lines.push(`DELETE,${tag},avg_p95_db_time_us,${num(dl.avgP95DbTimeUs)}`)
      lines.push(`DELETE,${tag},avg_p99_db_time_us,${num(dl.avgP99DbTimeUs)}`)
      lines.push(`DELETE,${tag},size_freed_bytes,${num(dl.totalSizeFreedBytes)}`)
      lines.push(`DELETE,${tag},success_count,${dl.successCount}`)
      lines.push(`DELETE,${tag},failed_count,${dl.failedCount}`)
    }
    const rs = report.radarScores.find((s) => s.databaseId === db.databaseId)
    if (rs) {
      lines.push(`RADAR,${tag},insert_speed_score,${num(rs.insertSpeed)}`)
      lines.push(`RADAR,${tag},read_speed_score,${num(rs.readSpeed)}`)
      lines.push(`RADAR,${tag},delete_speed_score,${num(rs.deleteSpeed)}`)
      lines.push(`RADAR,${tag},size_efficiency_score,${num(rs.sizeEfficiency)}`)
      lines.push(`RADAR,${tag},consistency_score,${num(rs.consistency)}`)
    }
  }

  const blob = new Blob([lines.join("\n")], { type: "text/csv" })
  trigger(blob, `comparison-report-${report.benchmarkId}.csv`)
}

function num(value: number | null): string {
  if (value == null) return ""
  return String(value)
}

function escape(value: string): string {
  return value.replace(/[\r\n,]/g, " ")
}

function trigger(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
