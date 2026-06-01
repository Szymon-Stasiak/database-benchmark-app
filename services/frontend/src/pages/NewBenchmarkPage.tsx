import { useState, useEffect } from "react"
import { useNavigate } from "react-router-dom"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Info, Loader2 } from "lucide-react"
import { DatabaseSetupPanel } from "@/components/benchmark/DatabaseSetupPanel"
import { DatabaseTargetList } from "@/components/benchmark/DatabaseTargetList"
import { AppLayout } from "@/components/AppLayout"
import { benchmarkApi } from "@/lib/api"
import type { DatabaseTarget, SupportedDatabases } from "@/types/benchmark"

const FALLBACK_CATALOG: SupportedDatabases = {
  types: {
    RELATIONAL: [
      { name: "postgresql", displayName: "PostgreSQL", versions: ["17", "16", "15"] },
      { name: "mysql", displayName: "MySQL", versions: ["9.0", "8.4", "8.0"] },
      { name: "sqlite", displayName: "SQLite", versions: ["3"] },
    ],
    GRAPH: [
      { name: "neo4j", displayName: "Neo4j", versions: ["5.26", "5.25", "5.24"] },
      { name: "arangodb", displayName: "ArangoDB", versions: ["3.12", "3.11"] },
      { name: "memgraph", displayName: "Memgraph", versions: ["2.21", "2.20"] },
    ],
    VECTOR: [
      { name: "milvus", displayName: "Milvus", versions: ["2.4", "2.3"] },
      { name: "qdrant", displayName: "Qdrant", versions: ["1.12.6", "1.11.5"] },
      { name: "weaviate", displayName: "Weaviate", versions: ["1.27", "1.26"] },
    ],
    DOCUMENT: [
      { name: "mongodb", displayName: "MongoDB", versions: ["8.0", "7.0", "6.0"] },
      { name: "couchdb", displayName: "CouchDB", versions: ["3.4", "3.3"] },
      { name: "elasticsearch", displayName: "Elasticsearch", versions: ["8.16", "8.15"] },
    ],
    KEY_VALUE: [
      { name: "redis", displayName: "Redis", versions: ["7.4", "7.2"] },
      { name: "dynamodb", displayName: "DynamoDB Local", versions: ["2.5", "2.4"] },
      { name: "etcd", displayName: "etcd", versions: ["3.5", "3.4"] },
    ],
    TIME_SERIES: [
      { name: "timescaledb", displayName: "TimescaleDB", versions: ["2.17", "2.16"] },
      { name: "influxdb", displayName: "InfluxDB", versions: ["2.7", "2.6"] },
      { name: "questdb", displayName: "QuestDB", versions: ["8.2", "8.1"] },
    ],
  },
}

export default function NewBenchmarkPage() {
  const navigate = useNavigate()
  const [topic, setTopic] = useState("")
  const [depth, setDepth] = useState(4)
  const [targets, setTargets] = useState<DatabaseTarget[]>([])

  const [catalog, setCatalog] = useState<SupportedDatabases | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    benchmarkApi
      .getSupportedDatabases()
      .then(setCatalog)
      .catch(() => setCatalog(FALLBACK_CATALOG))
  }, [])

  const handleAdd = (target: DatabaseTarget) => {
    setTargets((prev) => [...prev, target])
  }

  const handleRemove = (index: number) => {
    setTargets((prev) => prev.filter((_, i) => i !== index))
  }

  const canSubmit = topic.trim().length > 0 && targets.length > 0 && !loading

  const handleSubmit = async () => {
    setLoading(true)
    setError(null)
    try {
      const response = await benchmarkApi.create({
        topic: topic.trim(),
        depth,
        databases: targets,
      })
      navigate(`/benchmarks/${response.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create benchmark")
    } finally {
      setLoading(false)
    }
  }

  return (
    <AppLayout maxWidth="narrow">
      <h1 className="text-2xl font-bold mb-6">New Benchmark</h1>

      <Card className="mb-6">
        <CardHeader>
          <CardTitle>Topic</CardTitle>
        </CardHeader>
        <CardContent>
          <Label htmlFor="topic" className="mb-2 block">
            Database theme / idea
          </Label>
          <Input
            id="topic"
            placeholder="e.g., Movie management system with actors, directors, genres and reviews"
            value={topic}
            onChange={(e) => setTopic(e.target.value)}
          />
        </CardContent>
      </Card>

      <Card className="mb-6">
        <CardHeader>
          <CardTitle>Relationship Depth</CardTitle>
        </CardHeader>
        <CardContent>
          <Label htmlFor="depth" className="mb-2 block">
            How deep should the schema relationships be? ({depth})
          </Label>
          <div className="flex items-center gap-4">
            <span className="text-xs text-muted-foreground">1</span>
            <input
              id="depth"
              type="range"
              min={1}
              max={10}
              value={depth}
              onChange={(e) => setDepth(Number(e.target.value))}
              className="flex-1 accent-primary"
            />
            <span className="text-xs text-muted-foreground">10</span>
          </div>
          <p className="text-xs text-muted-foreground mt-2">
            Higher depth means more complex entity chains. Recommended: 3-5 for most use cases.
          </p>
        </CardContent>
      </Card>

      <Card className="mb-6">
        <CardHeader>
          <CardTitle>Databases ({targets.length}/5)</CardTitle>
        </CardHeader>
        <CardContent>
          <DatabaseSetupPanel catalog={catalog} targets={targets} onAdd={handleAdd} />
          <div className="border-t border-border my-4" />
          <DatabaseTargetList targets={targets} onRemove={handleRemove} />
        </CardContent>
      </Card>

      {/* Summary panel */}
      {targets.length > 0 && (
        <div className="flex items-start gap-3 rounded-lg bg-status-info-bg p-4 mb-6">
          <Info className="h-5 w-5 text-status-info-text shrink-0 mt-0.5" />
          <div className="text-sm text-status-info-text">
            <p>
              This will generate initialization scripts for{" "}
              <strong>{targets.length} database{targets.length !== 1 ? "s" : ""}</strong>{" "}
              using AI, then start Docker containers for each.
            </p>
            <p className="mt-1">
              Estimated time: ~{3 + targets.length * 2} minutes (scripts are generated in parallel).
            </p>
          </div>
        </div>
      )}

      {error && (
        <Alert variant="destructive" className="mb-6">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Button onClick={handleSubmit} disabled={!canSubmit} className="w-full" size="lg">
        {loading ? (
          <>
            <Loader2 className="h-4 w-4 animate-spin mr-2" />
            Creating...
          </>
        ) : (
          "Host Benchmark"
        )}
      </Button>
    </AppLayout>
  )
}
