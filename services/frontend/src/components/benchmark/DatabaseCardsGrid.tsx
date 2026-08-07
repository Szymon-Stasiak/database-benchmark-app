import { motion } from "framer-motion"
import { DatabaseCard } from "@/components/benchmark/DatabaseCard"
import type { BenchmarkResponse, DatabaseStatusEvent } from "@/types/benchmark"

interface Props {
  benchmark: BenchmarkResponse
  scriptPreviews: Record<string, string>
  onStatusChange: (databaseId: string, status: DatabaseStatusEvent["status"]) => void
  onDelete: (databaseId: string) => void
}

export function DatabaseCardsGrid({ benchmark, scriptPreviews, onStatusChange, onDelete }: Props) {
  return (
    <div className="mb-6 grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
      {benchmark.databases.map((db, i) => (
        <motion.div
          key={db.id}
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: i * 0.06, duration: 0.35 }}
        >
          <DatabaseCard
            database={db}
            benchmarkId={benchmark.id}
            scriptPreview={scriptPreviews[db.id]}
            onStatusChange={onStatusChange}
            onDelete={onDelete}
          />
        </motion.div>
      ))}
    </div>
  )
}
