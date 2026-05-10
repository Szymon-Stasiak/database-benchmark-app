import { useEffect, useRef } from "react"

interface SseEvent {
  type: string
  data: unknown
}

export function useBenchmarkEvents(
  benchmarkId: string | null,
  onEvent: (event: SseEvent) => void,
) {
  const onEventRef = useRef(onEvent)
  onEventRef.current = onEvent

  useEffect(() => {
    if (!benchmarkId) return

    const token = localStorage.getItem("auth_token")
    const abortController = new AbortController()

    async function connect() {
      try {
        const res = await fetch(`/api/benchmarks/${benchmarkId}/events`, {
          headers: token ? { Authorization: `Bearer ${token}` } : {},
          signal: abortController.signal,
        })

        if (!res.ok || !res.body) return

        const reader = res.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ""

        while (true) {
          const { done, value } = await reader.read()
          if (done) break

          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split("\n")
          buffer = lines.pop() || ""

          let currentEvent = ""
          let currentData = ""

          for (const line of lines) {
            if (line.startsWith("event:")) {
              currentEvent = line.slice(6).trim()
            } else if (line.startsWith("data:")) {
              currentData = line.slice(5).trim()
            } else if (line === "" && currentEvent && currentData) {
              try {
                const parsed = JSON.parse(currentData)
                onEventRef.current({ type: currentEvent, data: parsed })
              } catch {
                // skip invalid JSON
              }
              currentEvent = ""
              currentData = ""
            }
          }
        }
      } catch (e) {
        if ((e as Error).name !== "AbortError") {
          // Reconnect after 3 seconds
          setTimeout(connect, 3000)
        }
      }
    }

    connect()

    return () => {
      abortController.abort()
    }
  }, [benchmarkId])
}
