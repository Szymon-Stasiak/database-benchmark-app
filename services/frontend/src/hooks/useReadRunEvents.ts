import { useRef } from "react"
import { useBenchmarkEvents } from "@/hooks/useBenchmarkEvents"
import type {
  ReadResultResponse,
  ReadResultStatusEvent,
  ReadRunStatusEvent,
  ReadStatus,
} from "@/types/read"
import type { ContainerStatsEvent } from "@/types/resource"

interface Handlers {
  onRunStatus?: (status: ReadStatus) => void
  onResultUpdate?: (result: ReadResultResponse) => void
  onContainerStats?: (event: ContainerStatsEvent) => void
}

export function useReadRunEvents(
  benchmarkId: string | null,
  runId: string | null,
  handlers: Handlers,
) {
  const ref = useRef(handlers)
  ref.current = handlers

  useBenchmarkEvents(benchmarkId, (event) => {
    if (!runId) return
    if (event.type === "read_run_status") {
      const payload = event.data as ReadRunStatusEvent
      if (payload.runId === runId) ref.current.onRunStatus?.(payload.status)
    } else if (event.type === "read_result_status") {
      const payload = event.data as ReadResultStatusEvent
      if (payload.runId === runId) ref.current.onResultUpdate?.(payload.result)
    } else if (event.type === "container_stats") {
      const payload = event.data as ContainerStatsEvent
      if (payload.runId === runId && payload.operation === "read") {
        ref.current.onContainerStats?.(payload)
      }
    }
  })
}
