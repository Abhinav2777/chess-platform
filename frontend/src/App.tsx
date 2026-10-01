import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, type GameSummary, type Session, type TimeControl } from './api';
import { Board } from './Board';
import { PlayerClock } from './Clock';
import { PIECE_IMAGES } from './pieces';
import { useGame } from './useGame';
import { useSeek } from './useSeek';
import type { GameSnapshot, MatchFound, Side } from './protocol';

/**
 * Presets rather than free-form inputs. The server accepts anything from 10 s to 24 h
 * (validated there, not here), but a challenge form with two number fields is friction for a
 * decision most players make by habit.
 */
const TIME_CONTROLS: { label: string; time: string; kind: string; value: TimeControl }[] = [
  { label: '1+0 bullet', time: '1+0', kind: 'Bullet', value: { initialSeconds: 60, incrementSeconds: 0 } },
  { label: '3+2 blitz', time: '3+2', kind: 'Blitz', value: { initialSeconds: 180, incrementSeconds: 2 } },
  { label: '5+3 blitz', time: '5+3', kind: 'Blitz', value: { initialSeconds: 300, incrementSeconds: 3 } },
  { label: '10+0 rapid', time: '10+0', kind: 'Rapid', value: { initialSeconds: 600, incrementSeconds: 0 } },
];
const DEFAULT_TIME_CONTROL = 2;

/** Mirrors Game.FIRST_MOVE_WINDOW on the server. Used for a hint, never for a decision. */
const FIRST_MOVE_WINDOW_SECONDS = 30;

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [game, setGame] = useState<GameSummary | null>(null);
  const [rating, setRating] = useState<number | null>(null);

  // The current rating, read from the server whenever the lobby is shown — the pull side of
  // RATING_UPDATED, so a missed push never leaves a stale number on screen.
  useEffect(() => {
    if (!session || game) return;
    api.me().then((me) => setRating(me.rating)).catch(() => { /* shown without it */ });
  }, [session, game]);

  async function signOut() {
    try { await api.logout(); } catch { /* the session ends locally either way */ }
    setGame(null);
    setRating(null);
    setSession(null);
  }

  return (
    <div className="app">
      <TopBar session={session} rating={rating} onHome={() => setGame(null)} onSignOut={signOut} />
      {!session
        ? <SignIn onSignedIn={setSession} />
        : (
          <main className="container">
            {game
              ? <GameView session={session} game={game} onLeave={() => setGame(null)} />
              : <Lobby session={session} rating={rating} onOpen={setGame} />}
          </main>
        )}
      <footer className="footer">
        <div className="container">
          Real-time chess · pieces by{' '}
          <a href="https://commons.wikimedia.org/wiki/User:Cburnett" target="_blank" rel="noreferrer">Cburnett</a>,{' '}
          <a href="https://creativecommons.org/licenses/by-sa/3.0/" target="_blank" rel="noreferrer">CC BY-SA 3.0</a>
        </div>
      </footer>
    </div>
  );
}

function Logo() {
  return <span className="brand-mark" aria-hidden="true"><img src={PIECE_IMAGES.N} alt="" /></span>;
}

function TopBar({ session, rating, onHome, onSignOut }: {
  session: Session | null; rating: number | null; onHome: () => void; onSignOut: () => void;
}) {
  return (
    <header className="topbar">
      <div className="topbar-inner">
        <button type="button" className="brand" onClick={onHome} disabled={!session}>
          <Logo /> Chess Platform
        </button>
        {session && (
          <div className="user-menu">
            {rating !== null && <span className="rating-chip" title="Your rating">{rating}</span>}
            <span className="user-name">{session.username}</span>
            <button type="button" className="btn ghost" onClick={onSignOut}>Sign out</button>
          </div>
        )}
      </div>
    </header>
  );
}

