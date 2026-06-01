import { Routes, Route, Navigate, useLocation } from "react-router-dom"
import { GoogleOAuthProvider } from "@react-oauth/google"
import { AuthProvider } from "@/lib/AuthProvider"
import { ProtectedRoute } from "@/components/ProtectedRoute"
import { LoginPage } from "@/pages/LoginPage"
import { DashboardPage } from "@/pages/DashboardPage"
import NewBenchmarkPage from "@/pages/NewBenchmarkPage"
import BenchmarkDetailPage from "@/pages/BenchmarkDetailPage"
import BenchmarkTestsPage from "@/pages/BenchmarkTestsPage"
import BenchmarkInsertsPage from "@/pages/BenchmarkInsertsPage"
import BenchmarkReadsPage from "@/pages/BenchmarkReadsPage"
import BenchmarkDeletesPage from "@/pages/BenchmarkDeletesPage"
import BenchmarkComparisonPage from "@/pages/BenchmarkComparisonPage"
import { AnimatePresence, motion } from "framer-motion"

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID as string

function AnimatedRoutes() {
  const location = useLocation()
  return (
    <AnimatePresence mode="wait">
      <motion.div
        key={location.pathname}
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, y: -8 }}
        transition={{ duration: 0.2, ease: "easeOut" }}
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
            path="/benchmarks/tests"
            element={
              <ProtectedRoute>
                <BenchmarkTestsPage />
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
          <Route
            path="/benchmarks/:id/inserts"
            element={
              <ProtectedRoute>
                <BenchmarkInsertsPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/benchmarks/:id/reads"
            element={
              <ProtectedRoute>
                <BenchmarkReadsPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/benchmarks/:id/deletes"
            element={
              <ProtectedRoute>
                <BenchmarkDeletesPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/benchmarks/:id/comparison"
            element={
              <ProtectedRoute>
                <BenchmarkComparisonPage />
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
