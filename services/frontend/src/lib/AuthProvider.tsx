import { useState, useCallback, useEffect, type ReactNode } from "react"
import { jwtDecode } from "jwt-decode"
import { AuthContext, type AuthUser } from "@/lib/auth"

interface GoogleJwtPayload {
  email: string
  name: string
  picture: string
}

const STORAGE_KEY = "auth_token"

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)

  useEffect(() => {
    const storedToken = localStorage.getItem(STORAGE_KEY)
    if (storedToken) {
      try {
        const decoded = jwtDecode<GoogleJwtPayload>(storedToken)
        setUser({
          email: decoded.email,
          name: decoded.name,
          picture: decoded.picture,
          token: storedToken,
        })
      } catch {
        localStorage.removeItem(STORAGE_KEY)
      }
    }
  }, [])

  const login = useCallback(async (credential: string) => {
    const decoded = jwtDecode<GoogleJwtPayload>(credential)

    const authUser: AuthUser = {
      email: decoded.email,
      name: decoded.name,
      picture: decoded.picture,
      token: credential,
    }

    localStorage.setItem(STORAGE_KEY, credential)
    setUser(authUser)

    try {
      await fetch("/api/user", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${credential}`,
        },
        body: JSON.stringify({
          email: authUser.email,
          name: authUser.name,
        }),
      })
    } catch {
      // API call is best-effort; user is still authenticated client-side
    }
  }, [])

  const logout = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY)
    setUser(null)
  }, [])

  return (
    <AuthContext.Provider
      value={{ user, login, logout, isAuthenticated: user !== null }}
    >
      {children}
    </AuthContext.Provider>
  )
}
