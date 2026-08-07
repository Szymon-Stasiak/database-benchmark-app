export function chartFilename(chartName: string, ext: "png" | "svg" = "png"): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, "0")
  const stamp =
    `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}` +
    `_${pad(now.getHours())}-${pad(now.getMinutes())}-${pad(now.getSeconds())}`
  const slug = chartName
    .toLowerCase()
    .replace(/[^a-z0-9-]+/g, "-")
    .replace(/^-+|-+$/g, "")
  return `${stamp}_${slug}.${ext}`
}

export async function downloadChartAsPng(
  container: HTMLElement | null,
  chartName: string,
): Promise<void> {
  if (!container) return
  const svg = container.querySelector("svg")
  if (!svg) {
    console.warn("downloadChartAsPng: no <svg> found in container")
    return
  }

  const clone = svg.cloneNode(true) as SVGSVGElement
  inlineComputedStyles(svg, clone)

  const bbox = svg.getBoundingClientRect()
  const width = Math.max(1, Math.floor(bbox.width))
  const height = Math.max(1, Math.floor(bbox.height))
  clone.setAttribute("width", String(width))
  clone.setAttribute("height", String(height))
  clone.setAttribute("xmlns", "http://www.w3.org/2000/svg")

  const serializer = new XMLSerializer()
  const svgString = serializer.serializeToString(clone)
  const svgBlob = new Blob([svgString], { type: "image/svg+xml;charset=utf-8" })
  const svgUrl = URL.createObjectURL(svgBlob)

  try {
    const img = await loadImage(svgUrl)
    const scale = 2
    const canvas = document.createElement("canvas")
    canvas.width = width * scale
    canvas.height = height * scale
    const ctx = canvas.getContext("2d")
    if (!ctx) throw new Error("Could not get 2D context")
    ctx.fillStyle = "#ffffff"
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.scale(scale, scale)
    ctx.drawImage(img, 0, 0, width, height)

    const blob: Blob | null = await new Promise((resolve) =>
      canvas.toBlob((b) => resolve(b), "image/png"),
    )
    if (!blob) throw new Error("toBlob failed")
    triggerDownload(blob, chartFilename(chartName, "png"))
  } finally {
    URL.revokeObjectURL(svgUrl)
  }
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.crossOrigin = "anonymous"
    img.onload = () => resolve(img)
    img.onerror = (e) => reject(e)
    img.src = src
  })
}

function triggerDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1_000)
}


interface TableCell {
  text: string
  align: "left" | "right" | "center"
}

