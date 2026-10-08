import { useEffect, useMemo, useRef, useState } from "react"
import mermaid from "mermaid"
import { motion, AnimatePresence } from "framer-motion"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Download, FileImage, ChevronDown, ChevronUp, AlertCircle } from "lucide-react"
import type { LogicalSchema, LogicalSchemaAttribute } from "@/types/benchmark"
import { chartFilename } from "@/lib/chartDownload"

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
        if (!container) {
            console.warn("ERD download: no container ref")
            return
        }
        const svgEl = container.querySelector("svg") as SVGSVGElement | null
        if (!svgEl) {
            console.warn("ERD download: no <svg> found")
            return
        }

        try {
            // Resolve real pixel dimensions. Prefer viewBox (mermaid always sets it) over
            // getBoundingClientRect, which gets clamped by the scrolled wrapper.
            const vb = svgEl.viewBox.baseVal
            const bbox = svgEl.getBoundingClientRect()
            const width = Math.ceil(vb && vb.width ? vb.width : bbox.width || 800)
            const height = Math.ceil(vb && vb.height ? vb.height : bbox.height || 600)

            const clone = svgEl.cloneNode(true) as SVGSVGElement
            // Mermaid injects style="max-width: ...; width: 100%; ..." which overrides
            // our size attributes once the SVG is loaded in <img>. Strip it entirely
            // and set explicit pixel size + xmlns so Image() can rasterize.
            clone.removeAttribute("style")
            clone.setAttribute("width", String(width))
            clone.setAttribute("height", String(height))
            clone.setAttribute("xmlns", "http://www.w3.org/2000/svg")
            clone.setAttribute("xmlns:xlink", "http://www.w3.org/1999/xlink")

            // Mermaid's injected <style> uses CSS variables (--mermaid-*) that are only
            // defined in the live document; inside <img> they resolve to empty, giving
            // us invisible text. Replace with a self-contained stylesheet.
            const inlineStyle = document.createElementNS("http://www.w3.org/2000/svg", "style")
            inlineStyle.textContent = `
                * { font-family: ui-sans-serif, system-ui, -apple-system, sans-serif; }
                .er.entityLabel, .er.relationshipLabel, text { fill: #0f172a; }
                .er.entityBox { fill: #ffffff; stroke: #475569; stroke-width: 1px; }
                .er.attributeBoxOdd { fill: #f8fafc; stroke: #cbd5e1; }
                .er.attributeBoxEven { fill: #ffffff; stroke: #cbd5e1; }
                .er.relationshipLine { stroke: #475569; stroke-width: 1px; fill: none; }
                .er.relationshipLabelBox { fill: #ffffff; opacity: 0.9; }
            `
            clone.insertBefore(inlineStyle, clone.firstChild)

            const serialized = new XMLSerializer().serializeToString(clone)
            // encodeURIComponent → data URL dodges the Blob-URL + <img> taint quirk
            // some Chromium builds hit when the SVG embeds a <style> block.
            const dataUrl = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(serialized)}`

            const img = new Image()
            await new Promise<void>((resolve, reject) => {
                img.onload = () => resolve()
                img.onerror = () => reject(new Error("SVG failed to decode in <img>"))
                img.src = dataUrl
            })

            const scale = 2
            const canvas = document.createElement("canvas")
            canvas.width = width * scale
            canvas.height = height * scale
            const ctx = canvas.getContext("2d")
            if (!ctx) throw new Error("Could not get 2D context")
            ctx.fillStyle = "#ffffff"
            ctx.fillRect(0, 0, canvas.width, canvas.height)
            ctx.scale(scale, scale)
            ctx.drawImage(img, 0, 0, width, height)

            const pngBlob: Blob | null = await new Promise((resolve) =>
                canvas.toBlob((b) => resolve(b), "image/png"),
            )
            if (!pngBlob) throw new Error("canvas.toBlob returned null")

            const pngUrl = URL.createObjectURL(pngBlob)
            const a = document.createElement("a")
            a.href = pngUrl
            a.download = chartFilename("erd-diagram", "png")
            document.body.appendChild(a)
            a.click()
            document.body.removeChild(a)
            setTimeout(() => URL.revokeObjectURL(pngUrl), 1000)
        } catch (e) {
            console.error("ERD PNG download failed:", e)
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
