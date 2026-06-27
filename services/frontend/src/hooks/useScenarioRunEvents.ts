import { useRef } from "react"
import { useBenchmarkEvents } from "@/hooks/useBenchmarkEvents"
import type {
  ScenarioResultResponse,
  ScenarioResultStatusEvent,
  ScenarioRunStatusEvent,
  ScenarioStatus,
} from "@/types/scenario"
import type { ContainerStatsEvent } from "@/types/resource"

interface Handlers {
  onRunStatus?: (status: ScenarioStatus, consistencyStatus: string) => void
  onResultUpdate?: (result: ScenarioResultResponse) => void
  onContainerStats?: (event: ContainerStatsEvent) => void
}

export function useScenarioRunEvents(
  benchmarkId: string | null,
  runId: string | null,
  handlers: Handlers,
) {
  const ref = useRef(handlers)
  ref.current = handlers

  useBenchmarkEvents(benchmarkId, (event) => {
    if (!runId) return
    if (event.type === "scenario_run_status") {
      const payload = event.data as ScenarioRunStatusEvent
      if (payload.runId === runId) {
        ref.current.onRunStatus?.(payload.status, payload.consistencyStatus)
      }
    } else if (event.type === "scenario_result_status") {
      const payload = event.data as ScenarioResultStatusEvent
      if (payload.runId === runId) ref.current.onResultUpdate?.(payload.result)
    } else if (event.type === "container_stats") {
      const payload = event.data as ContainerStatsEvent & { operation: string }
      if (payload.runId === runId && payload.operation?.startsWith("scenario")) {
        ref.current.onContainerStats?.(payload)
      }
    }
  })
}
