import { ArrowLeft } from "lucide-react"
import { useNavigate } from "react-router-dom"
import { Button } from "@/components/ui/button"

interface Props {
  to: string
  label?: string
  className?: string
}

export function BackButton({ to, label = "Back", className }: Props) {
  const navigate = useNavigate()
  return (
    <Button
      variant="ghost"
      size="sm"
      onClick={() => navigate(to)}
      className={
        "mb-4 -ml-2 gap-1.5 text-muted-foreground transition-transform hover:-translate-x-0.5 hover:text-foreground " +
        (className ?? "")
      }
    >
      <ArrowLeft className="h-4 w-4" />
      {label}
    </Button>
  )
}
