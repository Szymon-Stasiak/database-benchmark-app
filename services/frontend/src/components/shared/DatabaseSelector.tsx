import { cn } from "@/lib/utils"
import { Label } from "@/components/ui/label"

export interface RunnableDatabase {
  id: string
  dbName: string
  dbVersion: string
}

interface Props {
  runnableDatabases: RunnableDatabase[]
  selectedDbIds: Set<string>
  onToggle: (id: string) => void
  label?: string
  emptyText?: string
}

/**
 * Grid of database toggles used by every run form (Insert / Read / Delete).
 * Extracted so the three forms stop diverging on visual details.
 */
export function DatabaseSelector({
  runnableDatabases,
  selectedDbIds,
  onToggle,
  label = "Run against",
  emptyText = "No databases are RUNNING right now.",
}: Props) {
  return (
    <div className="space-y-2">
      <Label>
        {label} ({selectedDbIds.size} selected)
      </Label>
      {runnableDatabases.length === 0 ? (
        <p className="text-sm text-muted-foreground">{emptyText}</p>
      ) : (
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {runnableDatabases.map((db) => {
            const active = selectedDbIds.has(db.id)
            return (
              <button
                key={db.id}
                type="button"
                onClick={() => onToggle(db.id)}
                aria-pressed={active}
                className={cn(
                  "group flex items-center justify-between rounded-lg border px-3 py-2 text-left transition-all",
                  active
                    ? "border-primary bg-primary/5 ring-1 ring-primary shadow-sm"
                    : "border-border hover:border-foreground/30 hover:bg-muted/40",
                )}
              >
                <div>
                  <div className="text-sm font-medium capitalize">{db.dbName}</div>
                  <div className="text-xs text-muted-foreground">v{db.dbVersion}</div>
                </div>
                <span
                  className={cn(
                    "flex h-4 w-4 items-center justify-center rounded-sm border transition-colors",
                    active ? "border-primary bg-primary" : "border-border",
                  )}
                  aria-hidden
                >
                  {active && (
                    <svg viewBox="0 0 12 12" className="h-3 w-3 text-primary-foreground" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M2.5 6.5L5 9L9.5 3.5" />
                    </svg>
                  )}
                </span>
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
