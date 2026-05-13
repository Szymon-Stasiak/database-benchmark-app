import { useState, useMemo } from "react"
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"
import { Download, ChevronDown, ChevronUp, ArrowRight, Key, Fingerprint, Search } from "lucide-react"
import { motion, AnimatePresence } from "framer-motion"
import { cn } from "@/lib/utils"
import type { LogicalSchema, LogicalSchemaAttribute } from "@/types/benchmark"

function ConstraintBadges({ attr }: { attr: LogicalSchemaAttribute }) {
  const c = attr.constraints
  return (
    <div className="flex items-center gap-1">
      {c.is_primary_key && (
        <TooltipProvider delayDuration={200}>
          <Tooltip>
            <TooltipTrigger>
              <Key className="h-3 w-3 text-primary" />
            </TooltipTrigger>
            <TooltipContent side="top"><p>Primary Key</p></TooltipContent>
          </Tooltip>
        </TooltipProvider>
      )}
      {c.is_unique && !c.is_primary_key && (
        <TooltipProvider delayDuration={200}>
          <Tooltip>
            <TooltipTrigger>
              <Fingerprint className="h-3 w-3 text-accent-foreground" />
            </TooltipTrigger>
            <TooltipContent side="top"><p>Unique</p></TooltipContent>
          </Tooltip>
        </TooltipProvider>
      )}
      {c.is_indexed && !c.is_primary_key && (
        <TooltipProvider delayDuration={200}>
          <Tooltip>
            <TooltipTrigger>
              <Search className="h-3 w-3 text-status-info-text" />
            </TooltipTrigger>
            <TooltipContent side="top"><p>Indexed</p></TooltipContent>
          </Tooltip>
        </TooltipProvider>
      )}
    </div>
  )
}

const CARDINALITY_COLORS: Record<string, string> = {
  "1:1": "bg-status-info-bg text-status-info-text",
  "1:N": "bg-status-progress-bg text-status-progress-text",
  "M:N": "bg-status-ready-bg text-status-ready-text",
}

function useSchema(logicalSchemaJson: string) {
  return useMemo<LogicalSchema | null>(() => {
    try {
      return JSON.parse(logicalSchemaJson)
    } catch {
      return null
    }
  }, [logicalSchemaJson])
}

interface SchemaSectionProps {
  logicalSchemaJson: string
}

export function SchemaRelationships({ logicalSchemaJson }: SchemaSectionProps) {
  const schema = useSchema(logicalSchemaJson)
  if (!schema || schema.relationships.length === 0) return null

  return (
    <div className="mb-6">
      <h3 className="text-lg font-semibold mb-3">
        Relationships ({schema.relationships.length})
      </h3>
      <div className="flex flex-wrap gap-2">
        {schema.relationships.map((rel, i) => (
          <motion.div
            key={rel.name}
            initial={{ opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ delay: i * 0.04, duration: 0.25 }}
            className="flex items-center gap-1.5 rounded-lg border border-border bg-card px-3 py-1.5 text-xs"
          >
            <span className="font-medium capitalize">{rel.source_entity}</span>
            <Badge className={cn(
              "rounded-full px-1.5 py-0 text-[10px] font-mono border-0",
              CARDINALITY_COLORS[rel.cardinality] ?? "bg-muted text-muted-foreground",
            )}>
              {rel.cardinality}
            </Badge>
            <ArrowRight className="h-3 w-3 text-muted-foreground" />
            <span className="font-medium capitalize">{rel.target_entity}</span>
          </motion.div>
        ))}
      </div>
    </div>
  )
}

export function SchemaEntities({ logicalSchemaJson }: SchemaSectionProps) {
  const schema = useSchema(logicalSchemaJson)
  const [expanded, setExpanded] = useState(true)

  if (!schema) return null

  const handleDownload = () => {
    const blob = new Blob([JSON.stringify(schema, null, 2)], { type: "application/json" })
    const url = URL.createObjectURL(blob)
    const a = document.createElement("a")
    a.href = url
    a.download = "logical-schema.json"
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="mb-6">
      <div className="flex items-center justify-between mb-4">
        <button
          onClick={() => setExpanded(!expanded)}
          className="flex items-center gap-2 text-lg font-semibold hover:text-primary transition-colors"
        >
          {expanded ? <ChevronUp className="h-5 w-5" /> : <ChevronDown className="h-5 w-5" />}
          Logical Schema
          <Badge variant="outline" className="text-xs font-normal ml-1">
            {schema.entities.length} entities
          </Badge>
        </button>
        <Button variant="outline" size="sm" onClick={handleDownload} className="text-xs">
          <Download className="h-3.5 w-3.5 mr-1" />
          Download JSON
        </Button>
      </div>

      <AnimatePresence>
        {expanded && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.3 }}
            className="overflow-hidden"
          >
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
              {schema.entities.map((entity, i) => (
                <motion.div
                  key={entity.name}
                  initial={{ opacity: 0, y: 16 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: i * 0.06, duration: 0.3 }}
                >
                  <Card className="h-full">
                    <CardHeader className="pb-2">
                      <CardTitle className="text-sm font-semibold capitalize">
                        {entity.name}
                      </CardTitle>
                      {entity.description && (
                        <CardDescription className="text-xs">
                          {entity.description}
                        </CardDescription>
                      )}
                    </CardHeader>
                    <CardContent className="pt-0">
                      <div className="space-y-1">
                        {entity.attributes.map((attr) => (
                          <div
                            key={attr.name}
                            className={cn(
                              "flex items-center justify-between py-1 px-2 rounded text-xs",
                              attr.constraints.is_primary_key && "bg-primary/5",
                            )}
                          >
                            <div className="flex items-center gap-2 min-w-0">
                              <ConstraintBadges attr={attr} />
                              <span className="font-medium truncate">{attr.name}</span>
                            </div>
                            <span className="text-muted-foreground font-mono text-[10px] shrink-0 ml-2">
                              {attr.data_type}
                            </span>
                          </div>
                        ))}
                      </div>
                    </CardContent>
                  </Card>
                </motion.div>
              ))}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
