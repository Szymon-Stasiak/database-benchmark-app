import { useEffect, useState } from "react"

const STORAGE_KEY = "app_theme"

type Theme = "light" | "dark"

function readInitial(): Theme {
  if (typeof window === "undefined") return "light"
  const stored = window.localStorage.getItem(STORAGE_KEY) as Theme | null
  if (stored === "light" || stored === "dark") return stored
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"
}

function apply(theme: Theme) {
  const root = document.documentElement
  root.classList.toggle("dark", theme === "dark")
  root.style.colorScheme = theme
}

export function useDarkMode() {
  const [theme, setTheme] = useState<Theme>(() => {
    const t = readInitial()
    if (typeof document !== "undefined") apply(t)
    return t
  })

  useEffect(() => {
    apply(theme)
    window.localStorage.setItem(STORAGE_KEY, theme)
  }, [theme])

  return {
    theme,
    isDark: theme === "dark",
    toggle: () => setTheme((t) => (t === "dark" ? "light" : "dark")),
    setTheme,
  }
}
