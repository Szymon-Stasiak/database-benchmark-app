import { useEffect, useRef } from "react"
import { connectSse, type SseEvent } from "@/lib/sseClient"
import type { BatchProgressEvent, InsertResultResponse, InsertStatus } from "@/types/insert"

interface Handlers {
  onRunStatus?: (status: InsertStatus) => void
  onResultUpdate?: (result: InsertResultResponse) => void
  onBatchProgress?: (event: BatchProgressEvent) => void
}

/**
 * Subscribes to the per-run SSE stream and dispatches typed events to the supplied callbacks.
 *
 * <p>Three event types flow over the channel:
 * <ul>
 *   <li>{@code insert_run_status} — the overall run moved between RUNNING / SUCCESS / FAILED.</li>
 *   <li>{@code insert_result_status} — one (entity × DB) phase updated.</li>
 *   <li>{@code insert_batch_progress} — one batch within a phase finished (emitted by JDBC /
 *       native strategies that have a worker-pool implementation).</li>
 * </ul>
 *
 * <p>Two overloads keep callers tidy:
 * <ul>
 *   <li>{@code useInsertRunEvents(runId, onEvent)} — legacy: receives raw SSE events.</li>
 *   <li>{@code useInsertRunEvents(runId, handlers)} — preferred: typed callbacks per event kind.</li>
 * </ul>
 */
export function useInsertRunEvents(
  runId: string | null,
  handlersOrCallback: Handlers | ((event: SseEvent) => void),
) {
  const ref = useRef(handlersOrCallback)
  ref.current = handlersOrCallback

  useEffect(() => {
    if (!runId) return
    const controller = new AbortController()
    connectSse(`/api/insert-runs/${runId}/events`, (e) => dispatch(ref.current, e), controller.signal)
    return () => controller.abort()
  }, [runId])
}

function dispatch(target: Handlers | ((event: SseEvent) => void), event: SseEvent) {
  if (typeof target === "function") {
    target(event)
    return
  }
  switch (event.type) {
    case "insert_run_status":
      target.onRunStatus?.((event.data as { status: InsertStatus }).status)
      break
    case "insert_result_status":
      target.onResultUpdate?.(event.data as InsertResultResponse)
      break
    case "insert_batch_progress":
      target.onBatchProgress?.(event.data as BatchProgressEvent)
      break
  }
}
