import { PROTOCOL_VERSION, type Envelope, type Failure, type GameFinished,
         type GameSnapshot, type MatchFound, type MoveMade, type PlayerPresence,
         type RatingUpdated, type SeekStatus } from './protocol';

const WS_URL = import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080/ws';

export type ConnectionState = 'connecting' | 'live' | 'reconnecting' | 'closed';

/**
 * Game handlers are optional because the same socket serves the lobby, where there is no
 * game. A message with no handler is ignored, like an unknown type.
 */
export interface GameSocketHandlers {
  onState: (state: ConnectionState) => void;
  onError: (failure: Failure) => void;
  onSnapshot?: (snapshot: GameSnapshot) => void;
  onMove?: (move: MoveMade) => void;
  onFinished?: (finished: GameFinished) => void;
  onPresence?: (presence: PlayerPresence) => void;
  onSeekStatus?: (status: SeekStatus) => void;
  onMatchFound?: (match: MatchFound) => void;
  onRatingUpdated?: (update: RatingUpdated) => void;
}

export interface MoveRequest {
  clientMoveId: string;
  expectedPly: number;
  from: string;
  to: string;
  promotion?: 'QUEEN' | 'ROOK' | 'BISHOP' | 'KNIGHT';
}

/**
 * The protocol client.
 *
 * Deliberately not a React hook: reconnection, backoff and heartbeats are long-lived
 * concerns with their own lifecycle, and expressing them through effects and refs makes
 * them harder to follow, not easier. `useGame` adapts this to React; this file can be
 * reasoned about — and tested — without React at all.
 *
 * One class for the game and the lobby (Milestone 4.1c). With a gameId it subscribes after
 * AUTH_OK; with null it is a lobby socket that only authenticates, for matchmaking. A
 * second socket class would have been a second copy of reconnect, backoff, heartbeat and
 * first-frame auth — the parts of this file that are hard to get right.
 */
export class GameSocket {
  private socket: WebSocket | null = null;
  private heartbeat: number | null = null;
  private reconnectTimer: number | null = null;
  private attempt = 0;
  private closedByUs = false;

  constructor(
    private readonly gameId: string | null,
    private readonly token: () => string | null,
    private readonly handlers: GameSocketHandlers,
  ) {}

  connect(): void {
    this.closedByUs = false;
    this.handlers.onState(this.attempt === 0 ? 'connecting' : 'reconnecting');

    const socket = new WebSocket(WS_URL);
    this.socket = socket;

    socket.onopen = () => {
      // The handshake carries no credential — the browser API cannot set an
      // Authorization header on it — so the first frame authenticates instead (ADR-009).
      const token = this.token();
      if (!token) {
        socket.close();
        return;
      }
      this.send('AUTH', { token });
    };

    socket.onmessage = (event) => this.receive(JSON.parse(event.data) as Envelope);

    socket.onclose = () => {
      this.stopHeartbeat();
      if (!this.closedByUs) {
        this.scheduleReconnect();
      }
    };

    // onerror is followed by onclose, so reconnection is handled in one place rather than
    // racing two handlers to schedule it.
    socket.onerror = () => socket.close();
  }

