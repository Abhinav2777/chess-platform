import { useCallback, useEffect, useRef, useState } from 'react';
import { currentToken, type TimeControl } from './api';
import { GameSocket, type ConnectionState } from './GameSocket';
import type { MatchFound } from './protocol';

/** Well inside the server's 45 s seek TTL: two re-seeks can be lost before it lapses. */
const RESEEK_INTERVAL_MS = 15_000;

/** If CANCEL_SEEK gets no answer (socket reconnecting), give up waiting and just close. */
const CANCEL_TIMEOUT_MS = 3_000;

export type SeekPhase =
  | { phase: 'idle' }
  | { phase: 'seeking'; timeControl: TimeControl; since: number }
  | { phase: 'cancelling'; timeControl: TimeControl; since: number };

/**
 * Matchmaking from the lobby (ADR-016).
 *
 * A lobby socket exists only while seeking. It authenticates, seeks, and re-seeks every
 * 15 s — the repetition is the seek's heartbeat on the server, and after a reconnect the
 * re-seek on `live` is also what puts the player back in the queue. The server cancels a
 * seek when its socket closes, so leaving the lobby needs no special handling.
 *
 * Every way out goes back to idle and closes the socket: a match, a confirmed cancel, or
 * an error. A match that races a cancel wins — the server answers CANCEL_SEEK with
 * MATCH_FOUND when it is too late — and the player is taken to the game rather than
 * being told they cancelled a game that now exists.
 */
export function useSeek(onMatch: (match: MatchFound) => void) {
  const [state, setState] = useState<SeekPhase>({ phase: 'idle' });
  const [connection, setConnection] = useState<ConnectionState>('closed');
  const [error, setError] = useState<string | null>(null);

  const socketRef = useRef<GameSocket | null>(null);
  const timerRef = useRef<number | null>(null);
  const onMatchRef = useRef(onMatch);
  useEffect(() => { onMatchRef.current = onMatch; }, [onMatch]);

  const stop = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
    socketRef.current?.close();
    socketRef.current = null;
    setState({ phase: 'idle' });
  }, []);

  const start = useCallback((timeControl: TimeControl) => {
    stop();
    setError(null);
    const { initialSeconds, incrementSeconds } = timeControl;

    const socket = new GameSocket(null, currentToken, {
      onState: (next) => {
        setConnection(next);
        // First connection and every reconnect: (re)assert the seek. Idempotent.
        if (next === 'live') socket.seek(initialSeconds, incrementSeconds);
      },
      onSeekStatus: (status) => {
        if (status.status === 'CANCELLED' || status.status === 'NOT_SEEKING') stop();
      },
      onMatchFound: (match) => {
        stop();
        onMatchRef.current(match);
      },
      onError: (failure) => {
        stop();
        setError(failure.code === 'MATCHMAKING_UNAVAILABLE'
          ? 'Matchmaking is unavailable right now. You can still challenge someone by name.'
          : failure.message);
      },
    });

    socketRef.current = socket;
    socket.connect();
    timerRef.current = window.setInterval(
      () => socket.seek(initialSeconds, incrementSeconds), RESEEK_INTERVAL_MS);
    setState({ phase: 'seeking', timeControl, since: Date.now() });
  }, [stop]);

  const cancel = useCallback(() => {
    const socket = socketRef.current;
    if (!socket) return;
    setState((current) => current.phase === 'seeking' ? { ...current, phase: 'cancelling' } : current);
    socket.cancelSeek();
    // Closing also cancels on the server, so a lost answer costs nothing but the wait.
    window.setTimeout(() => {
      if (socketRef.current === socket) stop();
    }, CANCEL_TIMEOUT_MS);
  }, [stop]);

  // Unmounting (signing out, opening a game) closes the socket; the server cancels the seek.
  useEffect(() => stop, [stop]);

  return { state, connection, error, start, cancel };
}
