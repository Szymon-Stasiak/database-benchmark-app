export type DeleteStatus = "PENDING" | "RUNNING" | "SUCCESS" | "PARTIAL" | "FAILED" | "SKIPPED"

export interface StartDeleteRunRequest {
  entityName: string
  sampleSize?: number | null
  includeChildren?: boolean | null
  databaseIds: string[]
}

export interface DeleteResultResponse {
  id: string
  databaseId: string
  dbName: string
  entityName: string | null
  status: DeleteStatus
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  rowsDeleted: number | null
  errorMessage: string | null
  meanDbTimeUs: number | null
  p50DbTimeUs: number | null
  p95DbTimeUs: number | null
  p99DbTimeUs: number | null
  wireTimeMs: number | null
  samplesRecorded: number | null
  dataSizeBefore: number | null
  dataSizeAfter: number | null
  dataSizeDelta: number | null
}

export interface DeleteRunResponse {
  id: string
  benchmarkId: string
  entityName: string | null
  sampleSize: number | null
  includeChildren: boolean | null
  status: DeleteStatus
  createdAt: string
  finishedAt: string | null
  results: DeleteResultResponse[]
}

export interface DeleteRunStatusEvent {
  runId: string
  status: DeleteStatus
}

export interface DeleteResultStatusEvent {
  runId: string
  result: DeleteResultResponse
}
