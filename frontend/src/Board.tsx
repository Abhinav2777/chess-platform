import { useMemo, useState } from 'react';
import type { Side } from './protocol';
import { PIECE_IMAGES, PIECE_NAMES } from './pieces';

const FILES = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];
const PROMOTIONS = [
  { code: 'q', name: 'QUEEN' },
  { code: 'r', name: 'ROOK' },
  { code: 'b', name: 'BISHOP' },
  { code: 'n', name: 'KNIGHT' },
] as const;

export type PromotionChoice = typeof PROMOTIONS[number]['name'];

interface BoardProps {
  fen: string;
  orientation: Side;
  legalMoves: string[];
  interactive: boolean;
  lastMoveUci: string | null;
  /** The side whose king is in check, if any — from the server's SAN, never computed here. */
  checkSide: Side | null;
  onMove: (from: string, to: string, promotion?: PromotionChoice) => void;
}

/**
 * An 8x8 board, click-to-move.
 *
 * <h3>Why this is hand-written rather than `react-chessboard`</h3>
 *
 * <p>Rendering a board is an 8x8 grid and a FEN parser. Integrating, configuring and
 * version-tracking a library to do it is not obviously less work, and this project's own
 * principle (ADR-002) is about not reimplementing a *rules engine*, which is a genuinely hard,
 * genuinely wrong-in-subtle-ways problem. A grid of buttons is not that.
 *
 * <p>The decisive point: **this component contains no chess logic at all.** It cannot tell a
 * legal move from an illegal one. Every square it will accept comes from the server's
 * `legalMoves`; even the check highlight is read off the server's SAN (`+`/`#`). The claim
 * that the client is never authoritative is structural here, not a convention.
 *
 * <p>Pieces: the Cburnett SVG set (CC BY-SA 3.0, `pieces/README.md`). Unicode glyphs were used
 * before; they depend on the font stack and the white ones washed out on light squares.
 */
export function Board({ fen, orientation, legalMoves, interactive, lastMoveUci, checkSide, onMove }: BoardProps) {
  const [selected, setSelected] = useState<string | null>(null);
  const [pendingPromotion, setPendingPromotion] = useState<{ from: string; to: string } | null>(null);

  const squares = useMemo(() => parseFen(fen), [fen]);

  // Destinations reachable from the selected square — read off the server's list, never
  // computed. A UCI move is 4 characters, or 5 when it promotes.
  const destinations = useMemo(() => {
    if (!selected) return new Set<string>();
    return new Set(legalMoves
      .filter((uci) => uci.startsWith(selected))
      .map((uci) => uci.slice(2, 4)));
  }, [selected, legalMoves]);

  // Squares with at least one legal move: only those can be picked up.
  const movable = useMemo(() => new Set(legalMoves.map((uci) => uci.slice(0, 2))), [legalMoves]);

  const checkedKing = useMemo(() => {
    if (!checkSide) return null;
    const king = checkSide === 'WHITE' ? 'K' : 'k';
    return Object.keys(squares).find((square) => squares[square] === king) ?? null;
  }, [checkSide, squares]);

  const ranks = orientation === 'WHITE' ? [8, 7, 6, 5, 4, 3, 2, 1] : [1, 2, 3, 4, 5, 6, 7, 8];
  const files = orientation === 'WHITE' ? FILES : [...FILES].reverse();
  const bottomRank = ranks[7];
  const leftFile = files[0];

  const lastFrom = lastMoveUci?.slice(0, 2);
  const lastTo = lastMoveUci?.slice(2, 4);

  function clickSquare(square: string) {
    if (!interactive) return;

    if (selected && destinations.has(square)) {
      // A promotion is detectable without any chess knowledge: the plain move is absent
      // from legalMoves while the five-character forms are present.
      const needsPromotion = !legalMoves.includes(selected + square)
        && legalMoves.some((uci) => uci.startsWith(selected + square) && uci.length === 5);

      if (needsPromotion) {
        setPendingPromotion({ from: selected, to: square });
      } else {
        onMove(selected, square);
      }
      setSelected(null);
      return;
    }
    setSelected(square === selected || !movable.has(square) ? null : square);
  }

  const promotingWhite = pendingPromotion ? squares[pendingPromotion.from] === 'P' : true;

  return (
    <div className="board-wrapper">
      <div className={`board${interactive ? ' interactive' : ''}`}>
        {ranks.flatMap((rank) => files.map((file) => {
          const square = `${file}${rank}`;
          const piece = squares[square];
          const dark = (FILES.indexOf(file) + rank) % 2 === 0;
          const classes = [
            'square',
            dark ? 'dark' : 'light',
            selected === square ? 'selected' : '',
            destinations.has(square) ? (piece ? 'capture' : 'target') : '',
            square === lastFrom || square === lastTo ? 'last-move' : '',
            square === checkedKing ? 'in-check' : '',
          ].filter(Boolean).join(' ');

          return (
            // aria-label is the square name alone: it is what assistive tech announces, and
            // what the browser checks select (getByRole('button', { name: 'e2' })).
            <button key={square} className={classes} onClick={() => clickSquare(square)}
                    aria-label={square} type="button">
              {file === leftFile && <span className="coord rank" aria-hidden="true">{rank}</span>}
              {rank === bottomRank && <span className="coord file" aria-hidden="true">{file}</span>}
              {piece && (
                <img className="piece" src={PIECE_IMAGES[piece]} draggable={false}
                     alt={`${piece === piece.toUpperCase() ? 'white' : 'black'} ${PIECE_NAMES[piece.toLowerCase()]}`} />
              )}
            </button>
          );
        }))}
      </div>

      {pendingPromotion && (
        <div className="promotion" role="dialog" aria-label="Choose a promotion piece">
          <div className="promotion-choices">
            {/* No default to queen. Underpromotion to a knight is a real tactic, and guessing
                would be silently wrong roughly one game in a thousand. */}
            {PROMOTIONS.map((option) => {
              const letter = promotingWhite ? option.code.toUpperCase() : option.code;
              return (
                <button key={option.code} type="button" aria-label={`Promote to ${option.name.toLowerCase()}`}
                        onClick={() => {
                          onMove(pendingPromotion.from, pendingPromotion.to, option.name);
                          setPendingPromotion(null);
                        }}>
                  <img src={PIECE_IMAGES[letter]} alt="" />
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

/** Expands the placement field of a FEN into a square-to-piece map. */
function parseFen(fen: string): Record<string, string> {
  const placement = fen.split(' ')[0] ?? '';
  const squares: Record<string, string> = {};

  placement.split('/').forEach((row, index) => {
    const rank = 8 - index;
    let file = 0;
    for (const symbol of row) {
      if (symbol >= '1' && symbol <= '8') {
        file += Number(symbol);
      } else {
        squares[`${FILES[file]}${rank}`] = symbol;
        file += 1;
      }
    }
  });

  return squares;
}
