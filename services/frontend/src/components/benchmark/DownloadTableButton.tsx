import { useState } from "react"
import { Download, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { downloadTableAsPng } from "@/lib/chartDownload"

interface Props {
  containerRef: React.RefObject<HTMLElement | null>
  tableName: string
  className?: string
}

export function DownloadTableButton({ containerRef, tableName, className }: Props) {
  const [busy, setBusy] = useState(false)
  const onClick = async () => {
    setBusy(true)
    try {
      await downloadTableAsPng(containerRef.current, tableName)
    } catch (e) {
      console.warn("Table download failed:", e)
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
      title="Download table as PNG"
    >
      {busy ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Download className="h-3.5 w-3.5" />}
    </Button>
  )
}
