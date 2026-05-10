import { useState } from "react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Download, Square, RotateCcw, FileText, Loader2, RefreshCw, ChevronDown, ChevronUp } from "lucide-react"
import { motion, AnimatePresence } from "framer-motion"
import { benchmarkApi } from "@/lib/api"
import { ContainerLogsDialog } from "@/components/benchmark/ContainerLogsDialog"
import { getDatabaseStatusConfig, cn } from "@/lib/utils"
import type { DatabaseResponse } from "@/types/benchmark"

interface DatabaseCardProps {
  database: DatabaseResponse
  benchmarkId: string
  scriptPreview?: string
  onStatusChange?: (databaseId: string, status: DatabaseResponse["status"]) => void
}

export function DatabaseCard({ database, benchmarkId, scriptPreview, onStatusChange }: DatabaseCardProps) {
  const [logsOpen, setLogsOpen] = useState(false)
  const [actionLoading, setActionLoading] = useState<string | null>(null)
  const [showScript, setShowScript] = useState(false)

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
          <div className="flex items-center justify-between">
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
            </div>
          </div>

          {database.errorMessage && (
            <p className="mt-2 text-sm text-destructive">{database.errorMessage}</p>
          )}

          {/* Script Preview */}
          <AnimatePresence>
            {hasScriptPreview && showScript && (
              <motion.div
                initial={{ opacity: 0, height: 0 }}
                animate={{ opacity: 1, height: "auto" }}
                exit={{ opacity: 0, height: 0 }}
                transition={{ duration: 0.25 }}
                className="overflow-hidden"
              >
                <pre className="mt-3 rounded-md bg-muted p-3 text-xs font-mono text-muted-foreground overflow-x-auto max-h-48 overflow-y-auto whitespace-pre-wrap break-all">
                  {scriptPreview}
                </pre>
              </motion.div>
            )}
          </AnimatePresence>

          <div className="flex items-center flex-wrap gap-2 mt-3">
            {hasScriptPreview && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowScript(!showScript)}
                className="text-xs"
              >
                {showScript ? <ChevronUp className="h-3.5 w-3.5 mr-1" /> : <ChevronDown className="h-3.5 w-3.5 mr-1" />}
                {showScript ? "Hide Script" : "View Script"}
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

                <Button variant="outline" size="sm" onClick={handleRestart} disabled={actionLoading === "restart"} title="Restart container" className="text-xs">
                  {actionLoading === "restart" ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5 mr-1" />}
                  Restart
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
