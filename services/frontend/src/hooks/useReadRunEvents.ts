import { useRef } from "react"
import { useBenchmarkEvents } from "@/hooks/useBenchmarkEvents"
import type {
  ReadResultResponse,
  ReadResultStatusEvent,
  ReadRunStatusEvent,
  ReadStatus,
} from "@/types/read"

interface Handlers {
  onRunStatus?: (status: ReadStatus) => void
  onResultUpdate?: (result: ReadResultResponse) => void
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
    }
  })
}
