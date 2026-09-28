/// <reference types="vite/client" />

// Types `import.meta.env` (VITE_API_URL, VITE_WS_URL). Without this file `npm run dev`
// works — Vite does not type-check — but `npm run build` fails in `tsc -b`, which is how
// it went unnoticed until Milestone 3.2 first ran the production build.
