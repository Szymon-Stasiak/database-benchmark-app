import type {
  BenchmarkResponse,
  CreateBenchmarkRequest,
  SupportedDatabases,
  LogsResponse,
} from "@/types/benchmark"
import type {
  CascadePreviewRequest,
  CascadePreviewResponse,
  DatabaseSizeResponse,
  EntityChoice,
  InsertRunResponse,
  StartInsertRunRequest,
} from "@/types/insert"
import type {
  ReadRunResponse,
  StartReadRunRequest,
} from "@/types/read"
import type {
  DeleteRunResponse,
  StartDeleteRunRequest,
} from "@/types/delete"
import type { ComparisonReportResponse } from "@/types/comparison"

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

  createFromBundle: async (file: File): Promise<BenchmarkResponse> => {
    const token = localStorage.getItem("auth_token")
    const formData = new FormData()
    formData.append("file", file)
    const res = await fetch("/api/benchmarks/import", {
      method: "POST",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData,
    })
    if (!res.ok) {
      if (res.status === 401) {
        localStorage.removeItem("auth_token")
        window.location.reload()
      }
      const text = await res.text()
      throw new ApiError(res.status, text)
    }
    return res.json()
  },

  downloadBundle: async (benchmarkId: string) => {
    const token = localStorage.getItem("auth_token")
    const res = await fetch(`/api/benchmarks/${benchmarkId}/bundle`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!res.ok) throw new ApiError(res.status, await res.text())
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement("a")
    a.href = url
    a.download = `benchmark-${benchmarkId}.zip`
    a.click()
    URL.revokeObjectURL(url)
  },

  list: () => apiFetch<BenchmarkResponse[]>("/api/benchmarks"),

  get: (id: string) => apiFetch<BenchmarkResponse>(`/api/benchmarks/${id}`),

  getFullScript: async (benchmarkId: string, dbId: string): Promise<string> => {
    const token = localStorage.getItem("auth_token")
    const res = await fetch(
      `/api/benchmarks/${benchmarkId}/databases/${dbId}/script`,
      {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      },
    )
    if (!res.ok) throw new ApiError(res.status, await res.text())
    return res.text()
  },

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

  hardResetBenchmark: (benchmarkId: string) =>
    apiFetch<void>(`/api/benchmarks/${benchmarkId}/hard-reset`, {
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

  deleteDatabase: (benchmarkId: string, dbId: string) =>
    apiFetch<void>(`/api/benchmarks/${benchmarkId}/databases/${dbId}`, {
      method: "DELETE",
    }),

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

export const insertApi = {
  listEntities: (benchmarkId: string) =>
    apiFetch<EntityChoice[]>(`/api/benchmarks/${benchmarkId}/entities`),

  listRuns: (benchmarkId: string) =>
    apiFetch<InsertRunResponse[]>(`/api/benchmarks/${benchmarkId}/insert-runs`),

  startRun: (benchmarkId: string, req: StartInsertRunRequest) =>
    apiFetch<InsertRunResponse>(`/api/benchmarks/${benchmarkId}/insert-runs`, {
      method: "POST",
      body: JSON.stringify(req),
    }),

  getRun: (runId: string) =>
    apiFetch<InsertRunResponse>(`/api/insert-runs/${runId}`),

  getDatabaseSizes: (benchmarkId: string) =>
    apiFetch<DatabaseSizeResponse[]>(`/api/benchmarks/${benchmarkId}/database-sizes`),

  cascadePreview: (benchmarkId: string, req: CascadePreviewRequest) =>
    apiFetch<CascadePreviewResponse>(`/api/benchmarks/${benchmarkId}/cascade-preview`, {
      method: "POST",
      body: JSON.stringify(req),
    }),
}

export const readApi = {
  listRuns: (benchmarkId: string) =>
    apiFetch<ReadRunResponse[]>(`/api/benchmarks/${benchmarkId}/read-runs`),

  startRun: (benchmarkId: string, req: StartReadRunRequest) =>
    apiFetch<ReadRunResponse>(`/api/benchmarks/${benchmarkId}/read-runs`, {
      method: "POST",
      body: JSON.stringify(req),
    }),

  getRun: (runId: string) =>
    apiFetch<ReadRunResponse>(`/api/read-runs/${runId}`),
}

export const comparisonApi = {
  getReport: (benchmarkId: string) =>
    apiFetch<ComparisonReportResponse>(
      `/api/benchmarks/${benchmarkId}/comparison-report`,
    ),
}

export const deleteApi = {
  listRuns: (benchmarkId: string) =>
    apiFetch<DeleteRunResponse[]>(`/api/benchmarks/${benchmarkId}/delete-runs`),

  startRun: (benchmarkId: string, req: StartDeleteRunRequest) =>
    apiFetch<DeleteRunResponse>(`/api/benchmarks/${benchmarkId}/delete-runs`, {
      method: "POST",
      body: JSON.stringify(req),
    }),

  getRun: (runId: string) =>
    apiFetch<DeleteRunResponse>(`/api/delete-runs/${runId}`),
}

export { ApiError }