function SignIn({ onSignedIn }: { onSignedIn: (session: Session) => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [registering, setRegistering] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      onSignedIn(registering
        ? await api.register(username, `${username}@example.com`, password)
        : await api.login(username, password));
    } catch (failure) {
      // The server's problem+json `detail` is written for users, so it is shown as-is.
      // Anything unexpected is deliberately opaque — an exception message can carry a stack
      // frame or a hostname.
      setError(failure instanceof ApiError ? failure.message : 'Could not sign in.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth">
      <form className="card auth-card" onSubmit={submit}>
        <div className="auth-head">
          <Logo />
          <h1>{registering ? 'Create your account' : 'Welcome back'}</h1>
          <p className="hint">{registering ? 'Pick a username and start playing in seconds.' : 'Sign in to find a game.'}</p>
        </div>
        <div className="field">
          <label htmlFor="username">Username</label>
          <input id="username" className="input" value={username} onChange={(e) => setUsername(e.target.value)}
                 placeholder="username" autoComplete="username" autoFocus required />
        </div>
        <div className="field">
          <label htmlFor="password">Password</label>
          <input id="password" className="input" value={password} onChange={(e) => setPassword(e.target.value)}
                 placeholder="password" type="password" required
                 autoComplete={registering ? 'new-password' : 'current-password'} />
          {registering && <span className="hint">At least 12 characters. Length beats symbols.</span>}
        </div>
        {error && <p className="alert error" role="alert">{error}</p>}
        <button type="submit" className="btn primary block" disabled={busy}>
          {registering ? 'Register' : 'Sign in'}
        </button>
        <p className="auth-switch">
          {registering ? 'Already have an account? ' : 'New here? '}
          <button type="button" className="link" onClick={() => { setRegistering(!registering); setError(null); }}>
            {registering ? 'I already have an account' : 'Create an account'}
          </button>
        </p>
      </form>
    </div>
  );
}

function Lobby({ session, rating, onOpen }: {
  session: Session; rating: number | null; onOpen: (game: GameSummary) => void;
}) {
  const [opponent, setOpponent] = useState('');
  const [timeControl, setTimeControl] = useState(DEFAULT_TIME_CONTROL);
  const [games, setGames] = useState<GameSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.myGames().then(setGames).catch(() => setError('Could not load games.'));
  }, []);

  // A match arrives as a game id. The game list already carries usernames, so the new game is
  // read from there rather than adding an endpoint for one screen.
  const openMatch = useCallback((match: MatchFound) => {
    api.myGames()
      .then((latest) => {
        const found = latest.find((entry) => entry.id === match.gameId);
        if (found) onOpen(found);
        else setError('Matched — open the game from your games list.');
      })
      .catch(() => setError('Matched — open the game from your games list.'));
  }, [onOpen]);

  const seek = useSeek(openMatch);

  // A ticking "0:12" while seeking. Display only.
  const [, setTick] = useState(0);
  useEffect(() => {
    if (seek.state.phase === 'idle') return;
    const timer = window.setInterval(() => setTick((n) => n + 1), 1_000);
    return () => window.clearInterval(timer);
  }, [seek.state.phase]);

  // Direct challenges are still discovered by polling: a challenged player gets no
  // notification (matchmaking does not need this — MATCH_FOUND is pushed, ADR-016).
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

  const active = games.filter((entry) => entry.status === 'ACTIVE');
  const finished = games.filter((entry) => entry.status !== 'ACTIVE');

  return (
    <div className="lobby">
      <div className="lobby-hero">
        <div>
          <h1>Hello, {session.username}{rating !== null ? <> · <span className="rating-value">{rating}</span></> : null}</h1>
          <p className="hint">Pick a time control and we will find you an opponent near your rating.</p>
        </div>
      </div>

      <div className="lobby-grid">
        <section className="card" aria-labelledby="quick-pairing">
          <div className="card-header">
            <h2 id="quick-pairing">Quick pairing</h2>
            <span className="hint">Rated</span>
          </div>
          {seek.state.phase === 'idle' ? (
            <div className="seek">
              {TIME_CONTROLS.map((option) => (
                <button key={option.label} type="button" className="tc-card" aria-label={option.label}
                        onClick={() => seek.start(option.value)}>
                  <span className="tc-time">{option.time}</span>
                  <span className="tc-kind">{option.kind}</span>
                </button>
              ))}
            </div>
          ) : (
            <div className="seek seeking" aria-live="polite">
              <div className="spinner" aria-hidden="true" />
              <span>
                Looking for a {seek.state.timeControl.initialSeconds / 60}+
                {seek.state.timeControl.incrementSeconds} opponent… {elapsed(seek.state.since)}
                {seek.connection !== 'live' ? ` (${seek.connection})` : ''}
              </span>
              <p className="hint">The acceptable rating gap widens the longer you wait.</p>
              <button type="button" className="btn" onClick={seek.cancel} disabled={seek.state.phase === 'cancelling'}>
                {seek.state.phase === 'cancelling' ? 'Cancelling…' : 'Cancel'}
              </button>
            </div>
          )}
          {seek.error && <p className="alert error" style={{ marginTop: '0.75rem' }}>{seek.error}</p>}
        </section>

        <div className="lobby-side">
          <section className="card" aria-labelledby="challenge">
            <div className="card-header"><h2 id="challenge">Challenge a friend</h2></div>
            <form onSubmit={challenge} className="challenge">
              <input className="input" value={opponent} onChange={(e) => setOpponent(e.target.value)}
                     placeholder="opponent username" aria-label="opponent username" required />
              <select className="input" value={timeControl} onChange={(e) => setTimeControl(Number(e.target.value))}
                      aria-label="time control">
                {TIME_CONTROLS.map((option, index) =>
                  <option key={option.label} value={index}>{option.label}</option>)}
              </select>
              {/* No colour picker: the server draws at random. Letting the challenger always take
                  White would be a way to farm rating — White scores about 54%. */}
              <button type="submit" className="btn primary">Send challenge</button>
            </form>
            <p className="hint" style={{ marginTop: '0.75rem' }}>
              They will find it under their games. Each side has {FIRST_MOVE_WINDOW_SECONDS} seconds
              to make a first move, or the game is aborted, unrated.
            </p>
            {error && <p className="alert error" style={{ marginTop: '0.75rem' }}>{error}</p>}
          </section>

          <section className="card" aria-labelledby="your-games">
            <div className="card-header">
              <h2 id="your-games">Your games</h2>
              {active.length > 0 && <span className="badge live">{active.length} in progress</span>}
            </div>
            {games.length === 0
              ? <p className="empty">No games yet. Start one with quick pairing.</p>
              : (
                <ul className="games">
                  {[...active, ...finished].map((entry) => (
                    <li key={entry.id}><GameRow entry={entry} session={session} onOpen={onOpen} /></li>
                  ))}
                </ul>
              )}
          </section>
        </div>
      </div>
    </div>
  );
}

