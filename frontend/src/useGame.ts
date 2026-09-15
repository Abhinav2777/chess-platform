import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { currentToken } from './api';
import { GameSocket, type ConnectionState, type MoveRequest } from './GameSocket';
import type { Failure, GameSnapshot } from './protocol';

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
 */
export function useGame(gameId: string) {
  const [connection, setConnection] = useState<ConnectionState>('connecting');
  const [snapshot, setSnapshot] = useState<GameSnapshot | null>(null);
  const [moves, setMoves] = useState<PlayedMove[]>([]);
  const [failure, setFailure] = useState<Failure | null>(null);
  const socketRef = useRef<GameSocket | null>(null);

  useEffect(() => {
    const socket = new GameSocket(gameId, currentToken, {
      onState: setConnection,

      onSnapshot: (incoming) => {
        // Adopted unconditionally, replacing whatever the client believed. That single
        // line is what makes the transport allowed to be unreliable: a dropped message
        // becomes a display gap, never divergence, so there is no replay buffer and no
        // per-client cursor anywhere in this codebase (ADR-007).
        setSnapshot(incoming);
        setFailure(null);
        // The move list is not in the snapshot, so a reconnect mid-game starts it empty
        // rather than showing a list that contradicts the board. Known gap — the REST
        // endpoint returns the full log and wiring it in is a small follow-up.
        setMoves([]);
      },

      onMove: (move) => {
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
        });
        setMoves((previous) => [...previous, { ply: move.ply, san: move.san }]);
      },

      onFinished: (finished) => {
        setSnapshot((previous) => previous && {
          ...previous,
          status: 'FINISHED',
          result: finished.result,
          termination: finished.termination,
          legalMoves: [],
        });
      },

      onPresence: (presence) => {
        setSnapshot((previous) => {
          if (!previous) return previous;
          // Ignore our own echo: pub/sub delivers to every subscriber including the
          // publishing instance, so a client sees presence events about itself.
          const isOpponent = presence.userId !== meIn(previous);
          return isOpponent ? { ...previous, opponentOnline: presence.online } : previous;
        });
      },

      onError: setFailure,
    });

    socketRef.current = socket;
    socket.connect();
    return () => socket.close();
  }, [gameId]);

  const submitMove = useCallback((from: string, to: string, promotion?: MoveRequest['promotion']) => {
    const current = socketRef.current;
    if (!current || !snapshot) return;
    current.move({
      // Generated here, by the client, which is the entire point: the server cannot mint
      // this or a retry would look like a new move. crypto.randomUUID is available in
      // every browser this project targets.
      clientMoveId: crypto.randomUUID(),
      expectedPly: snapshot.ply,
      from,
      to,
      promotion,
    });
  }, [snapshot]);

  const resign = useCallback(() => socketRef.current?.resign(), []);

  const myTurn = useMemo(
    () => snapshot !== null
      && snapshot.status === 'ACTIVE'
      && snapshot.yourSide === snapshot.sideToMove,
    [snapshot],
  );

  return { connection, snapshot, moves, failure, myTurn, submitMove, resign };
}

function meIn(snapshot: GameSnapshot): string {
  return snapshot.yourSide === 'WHITE' ? snapshot.whitePlayerId : snapshot.blackPlayerId;
}
