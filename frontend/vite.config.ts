import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Port 5173 is not incidental — it is one of the origins the backend permits for both
// CORS and the WebSocket handshake (chess.realtime.allowed-origins). Changing it here
// means changing it there.
//
// Requests go directly to the backend rather than through a Vite proxy, deliberately: a
// proxy would make the browser see one origin and hide the CORS and WebSocket-origin
// configuration entirely. Those are real production concerns and worth exercising in
// development rather than discovering after deployment.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173 },
});
