export type ResourceOperation = "insert" | "read" | "delete"

export interface ContainerStatsEvent {
  runId: string
  resultId: string
  databaseId: string
  dbName: string
  operation: ResourceOperation
  timestamp: number
  cpuPercent: number
  memoryBytes: number
  memoryLimitBytes: number
}

export interface ResourceSample {
  tMs: number
  cpuPercent: number
  memoryBytes: number
  memoryLimitBytes: number
}

export interface ResourceMetricsFields {
  cpuPercentMax?: number | null
  cpuPercentMean?: number | null
  cpuPercentP95?: number | null
  memoryBytesMax?: number | null
  memoryBytesMean?: number | null
  memoryBytesP95?: number | null
  resourceSampleCount?: number | null
}
