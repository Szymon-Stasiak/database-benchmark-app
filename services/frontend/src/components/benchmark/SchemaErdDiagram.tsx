import { useEffect, useMemo, useRef, useState } from "react"
import mermaid from "mermaid"
import { motion, AnimatePresence } from "framer-motion"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Download, FileImage, ChevronDown, ChevronUp, AlertCircle } from "lucide-react"
import type { LogicalSchema, LogicalSchemaAttribute } from "@/types/benchmark"

mermaid.initialize({
    startOnLoad: false,
    theme: "default",
    securityLevel: "loose",
    er: {
        diagramPadding: 20,
        layoutDirection: "TB",
        minEntityWidth: 120,
        minEntityHeight: 80,
        entityPadding: 15,
        fontSize: 14,
    },
})

const CARDINALITY_TO_MERMAID: Record<string, string> = {
    "1:1": "||--||",
    "1:N": "||--o{",
    "1:M": "||--o{",
    "N:1": "}o--||",
    "M:1": "}o--||",
    "M:N": "}o--o{",
    "N:M": "}o--o{",
}

/**
 * Mermaid ERD identifiers must be a bare word — no whitespace, no dashes.
 * We normalise entity names but keep the original as the display label.
 */
function toMermaidId(name: string): string {
    return name.replace(/[^A-Za-z0-9_]/g, "_")
}

function attributeType(attr: LogicalSchemaAttribute): string {
    // Mermaid ER doesn't like some upstream type names — normalise to a
    // compact identifier that reads well in the diagram.
    return attr.data_type.toLowerCase().replace(/[^a-z0-9_]/g, "_")
}

function buildMermaidDefinition(schema: LogicalSchema): string {
    const lines: string[] = ["erDiagram"]

    for (const entity of schema.entities) {
        const id = toMermaidId(entity.name)
        lines.push(`  ${id} {`)
        for (const attr of entity.attributes) {
            const type = attributeType(attr)
            const marks: string[] = []
            if (attr.constraints.is_primary_key) marks.push("PK")
            if (attr.constraints.is_unique && !attr.constraints.is_primary_key)
                marks.push("UK")
            const suffix = marks.length ? ` ${marks.join(",")}` : ""
            lines.push(`    ${type} ${attr.name}${suffix}`)
        }
        lines.push("  }")
    }

    for (const rel of schema.relationships) {
        const source = toMermaidId(rel.source_entity)
        const target = toMermaidId(rel.target_entity)
        const connector = CARDINALITY_TO_MERMAID[rel.cardinality] ?? "||--o{"
        const label = rel.name.replace(/[^A-Za-z0-9_]/g, "_") || "rel"
        lines.push(`  ${source} ${connector} ${target} : ${label}`)
    }

    return lines.join("\n")
}

interface Props {
    logicalSchemaJson: string
}

