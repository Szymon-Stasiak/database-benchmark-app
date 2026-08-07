import { useCallback, useEffect, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { BarChart3, Download, FileJson, Loader2, RefreshCw } from "lucide-react"
import { AppLayout } from "@/components/AppLayout"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { BackButton } from "@/components/shared/BackButton"
import { PageHeader } from "@/components/shared/PageHeader"
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
    <AppLayout
      breadcrumbs={[
        { label: "Dashboard", to: "/dashboard" },
        { label: report?.topic ?? "Benchmark", to: id ? `/benchmarks/${id}` : undefined },
        { label: "Comparison" },
      ]}
    >
      <BackButton to={id ? `/benchmarks/${id}` : "/dashboard"} label="Back to benchmark" />

      <PageHeader
        icon={BarChart3}
        title="Comparison report"
        subtitle={
          report ? (
            <>
              Benchmark: <span className="font-medium text-foreground">{report.topic}</span> ·{" "}
              {report.databases.length} database(s) · Generated{" "}
              {new Date(report.generatedAt).toLocaleString()}
            </>
          ) : (
            "Loading aggregated metrics…"
          )
        }
        actions={
          <>
            <Button
              variant="outline"
              size="sm"
              onClick={fetchReport}
              disabled={loading}
              title="Refresh"
            >
              {loading ? (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              ) : (
                <RefreshCw className="mr-2 h-4 w-4" />
              )}
              Refresh
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => report && downloadCsv(report)}
              disabled={!report}
            >
              <Download className="mr-2 h-4 w-4" />
              CSV
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => report && downloadJson(report)}
              disabled={!report}
            >
              <FileJson className="mr-2 h-4 w-4" />
              JSON
            </Button>
          </>
        }
      />

      <div className="space-y-6">
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
              <div key={i} className="space-y-3 rounded-xl border border-border p-6">
                <Skeleton className="h-5 w-48" />
                <Skeleton className="h-32 w-full" />
              </div>
            ))}
          </div>
        )}
      </div>
    </AppLayout>
  )
}
