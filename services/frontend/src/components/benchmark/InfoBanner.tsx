import { AnimatePresence, motion } from "framer-motion"
import { Info } from "lucide-react"
import type { BenchmarkStatus } from "@/types/benchmark"

const INFO_MESSAGES: Partial<Record<BenchmarkStatus, string>> = {
  GENERATING_SCRIPTS:
    "Script generation typically takes 3-5 minutes per database. All databases are processed in parallel.",
  READY_TO_RUN: "Scripts are generated and ready. Use Redeploy to start Docker containers.",
  STARTING_CONTAINERS:
    "Docker containers are being pulled and started. This usually takes 1-2 minutes.",
  INITIALIZING: "Initialization scripts are being executed on the database containers.",
}

export function InfoBanner({ status }: { status: BenchmarkStatus }) {
  const message = INFO_MESSAGES[status]
  return (
    <AnimatePresence mode="wait">
      {message && (
        <motion.div
          key={status}
          initial={{ opacity: 0, height: 0 }}
          animate={{ opacity: 1, height: "auto" }}
          exit={{ opacity: 0, height: 0 }}
          transition={{ duration: 0.3 }}
          className="mb-6 overflow-hidden"
        >
          <div className="flex items-start gap-3 rounded-lg bg-status-info-bg p-4">
            <Info className="mt-0.5 h-5 w-5 shrink-0 text-status-info-text" />
            <p className="text-sm text-status-info-text">{message}</p>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}
