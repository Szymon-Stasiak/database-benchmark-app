import {
  Legend,
  PolarAngleAxis,
  PolarGrid,
  PolarRadiusAxis,
  Radar,
  RadarChart,
  ResponsiveContainer,
  Tooltip,
} from "recharts"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Radar as RadarIcon } from "lucide-react"
import type { RadarScore } from "@/types/comparison"

interface Props {
  scores: RadarScore[]
}

const PALETTE = [
  "#3b82f6",
  "#10b981",
  "#ef4444",
  "#a855f7",
  "#f97316",
  "#14b8a6",
  "#eab308",
  "#ec4899",
]

const AXES: { key: keyof RadarScore; label: string }[] = [
  { key: "insertSpeed", label: "Insert speed" },
  { key: "readSpeed", label: "Read speed" },
  { key: "deleteSpeed", label: "Delete speed" },
  { key: "sizeEfficiency", label: "Size freed" },
  { key: "consistency", label: "Consistency" },
]

export function ParadigmRadarChart({ scores }: Props) {
  if (scores.length === 0) {
    return (
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base inline-flex items-center gap-2">
            <RadarIcon className="h-4 w-4" />
            Paradigm positioning
          </CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">No runs yet.</p>
        </CardContent>
      </Card>
    )
  }

  const data = AXES.map((axis) => {
    const row: Record<string, number | string> = { axis: axis.label }
    for (const s of scores) {
      row[s.dbName] = roundTo(Number(s[axis.key]), 1)
    }
    return row
  })

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base inline-flex items-center gap-2">
          <RadarIcon className="h-4 w-4" />
          Paradigm positioning (0..100 normalized)
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="h-96">
          <ResponsiveContainer width="100%" height="100%">
            <RadarChart data={data}>
              <PolarGrid />
              <PolarAngleAxis dataKey="axis" tick={{ fontSize: 12 }} />
              <PolarRadiusAxis angle={30} domain={[0, 100]} tick={{ fontSize: 10 }} />
              {scores.map((s, i) => (
                <Radar
                  key={s.databaseId}
                  name={s.dbName}
                  dataKey={s.dbName}
                  stroke={PALETTE[i % PALETTE.length]}
                  fill={PALETTE[i % PALETTE.length]}
                  fillOpacity={0.18}
                />
              ))}
              <Tooltip />
              <Legend wrapperStyle={{ fontSize: 12 }} />
            </RadarChart>
          </ResponsiveContainer>
        </div>
      </CardContent>
    </Card>
  )
}

function roundTo(value: number, digits: number): number {
  const f = Math.pow(10, digits)
  return Math.round(value * f) / f
}
