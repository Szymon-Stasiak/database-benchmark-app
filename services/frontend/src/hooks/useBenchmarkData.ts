import { useCallback, useEffect, useState } from "react"
import { useNavigate } from "react-router-dom"
import { ApiError, benchmarkApi } from "@/lib/api"
import type { BenchmarkResponse } from "@/types/benchmark"

/**
 * Shared "fetch benchmark by id → 404 redirects to dashboard → surface error"
 * pattern used by every benchmark subroute page. Extra per-page loads (runs,
 * registry, scenarios…) still live in the page because they diverge, but this
 * hook wipes the boilerplate common to all of them.
 */
export function useBenchmarkData(id: string | undefined) {
  const navigate = useNavigate()
  const [benchmark, setBenchmark] = useState<BenchmarkResponse | null>(null)
  const [loading, setLoading] = useState<boolean>(!!id)
  const [error, setError] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    if (!id) {
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    benchmarkApi
      .get(id)
      .then((b) => {
        if (!cancelled) setBenchmark(b)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        if (e instanceof ApiError && e.status === 404) {
          navigate("/dashboard")
          return
        }
        setError(e instanceof Error ? e.message : "Failed to load benchmark")
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [id, navigate, reloadKey])

  const refetch = useCallback(() => setReloadKey((k) => k + 1), [])

  return { benchmark, loading, error, refetch }
}
