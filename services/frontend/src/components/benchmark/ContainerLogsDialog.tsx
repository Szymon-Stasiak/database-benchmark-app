import { useState, useEffect, useRef } from "react"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { RefreshCw, Loader2 } from "lucide-react"
import { benchmarkApi } from "@/lib/api"

interface ContainerLogsDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  benchmarkId: string
  databaseId: string
  databaseName: string
}

export function ContainerLogsDialog({
  open,
  onOpenChange,
  benchmarkId,
  databaseId,
  databaseName,
}: ContainerLogsDialogProps) {
  const [logs, setLogs] = useState("")
  const [loading, setLoading] = useState(false)
  const scrollRef = useRef<HTMLPreElement>(null)

  const fetchLogs = async () => {
    setLoading(true)
    try {
      const response = await benchmarkApi.getLogs(benchmarkId, databaseId)
      setLogs(response.logs)
    } catch (e) {
      setLogs(`Failed to fetch logs: ${e instanceof Error ? e.message : "Unknown error"}`)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (open) {
      fetchLogs()
    } else {
      setLogs("")
    }
  }, [open, benchmarkId, databaseId])

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [logs])

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-3xl max-h-[80vh] flex flex-col">
        <DialogHeader className="flex-row items-center justify-between gap-4">
          <DialogTitle>Logs: {databaseName}</DialogTitle>
          <Button variant="outline" size="sm" onClick={fetchLogs} disabled={loading}>
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            <span className="ml-1">Refresh</span>
          </Button>
        </DialogHeader>
        <pre
          ref={scrollRef}
          className="flex-1 overflow-auto rounded-md bg-muted p-4 font-mono text-xs text-muted-foreground whitespace-pre-wrap break-all min-h-48 max-h-[60vh]"
        >
          {loading && !logs ? "Loading logs..." : logs || "No logs available"}
        </pre>
      </DialogContent>
    </Dialog>
  )
}
