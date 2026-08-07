import { Navigate, useLocation, useNavigate } from "react-router-dom"
import type { Location } from "react-router-dom"
import { GoogleLogin } from "@react-oauth/google"
import { useAuth } from "@/lib/auth"
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
} from "@/components/ui/card"

interface FromState {
  from?: Location
}

export function LoginPage() {
  const { login, isAuthenticated } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as FromState | null)?.from?.pathname ?? "/dashboard"

  if (isAuthenticated) {
    return <Navigate to={from} replace />
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-background via-secondary to-accent px-4">
      <Card className="w-full max-w-sm shadow-lg">
        <CardHeader className="text-center">
          <CardTitle className="text-2xl">Welcome to DBagnets</CardTitle>
          <CardDescription>
            Sign in with your Google account to continue
          </CardDescription>
        </CardHeader>
        <CardContent className="flex justify-center">
          <GoogleLogin
            onSuccess={async (credentialResponse) => {
              if (credentialResponse.credential) {
                await login(credentialResponse.credential)
                navigate(from, { replace: true })
              }
            }}
            onError={() => {
              console.error("Google Login Failed")
            }}
          />
        </CardContent>
      </Card>
    </div>
  )
}
