import { useEffect, useState } from "react"
import { motion } from "framer-motion"

type Status = "checking" | "ready" | "starting"

/**
 * Polls a lightweight backend endpoint until it responds with something other
 * than a proxy-level 503 (which vite emits while Spring Boot is still warming
 * up). Renders a friendly overlay in the meantime so users don't see the app
 * crash on a broken fetch.
 */
export function BackendReadyGate({ children }: { children: React.ReactNode }) {
    const [status, setStatus] = useState<Status>("checking")
    const [elapsed, setElapsed] = useState(0)

    useEffect(() => {
        let cancelled = false
        let attempts = 0
        const startedAt = Date.now()

        const timer = window.setInterval(() => {
            setElapsed(Math.floor((Date.now() - startedAt) / 1000))
        }, 500)

        async function probe() {
            while (!cancelled) {
                attempts += 1
                try {
                    // /api/user requires auth → responds 401 quickly when the
                    // backend is up. Any structured HTTP status means Spring
                    // Boot is listening; only 503 (vite proxy fallback) or a
                    // network error means the backend socket isn't open yet.
                    const res = await fetch("/api/user", {
                        method: "GET",
                        cache: "no-store",
                        credentials: "include",
                    })
                    if (res.status !== 503) {
                        if (!cancelled) setStatus("ready")
                        return
                    }
                } catch {
                    // network error → backend not reachable yet
                }
                if (attempts === 1 && !cancelled) setStatus("starting")
                await new Promise((r) => setTimeout(r, 1500))
            }
        }

        probe()
        return () => {
            cancelled = true
            window.clearInterval(timer)
        }
    }, [])

    if (status === "ready") return <>{children}</>

    return (
        <div className="fixed inset-0 z-50 flex flex-col items-center justify-center bg-gradient-to-br from-slate-950 via-slate-900 to-slate-950 text-slate-100">
            <motion.div
                initial={{ opacity: 0, scale: 0.95 }}
                animate={{ opacity: 1, scale: 1 }}
                transition={{ duration: 0.3 }}
                className="flex flex-col items-center gap-6 px-8 text-center"
            >
                <div className="relative h-16 w-16">
                    <motion.div
                        className="absolute inset-0 rounded-full border-2 border-slate-700"
                        animate={{ rotate: 360 }}
                        transition={{
                            duration: 1.4,
                            repeat: Infinity,
                            ease: "linear",
                        }}
                        style={{
                            borderTopColor: "rgb(99 102 241)",
                        }}
                    />
                </div>
                <div>
                    <h1 className="text-xl font-semibold tracking-tight">
                        {status === "checking" ? "Connecting to backend…" : "Backend is starting"}
                    </h1>
                    <p className="mt-2 max-w-md text-sm text-slate-400">
                        Spring Boot warms up ~30–45 s on a cold start (loads Mongo, Neo4j,
                        Redis, DynamoDB drivers + Docker SDK). Hang tight — the UI will
                        reload automatically the moment the API is reachable.
                    </p>
                </div>
                <div className="rounded-lg border border-slate-800 bg-slate-900/60 px-4 py-2 font-mono text-xs text-slate-500">
                    elapsed: {elapsed}s
                </div>
            </motion.div>
        </div>
    )
}
