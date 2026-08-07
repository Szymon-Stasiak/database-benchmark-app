import { Navigate, useLocation } from "react-router-dom"
import { useAuth } from "@/lib/auth"
import type { ReactNode } from "react"

interface ProtectedRouteProps {
  children: ReactNode
}

export function ProtectedRoute({ children }: ProtectedRouteProps) {
  const { isAuthenticated } = useAuth()
  const location = useLocation()

  if (!isAuthenticated) {
    // Preserve the URL the user was trying to reach so LoginPage can send them
    // back there after authenticating.
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  return <>{children}</>
}
