import { useState, useEffect } from "react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Download, Square, RotateCcw, FileText, Loader2, RefreshCw, Code, Trash2 } from "lucide-react"
import { AnimatePresence, motion } from "framer-motion"
import { toast } from "sonner"
import { benchmarkApi } from "@/lib/api"
import { useConfirm } from "@/hooks/useConfirm"
import { ContainerLogsDialog } from "@/components/benchmark/ContainerLogsDialog"
import { getDatabaseStatusConfig, cn } from "@/lib/utils"
import type { DatabaseResponse } from "@/types/benchmark"

interface DatabaseCardProps {
  database: DatabaseResponse
  benchmarkId: string
  scriptPreview?: string
  onStatusChange?: (databaseId: string, status: DatabaseResponse["status"]) => void
  onDelete?: (databaseId: string) => void
}

export function DatabaseCard({ database, benchmarkId, scriptPreview, onStatusChange, onDelete }: DatabaseCardProps) {
  const [logsOpen, setLogsOpen] = useState(false)
  const [scriptOpen, setScriptOpen] = useState(false)
  const [fullScript, setFullScript] = useState<string | null>(null)
  const [scriptLoading, setScriptLoading] = useState(false)
  const [actionLoading, setActionLoading] = useState<string | null>(null)
  const confirm = useConfirm()

  useEffect(() => {
    if (scriptOpen) {
      setScriptLoading(true)
      benchmarkApi.getFullScript(benchmarkId, database.id)
        .then(setFullScript)
        .catch(() => setFullScript("Failed to load script"))
        .finally(() => setScriptLoading(false))
    } else {
      setFullScript(null)
    }
  }, [scriptOpen, benchmarkId, database.id])

  const config = getDatabaseStatusConfig(database.status)
  const canDownloadScript = !["PENDING", "SCRIPT_GENERATING"].includes(database.status)
  const canManageContainer = ["RUNNING", "STOPPED"].includes(database.status)
  const hasScriptPreview = scriptPreview && canDownloadScript

  const handleStop = async () => {
    setActionLoading("stop")
    try {
      await benchmarkApi.stopDatabase(benchmarkId, database.id)
      onStatusChange?.(database.id, "STOPPED")
    } finally {
      setActionLoading(null)
    }
  }

  const handleRestart = async () => {
    setActionLoading("restart")
    try {
      await benchmarkApi.restartDatabase(benchmarkId, database.id)
      onStatusChange?.(database.id, "RUNNING")
    } finally {
      setActionLoading(null)
    }
  }

  const handleDelete = async () => {
    const ok = await confirm({
      title: `Delete ${database.dbName}?`,
      description: "The container will be stopped and removed. This action cannot be undone.",
      confirmLabel: "Delete container",
      variant: "destructive",
    })
    if (!ok) return
    setActionLoading("delete")
    try {
      await benchmarkApi.deleteDatabase(benchmarkId, database.id)
      onDelete?.(database.id)
      toast.success(`${database.dbName} container removed`)
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to remove container")
    } finally {
      setActionLoading(null)
    }
  }

  const handleRedeploy = async () => {
    setActionLoading("redeploy")
    try {
      await benchmarkApi.redeployDatabase(benchmarkId, database.id)
      onStatusChange?.(database.id, "CONTAINER_STARTING")
    } finally {
      setActionLoading(null)
    }
  }

  return (
    <>
      <Card className="h-full">
        <CardContent className="p-4">
          <div className="flex items-start justify-between">
            <div>
              <div className="flex items-center gap-2">
                <span className="font-semibold capitalize">{database.dbName}</span>
                <span className="text-sm text-muted-foreground">v{database.dbVersion}</span>
              </div>
              <p className="text-xs text-muted-foreground capitalize">
                {database.dbType.toLowerCase().replace("_", " ")}
              </p>
            </div>

            <div className="flex items-center gap-2">
              <AnimatePresence mode="wait">
                <motion.div
                  key={database.status}
                  initial={{ opacity: 0, scale: 0.9 }}
                  animate={{ opacity: 1, scale: 1 }}
                  exit={{ opacity: 0, scale: 0.9 }}
                  transition={{ duration: 0.2 }}
                >
                  <Badge className={cn("rounded-full px-2.5 py-0.5 text-xs font-medium border-0 inline-flex items-center gap-1", config.bgClass, config.textClass)}>
                    {config.animate && <Loader2 className="h-3 w-3 animate-spin" />}
                    {config.label}
                  </Badge>
                </motion.div>
              </AnimatePresence>

              {database.hostPort && database.status === "RUNNING" && (
                <Badge variant="outline" className="text-xs">Port: {database.hostPort}</Badge>
              )}

              <Button
                variant="ghost"
                size="icon"
                onClick={handleDelete}
                disabled={actionLoading === "delete"}
                title="Delete database"
                aria-label={`Delete ${database.dbName} container`}
                className="h-7 w-7 text-muted-foreground hover:text-destructive"
              >
                {actionLoading === "delete" ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Trash2 className="h-3.5 w-3.5" />}
              </Button>
            </div>
          </div>

          {database.errorMessage && (
            <p className="mt-2 text-sm text-destructive">{database.errorMessage}</p>
          )}

          <div className="flex items-center flex-wrap gap-2 mt-3">
            {hasScriptPreview && (
              <Button variant="outline" size="sm" onClick={() => setScriptOpen(true)} className="text-xs">
                <Code className="h-3.5 w-3.5 mr-1" />
                View Script
              </Button>
            )}

            {canDownloadScript && (
              <Button variant="outline" size="sm" onClick={() => benchmarkApi.downloadScript(benchmarkId, database.id)} title="Download initialization script" className="text-xs">
                <Download className="h-3.5 w-3.5 mr-1" />
                Download
              </Button>
            )}

            {database.status === "FAILED" && (
              <Button variant="outline" size="sm" onClick={handleRedeploy} disabled={actionLoading === "redeploy"} title="Redeploy container" className="text-xs">
                {actionLoading === "redeploy" ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5 mr-1" />}
                Redeploy
              </Button>
            )}

            {canManageContainer && (
              <>
                {database.status === "RUNNING" && (
                  <Button variant="outline" size="sm" onClick={handleStop} disabled={actionLoading === "stop"} title="Stop container" className="text-xs">
                    {actionLoading === "stop" ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Square className="h-3.5 w-3.5 mr-1" />}
                    Stop
                  </Button>
                )}

                <Button variant="outline" size="sm" onClick={handleRestart} disabled={actionLoading === "restart"} title="Redeploy container" className="text-xs">
                  {actionLoading === "restart" ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5 mr-1" />}
                  Redeploy
                </Button>

                <Button variant="outline" size="sm" onClick={() => setLogsOpen(true)} title="View container logs" className="text-xs">
                  <FileText className="h-3.5 w-3.5 mr-1" />
                  Logs
                </Button>
              </>
            )}
          </div>
        </CardContent>
      </Card>

      <Dialog open={scriptOpen} onOpenChange={setScriptOpen}>
        <DialogContent className="max-w-3xl max-h-[80vh] flex flex-col">
          <DialogHeader className="flex-row items-center justify-between gap-4">
            <DialogTitle>Script: {database.dbName}</DialogTitle>
            <Button variant="outline" size="sm" onClick={() => benchmarkApi.downloadScript(benchmarkId, database.id)}>
              <Download className="h-4 w-4 mr-1" />
              Download
            </Button>
          </DialogHeader>
          <pre className="flex-1 overflow-auto rounded-md bg-muted p-4 font-mono text-xs text-muted-foreground whitespace-pre-wrap break-all min-h-48 max-h-[60vh]">
            {scriptLoading ? "Loading script..." : fullScript || "No script available"}
          </pre>
        </DialogContent>
      </Dialog>

      <ContainerLogsDialog
        open={logsOpen}
        onOpenChange={setLogsOpen}
        benchmarkId={benchmarkId}
        databaseId={database.id}
        databaseName={database.dbName}
      />
    </>
  )
}
