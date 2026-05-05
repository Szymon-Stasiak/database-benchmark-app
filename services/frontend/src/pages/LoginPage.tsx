import { useNavigate } from "react-router-dom"
import { GoogleLogin } from "@react-oauth/google"
import { useAuth } from "@/lib/auth"
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
} from "@/components/ui/card"

export function LoginPage() {
  const { login, isAuthenticated } = useAuth()
  const navigate = useNavigate()

  if (isAuthenticated) {
    navigate("/dashboard", { replace: true })
    return null
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <Card className="w-full max-w-sm">
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
                navigate("/dashboard", { replace: true })
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
