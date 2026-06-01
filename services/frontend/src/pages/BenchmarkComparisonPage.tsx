import { useCallback, useEffect, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { ArrowLeft, BarChart3, Download, FileJson, Loader2, RefreshCw } from "lucide-react"
import { motion } from "framer-motion"
import { AppLayout } from "@/components/AppLayout"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { ApiError, comparisonApi } from "@/lib/api"
import { downloadCsv, downloadJson } from "@/lib/comparisonExport"
import { ParadigmRadarChart } from "@/components/comparison/ParadigmRadarChart"
import {
  DeleteSummaryTable,
  InsertSummaryTable,
  ReadSummaryTable,
} from "@/components/comparison/ComparisonSummaryTables"
import type { ComparisonReportResponse } from "@/types/comparison"

export default function BenchmarkComparisonPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const [report, setReport] = useState<ComparisonReportResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  const fetchReport = useCallback(async () => {
    if (!id) return
    setLoading(true)
    setError(null)
    try {
      const r = await comparisonApi.getReport(id)
      setReport(r)
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        navigate("/dashboard")
        return
      }
      setError(e instanceof Error ? e.message : "Failed to load comparison report")
    } finally {
      setLoading(false)
    }
  }, [id, navigate])

  useEffect(() => {
    fetchReport()
  }, [fetchReport])

  return (
    <AppLayout>
      <Button
        variant="ghost"
        size="sm"
        onClick={() => navigate(id ? `/benchmarks/${id}` : "/dashboard")}
        className="mb-4"
      >
        <ArrowLeft className="h-4 w-4 mr-2" />
        Back to benchmark
      </Button>

      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
        className="space-y-6"
      >
        <div className="flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold inline-flex items-center gap-2">
              <BarChart3 className="h-6 w-6 text-primary" />
              Comparison report
            </h1>
            <p className="text-sm text-muted-foreground mt-1">
              {report ? (
                <>
                  Benchmark: <span className="font-medium">{report.topic}</span> ·{" "}
                  {report.databases.length} database(s) ·{" "}
                  Generated {new Date(report.generatedAt).toLocaleString()}
                </>
              ) : (
                "Loading aggregated metrics…"
              )}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={fetchReport}
              disabled={loading}
              title="Refresh"
            >
              {loading ? (
                <Loader2 className="h-4 w-4 mr-2 animate-spin" />
              ) : (
                <RefreshCw className="h-4 w-4 mr-2" />
              )}
              Refresh
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => report && downloadCsv(report)}
              disabled={!report}
            >
              <Download className="h-4 w-4 mr-2" />
              CSV
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => report && downloadJson(report)}
              disabled={!report}
            >
              <FileJson className="h-4 w-4 mr-2" />
              JSON
            </Button>
          </div>
        </div>

        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        {report && (
          <>
            <ParadigmRadarChart scores={report.radarScores} />
            <InsertSummaryTable rows={report.insertSummary} />
            <ReadSummaryTable rows={report.readSummary} />
            <DeleteSummaryTable rows={report.deleteSummary} />
          </>
        )}

        {!report && loading && (
          <div className="grid grid-cols-1 gap-4">
            {Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="rounded-xl border border-border p-6 space-y-3 animate-pulse">
                <div className="h-5 w-48 bg-muted rounded" />
                <div className="h-32 w-full bg-muted rounded" />
              </div>
            ))}
          </div>
        )}
      </motion.div>
    </AppLayout>
  )
}
