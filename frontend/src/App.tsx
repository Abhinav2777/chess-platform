import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, type GameSummary, type Session, type TimeControl } from './api';
import { Board } from './Board';
import { PlayerClock } from './Clock';
import { useGame } from './useGame';
import type { GameSnapshot, Side } from './protocol';

/**
 * Presets rather than free-form inputs. The server accepts anything from 10 s to 24 h
 * (validated there, not here), but a challenge form with two number fields is friction
 * for a decision most players make by habit.
 */
const TIME_CONTROLS: { label: string; value: TimeControl }[] = [
  { label: '1+0 bullet', value: { initialSeconds: 60, incrementSeconds: 0 } },
  { label: '3+2 blitz', value: { initialSeconds: 180, incrementSeconds: 2 } },
  { label: '5+3 blitz', value: { initialSeconds: 300, incrementSeconds: 3 } },
  { label: '10+0 rapid', value: { initialSeconds: 600, incrementSeconds: 0 } },
];
const DEFAULT_TIME_CONTROL = 2;

/** Mirrors Game.FIRST_MOVE_WINDOW on the server. Used for a hint, never for a decision. */
const FIRST_MOVE_WINDOW_SECONDS = 30;

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [game, setGame] = useState<GameSummary | null>(null);

  if (!session) return <SignIn onSignedIn={setSession} />;
  if (!game) return <Lobby session={session} onOpen={setGame} />;
  return <GameView session={session} game={game} onLeave={() => setGame(null)} />;
}

function SignIn({ onSignedIn }: { onSignedIn: (session: Session) => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [registering, setRegistering] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      onSignedIn(registering
        ? await api.register(username, `${username}@example.com`, password)
        : await api.login(username, password));
    } catch (failure) {
      // The server's problem+json `detail` is written for users, so it is shown as-is.
      // Anything unexpected is deliberately opaque — an exception message can carry a
      // stack frame or a hostname.
      setError(failure instanceof ApiError ? failure.message : 'Could not sign in.');
    }
  }

  return (
    <form className="panel" onSubmit={submit}>
      <h1>Chess Platform</h1>
      <input value={username} onChange={(e) => setUsername(e.target.value)}
             placeholder="username" autoComplete="username" />
      <input value={password} onChange={(e) => setPassword(e.target.value)}
             placeholder="password" type="password"
             autoComplete={registering ? 'new-password' : 'current-password'} />
      <button type="submit">{registering ? 'Register' : 'Sign in'}</button>
      <button type="button" className="link" onClick={() => setRegistering(!registering)}>
        {registering ? 'I already have an account' : 'Create an account'}
      </button>
      {error && <p className="error">{error}</p>}
    </form>
  );
}

