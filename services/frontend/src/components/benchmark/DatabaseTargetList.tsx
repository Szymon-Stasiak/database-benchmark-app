import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { X } from "lucide-react"
import { motion, AnimatePresence } from "framer-motion"
import type { DatabaseTarget } from "@/types/benchmark"
import { DATABASE_TYPE_LABELS } from "@/types/benchmark"

interface DatabaseTargetListProps {
  targets: DatabaseTarget[]
  onRemove: (index: number) => void
}

export function DatabaseTargetList({ targets, onRemove }: DatabaseTargetListProps) {
  if (targets.length === 0) {
    return (
      <p className="text-center py-8 text-muted-foreground text-sm">
        No databases added yet. Select a database type, name, and version above.
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      <AnimatePresence>
        {targets.map((target, index) => (
          <motion.div
            key={`${target.dbName}-${target.dbVersion}`}
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.2 }}
          >
            <div className="flex items-center justify-between p-3 border border-border rounded-lg bg-card">
              <div className="flex items-center gap-3">
                <Badge variant="secondary">{DATABASE_TYPE_LABELS[target.dbType]}</Badge>
                <span className="font-medium">{target.dbName}</span>
                <span className="text-sm text-muted-foreground">v{target.dbVersion}</span>
              </div>
              <Button variant="ghost" size="sm" onClick={() => onRemove(index)}>
                <X className="h-4 w-4" />
              </Button>
            </div>
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  )
}
