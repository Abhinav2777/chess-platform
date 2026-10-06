import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, currentToken, type GameDetail } from './api';
import { anchor, freeze, type ClockAnchor } from './clock';
import { GameSocket, type ConnectionState, type MoveRequest } from './GameSocket';
import type { Failure, GameSnapshot } from './protocol';
import { randomUuid } from './uuid';

/** Our own MOVE_MADE normally arrives in milliseconds; its absence means fanout is down. */
const ECHO_TIMEOUT_MS = 1_500;
/** Poll interval while fanout is known to be degraded. */
const DEGRADED_POLL_MS = 2_000;
/**
 * Safety net for the player who is WAITING when fanout dies: they sent nothing, so there is
 * no echo to miss. With a silent socket this long on the opponent's turn, poll once. 10 s
 * bounds how stale their board can get, at one cheap read per 10 s per waiting player
 * (~100 req/s at 1,000 concurrent players). The better fix, if that ever matters: each
 * instance sees its own Valkey subscription drop and could tell its local sockets directly.
 */
const RECONCILE_AFTER_MS = 10_000;

export interface PlayedMove {
  ply: number;
  san: string;
}

/**
 * Adapts {@link GameSocket} to React.
 *
 * <p>The server's snapshot is the state. Nothing here derives a position, validates a
 * move, or decides whose turn it is — the client has no rules engine, by design
 * (ARCHITECTURE.md §10). Every legal move comes from the server in `legalMoves`.
 *
 * <p>The clock follows the same rule. Every server message re-anchors it (see
 * `clock.ts`); between messages it is extrapolated for display only. The client never
 * decides that anyone has run out of time.
 */
