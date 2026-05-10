import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"
import type { BenchmarkStatus, DatabaseStatus } from "@/types/benchmark"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

interface StatusConfig {
  bgClass: string
  textClass: string
  label: string
  animate: boolean
}

const BENCHMARK_STATUS_MAP: Record<BenchmarkStatus, StatusConfig> = {
  PENDING: { bgClass: "bg-status-pending-bg", textClass: "text-status-pending-text", label: "Pending", animate: false },
  GENERATING_SCRIPTS: { bgClass: "bg-status-progress-bg", textClass: "text-status-progress-text", label: "Generating Scripts...", animate: true },
  READY_TO_RUN: { bgClass: "bg-status-ready-bg", textClass: "text-status-ready-text", label: "Ready to Run", animate: false },
  STARTING_CONTAINERS: { bgClass: "bg-status-progress-bg", textClass: "text-status-progress-text", label: "Starting Containers...", animate: true },
  INITIALIZING: { bgClass: "bg-status-progress-bg", textClass: "text-status-progress-text", label: "Initializing...", animate: true },
  RUNNING: { bgClass: "bg-status-success-bg", textClass: "text-status-success-text", label: "Running", animate: false },
  STOPPED: { bgClass: "bg-status-pending-bg", textClass: "text-status-pending-text", label: "Stopped", animate: false },
  FAILED: { bgClass: "bg-status-error-bg", textClass: "text-status-error-text", label: "Failed", animate: false },
}

const DATABASE_STATUS_MAP: Record<DatabaseStatus, StatusConfig> = {
  PENDING: { bgClass: "bg-status-pending-bg", textClass: "text-status-pending-text", label: "Pending", animate: false },
  SCRIPT_GENERATING: { bgClass: "bg-status-progress-bg", textClass: "text-status-progress-text", label: "Generating Script...", animate: true },
  SCRIPT_READY: { bgClass: "bg-status-info-bg", textClass: "text-status-info-text", label: "Script Ready", animate: false },
  CONTAINER_STARTING: { bgClass: "bg-status-progress-bg", textClass: "text-status-progress-text", label: "Starting Container...", animate: true },
  INITIALIZING: { bgClass: "bg-status-progress-bg", textClass: "text-status-progress-text", label: "Initializing...", animate: true },
  RUNNING: { bgClass: "bg-status-success-bg", textClass: "text-status-success-text", label: "Running", animate: false },
  STOPPED: { bgClass: "bg-status-pending-bg", textClass: "text-status-pending-text", label: "Stopped", animate: false },
  FAILED: { bgClass: "bg-status-error-bg", textClass: "text-status-error-text", label: "Failed", animate: false },
}

export function getBenchmarkStatusConfig(status: BenchmarkStatus): StatusConfig {
  return BENCHMARK_STATUS_MAP[status] ?? BENCHMARK_STATUS_MAP.PENDING
}

export function getDatabaseStatusConfig(status: DatabaseStatus): StatusConfig {
  return DATABASE_STATUS_MAP[status] ?? DATABASE_STATUS_MAP.PENDING
}

export function relativeTime(dateStr: string): string {
  const now = Date.now()
  const then = new Date(dateStr).getTime()
  const diff = now - then
  const seconds = Math.floor(diff / 1000)
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)
  const days = Math.floor(hours / 24)

  if (seconds < 60) return "just now"
  if (minutes < 60) return `${minutes}m ago`
  if (hours < 24) return `${hours}h ago`
  if (days < 7) return `${days}d ago`
  return new Date(dateStr).toLocaleDateString()
}
