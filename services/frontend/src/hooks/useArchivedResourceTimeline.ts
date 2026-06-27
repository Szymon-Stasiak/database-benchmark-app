import { useEffect, useState } from "react"
import type { ContainerStatsEvent, ResourceOperation, ResourceSample } from "@/types/resource"

interface ResultRef {
  id: string
  databaseId: string
  dbName: string
  resourceSampleCount?: number | null
}

interface Options<R extends ResultRef> {
  runId: string
  status: string
  results: R[]
  operation: ResourceOperation
  loadTimeline: (runId: string, resultId: string) => Promise<ResourceSample[]>
  enabled: boolean
}

const FINISHED_STATUSES = new Set(["SUCCESS", "FAILED", "PARTIAL", "SKIPPED"])

export function useArchivedResourceTimeline<R extends ResultRef>({
  runId,
  status,
  results,
  operation,
  loadTimeline,
  enabled,
}: Options<R>): ContainerStatsEvent[] {
  const [events, setEvents] = useState<ContainerStatsEvent[]>([])
  const sampleCountKey = results
    .map((r) => `${r.id}:${r.resourceSampleCount ?? 0}`)
    .join(",")

  useEffect(() => {
    if (!enabled || !FINISHED_STATUSES.has(status)) {
      setEvents([])
      return
    }
    const withSamples = results.filter((r) => (r.resourceSampleCount ?? 0) > 0)
    if (withSamples.length === 0) {
      setEvents([])
      return
    }

    let cancelled = false
    Promise.all(
      withSamples.map(async (result) => {
        try {
          const timeline = await loadTimeline(runId, result.id)
          return timeline.map<ContainerStatsEvent>((sample) => ({
            runId,
            resultId: result.id,
            databaseId: result.databaseId,
            dbName: result.dbName,
            operation,
            timestamp: sample.tMs,
            cpuPercent: sample.cpuPercent,
            memoryBytes: sample.memoryBytes,
            memoryLimitBytes: sample.memoryLimitBytes,
          }))
        } catch {
          return []
        }
      }),
    ).then((batches) => {
      if (cancelled) return
      const flattened = batches.flat().sort((a, b) => a.timestamp - b.timestamp)
      setEvents(flattened)
    })

    return () => {
      cancelled = true
    }
  }, [runId, status, sampleCountKey, operation, enabled, loadTimeline])

  return events
}