export function useGame(gameId: string) {
  const [connection, setConnection] = useState<ConnectionState>('connecting');
  const [snapshot, setSnapshot] = useState<GameSnapshot | null>(null);
  const [clock, setClock] = useState<ClockAnchor | null>(null);
  const [moves, setMoves] = useState<PlayedMove[]>([]);
  const [failure, setFailure] = useState<Failure | null>(null);
  // The rating change for this game, when the worker has applied it (Phase 5.3).
  const [rating, setRating] = useState<{ rating: number; delta: number } | null>(null);
  const socketRef = useRef<GameSocket | null>(null);

  // ---- degraded fanout (Milestone 4.3) ------------------------------------------------
  // With several instances, moves reach the opponent through Valkey. If Valkey is down the
  // socket stays up — sending works — but nothing arrives. Two signals notice:
  //   1. the echo: the server sends a mover their own MOVE_MADE through the same fanout as
  //      the opponent's, so a missing echo is a precise, fast signal (ECHO_TIMEOUT_MS);
  //   2. reconciliation: signal 1 needs us to have moved. Waiting on the opponent with a
  //      silent socket for RECONCILE_AFTER_MS, poll once anyway.
  // Degraded, the client polls GET /api/games/{id} (the same snapshot as GAME_SNAPSHOT)
  // until an event arrives over the socket again.
  const [degraded, setDegraded] = useState(false);
  const degradedRef = useRef(false);
  const snapshotRef = useRef<GameSnapshot | null>(null);
  const lastEventAt = useRef(performance.now());
  const lastPollAt = useRef(0);
  const awaitingEcho = useRef<number | null>(null);
  // The last move sent and not yet seen applied. A move sent in the instant a server drains (a
  // deploy) is lost with its socket — the server never applies it. Kept, so the snapshot after
  // the reconnect can re-send it with the SAME clientMoveId: if the original did land, the server
  // answers with the stored result instead of applying it twice (idempotency key, ADR-005).
  // Found recording the demo (10.6): before this, the player's move silently vanished.
  const pendingMove = useRef<{ request: MoveRequest; expectedPly: number } | null>(null);
  useEffect(() => { snapshotRef.current = snapshot; }, [snapshot]);
  useEffect(() => { degradedRef.current = degraded; }, [degraded]);

  const applyDetail = useCallback((detail: GameDetail) => {
    const current = snapshotRef.current;
    if (!current) return;
    const game = detail.game;
    // Forward only. A poll that raced a live event must never rewind the board.
    if (pendingMove.current && game.ply > pendingMove.current.expectedPly) pendingMove.current = null;
    if (game.ply < current.ply || (game.ply === current.ply && game.status === current.status)) return;
    setSnapshot({
      ...current,
      fen: game.fen,
      ply: game.ply,
      sideToMove: game.sideToMove,
      status: game.status,
      result: game.result,
      termination: game.termination,
      legalMoves: detail.legalMoves,
      lastMoveUci: detail.moves[detail.moves.length - 1]?.uci ?? null,
      whiteMsLeft: game.whiteMsLeft,
      blackMsLeft: game.blackMsLeft,
    });
    setClock(anchor(game.whiteMsLeft, game.blackMsLeft,
      game.status === 'ACTIVE' ? game.sideToMove : null));
    setMoves(detail.moves.map((move) => ({ ply: move.ply, san: move.san })));
  }, []);

  const poll = useCallback(() => {
    lastPollAt.current = performance.now();
    api.getGame(gameId).then(applyDetail).catch(() => {
      // The next tick retries; a failed poll is no worse than no poll.
    });
  }, [gameId, applyDetail]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      const current = snapshotRef.current;
      if (!current || current.status !== 'ACTIVE') return;
      const now = performance.now();
      if (degradedRef.current) {
        if (now - lastPollAt.current >= DEGRADED_POLL_MS) poll();
        return;
      }
      const opponentsTurn = current.yourSide !== null && current.sideToMove !== current.yourSide;
      if (opponentsTurn
          && now - lastEventAt.current >= RECONCILE_AFTER_MS
          && now - lastPollAt.current >= RECONCILE_AFTER_MS) {
        poll();
      }
    }, 1_000);
    return () => window.clearInterval(timer);
  }, [poll]);

  useEffect(() => {
    const socket = new GameSocket(gameId, currentToken, {
      onState: setConnection,

      onSnapshot: (incoming) => {
        lastEventAt.current = performance.now();
        // Adopted unconditionally, replacing whatever the client believed. That single
        // line is what makes the transport allowed to be unreliable: a dropped message
        // becomes a display gap, never divergence, so there is no replay buffer and no
        // per-client cursor anywhere in this codebase (ADR-007).
        setSnapshot(incoming);
        // The snapshot's clocks are "as of now" on the server, so anchoring them at our
        // "now" is correct to within one-way latency. This is also what repairs the
        // clock after a reconnect or a backgrounded tab: nothing to reconcile, just
        // replace.
        setClock(anchor(incoming.whiteMsLeft, incoming.blackMsLeft,
          incoming.status === 'ACTIVE' ? incoming.sideToMove : null));
        setFailure(null);
        // Replaced, like everything else in the snapshot — never merged with what we had.
        setMoves((incoming.moves ?? []).map((san, index) => ({ ply: index + 1, san })));

        // A move in flight across the reconnect: applied (the board is past it) — forget it;
        // still our turn at the same ply — it never landed, send it again, same clientMoveId.
        const pending = pendingMove.current;
        if (pending) {
          if (incoming.status !== 'ACTIVE' || incoming.ply > pending.expectedPly) {
            pendingMove.current = null;
          } else if (incoming.ply === pending.expectedPly && incoming.sideToMove === incoming.yourSide) {
            awaitingEcho.current = pending.expectedPly + 1;
            socket.move(pending.request);
          }
        }
      },

      onMove: (move) => {
        lastEventAt.current = performance.now();
        if (pendingMove.current && move.ply > pendingMove.current.expectedPly) pendingMove.current = null;
        if (awaitingEcho.current !== null && move.ply >= awaitingEcho.current) {
          awaitingEcho.current = null;
        }
        // An event over the socket means fanout works again.
        setDegraded(false);
        setSnapshot((previous) => previous && {
          ...previous,
          fen: move.fenAfter,
          ply: move.ply,
          sideToMove: move.sideToMove,
          lastMoveUci: move.uci,
          // Taken from the event, not cleared.
          //
          // An earlier version emptied this on the reasoning that the next legal moves
          // were the server's to supply — but nothing supplied them, so after one move a
          // player's board accepted no input at all until the next snapshot. Carrying
          // them on the event is the fix: the client never holds a board it cannot play
          // on, and never has to guess.
          legalMoves: move.legalMoves ?? [],
          whiteMsLeft: move.whiteMsLeft,
          blackMsLeft: move.blackMsLeft,
        });
        // A mating move arrives with no legal replies and is followed by GAME_FINISHED,
        // which freezes the clock. Until then the loser's clock runs for a few ms, which
        // is what the server believes too.
        setClock(anchor(move.whiteMsLeft, move.blackMsLeft, move.sideToMove));
        setMoves((previous) => [...previous, { ply: move.ply, san: move.san }]);
      },

      onFinished: (finished) => {
        lastEventAt.current = performance.now();
        const now = performance.now();
        // On a timeout the loser is whoever's clock was running — which the anchor
        // already records, so there is no need to read it from the snapshot (and no
        // setState inside another setState's updater, which React may run twice).
        setClock((current) => current
          && freeze(current, now, finished.termination === 'TIMEOUT' ? current.running : null));
        setSnapshot((previous) => previous && {
          ...previous,
          status: finished.status,
          result: finished.result,
          termination: finished.termination,
          legalMoves: [],
        });
      },

      onRatingUpdated: (update) => {
        // Addressed to the player, not the game: ignore one for a different game.
        if (update.gameId === gameId) setRating({ rating: update.rating, delta: update.delta });
      },

      onPresence: (presence) => {
        lastEventAt.current = performance.now();
        setSnapshot((previous) => {
          if (!previous) return previous;
          // Ignore our own echo: pub/sub delivers to every subscriber including the
          // publishing instance, so a client sees presence events about itself.
          const isOpponent = presence.userId !== meIn(previous);
          return isOpponent ? { ...previous, opponentOnline: presence.online } : previous;
        });
      },

      onError: (incoming) => {
        // A refused move gets an ERROR instead of an echo; that is not a fanout problem.
        awaitingEcho.current = null;
        pendingMove.current = null;
        if (incoming.code === 'CONFLICT') {
          // Our board was out of date — usually the opponent's move crossed ours in
          // flight. The server is right, so fetch its view instead of showing an error
          // the player can do nothing about.
          //
          // The rejected move is NOT resubmitted. It was chosen against a position the
          // player is no longer looking at; replaying it on the new one could play a
          // move they never intended. They see the fresh board and choose again.
          socket.resync();
          return;
        }
        setFailure(incoming);
      },
    });

    socketRef.current = socket;
    socket.connect();
    return () => socket.close();
  }, [gameId]);

  const submitMove = useCallback((from: string, to: string, promotion?: MoveRequest['promotion']) => {
    const current = socketRef.current;
    if (!current || !snapshot) return;
    const request: MoveRequest = {
      // Generated here, by the client, which is the entire point: the server cannot mint
      // this or a retry would look like a new move. randomUuid, not crypto.randomUUID —
      // the latter does not exist on a plain-HTTP origin (uuid.ts).
      clientMoveId: randomUuid(),
      expectedPly: snapshot.ply,
      from,
      to,
      promotion,
    };
    pendingMove.current = { request, expectedPly: snapshot.ply };
    current.move(request);

    // Expect our own MOVE_MADE. If it has not come back in time, fanout is down: switch to
    // polling and fetch the result now rather than on the next tick.
    const expected = snapshot.ply + 1;
    awaitingEcho.current = expected;
    window.setTimeout(() => {
      if (awaitingEcho.current === expected) {
        awaitingEcho.current = null;
        setDegraded(true);
        poll();
      }
    }, ECHO_TIMEOUT_MS);
    // Already degraded: show our own move promptly instead of up to a poll interval later.
    if (degradedRef.current) window.setTimeout(poll, 300);
  }, [snapshot, poll]);

  const resign = useCallback(() => {
    socketRef.current?.resign();
    // GAME_FINISHED travels through the same fanout. One follow-up poll costs nothing if it
    // arrived (forward-only apply ignores it) and ends the game on screen if it did not.
    window.setTimeout(poll, ECHO_TIMEOUT_MS);
  }, [poll]);

  const myTurn = useMemo(
    () => snapshot !== null
      && snapshot.status === 'ACTIVE'
      && snapshot.yourSide === snapshot.sideToMove,
    [snapshot],
  );

  return { connection, degraded, snapshot, clock, moves, failure, myTurn, submitMove, resign, rating };
}

function meIn(snapshot: GameSnapshot): string {
  return snapshot.yourSide === 'WHITE' ? snapshot.whitePlayerId : snapshot.blackPlayerId;
}
