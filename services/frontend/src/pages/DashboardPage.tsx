import { useEffect, useState } from "react"
import { useAuth } from "@/lib/auth"
import { Avatar, AvatarImage, AvatarFallback } from "@/components/ui/avatar"
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuLabel,
} from "@/components/ui/dropdown-menu"
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
} from "@/components/ui/card"
import { Button } from "@/components/ui/button"

interface DashboardStats {
  totalBenchmarks: number
  activeDatabases: number
  avgScore: number
  lastRun: string
}

const defaultStats: DashboardStats = {
  totalBenchmarks: 0,
  activeDatabases: 0,
  avgScore: 0,
  lastRun: "N/A",
}

export function DashboardPage() {
  const { user, logout } = useAuth()
  const [stats, setStats] = useState<DashboardStats>(defaultStats)

  useEffect(() => {
    async function fetchStats() {
      try {
        const response = await fetch("/api/dashboard", {
          headers: {
            Authorization: `Bearer ${user?.token}`,
          },
        })
        if (response.ok) {
          const data = (await response.json()) as DashboardStats
          setStats(data)
        }
      } catch {
        // Keep default stats on error
      }
    }
    fetchStats()
  }, [user?.token])

  const initials = user?.name
    ? user.name
        .split(" ")
        .map((n) => n[0])
        .join("")
        .toUpperCase()
        .slice(0, 2)
    : "?"

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b border-border">
        <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-4">
          <h1 className="text-lg font-semibold text-foreground">DBagnets</h1>
          <DropdownMenu>
            <DropdownMenuTrigger>
              <Button variant="ghost" size="icon" className="rounded-full">
                <Avatar>
                  <AvatarImage src={user?.picture} alt={user?.name} />
                  <AvatarFallback>{initials}</AvatarFallback>
                </Avatar>
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" sideOffset={8}>
              <DropdownMenuLabel>
                <div className="flex flex-col gap-0.5">
                  <span className="text-sm font-medium">{user?.name}</span>
                  <span className="text-xs text-muted-foreground">
                    {user?.email}
                  </span>
                </div>
              </DropdownMenuLabel>
              <DropdownMenuSeparator />
              <DropdownMenuItem onSelect={logout}>Log out</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-4 py-8">
        <h2 className="mb-6 text-2xl font-semibold text-foreground">
          Dashboard
        </h2>

        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Card>
            <CardHeader>
              <CardDescription>Total Benchmarks</CardDescription>
              <CardTitle className="text-3xl">
                {stats.totalBenchmarks}
              </CardTitle>
            </CardHeader>
          </Card>

          <Card>
            <CardHeader>
              <CardDescription>Active Databases</CardDescription>
              <CardTitle className="text-3xl">
                {stats.activeDatabases}
              </CardTitle>
            </CardHeader>
          </Card>

          <Card>
            <CardHeader>
              <CardDescription>Average Score</CardDescription>
              <CardTitle className="text-3xl">{stats.avgScore}%</CardTitle>
            </CardHeader>
          </Card>

          <Card>
            <CardHeader>
              <CardDescription>Last Run</CardDescription>
              <CardTitle className="text-lg">{stats.lastRun}</CardTitle>
            </CardHeader>
          </Card>
        </div>

        <div className="mt-8">
          <Card>
            <CardHeader>
              <CardTitle>Recent Activity</CardTitle>
              <CardDescription>
                Your recent benchmark runs will appear here
              </CardDescription>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">
                No recent activity. Start a new benchmark to see results.
              </p>
            </CardContent>
          </Card>
        </div>
      </main>
    </div>
  )
}
