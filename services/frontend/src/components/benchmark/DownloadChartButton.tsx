import { useState } from "react"
import { Download, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { downloadChartAsPng } from "@/lib/chartDownload"

interface Props {
  /** Ref or function returning the container element holding the <svg>. */
  containerRef: React.RefObject<HTMLElement | null>
  /** Stable, kebab-cased base name. The button appends YYYY-MM-DD_HH-mm-ss prefix. */
  chartName: string
  className?: string
}

export function DownloadChartButton({ containerRef, chartName, className }: Props) {
  const [busy, setBusy] = useState(false)
  const onClick = async () => {
    setBusy(true)
    try {
      await downloadChartAsPng(containerRef.current, chartName)
    } catch (e) {
      console.warn("Chart download failed:", e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Button
      variant="ghost"
      size="sm"
      onClick={onClick}
      disabled={busy}
      className={`h-7 px-2 ${className ?? ""}`}
      title="Download PNG"
    >
      {busy ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Download className="h-3.5 w-3.5" />}
    </Button>
  )
}
