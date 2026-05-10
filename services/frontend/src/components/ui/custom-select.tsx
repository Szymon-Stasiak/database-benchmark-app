import { useState, useRef, useEffect, useCallback } from "react"
import { createPortal } from "react-dom"
import { ChevronDown, Check } from "lucide-react"

interface SelectOption {
  value: string
  label: string
}

interface CustomSelectProps {
  value: string
  onChange: (value: string) => void
  options: SelectOption[]
  placeholder?: string
  disabled?: boolean
}

export function CustomSelect({
  value,
  onChange,
  options,
  placeholder = "Select...",
  disabled = false,
}: CustomSelectProps) {
  const [open, setOpen] = useState(false)
  const [hoveredIndex, setHoveredIndex] = useState(-1)
  const [pos, setPos] = useState({ top: 0, left: 0, width: 0 })
  const triggerRef = useRef<HTMLButtonElement>(null)

  const updatePosition = useCallback(() => {
    if (!triggerRef.current) return
    const rect = triggerRef.current.getBoundingClientRect()
    setPos({
      top: rect.bottom + 4,
      left: rect.left,
      width: rect.width,
    })
  }, [])

  useEffect(() => {
    if (!open) return
    updatePosition()
    window.addEventListener("scroll", updatePosition, true)
    window.addEventListener("resize", updatePosition)
    return () => {
      window.removeEventListener("scroll", updatePosition, true)
      window.removeEventListener("resize", updatePosition)
    }
  }, [open, updatePosition])

  useEffect(() => {
    if (!open) return
    const handler = (e: MouseEvent) => {
      if (triggerRef.current?.contains(e.target as Node)) return
      setOpen(false)
    }
    document.addEventListener("mousedown", handler)
    return () => document.removeEventListener("mousedown", handler)
  }, [open])

  const selectedLabel = options.find((o) => o.value === value)?.label

  const dropdown = open && options.length > 0 && createPortal(
    <div
      className="fixed z-[99999] rounded-lg border border-border bg-popover p-1 shadow-lg max-h-[300px] overflow-y-auto"
      style={{ top: pos.top, left: pos.left, width: pos.width }}
      onMouseDown={(e) => e.stopPropagation()}
    >
      {options.map((option, i) => {
        const isSelected = value === option.value
        const isHovered = hoveredIndex === i
        return (
          <button
            key={option.value}
            type="button"
            onMouseEnter={() => setHoveredIndex(i)}
            onMouseLeave={() => setHoveredIndex(-1)}
            onClick={() => {
              onChange(option.value)
              setOpen(false)
            }}
            className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm cursor-pointer border-none outline-none text-left ${
              isSelected ? "bg-secondary" : isHovered ? "bg-muted" : "bg-transparent"
            } text-popover-foreground`}
          >
            <span className="flex-1">{option.label}</span>
            {isSelected && <Check size={16} className="shrink-0" />}
          </button>
        )
      })}
    </div>,
    document.body,
  )

  const emptyDropdown = open && options.length === 0 && createPortal(
    <div
      className="fixed z-[99999] rounded-lg border border-border bg-popover p-3 shadow-lg text-sm text-muted-foreground text-center"
      style={{ top: pos.top, left: pos.left, width: pos.width }}
      onMouseDown={(e) => e.stopPropagation()}
    >
      No options available
    </div>,
    document.body,
  )

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        onClick={() => {
          if (!disabled) {
            updatePosition()
            setOpen((prev) => !prev)
          }
        }}
        className={`flex h-9 w-full items-center justify-between gap-2 rounded-lg border border-input px-3 text-sm outline-none ${
          disabled ? "bg-muted cursor-not-allowed opacity-50" : "bg-background cursor-pointer"
        } text-foreground`}
      >
        <span className={`flex-1 text-left overflow-hidden text-ellipsis whitespace-nowrap ${
          selectedLabel ? "text-foreground" : "text-muted-foreground"
        }`}>
          {selectedLabel || placeholder}
        </span>
        <ChevronDown
          size={16}
          className={`shrink-0 text-muted-foreground transition-transform duration-150 ${open ? "rotate-180" : ""}`}
        />
      </button>
      {dropdown}
      {emptyDropdown}
    </>
  )
}
