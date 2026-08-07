import { useNavigate } from "react-router-dom"
import { BarChart3, Beaker, Eraser, FlaskConical, Search } from "lucide-react"
import type { LucideIcon } from "lucide-react"
import { motion } from "framer-motion"
import { cn } from "@/lib/utils"

interface Action {
  key: string
  title: string
  description: string
  icon: LucideIcon
  route: string
  requiresRunning: boolean
  tone: "primary" | "accent" | "danger" | "neutral"
  emphasis?: boolean
}

const ACTIONS: Action[] = [
  {
    key: "inserts",
    title: "Insert benchmark",
    description: "Populate databases with generated cascade-aware data.",
    icon: FlaskConical,
    route: "inserts",
    requiresRunning: true,
    tone: "primary",
    emphasis: true,
  },
  {
    key: "reads",
    title: "Read benchmark",
    description: "Measure per-target lookup latency with configurable depth.",
    icon: Search,
    route: "reads",
    requiresRunning: true,
    tone: "accent",
  },
  {
    key: "deletes",
    title: "Delete benchmark",
    description: "Time root/cascade deletion across the same working set.",
    icon: Eraser,
    route: "deletes",
    requiresRunning: true,
    tone: "danger",
  },
  {
    key: "scenarios",
    title: "Run scenarios",
    description: "Aggregate, range and traversal queries across engines.",
    icon: Beaker,
    route: "scenarios",
    requiresRunning: true,
    tone: "neutral",
  },
  {
    key: "comparison",
    title: "Comparison report",
    description: "Radar + summary tables. Export to CSV / JSON.",
    icon: BarChart3,
    route: "comparison",
    requiresRunning: false,
    tone: "neutral",
  },
]

const TONE_CLASSES: Record<Action["tone"], string> = {
  primary: "from-primary/20 to-primary/5 text-primary ring-primary/20",
  accent: "from-status-info-bg to-transparent text-status-info-text ring-status-info-text/20",
  danger:
    "from-destructive/15 to-destructive/5 text-destructive ring-destructive/20",
  neutral: "from-muted/80 to-muted/30 text-foreground ring-border",
}

interface Props {
  benchmarkId: string
  anyRunning: boolean
}

export function BenchmarkActionCards({ benchmarkId, anyRunning }: Props) {
  const navigate = useNavigate()

  return (
    <div className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {ACTIONS.map((action, i) => {
        const disabled = action.requiresRunning && !anyRunning
        const Icon = action.icon
        return (
          <motion.button
            key={action.key}
            type="button"
            onClick={() => navigate(`/benchmarks/${benchmarkId}/${action.route}`)}
            disabled={disabled}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.04, duration: 0.28 }}
            whileHover={disabled ? undefined : { y: -2 }}
            className={cn(
              "group relative overflow-hidden rounded-xl border border-border bg-card p-5 text-left transition-all",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              disabled
                ? "cursor-not-allowed opacity-50"
                : "hover:border-primary/30 hover:shadow-lg",
              action.emphasis && !disabled && "ring-1 ring-primary/20 shadow-md",
            )}
          >
            <div
              className={cn(
                "pointer-events-none absolute -right-8 -top-8 h-32 w-32 rounded-full bg-gradient-to-br opacity-70 blur-2xl transition-opacity group-hover:opacity-100",
                TONE_CLASSES[action.tone],
              )}
              aria-hidden
            />
            <div className="relative flex items-start gap-4">
              <div
                className={cn(
                  "flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br ring-1 transition-transform group-hover:scale-110",
                  TONE_CLASSES[action.tone],
                )}
              >
                <Icon className="h-5 w-5" />
              </div>
              <div className="min-w-0 flex-1">
                <div className="font-semibold tracking-tight">{action.title}</div>
                <div className="mt-0.5 text-xs text-muted-foreground">
                  {action.description}
                </div>
              </div>
            </div>
          </motion.button>
        )
      })}
    </div>
  )
}