export function SchemaErdDiagram({ logicalSchemaJson }: Props) {
    const containerRef = useRef<HTMLDivElement>(null)
    const [svg, setSvg] = useState<string>("")
    const [error, setError] = useState<string | null>(null)
    const [expanded, setExpanded] = useState(true)

    const schema = useMemo<LogicalSchema | null>(() => {
        try {
            return JSON.parse(logicalSchemaJson)
        } catch {
            return null
        }
    }, [logicalSchemaJson])

    const definition = useMemo(
        () => (schema ? buildMermaidDefinition(schema) : ""),
        [schema],
    )

    useEffect(() => {
        if (!definition) return
        let cancelled = false
        const uid = `erd-${Math.random().toString(36).slice(2, 10)}`

        mermaid
            .render(uid, definition)
            .then((result) => {
                if (!cancelled) {
                    setSvg(result.svg)
                    setError(null)
                }
            })
            .catch((err: Error) => {
                if (!cancelled) {
                    setError(err.message ?? "Failed to render diagram")
                    setSvg("")
                }
            })

        return () => {
            cancelled = true
        }
    }, [definition])

    if (!schema || schema.entities.length === 0) return null

    const downloadPng = async () => {
        const container = containerRef.current
        if (!container) return
        const svgEl = container.querySelector("svg")
        if (!svgEl) return

        // Clone to avoid mutating the live DOM while we inline size + backgrounds.
        const clone = svgEl.cloneNode(true) as SVGSVGElement
        const bbox = svgEl.getBoundingClientRect()
        const width = Math.max(bbox.width, 800)
        const height = Math.max(bbox.height, 400)
        clone.setAttribute("width", String(width))
        clone.setAttribute("height", String(height))
        clone.setAttribute("xmlns", "http://www.w3.org/2000/svg")

        // Give the exported PNG a white background — otherwise transparent
        // SVGs look terrible when opened in image viewers.
        const rect = document.createElementNS("http://www.w3.org/2000/svg", "rect")
        rect.setAttribute("width", "100%")
        rect.setAttribute("height", "100%")
        rect.setAttribute("fill", "white")
        clone.insertBefore(rect, clone.firstChild)

        const serialized = new XMLSerializer().serializeToString(clone)
        const blob = new Blob([serialized], { type: "image/svg+xml;charset=utf-8" })
        const url = URL.createObjectURL(blob)

        try {
            const img = new Image()
            img.crossOrigin = "anonymous"
            await new Promise<void>((resolve, reject) => {
                img.onload = () => resolve()
                img.onerror = () => reject(new Error("Failed to load SVG for PNG conversion"))
                img.src = url
            })

            // Render at 2x for crisp output on hi-DPI screens.
            const scale = 2
            const canvas = document.createElement("canvas")
            canvas.width = width * scale
            canvas.height = height * scale
            const ctx = canvas.getContext("2d")
            if (!ctx) return
            ctx.scale(scale, scale)
            ctx.drawImage(img, 0, 0, width, height)

            canvas.toBlob((pngBlob) => {
                if (!pngBlob) return
                const pngUrl = URL.createObjectURL(pngBlob)
                const a = document.createElement("a")
                a.href = pngUrl
                a.download = "erd-diagram.png"
                a.click()
                URL.revokeObjectURL(pngUrl)
            }, "image/png")
        } finally {
            URL.revokeObjectURL(url)
        }
    }

    const downloadSvg = () => {
        const container = containerRef.current
        if (!container) return
        const svgEl = container.querySelector("svg")
        if (!svgEl) return

        const serialized = new XMLSerializer().serializeToString(svgEl)
        const blob = new Blob(
            [`<?xml version="1.0" standalone="no"?>\n${serialized}`],
            { type: "image/svg+xml;charset=utf-8" },
        )
        const url = URL.createObjectURL(blob)
        const a = document.createElement("a")
        a.href = url
        a.download = "erd-diagram.svg"
        a.click()
        URL.revokeObjectURL(url)
    }

    return (
        <div className="mb-6">
            <div className="mb-4 flex items-center justify-between">
                <button
                    onClick={() => setExpanded(!expanded)}
                    className="flex items-center gap-2 text-lg font-semibold transition-colors hover:text-primary"
                >
                    {expanded ? (
                        <ChevronUp className="h-5 w-5" />
                    ) : (
                        <ChevronDown className="h-5 w-5" />
                    )}
                    ERD Diagram
                    <Badge variant="outline" className="ml-1 text-xs font-normal">
                        {schema.entities.length} entities · {schema.relationships.length} relationships
                    </Badge>
                </button>
                <div className="flex gap-2">
                    <Button
                        variant="outline"
                        size="sm"
                        onClick={downloadSvg}
                        disabled={!svg}
                        className="text-xs"
                    >
                        <Download className="mr-1 h-3.5 w-3.5" />
                        SVG
                    </Button>
                    <Button
                        variant="outline"
                        size="sm"
                        onClick={downloadPng}
                        disabled={!svg}
                        className="text-xs"
                    >
                        <FileImage className="mr-1 h-3.5 w-3.5" />
                        PNG
                    </Button>
                </div>
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
                        {error ? (
                            <div className="flex items-center gap-2 rounded-lg border border-destructive/40 bg-destructive/5 p-4 text-sm text-destructive">
                                <AlertCircle className="h-4 w-4 shrink-0" />
                                <div>
                                    <div className="font-medium">Failed to render diagram</div>
                                    <div className="text-xs opacity-80">{error}</div>
                                </div>
                            </div>
                        ) : (
                            <div className="rounded-lg border border-border bg-card p-4">
                                <div
                                    ref={containerRef}
                                    className="overflow-x-auto [&_svg]:mx-auto [&_svg]:h-auto [&_svg]:max-w-full"
                                    dangerouslySetInnerHTML={{ __html: svg }}
                                />
                            </div>
                        )}
                    </motion.div>
                )}
            </AnimatePresence>
        </div>
    )
}
