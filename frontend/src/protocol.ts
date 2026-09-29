/**
 * The WebSocket protocol, mirrored from the server.
 *
 * Hand-written rather than generated. The protocol is small and stable, and a code
 * generator would be more machinery than the thing it generates. If it grows, generating
 * these from an OpenAPI or JSON Schema document is the right move — the point at which to
 * do that is when the types drift, not before.
 */

export const PROTOCOL_VERSION = 1;

export type Side = 'WHITE' | 'BLACK';

export interface Envelope<T = unknown> {
  v: number;
  type: string;
  ts: string;
  payload: T;
}

export interface GameSnapshot {
  gameId: string;
  fen: string;
  ply: number;
  sideToMove: Side;
  whitePlayerId: string;
  blackPlayerId: string;
  yourSide: Side | null;
  opponentOnline: boolean;
  status: GameStatus;
  result: string | null;
  termination: string | null;
  /** Every legal move in UCI form. The client has no rules engine; this is the rules. */
  legalMoves: string[];
  lastMoveUci: string | null;
  /**
   * Remaining time AS OF THE MOMENT THE SERVER BUILT THIS SNAPSHOT — already net of the
   * current player's think so far. The client counts the side to move down from here.
   */
  whiteMsLeft: number;
  blackMsLeft: number;
  incrementMs: number;
  /** Every move so far in SAN, in ply order. Optional: servers before 3.3 omit it. */
  moves?: string[];
}

export interface MoveMade {
  gameId: string;
  ply: number;
  uci: string;
  san: string;
  fenAfter: string;
  sideToMove: Side;
  /** Legality in the new position. The client has no rules engine; this is the rules. */
  legalMoves: string[];
  /** Both clocks as the move committed, increment included. The new mover starts here. */
  whiteMsLeft: number;
  blackMsLeft: number;
}

export type GameStatus = 'ACTIVE' | 'FINISHED' | 'ABORTED';

export interface GameFinished {
  gameId: string;
  /** Since 3.2. FINISHED has a result; ABORTED never does and must never be scored. */
  status: Exclude<GameStatus, 'ACTIVE'>;
  result: 'WHITE_WIN' | 'BLACK_WIN' | 'DRAW' | null;
  termination: string | null;
}

export interface PlayerPresence {
  gameId: string;
  userId: string;
  online: boolean;
}

export interface Failure {
  code: string;
  message: string;
}

/** Reply to SEEK / CANCEL_SEEK (ADR-016). A match never appears here — see MatchFound. */
export interface SeekStatus {
  status: 'QUEUED' | 'PAIRING' | 'CANCELLED' | 'NOT_SEEKING';
  initialSeconds: number | null;
  incrementSeconds: number | null;
}

/**
 * A game has been created for this player. The one matchmaking message that takes the
 * client to a game — whether pushed at pairing time, returned to a seek, or re-sent after
 * AUTH_OK because the push was missed.
 */
export interface MatchFound {
  gameId: string;
  yourSide: Side;
  initialSeconds: number;
  incrementSeconds: number;
}

/**
 * A finished game changed this player's rating (Phase 5.3). Arrives after GAME_FINISHED —
 * ratings are applied asynchronously — and may be lost like any push; the lobby reads the
 * current rating from the server regardless.
 */
export interface RatingUpdated {
  gameId: string;
  rating: number;
  delta: number;
}
