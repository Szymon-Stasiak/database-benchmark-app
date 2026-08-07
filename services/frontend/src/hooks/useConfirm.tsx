import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from "react"
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { AlertTriangle } from "lucide-react"
import { cn } from "@/lib/utils"

type Variant = "default" | "destructive"

export interface ConfirmOptions {
  title: string
  description?: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  variant?: Variant
}

type Resolver = (value: boolean) => void

interface ConfirmState extends ConfirmOptions {
  open: boolean
}

interface ConfirmContextValue {
  confirm: (options: ConfirmOptions) => Promise<boolean>
}

const ConfirmContext = createContext<ConfirmContextValue | null>(null)

/**
 * Provider that renders a single AlertDialog and exposes an imperative
 * `confirm(...)` API — `await confirm({ title, description, variant })` returns
 * `true` if the user confirms, `false` otherwise. Replaces the four native
 * `window.confirm()` call sites with an on-brand modal.
 */
export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<ConfirmState>({
    open: false,
    title: "",
  })
  const resolverRef = useRef<Resolver | null>(null)

  const confirm = useCallback((options: ConfirmOptions) => {
    return new Promise<boolean>((resolve) => {
      resolverRef.current = resolve
      setState({ ...options, open: true })
    })
  }, [])

  const close = (result: boolean) => {
    setState((s) => ({ ...s, open: false }))
    resolverRef.current?.(result)
    resolverRef.current = null
  }

  const destructive = state.variant === "destructive"

  return (
    <ConfirmContext.Provider value={{ confirm }}>
      {children}
      <AlertDialog open={state.open} onOpenChange={(open) => (open ? null : close(false))}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <div className="flex items-start gap-3">
              {destructive && (
                <div className="mt-0.5 rounded-full bg-destructive/10 p-1.5 text-destructive">
                  <AlertTriangle className="h-4 w-4" />
                </div>
              )}
              <div className="flex-1">
                <AlertDialogTitle>{state.title}</AlertDialogTitle>
                {state.description && (
                  <AlertDialogDescription className="mt-2">
                    {state.description}
                  </AlertDialogDescription>
                )}
              </div>
            </div>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <Button variant="outline" onClick={() => close(false)}>
              {state.cancelLabel ?? "Cancel"}
            </Button>
            <Button
              variant={destructive ? "destructive" : "default"}
              onClick={() => close(true)}
              className={cn(destructive && "shadow-sm")}
              autoFocus
            >
              {state.confirmLabel ?? "Confirm"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ConfirmContext.Provider>
  )
}

export function useConfirm() {
  const ctx = useContext(ConfirmContext)
  if (!ctx) {
    throw new Error("useConfirm must be used within <ConfirmProvider>")
  }
  return ctx.confirm
}
