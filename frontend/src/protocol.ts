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
  status: 'ACTIVE' | 'FINISHED' | 'ABORTED';
  result: string | null;
  termination: string | null;
  /** Every legal move in UCI form. The client has no rules engine; this is the rules. */
  legalMoves: string[];
  lastMoveUci: string | null;
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
}

export interface GameFinished {
  gameId: string;
  result: string;
  termination: string;
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
