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
import { FlaskConical } from "lucide-react"
import { useNavigate, useLocation } from "react-router-dom"

interface AppLayoutProps {
  children: ReactNode
  maxWidth?: "default" | "narrow"
}

export function AppLayout({ children, maxWidth = "default" }: AppLayoutProps) {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const initials = user?.name
    ? user.name.split(" ").map((n: string) => n[0]).join("").toUpperCase().slice(0, 2)
    : "?"

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b border-border">
        <div className="mx-auto flex h-14 max-w-7xl items-center justify-between px-6">
          <div className="flex items-center gap-6">
            <h1
              className="text-lg font-semibold tracking-tight text-primary cursor-pointer"
              onClick={() => navigate("/dashboard")}
            >
              DBagnets
            </h1>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => navigate("/benchmarks/tests")}
              className={location.pathname === "/benchmarks/tests"
                ? "text-foreground"
                : "text-muted-foreground hover:text-foreground"}
            >
              <FlaskConical className="h-4 w-4 mr-1.5" />
              Tests
            </Button>
          </div>
          <DropdownMenu>
            {/* @ts-expect-error — @base-ui/react v1.4 dropped `asChild` from typings but it still works at runtime */}
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon" className="rounded-full">
                <Avatar className="h-8 w-8">
                  <AvatarImage src={user?.picture} alt={user?.name} />
                  <AvatarFallback className="bg-primary/10 text-primary text-xs">
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
      </header>

      <main className={`mx-auto px-6 py-8 ${maxWidth === "narrow" ? "max-w-3xl" : "max-w-7xl"}`}>
        {children}
      </main>
    </div>
  )
}