function Lobby({ session, onOpen }: { session: Session; onOpen: (game: GameSummary) => void }) {
  const [opponent, setOpponent] = useState('');
  const [timeControl, setTimeControl] = useState(DEFAULT_TIME_CONTROL);
  const [games, setGames] = useState<GameSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.myGames().then(setGames).catch(() => setError('Could not load games.'));
  }, []);

  // Loaded on arrival and then polled. A challenged player receives no notification —
  // there is no invitation system — so without this the lobby is a dead end: an empty
  // screen with no reason to click anything.
  //
  // Polling, not a WebSocket. The socket in this app is scoped to a single game, and a
  // second lobby-wide channel is real design work (who subscribes to what, and when) for
  // a screen people look at for five seconds. Ten-second polling is the honest choice
  // until there is a reason for more.
  useEffect(() => {
    load();
    const timer = window.setInterval(load, 10_000);
    return () => window.clearInterval(timer);
  }, [load]);

  async function challenge(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    const chosen = TIME_CONTROLS[timeControl];
    if (!chosen) return;
    try {
      onOpen(await api.createGame(opponent, chosen.value));
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : 'Could not start a game.');
    }
  }

  return (
    <div className="panel">
      <h1>Hello, {session.username}</h1>
      <form onSubmit={challenge} className="challenge">
        <input value={opponent} onChange={(e) => setOpponent(e.target.value)}
               placeholder="opponent username" />
        <select value={timeControl} onChange={(e) => setTimeControl(Number(e.target.value))}
                aria-label="time control">
          {TIME_CONTROLS.map((option, index) =>
            <option key={option.label} value={index}>{option.label}</option>)}
        </select>
        {/* No colour picker: the server draws at random. Letting the challenger always
            take White would be a way to farm rating — White scores about 54%. */}
        <button type="submit">Challenge</button>
      </form>
      <p className="hint">
        Only one player starts the game. The other opens it from the list below —
        challenging back creates a <em>second</em>, separate game. A game where either
        player has not moved within {FIRST_MOVE_WINDOW_SECONDS} seconds is aborted, unrated.
      </p>
      {error && <p className="error">{error}</p>}

      <h2>Your games</h2>
      {games.length === 0
        ? <p className="hint">No games yet. Challenge someone, or wait to be challenged —
            this list refreshes every few seconds.</p>
        : (
          <ul className="games">
            {games.map((entry) => {
              const iAmWhite = entry.whitePlayerId === session.userId;
              const opponent = (iAmWhite ? entry.blackUsername : entry.whiteUsername) ?? 'opponent';
              return (
                <li key={entry.id}>
                  <button type="button" className="link" onClick={() => onOpen(entry)}>
                    {entry.status === 'ACTIVE' ? '●' : '○'}{' '}
                    vs <strong>{opponent}</strong>
                    {' · as '}{iAmWhite ? 'white' : 'black'}
                    {' · ply '}{entry.ply}
                    {entry.status === 'ACTIVE' ? '' : ` · ${shortOutcome(entry.status, entry.result)}`}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
    </div>
  );
}

function GameView({ session, game, onLeave }:
                  { session: Session; game: GameSummary; onLeave: () => void }) {
  const { connection, snapshot, clock, moves, failure, myTurn, submitMove, resign } =
    useGame(game.id);

  const orientation: Side = snapshot?.yourSide ?? 'WHITE';
  const opponentSide: Side = orientation === 'WHITE' ? 'BLACK' : 'WHITE';
  const nameOf = (side: Side) =>
    (side === 'WHITE' ? game.whiteUsername : game.blackUsername)
    ?? (side === snapshot?.yourSide ? session.username : 'Opponent');

  // Before both players have moved, the server treats resigning as aborting (no result,
  // no rating change). The button says so, rather than surprising anyone.
  const awaitingFirstMove = snapshot !== null && snapshot.ply < 2;

  return (
    <div className="game">
      <header>
        <button type="button" className="link" onClick={onLeave}>&larr; Back</button>
        <Connection state={connection} />
      </header>

      {snapshot ? (
        <div className="game-body">
          <div className="board-column">
            <PlayerClock clock={clock} side={opponentSide} name={nameOf(opponentSide)}
                         isYou={false} />
            <Board
              fen={snapshot.fen}
              orientation={orientation}
              legalMoves={snapshot.legalMoves}
              // Interactivity follows the SERVER's view of whose turn it is, not a local
              // guess. The server would reject an out-of-turn move regardless — this only
              // stops the UI offering one.
              interactive={myTurn}
              lastMoveUci={snapshot.lastMoveUci}
              onMove={submitMove}
            />
            <PlayerClock clock={clock} side={orientation} name={nameOf(orientation)}
                         isYou={snapshot.yourSide !== null} />
          </div>

          <aside>
            <p className="status">{statusLine(snapshot, myTurn)}</p>
            {snapshot.status === 'ACTIVE' && awaitingFirstMove && (
              <p className="hint">
                Each player must make a first move within {FIRST_MOVE_WINDOW_SECONDS} seconds
                or the game is aborted, unrated.
              </p>
            )}
            <p className={snapshot.opponentOnline ? 'online' : 'offline'}>
              Opponent {snapshot.opponentOnline ? 'online' : 'offline'}
              {!snapshot.opponentOnline && snapshot.status === 'ACTIVE'
                ? ' — they may not have opened the game yet. Clocks do not pause.'
                : ''}
            </p>
            {/* Deliberately stated: a disconnected opponent's clock keeps running. Chess
                does not pause for network problems (ADR-006), and a UI that implied
                otherwise would be lying about the rules. */}

            <ol className="moves">
              {moves.map((move) => <li key={move.ply}>{move.san}</li>)}
            </ol>

            {snapshot.status === 'ACTIVE' && snapshot.yourSide !== null && (
              <button type="button" onClick={resign}>
                {awaitingFirstMove ? 'Abort' : 'Resign'}
              </button>
            )}
            {failure && <p className="error">{failure.code}: {failure.message}</p>}
          </aside>
        </div>
      ) : (
        <p className="status">Loading the board…</p>
      )}

      <footer className="hint">Signed in as {session.username}</footer>
    </div>
  );
}

const TERMINATIONS: Record<string, string> = {
  CHECKMATE: 'checkmate',
  STALEMATE: 'stalemate',
  RESIGNATION: 'resignation',
  TIMEOUT: 'time',
  DRAW_FIFTY_MOVE: 'the fifty-move rule',
  DRAW_REPETITION: 'threefold repetition',
  DRAW_INSUFFICIENT_MATERIAL: 'insufficient material',
};

function statusLine(snapshot: GameSnapshot, myTurn: boolean): string {
  if (snapshot.status === 'ACTIVE') {
    return myTurn ? 'Your move' : 'Waiting for your opponent';
  }
  if (snapshot.status === 'ABORTED') {
    return 'Game aborted — nobody moved in time. No rating change.';
  }
  // Unknown values fall back to the raw code rather than a blank: a new termination on
  // the server should read oddly here, not disappear.
  const how = snapshot.termination
    ? (TERMINATIONS[snapshot.termination] ?? snapshot.termination)
    : 'unknown';
  if (snapshot.result === 'DRAW') return `Draw by ${how}`;
  const winner = snapshot.result === 'WHITE_WIN' ? 'White' : 'Black';
  return snapshot.termination === 'TIMEOUT' ? `${winner} wins on time` : `${winner} wins by ${how}`;
}

const SCORES: Record<string, string> = { WHITE_WIN: '1–0', BLACK_WIN: '0–1', DRAW: '½–½' };

function shortOutcome(status: string, result: string | null): string {
  if (status === 'ABORTED') return 'aborted';
  return (result && SCORES[result]) ?? status.toLowerCase();
}

function Connection({ state }: { state: string }) {
  const label = {
    connecting: 'Connecting…',
    live: 'Live',
    reconnecting: 'Reconnecting…',
    closed: 'Disconnected',
  }[state] ?? state;

  // Shown always, not just when broken. A user who can see the connection is live trusts
  // a quiet board; one who cannot assumes the app is broken and reloads — which in a
  // real-time app is the worst possible response, because it drops the socket.
  return <span className={`connection ${state}`}>{label}</span>;
}
