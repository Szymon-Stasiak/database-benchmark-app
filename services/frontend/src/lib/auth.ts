import { createContext, useContext } from "react"

export interface AuthUser {
  email: string
  name: string
  picture: string
  token: string
}

export interface AuthContextType {
  user: AuthUser | null
  login: (credential: string) => Promise<void>
  logout: () => void
  isAuthenticated: boolean
}

export const AuthContext = createContext<AuthContextType | null>(null)

export function useAuth(): AuthContextType {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider")
  }
  return context
}
