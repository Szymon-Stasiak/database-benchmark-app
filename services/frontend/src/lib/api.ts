import type {
  BenchmarkResponse,
  CreateBenchmarkRequest,
  SupportedDatabases,
  LogsResponse,
} from "@/types/benchmark"

class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function apiFetch<T>(path: string, options?: RequestInit): Promise<T> {
  const token = localStorage.getItem("auth_token")
  const headers: Record<string, string> = {
    ...(options?.headers as Record<string, string>),
  }
  if (token) {
    headers["Authorization"] = `Bearer ${token}`
  }
  if (options?.body) {
    headers["Content-Type"] = "application/json"
  }

  const res = await fetch(path, {
    ...options,
    headers,
  })

  if (!res.ok) {
    if (res.status === 401) {
      localStorage.removeItem("auth_token")
      window.location.reload()
    }
    const text = await res.text()
    throw new ApiError(res.status, text)
  }

  if (res.status === 204 || res.headers.get("content-length") === "0") {
    return undefined as T
  }

  return res.json()
}

export const benchmarkApi = {
  getSupportedDatabases: () =>
    apiFetch<SupportedDatabases>("/api/catalog/databases"),

  create: (req: CreateBenchmarkRequest) =>
    apiFetch<BenchmarkResponse>("/api/benchmarks", {
      method: "POST",
      body: JSON.stringify(req),
    }),

  list: () => apiFetch<BenchmarkResponse[]>("/api/benchmarks"),

  get: (id: string) => apiFetch<BenchmarkResponse>(`/api/benchmarks/${id}`),

  downloadScript: async (benchmarkId: string, dbId: string) => {
    const token = localStorage.getItem("auth_token")
    const res = await fetch(
      `/api/benchmarks/${benchmarkId}/databases/${dbId}/script`,
      {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      },
    )
    if (!res.ok) throw new ApiError(res.status, await res.text())
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement("a")
    a.href = url
    a.download = `init-script-${dbId}.sql`
    a.click()
    URL.revokeObjectURL(url)
  },

  redeployBenchmark: (benchmarkId: string) =>
    apiFetch<void>(`/api/benchmarks/${benchmarkId}/redeploy`, {
      method: "POST",
    }),

  redeployDatabase: (benchmarkId: string, dbId: string) =>
    apiFetch<void>(
      `/api/benchmarks/${benchmarkId}/databases/${dbId}/redeploy`,
      { method: "POST" },
    ),

  stopDatabase: (benchmarkId: string, dbId: string) =>
    apiFetch<void>(`/api/benchmarks/${benchmarkId}/databases/${dbId}/stop`, {
      method: "POST",
    }),

  restartDatabase: (benchmarkId: string, dbId: string) =>
    apiFetch<void>(
      `/api/benchmarks/${benchmarkId}/databases/${dbId}/restart`,
      { method: "POST" },
    ),

  getLogs: (benchmarkId: string, dbId: string, tailLines = 200) =>
    apiFetch<LogsResponse>(
      `/api/benchmarks/${benchmarkId}/databases/${dbId}/logs?tailLines=${tailLines}`,
    ),

  getScriptPreview: (benchmarkId: string, dbId: string) =>
    apiFetch<{ preview: string }>(
      `/api/benchmarks/${benchmarkId}/databases/${dbId}/script/preview`,
    ),

  deleteBenchmark: async (benchmarkId: string) => {
    const token = localStorage.getItem("auth_token")
    const res = await fetch(`/api/benchmarks/${benchmarkId}`, {
      method: "DELETE",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!res.ok) {
      if (res.status === 401) {
        localStorage.removeItem("auth_token")
        window.location.reload()
      }
      const text = await res.text()
      throw new ApiError(res.status, text)
    }
  },
}

export { ApiError }
