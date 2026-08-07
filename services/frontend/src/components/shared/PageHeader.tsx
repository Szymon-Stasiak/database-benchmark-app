import type { ReactNode } from "react"
import type { LucideIcon } from "lucide-react"
import { motion } from "framer-motion"

interface Props {
  icon?: LucideIcon
  title: string
  subtitle?: ReactNode
  actions?: ReactNode
  eyebrow?: ReactNode
}

/**
 * Standard page header used across every benchmark subroute. Provides the
 * gradient icon, tight typography and optional right-aligned action slot.
 */
export function PageHeader({ icon: Icon, title, subtitle, actions, eyebrow }: Props) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25 }}
      className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between"
    >
      <div className="min-w-0 flex-1">
        {eyebrow && (
          <div className="mb-1 text-xs font-medium uppercase tracking-wider text-muted-foreground">
            {eyebrow}
          </div>
        )}
        <h1 className="flex items-center gap-3 text-2xl font-semibold tracking-tight">
          {Icon && (
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-primary/15 to-primary/5 text-primary ring-1 ring-primary/10">
              <Icon className="h-5 w-5" />
            </span>
          )}
          <span className="truncate">{title}</span>
        </h1>
        {subtitle && (
          <div className="mt-1.5 text-sm text-muted-foreground">{subtitle}</div>
        )}
      </div>
      {actions && <div className="flex flex-shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </motion.div>
  )
}
