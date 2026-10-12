<script setup>
// 一个极简图标组件：内置几十个线性图标，全部走 inline SVG。
//
// 为什么不引图标库（element-plus / iconify）：这个前端刻意保持零 UI 依赖 ——
// 少一层依赖就少一次"装不上、版本对不上、离线构建失败"的机会。
// 图标只有几十个、每个一行 path，抄进来比管依赖便宜。
const props = defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 18 }
})

// 24x24 视图框内的路径（线性风格，stroke 上色，所以 fill 一律 none）
const PATHS = {
  query: "M4 6h16M4 12h10M4 18h7M17 15l3 3 2-2",
  database: "M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3-8-1.3-8-3 3.6-3 8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3",
  review: "M9 11l2 2 4-4M6 3h9l4 4v14H6zM15 3v4h4",
  history: "M3 12a9 9 0 1 0 3-6.7M3 4v5h5M12 8v4l3 2",
  star: "M12 3l2.7 5.5 6.1.9-4.4 4.3 1 6-5.4-2.9-5.4 2.9 1-6L3.2 9.4l6.1-.9z",
  export: "M12 3v12M7 10l5 5 5-5M5 21h14",
  magic: "M15 4l1 2 2 1-2 1-1 2-1-2-2-1 2-1zM4 20l7-7M6 4l.8 1.7L8.5 6.5 6.8 7.3 6 9l-.8-1.7L3.5 6.5l1.7-.8z",
  shield: "M12 3l7 3v6c0 4-3 6.5-7 9-4-2.5-7-5-7-9V6z",
  plus: "M12 5v14M5 12h14",
  trash: "M4 7h16M9 7V4h6v3M6 7l1 14h10l1-14M10 11v6M14 11v6",
  play: "M7 4l12 8-12 8z",
  check: "M4 12l5 5L20 6",
  close: "M6 6l12 12M18 6L6 18",
  warn: "M12 3l9 17H3zM12 9v5M12 17h.01",
  info: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 8h.01M11 12h1v5h1",
  refresh: "M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5",
  copy: "M9 9h10v10H9zM5 15V5h10",
  search: "M10 4a6 6 0 1 0 0 12 6 6 0 0 0 0-12zM15 15l5 5",
  download: "M12 3v12M7 10l5 5 5-5M5 21h14",
  logout: "M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10",
  user: "M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM4 21c0-4 3.6-6 8-6s8 2 8 6",
  moon: "M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z",
  sun: "M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zM12 2v2M12 20v2M4 12H2M22 12h-2M5 5l1.5 1.5M17.5 17.5L19 19M19 5l-1.5 1.5M6.5 17.5L5 19",
  chevron: "M9 6l6 6-6 6",
  arrowLeft: "M15 6l-6 6 6 6",
  arrowRight: "M9 6l6 6-6 6",
  clock: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l4 2",
  filter: "M3 5h18l-7 8v6l-4-2v-4z",
  bolt: "M13 2L4 14h6l-1 8 9-12h-6z",
  book: "M4 4h7v16H4zM13 4h7v16h-7M11 4v16",
  lock: "M6 11h12v9H6zM9 11V8a3 3 0 0 1 6 0v3",
  eye: "M2 12s3.6-6 10-6 10 6 10 6-3.6 6-10 6-10-6-10-6zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
  chart: "M4 20V10M10 20V4M16 20v-7M22 20H2",
  inbox: "M3 13l2-8h14l2 8v7H3zM3 13h5l1 2h6l1-2h5"
}
</script>

<template>
  <svg
    class="ico"
    :width="size" :height="size" viewBox="0 0 24 24"
    fill="none" stroke="currentColor" stroke-width="1.7"
    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"
  >
    <path :d="PATHS[name] || PATHS.info" />
  </svg>
</template>