import type { SelectionStrategy } from "./preview"
import type { OperationMode } from "./delete"
import type { ResourceMetricsFields } from "./resource"

export type ReadStatus = "PENDING" | "RUNNING" | "SUCCESS" | "PARTIAL" | "FAILED" | "SKIPPED"

export interface StartReadRunRequest {
  entityName: string
  sampleSize?: number | null
  includeChildren?: boolean | null
  selectionStrategy?: SelectionStrategy | null
  mode?: OperationMode | null
  iterations?: number | null
  databaseIds: string[]
}

export interface ReadResultResponse extends ResourceMetricsFields {
  id: string
  databaseId: string
  dbName: string
  entityName: string | null
  status: ReadStatus
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  recordsRead: number | null
  errorMessage: string | null
  meanDbTimeUs: number | null
  p50DbTimeUs: number | null
  p95DbTimeUs: number | null
  p99DbTimeUs: number | null
  wireTimeMs: number | null
  samplesRecorded: number | null
}

export interface ReadRunResponse {
  id: string
  benchmarkId: string
  entityName: string | null
  sampleSize: number | null
  includeChildren: boolean | null
  status: ReadStatus
  createdAt: string
  finishedAt: string | null
  results: ReadResultResponse[]
}

export interface ReadRunStatusEvent {
  runId: string
  status: ReadStatus
}

export interface ReadResultStatusEvent {
  runId: string
  result: ReadResultResponse
}
