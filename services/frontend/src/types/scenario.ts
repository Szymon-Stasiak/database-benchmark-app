import type { ResourceMetricsFields } from "./resource"

export type ScenarioType =
  | "AGGREGATE_GROUP_COUNT"
  | "RANGE_FILTER"
  | "GRAPH_TRAVERSAL"
  | "VECTOR_KNN"

export type ScenarioStatus = "PENDING" | "RUNNING" | "SUCCESS" | "PARTIAL" | "FAILED" | "SKIPPED"

export type ConsistencyStatus = "MATCH" | "MISMATCH" | "INCOMPLETE" | "" | null

export interface AggregateParams {
  type: "AGGREGATE_GROUP_COUNT"
  childEntity: string
  parentEntity: string
}

export interface RangeParams {
  type: "RANGE_FILTER"
  entityName: string
  attribute: string
  min: number
  max: number
}

export interface TraversalParams {
  type: "GRAPH_TRAVERSAL"
  startEntity: string
  startLogicalId: string
  depth: number
}

export interface KnnParams {
  type: "VECTOR_KNN"
  entityName: string
  vectorAttribute: string
  queryVector: number[]
  topK: number
}

export type ScenarioParams = AggregateParams | RangeParams | TraversalParams | KnnParams

export interface StartScenarioRunRequest {
  params: ScenarioParams
  iterations?: number | null
  databaseIds: string[]
}

export interface ScenarioApplicabilityEntry {
  databaseId: string
  dbName: string
  applicable: boolean
  reason: string | null
}

export interface PreparedScenarioRunResponse {
  runId: string
  benchmarkId: string
  scenarioType: ScenarioType
  status: ScenarioStatus
  applicability: ScenarioApplicabilityEntry[]
}

export interface ScenarioResultResponse extends ResourceMetricsFields {
  id: string
  databaseId: string
  dbName: string
  status: ScenarioStatus
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  errorMessage: string | null
  meanDbTimeUs: number | null
  p50DbTimeUs: number | null
  p95DbTimeUs: number | null
  p99DbTimeUs: number | null
  samplesRecorded: number | null
  scenarioType: ScenarioType | null
  scenarioResultHash: string | null
  scenarioRowsReturned: number | null
  scenarioResultPreview: unknown
}

export interface ScenarioRunResponse {
  id: string
  benchmarkId: string
  scenarioType: ScenarioType
  iterations: number | null
  status: ScenarioStatus
  consistencyStatus: ConsistencyStatus
  createdAt: string
  finishedAt: string | null
  configJson: string | null
  results: ScenarioResultResponse[]
}

export interface ScenarioRunStatusEvent {
  runId: string
  status: ScenarioStatus
  consistencyStatus: string
}

export interface ScenarioResultStatusEvent {
  runId: string
  result: ScenarioResultResponse
}

export type ScenarioApplicabilityMap = Record<ScenarioType, string[]>

export interface SchemaRelationship {
  name: string
  parentEntity: string
  childEntity: string
  fkColumnInChild: string
  cardinality: string
}