function GameRow({ entry, session, onOpen }: { entry: GameSummary; session: Session; onOpen: (g: GameSummary) => void }) {
  const iAmWhite = entry.whitePlayerId === session.userId;
  const opponent = (iAmWhite ? entry.blackUsername : entry.whiteUsername) ?? 'opponent';
  const live = entry.status === 'ACTIVE';
  return (
    <button type="button" className="game-row" onClick={() => onOpen(entry)}>
      <span className={`dot${live ? ' live' : ''}`} aria-hidden="true" />
      <span>
        <span className="who">vs {opponent}</span>
        <span className="meta">as {iAmWhite ? 'white' : 'black'} · {Math.ceil(entry.ply / 2)} moves</span>
      </span>
      <span className={`badge${live ? ' live' : ''}`}>{live ? 'Resume' : shortOutcome(entry.status, entry.result)}</span>
    </button>
  );
}

function GameView({ session, game, onLeave }: { session: Session; game: GameSummary; onLeave: () => void }) {
  const { connection, degraded, snapshot, clock, moves, failure, myTurn, submitMove, resign, rating } =
    useGame(game.id);
  const [confirmingResign, setConfirmingResign] = useState(false);

  if (!snapshot) {
    return <div className="loading"><div className="spinner" /><span>Loading the board…</span></div>;
  }

  const orientation: Side = snapshot.yourSide ?? 'WHITE';
  const opponentSide: Side = orientation === 'WHITE' ? 'BLACK' : 'WHITE';
  const nameOf = (side: Side) =>
    (side === 'WHITE' ? game.whiteUsername : game.blackUsername)
    ?? (side === snapshot.yourSide ? session.username : 'Opponent');

  // Before both players have moved, the server treats resigning as aborting (no result, no
  // rating change). The button says so, rather than surprising anyone.
  const awaitingFirstMove = snapshot.ply < 2;
  const over = snapshot.status !== 'ACTIVE';

  // Check is read off the server's notation: the last move's SAN ends in + or # exactly when
  // the side now to move is in check. No rules on the client.
  const lastSan = moves.length > 0 ? moves[moves.length - 1]!.san : '';
  const checkSide: Side | null = /[+#]$/.test(lastSan) ? snapshot.sideToMove : null;

  return (
    <div className="game">
      <div className="board-column">
        <PlayerClock clock={clock} side={opponentSide} name={nameOf(opponentSide)} isYou={false}
                     online={snapshot.opponentOnline} />
        <div style={{ position: 'relative' }}>
          <Board
            fen={snapshot.fen}
            orientation={orientation}
            legalMoves={snapshot.legalMoves}
            // Interactivity follows the SERVER's view of whose turn it is, not a local guess.
            // The server would reject an out-of-turn move regardless — this only stops the UI
            // offering one.
            interactive={myTurn}
            lastMoveUci={snapshot.lastMoveUci}
            checkSide={checkSide}
            onMove={submitMove}
          />
          {over && <GameOver snapshot={snapshot} rating={rating} onLeave={onLeave} />}
        </div>
        <PlayerClock clock={clock} side={orientation} name={nameOf(orientation)}
                     isYou={snapshot.yourSide !== null} />
      </div>

      <aside className="sidebar">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '0.5rem' }}>
          <button type="button" className="btn ghost" onClick={onLeave}>← Lobby</button>
          <Connection state={connection} degraded={degraded} />
        </div>

        <div className={`status${myTurn ? ' your-move' : ''}${over ? ' over' : ''}`} role="status">
          {statusLine(snapshot, myTurn)}
          {snapshot.status === 'ACTIVE' && awaitingFirstMove && (
            <span className="sub">First move within {FIRST_MOVE_WINDOW_SECONDS} s, or the game is aborted, unrated.</span>
          )}
          {snapshot.status === 'ACTIVE' && !snapshot.opponentOnline && (
            // Deliberately stated: a disconnected opponent's clock keeps running. Chess does
            // not pause for network problems (ADR-006).
            <span className="sub">Your opponent is offline. Clocks do not pause.</span>
          )}
        </div>

        <section className="card moves-card" aria-label="Moves">
          <div className="card-header"><h2>Moves</h2><span className="hint">{moves.length} plies</span></div>
          {moves.length === 0
            ? <p className="hint moves-empty">No moves yet.</p>
            : <ol className="moves">{moves.map((move) => <li key={move.ply}>{move.san}</li>)}</ol>}
        </section>

        {snapshot.status === 'ACTIVE' && snapshot.yourSide !== null && (
          awaitingFirstMove
            ? <button type="button" className="btn block" onClick={resign}>Abort</button>
            : confirmingResign
              ? (
                <div className="confirm card">
                  <span>Resign this game? It counts as a loss.</span>
                  <div className="actions">
                    <button type="button" className="btn danger" onClick={() => { setConfirmingResign(false); resign(); }}>
                      Yes, resign
                    </button>
                    <button type="button" className="btn" onClick={() => setConfirmingResign(false)}>Keep playing</button>
                  </div>
                </div>
              )
              : <button type="button" className="btn danger block" onClick={() => setConfirmingResign(true)}>Resign</button>
        )}
        {failure && <p className="alert error" role="alert">{failure.message}</p>}
      </aside>
    </div>
  );
}

