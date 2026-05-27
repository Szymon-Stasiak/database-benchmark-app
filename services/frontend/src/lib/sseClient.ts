export interface SseEvent {
  type: string
  data: unknown
}

/**
 * Connects to an SSE endpoint using fetch streams (so we can attach a Bearer token)
 * and dispatches parsed events. Reconnects with backoff while the AbortController
 * is still live.
 */
export function connectSse(
  url: string,
  onEvent: (event: SseEvent) => void,
  signal: AbortSignal,
): void {
  const token = localStorage.getItem("auth_token")

  async function run() {
    try {
      const res = await fetch(url, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        signal,
      })
      if (!res.ok || !res.body) return

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ""
      let currentEvent = ""
      let currentData = ""

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split("\n")
        buffer = lines.pop() || ""

        for (const line of lines) {
          if (line.startsWith("event:")) {
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith("data:")) {
            currentData = line.slice(5).trim()
          } else if (line === "" && currentEvent && currentData) {
            try {
              onEvent({ type: currentEvent, data: JSON.parse(currentData) })
            } catch {
              // skip malformed payload
            }
            currentEvent = ""
            currentData = ""
          }
        }
      }
    } catch (e) {
      if ((e as Error).name === "AbortError") return
      setTimeout(run, 3000)
    }
  }

  void run()
}
