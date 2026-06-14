export type SelectionStrategy = "RANDOM_UNIFORM"

export interface CascadeImpact {
  entity: string
  parentEntity: string
  fkColumn: string | null
  cardinality: "ONE_TO_ONE" | "ONE_TO_MANY" | "MANY_TO_MANY"
  ratio: number
  estimatedRowsAffected: number
  depth: number
}

export interface RunPreview {
  rootEntity: string
  sampleSize: number
  availablePool: number
  cascade: CascadeImpact[]
}

export interface PreparedRunResponse {
  runId: string
  benchmarkId: string
  operationType: "READ" | "DELETE"
  entityName: string
  status: string
  preview: RunPreview
}

export interface RegistrySummaryEntry {
  entityName: string
  availableIds: number
}
