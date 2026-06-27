import type { ResourceMetricsFields } from "./resource"

export type InsertMode = "SINGLE" | "BATCH" | "BULK"

export const INSERT_MODE_LABELS: Record<InsertMode, string> = {
  SINGLE: "Single — one INSERT per record",
  BATCH: "Batch — group inserts in transactions",
  BULK: "Bulk — one big INSERT for everything",
}

export type InsertStatus = "PENDING" | "RUNNING" | "SUCCESS" | "PARTIAL" | "FAILED" | "SKIPPED"

export interface AttributeChoice {
  name: string
  dataType: string
  description: string | null
  primaryKey: boolean
  nullable: boolean
}

export interface EntityChoice {
  name: string
  description: string | null
  attributes: AttributeChoice[]
}

export interface EdgeRatio {
  childEntity: string
  parentEntity: string
  ratio: number
}

export interface EntityCascadeChoice {
  entityName: string
  recordCount: number
  edgeRatios?: EdgeRatio[]
}

export interface StartInsertRunRequest {
  entities: EntityCascadeChoice[]
  mode: InsertMode
  batchSize?: number | null
  workerCount?: number | null
  databaseIds: string[]
}

export interface CascadePreviewRequest {
  entities: EntityCascadeChoice[]
}

export interface CascadePreviewEntity {
  name: string
  recordCount: number
  leaf: boolean
  parents: string[]
}

export interface CascadePreviewEdge {
  childEntity: string
  parentEntity: string
  cardinality: "ONE_TO_ONE" | "ONE_TO_MANY" | "MANY_TO_MANY"
  defaultRatio: number
  ratio: number
  fkColumn: string | null
}

export interface CascadePreviewResponse {
  entities: CascadePreviewEntity[]
  edges: CascadePreviewEdge[]
}

export interface InsertResultResponse extends ResourceMetricsFields {
  id: string
  databaseId: string
  dbName: string
  entityName: string | null
  status: InsertStatus
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  recordsInserted: number | null
  throughputRps: number | null
  errorMessage: string | null
  dbTimeMs: number | null
  wireTimeMs: number | null
  overheadMs: number | null
  conflictsSkipped: number
}

export interface InsertRunResponse {
  id: string
  benchmarkId: string
  entityName: string
  recordCount: number
  mode: InsertMode
  batchSize: number | null
  workerCount: number | null
  cascadeJson: string | null
  status: InsertStatus
  createdAt: string
  finishedAt: string | null
  results: InsertResultResponse[]
}

export interface InsertRunStatusEvent {
  runId: string
  status: InsertStatus
}

export interface DatabaseSizeResponse {
  databaseId: string
  dbName: string
  dbVersion: string
  sizeBytes: number | null
  baselineBytes: number | null
  dataBytes: number | null
  sizeHuman: string
  available: boolean
}

export interface BatchProgressEvent {
  runId: string
  resultId: string
  databaseId: string
  entityName: string
  batchIndex: number
  batchCount: number
  recordsDone: number
  recordsTotal: number
}
