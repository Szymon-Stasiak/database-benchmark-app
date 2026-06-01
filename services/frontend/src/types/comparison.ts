export interface DatabaseDescriptor {
  databaseId: string
  dbName: string
  dbVersion: string
  engineCategory: string
}

export interface InsertSummary {
  databaseId: string
  dbName: string
  totalRuns: number
  totalRowsInserted: number
  avgDbTimeMs: number | null
  avgWireTimeMs: number | null
  avgOverheadMs: number | null
  avgThroughputRps: number | null
  totalConflicts: number
  successCount: number
  failedCount: number
}

export interface ReadSummary {
  databaseId: string
  dbName: string
  totalRuns: number
  totalSamples: number
  avgP50DbTimeUs: number | null
  avgP95DbTimeUs: number | null
  avgP99DbTimeUs: number | null
  avgMeanDbTimeUs: number | null
  avgWireTimeMs: number | null
  successCount: number
  failedCount: number
}

export interface DeleteSummary {
  databaseId: string
  dbName: string
  totalRuns: number
  totalRowsDeleted: number
  avgP50DbTimeUs: number | null
  avgP95DbTimeUs: number | null
  avgP99DbTimeUs: number | null
  totalSizeFreedBytes: number | null
  successCount: number
  failedCount: number
}

export interface RadarScore {
  databaseId: string
  dbName: string
  insertSpeed: number
  readSpeed: number
  deleteSpeed: number
  sizeEfficiency: number
  consistency: number
}

export interface ComparisonReportResponse {
  benchmarkId: string
  topic: string
  generatedAt: string
  databases: DatabaseDescriptor[]
  insertSummary: InsertSummary[]
  readSummary: ReadSummary[]
  deleteSummary: DeleteSummary[]
  radarScores: RadarScore[]
}
