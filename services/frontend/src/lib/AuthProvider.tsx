import { useState, useCallback, type ReactNode } from "react"
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

/**
 * Reads and validates the persisted token synchronously so the very first
 * render of the app already knows whether the user is authenticated. Doing
 * this in useEffect caused a redirect flash: <ProtectedRoute> saw
 * isAuthenticated=false → pushed /login → AuthProvider useEffect ran → login
 * page saw isAuthenticated=true → pushed /dashboard, throwing away the URL
 * the user was actually trying to refresh.
 */
function readInitialUser(): AuthUser | null {
  const stored = localStorage.getItem(STORAGE_KEY)
  if (!stored) return null
  if (isTokenExpired(stored)) {
    localStorage.removeItem(STORAGE_KEY)
    return null
  }
  try {
    const decoded = jwtDecode<GoogleJwtPayload>(stored)
    return {
      email: decoded.email,
      name: decoded.name,
      picture: decoded.picture,
      token: stored,
    }
  } catch {
    localStorage.removeItem(STORAGE_KEY)
    return null
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(() => readInitialUser())

  const logout = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY)
    setUser(null)
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

  return (
    <AuthContext.Provider
      value={{ user, login, logout, isAuthenticated: user !== null }}
    >
      {children}
    </AuthContext.Provider>
  )
}
