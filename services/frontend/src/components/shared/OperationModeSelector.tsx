import { cn } from "@/lib/utils"
import { Label } from "@/components/ui/label"

export interface ModeOption<T extends string> {
  value: T
  label: string
  description?: string
}

interface Props<T extends string> {
  label?: string
  value: T
  options: readonly ModeOption<T>[]
  onChange: (value: T) => void
  columns?: 2 | 3 | 4
}

/**
 * Segmented button-group for SINGLE / BATCH / BULK (or any similar finite
 * enum). Extracted from Insert/Read/Delete run forms.
 */
export function OperationModeSelector<T extends string>({
  label = "Execution mode",
  value,
  options,
  onChange,
  columns = 3,
}: Props<T>) {
  const gridCols =
    columns === 4 ? "grid-cols-2 sm:grid-cols-4" : columns === 2 ? "grid-cols-2" : "grid-cols-3"
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      <div className={cn("grid gap-2", gridCols)}>
        {options.map((opt) => {
          const active = opt.value === value
          return (
            <button
              key={opt.value}
              type="button"
              onClick={() => onChange(opt.value)}
              aria-pressed={active}
              className={cn(
                "rounded-md border px-3 py-2 text-left text-sm transition-all",
                active
                  ? "border-primary bg-primary/5 ring-1 ring-primary shadow-sm"
                  : "border-border hover:border-foreground/30 hover:bg-muted/40",
              )}
            >
              <div className="font-medium">{opt.label}</div>
              {opt.description && (
                <div className="text-[11px] leading-tight text-muted-foreground">
                  {opt.description}
                </div>
              )}
            </button>
          )
        })}
      </div>
    </div>
  )
}
