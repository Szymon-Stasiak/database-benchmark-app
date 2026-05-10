export type DatabaseType = "RELATIONAL" | "GRAPH" | "VECTOR" | "DOCUMENT" | "KEY_VALUE" | "TIME_SERIES"

export const DATABASE_TYPE_LABELS: Record<DatabaseType, string> = {
  RELATIONAL: "Relational",
  GRAPH: "Graph",
  VECTOR: "Vector",
  DOCUMENT: "Document",
  KEY_VALUE: "Key-Value",
  TIME_SERIES: "Time Series",
}

export interface DatabaseOption {
  name: string
  displayName: string
  versions: string[]
}

export interface SupportedDatabases {
  types: Record<DatabaseType, DatabaseOption[]>
}

export interface DatabaseTarget {
  dbType: DatabaseType
  dbName: string
  dbVersion: string
}

export interface CreateBenchmarkRequest {
  topic: string
  databases: DatabaseTarget[]
}

export type BenchmarkStatus =
  | "PENDING"
  | "GENERATING_SCRIPTS"
  | "READY_TO_RUN"
  | "STARTING_CONTAINERS"
  | "INITIALIZING"
  | "RUNNING"
  | "STOPPED"
  | "FAILED"

export type DatabaseStatus =
  | "PENDING"
  | "SCRIPT_GENERATING"
  | "SCRIPT_READY"
  | "CONTAINER_STARTING"
  | "INITIALIZING"
  | "RUNNING"
  | "STOPPED"
  | "FAILED"

export interface DatabaseResponse {
  id: string
  dbType: string
  dbName: string
  dbVersion: string
  status: DatabaseStatus
  hostPort: number | null
  errorMessage: string | null
}

export interface BenchmarkResponse {
  id: string
  topic: string
  status: BenchmarkStatus
  createdAt: string
  databases: DatabaseResponse[]
}

export interface BenchmarkStatusEvent {
  benchmarkId: string
  status: BenchmarkStatus
}

export interface DatabaseStatusEvent {
  benchmarkId: string
  databaseId: string
  status: DatabaseStatus
  errorMessage?: string
}

export interface LogsResponse {
  logs: string
}

export interface ScriptGeneratedEvent {
  benchmarkId: string
  databaseId: string
  scriptPreview: string
}
