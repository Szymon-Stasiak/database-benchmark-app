import { useEffect, useState } from "react"
import { useNavigate } from "react-router-dom"
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Plus, Database, Activity, Layers, Trash2 } from "lucide-react"
import { motion } from "framer-motion"
import { benchmarkApi } from "@/lib/api"
import { AppLayout } from "@/components/AppLayout"
import { getBenchmarkStatusConfig, relativeTime, cn } from "@/lib/utils"
import type { BenchmarkResponse } from "@/types/benchmark"

export function DashboardPage() {
  const navigate = useNavigate()
  const [benchmarks, setBenchmarks] = useState<BenchmarkResponse[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    benchmarkApi.list()
      .then(setBenchmarks)
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [])

  const totalBenchmarks = benchmarks.length
  const activeDatabases = benchmarks
    .flatMap((b) => b.databases)
    .filter((db) => db.status === "RUNNING").length

  const stats = [
    { label: "Total Benchmarks", value: totalBenchmarks, icon: Database, accent: "text-primary bg-primary/10" },
    { label: "Active Databases", value: activeDatabases, icon: Activity, accent: "text-status-success-text bg-status-success-bg" },
    { label: "Database Types", value: 6, icon: Layers, accent: "text-accent-foreground bg-accent" },
  ]

  return (
    <AppLayout>
      <div className="flex items-center justify-between mb-8">
        <h2 className="text-2xl font-semibold tracking-tight">Dashboard</h2>
        <Button onClick={() => navigate("/benchmarks/new")}>
          <Plus className="h-4 w-4 mr-2" />
          New Benchmark
        </Button>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 mb-8">
        {stats.map((stat, i) => (
          <motion.div
            key={stat.label}
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.08, duration: 0.35 }}
          >
            <Card>
              <CardHeader className="flex flex-row items-center gap-4 pb-2">
                <div className={cn("rounded-lg p-2.5", stat.accent)}>
                  <stat.icon className="h-5 w-5" />
                </div>
                <div>
                  <CardDescription>{stat.label}</CardDescription>
                  <CardTitle className="text-3xl">
                    {loading ? (
                      <div className="h-9 w-10 bg-muted rounded animate-pulse" />
                    ) : stat.value}
                  </CardTitle>
                </div>
              </CardHeader>
            </Card>
          </motion.div>
        ))}
      </div>

      <h3 className="text-lg font-semibold mb-4">Benchmarks</h3>

      {loading ? (
        <div className="flex flex-col gap-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="animate-pulse rounded-xl border border-border p-4">
              <div className="flex items-center justify-between">
                <div className="space-y-2">
                  <div className="h-4 w-48 bg-muted rounded" />
                  <div className="h-3 w-32 bg-muted rounded" />
                </div>
                <div className="h-6 w-20 bg-muted rounded-full" />
              </div>
            </div>
          ))}
        </div>
      ) : benchmarks.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center">
            <p className="text-muted-foreground mb-4">
              No benchmarks yet. Create your first one!
            </p>
            <Button onClick={() => navigate("/benchmarks/new")}>
              <Plus className="h-4 w-4 mr-2" />
              New Benchmark
            </Button>
          </CardContent>
        </Card>
      ) : (
        <div className="flex flex-col gap-3">
          {benchmarks.map((benchmark, i) => {
            const config = getBenchmarkStatusConfig(benchmark.status)
            return (
              <motion.div
                key={benchmark.id}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: i * 0.05, duration: 0.3 }}
              >
                <Card
                  className="cursor-pointer transition-colors hover:bg-muted/50"
                  onClick={() => navigate(`/benchmarks/${benchmark.id}`)}
                >
                  <CardContent className="p-4">
                    <div className="flex items-center justify-between">
                      <div>
                        <h4 className="font-medium">{benchmark.topic}</h4>
                        <p className="text-sm text-muted-foreground">
                          {benchmark.databases.length} database{benchmark.databases.length !== 1 ? "s" : ""} &middot; {relativeTime(benchmark.createdAt)}
                        </p>
                      </div>
                      <div className="flex items-center gap-2">
                        <Badge className={cn("rounded-full px-3 py-0.5 text-xs font-medium border-0", config.bgClass, config.textClass)}>
                          {config.label}
                        </Badge>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7 text-muted-foreground hover:text-destructive"
                          onClick={(e) => {
                            e.stopPropagation()
                            if (!confirm("Delete this benchmark? All containers will be stopped and removed.")) return
                            benchmarkApi.deleteBenchmark(benchmark.id).then(() => {
                              setBenchmarks((prev) => prev.filter((b) => b.id !== benchmark.id))
                            })
                          }}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    </div>
                  </CardContent>
                </Card>
              </motion.div>
            )
          })}
        </div>
      )}
    </AppLayout>
  )
}
