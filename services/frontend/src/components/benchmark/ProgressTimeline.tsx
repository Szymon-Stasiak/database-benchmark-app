import { motion } from "framer-motion"
import { cn } from "@/lib/utils"
import type { BenchmarkStatus } from "@/types/benchmark"

const TIMELINE_STEPS: { key: BenchmarkStatus; label: string }[] = [
  { key: "PENDING", label: "Pending" },
  { key: "GENERATING_SCRIPTS", label: "Scripts" },
  { key: "READY_TO_RUN", label: "Ready" },
  { key: "STARTING_CONTAINERS", label: "Containers" },
  { key: "INITIALIZING", label: "Init" },
  { key: "RUNNING", label: "Running" },
]

const STEP_ORDER: Record<string, number> = {
  PENDING: 0,
  GENERATING_SCRIPTS: 1,
  READY_TO_RUN: 2,
  STARTING_CONTAINERS: 3,
  INITIALIZING: 4,
  RUNNING: 5,
  STOPPED: 5,
  FAILED: -1,
}

interface Props {
  status: BenchmarkStatus
}

export function ProgressTimeline({ status }: Props) {
  const currentStepIndex = STEP_ORDER[status] ?? 0
  const isFailed = status === "FAILED"

  return (
    <div className="mb-6 px-4">
      <div className="flex items-center justify-between">
        {TIMELINE_STEPS.map((step, i) => {
          const isComplete = !isFailed && currentStepIndex > i
          const isCurrent = !isFailed && currentStepIndex === i
          const isFailedStep = isFailed && i === 0

          return (
            <div key={step.key} className="flex flex-1 items-center last:flex-none">
              <div className="flex flex-col items-center gap-1.5">
                <div
                  className={cn(
                    "relative flex h-8 w-8 items-center justify-center rounded-full text-xs font-medium transition-all duration-500",
                    isComplete && "bg-primary text-primary-foreground",
                    isCurrent && "bg-primary/20 text-primary ring-2 ring-primary/40",
                    isFailedStep && "bg-destructive/20 text-destructive ring-2 ring-destructive/40",
                    !isComplete && !isCurrent && !isFailedStep && "bg-muted text-muted-foreground",
                  )}
                >
                  {isCurrent && (
                    <motion.div
                      className="absolute inset-0 rounded-full bg-primary/10"
                      animate={{ scale: [1, 1.4, 1], opacity: [0.5, 0, 0.5] }}
                      transition={{ duration: 2, repeat: Infinity }}
                    />
                  )}
                  {i + 1}
                </div>
                <span
                  className={cn(
                    "whitespace-nowrap text-xs",
                    isComplete || isCurrent
                      ? "font-medium text-foreground"
                      : "text-muted-foreground",
                  )}
                >
                  {step.label}
                </span>
              </div>
              {i < TIMELINE_STEPS.length - 1 && (
                <div className="mx-2 mt-[-1.5rem] flex-1">
                  <div className="h-0.5 w-full overflow-hidden rounded-full bg-muted">
                    <motion.div
                      className="h-full bg-primary"
                      initial={{ width: "0%" }}
                      animate={{ width: isComplete ? "100%" : isCurrent ? "50%" : "0%" }}
                      transition={{ duration: 0.6, ease: "easeOut" }}
                    />
                  </div>
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
