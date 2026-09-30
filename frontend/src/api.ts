import type { GameStatus, Side } from './protocol';

// Dev server (5173) talks to the backend on 8080 — cross-origin on purpose, so CORS and the
// socket's origin check are exercised locally. A production build is served BY the backend
// (Phase 7, ADR-023), so it calls its own origin: relative paths, no CORS at all.
const BASE = import.meta.env.VITE_API_URL ?? (import.meta.env.DEV ? 'http://localhost:8080' : '');

/**
 * The access token lives here — a module variable — and nowhere else.
 *
 * **Not localStorage, not sessionStorage.** Anything in web storage is readable by any
 * script on the page, so a single XSS exfiltrates the session. A module-scoped variable
 * is not meaningfully harder for an attacker who already has script execution, but it does
 * not survive a page reload, which bounds the window and forces a refresh through the
 * httpOnly cookie the browser will not hand to script at all.
 *
 * The refresh token is never seen by this file. It travels as an httpOnly, Secure,
 * SameSite=Strict cookie that the browser attaches automatically — which is exactly why
 * every request below sets `credentials: 'include'` (ADR-009).
 */
let accessToken: string | null = null;

export function currentToken(): string | null {
  return accessToken;
}

export interface Session {
  accessToken: string;
  expiresInSeconds: number;
  userId: string;
  username: string;
}

export interface GameSummary {
  id: string;
  whitePlayerId: string;
  blackPlayerId: string;
  /** Present on the list endpoint, absent when a game is created. */
  whiteUsername?: string | null;
  blackUsername?: string | null;
  status: GameStatus;
  result: string | null;
  termination: string | null;
  fen: string;
  ply: number;
  sideToMove: Side;
  incrementMs: number;
  whiteMsLeft: number;
  blackMsLeft: number;
}

/**
 * GET /api/games/{id}: the same consistent snapshot the socket's GAME_SNAPSHOT is built
 * from (GameFacade#state), which is what lets a poll stand in for a lost event.
 */
export interface GameDetail {
  game: GameSummary;
  moves: { ply: number; uci: string; san: string; playedAt: string }[];
  legalMoves: string[];
}

/** A time control, as the server's create endpoint takes it. */
export interface TimeControl {
  initialSeconds: number;
  incrementSeconds: number;
}

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(BASE + path, {
    ...init,
    // Required for the refresh cookie. It is also why the server must name its allowed
    // origins explicitly: the CORS spec forbids pairing credentials with a wildcard.
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...(init.headers ?? {}),
    },
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    // The server speaks RFC 7807 and always includes a machine-readable `code`. Branching
    // on that rather than on `detail` means error wording can change without breaking the
    // client — which is the entire reason the field exists.
    throw new ApiError(response.status, body.code ?? 'UNKNOWN',
      body.detail ?? 'Something went wrong.');
  }
  return body as T;
}

function remember(session: Session): Session {
  accessToken = session.accessToken;
  return session;
}

export const api = {
  register: (username: string, email: string, password: string) =>
    request<Session>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, email, password }),
    }).then(remember),

  login: (username: string, password: string) =>
    request<Session>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }).then(remember),

  /**
   * Exchanges the refresh cookie for a new access token.
   *
   * Sends no body: the cookie is the credential, and the browser attaches it. The client
   * cannot read it, which is the point of httpOnly.
   */
  refresh: () =>
    request<Session>('/api/auth/refresh', { method: 'POST' }).then(remember),

  logout: async () => {
    await request<void>('/api/auth/logout', { method: 'POST' });
    accessToken = null;
  },

  createGame: (opponentUsername: string, timeControl: TimeControl, playAs?: Side) =>
    request<GameSummary>('/api/games', {
      method: 'POST',
      body: JSON.stringify({ opponentUsername, playAs, ...timeControl }),
    }),

  myGames: () => request<GameSummary[]>('/api/games?page=0&size=20'),

  getGame: (gameId: string) => request<GameDetail>(`/api/games/${gameId}`),

  /** The signed-in player, including their current rating — correct even if a push was missed. */
  me: () => request<{ id: string; username: string; rating: number }>('/api/users/me'),
};