  private receive(envelope: Envelope): void {
    if (envelope.v !== PROTOCOL_VERSION) {
      this.handlers.onError({
        code: 'PROTOCOL_VERSION',
        message: `Server speaks protocol v${envelope.v}; this client speaks v${PROTOCOL_VERSION}. Reload.`,
      });
      return;
    }

    switch (envelope.type) {
      case 'AUTH_OK':
        // Only now is the connection genuinely usable. Resetting the backoff here rather
        // than on `open` matters: a server that accepts TCP connections but rejects every
        // token would otherwise look healthy and be hammered at full rate forever.
        this.attempt = 0;
        this.handlers.onState('live');
        if (this.gameId !== null) {
          this.send('SUBSCRIBE', { gameId: this.gameId });
        }
        this.startHeartbeat();
        break;

      case 'AUTH_FAILED':
        // Not retryable. Backing off and trying again with the same dead token would be
        // an infinite loop against the server.
        this.closedByUs = true;
        this.handlers.onError(envelope.payload as Failure);
        this.handlers.onState('closed');
        this.socket?.close();
        break;

      case 'GAME_SNAPSHOT':
        this.handlers.onSnapshot?.(envelope.payload as GameSnapshot);
        break;
      case 'MOVE_MADE':
        this.handlers.onMove?.(envelope.payload as MoveMade);
        break;
      case 'GAME_FINISHED':
        this.handlers.onFinished?.(envelope.payload as GameFinished);
        break;
      case 'PLAYER_PRESENCE':
        this.handlers.onPresence?.(envelope.payload as PlayerPresence);
        break;
      case 'SEEK_STATUS':
        this.handlers.onSeekStatus?.(envelope.payload as SeekStatus);
        break;
      case 'RATING_UPDATED':
        this.handlers.onRatingUpdated?.(envelope.payload as RatingUpdated);
        break;
      case 'MATCH_FOUND':
        this.handlers.onMatchFound?.(envelope.payload as MatchFound);
        break;
      case 'ERROR':
        this.handlers.onError(envelope.payload as Failure);
        break;
      case 'PONG':
        break;
      default:
        // Unknown types are ignored rather than treated as errors, so the server can add
        // message types without breaking deployed clients.
        break;
    }
  }

  move(request: MoveRequest): void {
    this.send('MOVE', { gameId: this.gameId, ...request });
  }

  /** Idempotent on the server: repeating it is the seek's heartbeat (ADR-016). */
  seek(initialSeconds: number, incrementSeconds: number): void {
    this.send('SEEK', { initialSeconds, incrementSeconds });
  }

  cancelSeek(): void {
    this.send('CANCEL_SEEK', null);
  }

  resign(): void {
    this.send('RESIGN', { gameId: this.gameId });
  }

  /**
   * Asks for a fresh snapshot. Re-subscribing to the game this socket already watches is
   * harmless on the server — the watcher set and presence both ignore a repeat — and
   * reuses the one path that already produces the full state, rather than adding a
   * second message type that would have to stay in step with it.
   */
  resync(): void {
    this.send('SUBSCRIBE', { gameId: this.gameId });
  }

  private send(type: string, payload: unknown): void {
    if (this.socket?.readyState !== WebSocket.OPEN) {
      return;
    }
    this.socket.send(JSON.stringify({
      v: PROTOCOL_VERSION, type, ts: new Date().toISOString(), payload,
    }));
  }

  /**
   * Application-level heartbeat.
   *
   * Not redundant with TCP keepalive. An AWS ALB closes an idle connection after 60
   * seconds regardless of TCP state, and a half-open connection — peer gone, no FIN
   * delivered — looks perfectly healthy to the socket layer. Only traffic the application
   * generates proves the path is alive end to end.
   *
   * It also refreshes the server-side presence key, so a connected but quiet player does
   * not expire and appear to have vanished mid-game.
   */
  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeat = window.setInterval(() => this.send('PING', null), 25_000);
  }

  private stopHeartbeat(): void {
    if (this.heartbeat !== null) {
      window.clearInterval(this.heartbeat);
      this.heartbeat = null;
    }
  }

  /**
   * Exponential backoff with full jitter.
   *
   * The jitter is the part that matters. When a server restarts it drops every connection
   * at the same instant; without randomisation every client would retry in lockstep and
   * arrive together, and the load spike from the reconnect storm can prevent the server
   * coming back at all. Spreading arrivals uniformly across the window turns a stampede
   * into a queue.
   *
   * Capped at 30 seconds, because a client that gives up entirely is worse than one that
   * keeps trying slowly — the user should not have to know to reload the page.
   */
  private scheduleReconnect(): void {
    this.handlers.onState('reconnecting');
    const ceiling = Math.min(30_000, 500 * 2 ** this.attempt);
    const delay = Math.random() * ceiling;
    this.attempt += 1;
    this.reconnectTimer = window.setTimeout(() => this.connect(), delay);
  }

  close(): void {
    this.closedByUs = true;
    this.stopHeartbeat();
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
    }
    this.socket?.close();
    this.handlers.onState('closed');
  }
}
