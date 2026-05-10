import { Routes, Route, Navigate, useLocation } from "react-router-dom"
import { GoogleOAuthProvider } from "@react-oauth/google"
import { AuthProvider } from "@/lib/AuthProvider"
import { ProtectedRoute } from "@/components/ProtectedRoute"
import { LoginPage } from "@/pages/LoginPage"
import { DashboardPage } from "@/pages/DashboardPage"
import NewBenchmarkPage from "@/pages/NewBenchmarkPage"
import BenchmarkDetailPage from "@/pages/BenchmarkDetailPage"
import { AnimatePresence, motion } from "framer-motion"

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID as string

function AnimatedRoutes() {
  const location = useLocation()
  return (
    <AnimatePresence mode="wait">
      <motion.div
        key={location.pathname}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.15 }}
      >
        <Routes location={location}>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <DashboardPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/benchmarks/new"
            element={
              <ProtectedRoute>
                <NewBenchmarkPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/benchmarks/:id"
            element={
              <ProtectedRoute>
                <BenchmarkDetailPage />
              </ProtectedRoute>
            }
          />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </motion.div>
    </AnimatePresence>
  )
}

export default function App() {
  return (
    <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
      <AuthProvider>
        <AnimatedRoutes />
      </AuthProvider>
    </GoogleOAuthProvider>
  )
}
