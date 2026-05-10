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
  depth: number
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
  logicalSchema: string | null
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

export interface LogicalSchemaConstraints {
  is_primary_key: boolean
  is_unique: boolean
  is_nullable: boolean
  is_indexed: boolean
}

export interface LogicalSchemaAttribute {
  name: string
  data_type: string
  constraints: LogicalSchemaConstraints
  description?: string
}

export interface LogicalSchemaEntity {
  name: string
  description: string
  attributes: LogicalSchemaAttribute[]
}

export interface LogicalSchemaRelationship {
  name: string
  source_entity: string
  target_entity: string
  cardinality: string
  description: string
}

export interface LogicalSchemaDataSizeHint {
  entity_name: string
  expected_row_count: number
}

export interface LogicalSchema {
  idea: string
  depth: number
  depth_chain: string[]
  entities: LogicalSchemaEntity[]
  relationships: LogicalSchemaRelationship[]
  data_size_hints: LogicalSchemaDataSizeHint[]
}

export interface ScriptGeneratedEvent {
  benchmarkId: string
  databaseId: string
  scriptPreview: string
}
