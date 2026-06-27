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
