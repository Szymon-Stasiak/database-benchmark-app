import { useRef } from "react"
import { useBenchmarkEvents } from "@/hooks/useBenchmarkEvents"
import type {
  DeleteResultResponse,
  DeleteResultStatusEvent,
  DeleteRunStatusEvent,
  DeleteStatus,
} from "@/types/delete"
import type { ContainerStatsEvent } from "@/types/resource"

interface Handlers {
  onRunStatus?: (status: DeleteStatus) => void
  onResultUpdate?: (result: DeleteResultResponse) => void
  onContainerStats?: (event: ContainerStatsEvent) => void
}

export function useDeleteRunEvents(
  benchmarkId: string | null,
  runId: string | null,
  handlers: Handlers,
) {
  const ref = useRef(handlers)
  ref.current = handlers

  useBenchmarkEvents(benchmarkId, (event) => {
    if (!runId) return
    if (event.type === "delete_run_status") {
      const payload = event.data as DeleteRunStatusEvent
      if (payload.runId === runId) ref.current.onRunStatus?.(payload.status)
    } else if (event.type === "delete_result_status") {
      const payload = event.data as DeleteResultStatusEvent
      if (payload.runId === runId) ref.current.onResultUpdate?.(payload.result)
    } else if (event.type === "container_stats") {
      const payload = event.data as ContainerStatsEvent
      if (payload.runId === runId && payload.operation === "delete") {
        ref.current.onContainerStats?.(payload)
      }
    }
  })
}
