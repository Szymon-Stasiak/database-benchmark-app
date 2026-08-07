import type { ReactNode } from "react"
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
import { Button } from "@/components/ui/button"
import { ChevronRight, FlaskConical } from "lucide-react"
import { Link, useNavigate, useLocation } from "react-router-dom"
import { DarkModeToggle } from "@/components/shared/DarkModeToggle"
import { cn } from "@/lib/utils"

export interface Crumb {
  label: string
  to?: string
}

interface AppLayoutProps {
  children: ReactNode
  maxWidth?: "default" | "narrow"
  breadcrumbs?: Crumb[]
}

export function AppLayout({ children, maxWidth = "default", breadcrumbs }: AppLayoutProps) {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const initials = user?.name
    ? user.name
        .split(" ")
        .map((n: string) => n[0])
        .join("")
        .toUpperCase()
        .slice(0, 2)
    : "?"

  return (
    <div className="min-h-screen bg-background">
      <header className="sticky top-0 z-40 border-b border-border/60 bg-background/70 backdrop-blur-md supports-[backdrop-filter]:bg-background/60">
        <div className="mx-auto flex h-14 max-w-7xl items-center justify-between px-6">
          <nav aria-label="Main navigation" className="flex items-center gap-6">
            <button
              onClick={() => navigate("/dashboard")}
              className="flex items-center gap-1.5 rounded-md text-lg font-semibold tracking-tight text-primary transition-opacity hover:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              aria-label="Go to dashboard"
            >
              <span className="inline-block h-5 w-5 rounded-md bg-gradient-to-br from-primary to-primary/60 shadow-sm ring-1 ring-primary/40" aria-hidden />
              DBagnets
            </button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => navigate("/benchmarks/tests")}
              className={cn(
                "gap-1.5 transition-colors",
                location.pathname === "/benchmarks/tests"
                  ? "text-foreground"
                  : "text-muted-foreground hover:text-foreground",
              )}
            >
              <FlaskConical className="h-4 w-4" />
              Tests
            </Button>
          </nav>
          <div className="flex items-center gap-1">
            <DarkModeToggle />
            <DropdownMenu>
              {/* @ts-expect-error — @base-ui/react v1.4 dropped `asChild` from typings but it still works at runtime */}
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  className="rounded-full"
                  aria-label="User menu"
                >
                  <Avatar className="h-8 w-8">
                    <AvatarImage src={user?.picture} alt={user?.name} />
                    <AvatarFallback className="bg-primary/10 text-xs text-primary">
                      {initials}
                    </AvatarFallback>
                  </Avatar>
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" sideOffset={8}>
                <DropdownMenuLabel>
                  <div className="flex flex-col gap-0.5">
                    <span className="text-sm font-medium">{user?.name}</span>
                    <span className="text-xs text-muted-foreground">{user?.email}</span>
                  </div>
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={logout}>Log out</DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
        {breadcrumbs && breadcrumbs.length > 0 && (
          <div className="mx-auto max-w-7xl px-6">
            <ol className="flex items-center gap-1.5 py-2 text-xs text-muted-foreground overflow-x-auto no-scrollbar">
              {breadcrumbs.map((crumb, i) => {
                const isLast = i === breadcrumbs.length - 1
                return (
                  <li key={`${crumb.label}-${i}`} className="flex items-center gap-1.5 whitespace-nowrap">
                    {i > 0 && <ChevronRight className="h-3 w-3 opacity-50" />}
                    {crumb.to && !isLast ? (
                      <Link
                        to={crumb.to}
                        className="rounded transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      >
                        {crumb.label}
                      </Link>
                    ) : (
                      <span className={cn(isLast && "font-medium text-foreground")}>{crumb.label}</span>
                    )}
                  </li>
                )
              })}
            </ol>
          </div>
        )}
      </header>

      <main
        role="main"
        className={cn(
          "mx-auto px-6 py-8",
          maxWidth === "narrow" ? "max-w-3xl" : "max-w-7xl",
        )}
      >
        {children}
      </main>
    </div>
  )
}
