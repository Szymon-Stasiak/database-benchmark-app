import { useEffect, useRef } from "react"
import { connectSse, type SseEvent } from "@/lib/sseClient"
import type { BatchProgressEvent, InsertResultResponse, InsertStatus } from "@/types/insert"
import type { ContainerStatsEvent } from "@/types/resource"

interface Handlers {
  onRunStatus?: (status: InsertStatus) => void
  onResultUpdate?: (result: InsertResultResponse) => void
  onBatchProgress?: (event: BatchProgressEvent) => void
  onContainerStats?: (event: ContainerStatsEvent) => void
}

export function useInsertRunEvents(
  benchmarkId: string | null,
  runId: string | null,
  handlers: Handlers,
) {
  const ref = useRef(handlers)
  ref.current = handlers

  useEffect(() => {
    if (!benchmarkId || !runId) return
    const controller = new AbortController()
    connectSse(
      `/api/benchmarks/${benchmarkId}/events`,
      (e) => {
        if (!matchesRun(e, runId)) return
        dispatch(ref.current, e)
      },
      controller.signal,
    )
    return () => controller.abort()
  }, [benchmarkId, runId])
}

function matchesRun(event: SseEvent, runId: string): boolean {
  if (event.type === "container_stats") {
    const data = event.data as { runId?: string; operation?: string }
    return data?.runId === runId && data?.operation === "insert"
  }
  if (
    event.type !== "insert_run_status" &&
    event.type !== "insert_result_status" &&
    event.type !== "insert_batch_progress"
  ) {
    return false
  }
  const data = event.data as { runId?: string }
  return data?.runId === runId
}

function dispatch(target: Handlers, event: SseEvent) {
  switch (event.type) {
    case "insert_run_status":
      target.onRunStatus?.((event.data as { status: InsertStatus }).status)
      break
    case "insert_result_status":
      target.onResultUpdate?.((event.data as { result: InsertResultResponse }).result)
      break
    case "insert_batch_progress":
      target.onBatchProgress?.(event.data as BatchProgressEvent)
      break
    case "container_stats":
      target.onContainerStats?.(event.data as ContainerStatsEvent)
      break
  }
}