export async function downloadTableAsPng(
  container: HTMLElement | null,
  chartName: string,
): Promise<void> {
  if (!container) return
  const table = container.matches("table")
    ? (container as HTMLTableElement)
    : container.querySelector("table")
  if (!table) {
    console.warn("downloadTableAsPng: no <table> in container")
    return
  }

  const headerCells = readCells(Array.from(table.querySelectorAll("thead th")))
  const bodyRows = Array.from(table.querySelectorAll("tbody tr"))
    .map((tr) => readCells(Array.from(tr.querySelectorAll("td"))))
    .filter((row) => row.length > 0)

  if (headerCells.length === 0 || bodyRows.length === 0) {
    console.warn("downloadTableAsPng: empty table")
    return
  }

  const padding = 20
  const cellPadX = 14
  const headerHeight = 36
  const rowHeight = 30
  const headerFont = "bold 13px system-ui, -apple-system, sans-serif"
  const monoFont = "13px ui-monospace, SF Mono, Menlo, monospace"

  const measureCanvas = document.createElement("canvas")
  const measure = measureCanvas.getContext("2d")
  if (!measure) throw new Error("Could not get 2D context")

  const colCount = headerCells.length
  const colWidths: number[] = []
  for (let c = 0; c < colCount; c++) {
    measure.font = headerFont
    let max = measure.measureText(headerCells[c].text).width
    measure.font = monoFont
    for (const row of bodyRows) {
      if (row[c]) {
        const w = measure.measureText(row[c].text).width
        if (w > max) max = w
      }
    }
    colWidths.push(Math.ceil(max) + cellPadX * 2)
  }

  const tableWidth = colWidths.reduce((a, b) => a + b, 0)
  const tableHeight = headerHeight + bodyRows.length * rowHeight
  const totalWidth = tableWidth + padding * 2
  const totalHeight = tableHeight + padding * 2

  const scale = 2
  const canvas = document.createElement("canvas")
  canvas.width = totalWidth * scale
  canvas.height = totalHeight * scale
  const ctx = canvas.getContext("2d")
  if (!ctx) throw new Error("Could not get 2D context")
  ctx.scale(scale, scale)

  ctx.fillStyle = "#ffffff"
  ctx.fillRect(0, 0, totalWidth, totalHeight)

  let y = padding
  ctx.fillStyle = "#f8fafc"
  ctx.fillRect(padding, y, tableWidth, headerHeight)

  ctx.textBaseline = "middle"
  ctx.font = headerFont
  ctx.fillStyle = "#475569"
  let x = padding
  for (let c = 0; c < colCount; c++) {
    drawCell(ctx, headerCells[c], x, y, colWidths[c], headerHeight, cellPadX)
    x += colWidths[c]
  }

  ctx.strokeStyle = "#e2e8f0"
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.moveTo(padding, y + headerHeight + 0.5)
  ctx.lineTo(padding + tableWidth, y + headerHeight + 0.5)
  ctx.stroke()

  y += headerHeight
  ctx.font = monoFont
  ctx.fillStyle = "#0f172a"
  for (let r = 0; r < bodyRows.length; r++) {
    const row = bodyRows[r]
    x = padding
    for (let c = 0; c < colCount && c < row.length; c++) {
      drawCell(ctx, row[c], x, y, colWidths[c], rowHeight, cellPadX)
      x += colWidths[c]
    }
    if (r < bodyRows.length - 1) {
      ctx.strokeStyle = "#f1f5f9"
      ctx.beginPath()
      ctx.moveTo(padding, y + rowHeight + 0.5)
      ctx.lineTo(padding + tableWidth, y + rowHeight + 0.5)
      ctx.stroke()
    }
    y += rowHeight
  }

  ctx.strokeStyle = "#e2e8f0"
  ctx.lineWidth = 1
  ctx.strokeRect(padding + 0.5, padding + 0.5, tableWidth - 1, tableHeight - 1)

  const blob: Blob | null = await new Promise((resolve) =>
    canvas.toBlob((b) => resolve(b), "image/png"),
  )
  if (!blob) throw new Error("toBlob failed")
  triggerDownload(blob, chartFilename(chartName, "png"))
}

function readCells(elements: Element[]): TableCell[] {
  return elements.map((el) => {
    const align = getComputedStyle(el).textAlign
    const normalized: "left" | "right" | "center" =
      align === "right" || align === "end"
        ? "right"
        : align === "center"
        ? "center"
        : "left"
    return { text: (el.textContent ?? "").trim(), align: normalized }
  })
}

function drawCell(
  ctx: CanvasRenderingContext2D,
  cell: TableCell,
  x: number,
  y: number,
  w: number,
  h: number,
  padX: number,
): void {
  let tx: number
  if (cell.align === "right") {
    ctx.textAlign = "right"
    tx = x + w - padX
  } else if (cell.align === "center") {
    ctx.textAlign = "center"
    tx = x + w / 2
  } else {
    ctx.textAlign = "left"
    tx = x + padX
  }
  ctx.fillText(cell.text, tx, y + h / 2)
}

function inlineComputedStyles(source: SVGSVGElement, target: SVGSVGElement): void {
  const sourceEls = source.querySelectorAll("*")
  const targetEls = target.querySelectorAll("*")
  for (let i = 0; i < sourceEls.length; i++) {
    const s = sourceEls[i] as Element
    const t = targetEls[i] as SVGElement
    if (!t) continue
    const computed = getComputedStyle(s)
    const props = ["fill", "stroke", "stroke-width", "opacity", "font-family", "font-size", "color"]
    for (const p of props) {
      const v = computed.getPropertyValue(p)
      if (v) t.style.setProperty(p, v)
    }
  }
}
