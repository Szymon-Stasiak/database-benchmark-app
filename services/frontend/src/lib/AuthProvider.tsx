import { useState, useCallback, useEffect, type ReactNode } from "react"
import { jwtDecode } from "jwt-decode"
import { AuthContext, type AuthUser } from "@/lib/auth"

interface GoogleJwtPayload {
  email: string
  name: string
  picture: string
  exp: number
}

const STORAGE_KEY = "auth_token"

function isTokenExpired(token: string): boolean {
  try {
    const { exp } = jwtDecode<GoogleJwtPayload>(token)
    return Date.now() >= exp * 1000
  } catch {
    return true
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)

  const logout = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY)
    setUser(null)
  }, [])

  useEffect(() => {
    const storedToken = localStorage.getItem(STORAGE_KEY)
    if (storedToken) {
      if (isTokenExpired(storedToken)) {
        logout()
        return
      }
      try {
        const decoded = jwtDecode<GoogleJwtPayload>(storedToken)
        setUser({
          email: decoded.email,
          name: decoded.name,
          picture: decoded.picture,
          token: storedToken,
        })
      } catch {
        logout()
      }
    }
  }, [logout])

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

  return (
    <AuthContext.Provider
      value={{ user, login, logout, isAuthenticated: user !== null }}
    >
      {children}
    </AuthContext.Provider>
  )
}