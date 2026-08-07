import type { ReactNode } from "react"
import type { LucideIcon } from "lucide-react"
import { motion } from "framer-motion"
import { cn } from "@/lib/utils"

interface Props {
  icon?: LucideIcon
  title: string
  description?: ReactNode
  action?: ReactNode
  className?: string
  compact?: boolean
}

/**
 * Unified empty-state block. Renders a soft radial glow behind an icon so
 * "nothing here" moments still feel intentional rather than broken.
 */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
  compact = false,
}: Props) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3, ease: "easeOut" }}
      className={cn(
        "relative flex flex-col items-center justify-center rounded-xl border border-dashed border-border/70 bg-card/40 text-center",
        compact ? "px-4 py-8" : "px-6 py-12",
        className,
      )}
    >
      {Icon && (
        <div className="relative mb-4">
          <div className="absolute inset-0 -m-4 rounded-full bg-primary/10 blur-2xl" aria-hidden />
          <div className="relative flex h-12 w-12 items-center justify-center rounded-full bg-gradient-to-br from-primary/20 to-primary/5 text-primary ring-1 ring-primary/20">
            <Icon className="h-6 w-6" />
          </div>
        </div>
      )}
      <h3 className="text-base font-semibold tracking-tight">{title}</h3>
      {description && (
        <p className="mt-1.5 max-w-md text-sm text-muted-foreground">{description}</p>
      )}
      {action && <div className="mt-5">{action}</div>}
    </motion.div>
  )
}