function GameOver({ snapshot, rating, onLeave }: {
  snapshot: GameSnapshot; rating: { rating: number; delta: number } | null; onLeave: () => void;
}) {
  const title = snapshot.status === 'ABORTED' ? 'Game aborted'
    : snapshot.result === 'DRAW' ? 'Draw'
      : (snapshot.result === 'WHITE_WIN') === (snapshot.yourSide === 'WHITE') && snapshot.yourSide !== null
        ? 'You won' : snapshot.yourSide === null ? `${snapshot.result === 'WHITE_WIN' ? 'White' : 'Black'} won` : 'You lost';
  return (
    <div className="game-over" role="dialog" aria-label="Game over">
      <div className="game-over-card">
        <h2>{title}</h2>
        <p className="hint">{statusLine(snapshot, false)}</p>
        {snapshot.result && <p className="score">{SCORES[snapshot.result]}</p>}
        {snapshot.status === 'FINISHED' && snapshot.yourSide !== null && (
          // Ratings are applied asynchronously by the worker, so this follows GAME_FINISHED by
          // a second or two. Said so, rather than left blank.
          <p className="rating">
            {rating
              ? <>Rating {rating.rating} <span className={rating.delta >= 0 ? 'gain' : 'loss'}>
                  ({rating.delta >= 0 ? '+' : ''}{rating.delta})</span></>
              : <span className="hint">Updating rating…</span>}
          </p>
        )}
        <button type="button" className="btn primary block" onClick={onLeave}>Back to lobby</button>
      </div>
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
    return 'Nobody moved in time. No rating change.';
  }
  // Unknown values fall back to the raw code rather than a blank: a new termination on the
  // server should read oddly here, not disappear.
  const how = snapshot.termination
    ? (TERMINATIONS[snapshot.termination] ?? snapshot.termination)
    : 'unknown';
  if (snapshot.result === 'DRAW') return `Draw by ${how}`;
  const winner = snapshot.result === 'WHITE_WIN' ? 'White' : 'Black';
  return snapshot.termination === 'TIMEOUT' ? `${winner} wins on time` : `${winner} wins by ${how}`;
}

function elapsed(since: number): string {
  const seconds = Math.max(0, Math.floor((Date.now() - since) / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

const SCORES: Record<string, string> = { WHITE_WIN: '1–0', BLACK_WIN: '0–1', DRAW: '½–½' };

function shortOutcome(status: string, result: string | null): string {
  if (status === 'ABORTED') return 'Aborted';
  return (result && SCORES[result]) ?? status.toLowerCase();
}

function Connection({ state, degraded = false }: { state: string; degraded?: boolean }) {
  const label = degraded && state === 'live'
    // The socket is up but live updates are not arriving (fanout down); the board is being
    // refreshed by polling. Said plainly, so a slower board does not look broken.
    ? 'Live · updates delayed'
    : {
      connecting: 'Connecting…',
      live: 'Live',
      reconnecting: 'Reconnecting…',
      closed: 'Disconnected',
    }[state] ?? state;

  // Shown always, not just when broken. A user who can see the connection is live trusts a
  // quiet board; one who cannot assumes the app is broken and reloads — which in a real-time
  // app is the worst possible response, because it drops the socket.
  return <span className={`connection ${state}${degraded ? ' degraded' : ''}`}>{label}</span>;
}
