import { useMemo, useState } from 'react';
import type { Side } from './protocol';

/**
 * One glyph per piece type, used for BOTH colours and tinted with CSS.
 *
 * The obvious mapping uses U+2654–2659 for white and U+265A–265F for black. It does not
 * survive contact with real font stacks: plenty of systems ship the outline set and not
 * the filled one, and the filled pieces render as tofu boxes. Partial coverage of a
 * Unicode block is normal, and there is no way to feature-detect it.
 *
 * Using six codepoints instead of twelve halves the surface, and `color` plus
 * `-webkit-text-stroke` distinguishes the sides more reliably than the glyphs did — a
 * white piece is genuinely white rather than "hollow", which is also easier to read on a
 * light square.
 */
const GLYPHS: Record<string, string> = {
  k: '\u2654', q: '\u2655', r: '\u2656', b: '\u2657', n: '\u2658', p: '\u2659',
};

const FILES = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];
const PROMOTIONS = [
  { code: 'q', label: '\u2655', name: 'QUEEN' },
  { code: 'r', label: '\u2656', name: 'ROOK' },
  { code: 'b', label: '\u2657', name: 'BISHOP' },
  { code: 'n', label: '\u2658', name: 'KNIGHT' },
] as const;

export type PromotionChoice = typeof PROMOTIONS[number]['name'];

interface BoardProps {
  fen: string;
  orientation: Side;
  legalMoves: string[];
  interactive: boolean;
  lastMoveUci: string | null;
  onMove: (from: string, to: string, promotion?: PromotionChoice) => void;
}

/**
 * An 8x8 board, click-to-move.
 *
 * <h3>Why this is hand-written rather than `react-chessboard`</h3>
 *
 * <p>Rendering a board is an 8x8 grid and a FEN parser — about eighty lines. Integrating,
 * configuring and version-tracking a library to do it is not obviously less work, and this
 * project's own principle (ADR-002) is about not reimplementing a *rules engine*, which is
 * a genuinely hard, genuinely wrong-in-subtle-ways problem. A grid of divs is not that.
 *
 * <p>The decisive point is below: **this component contains no chess logic at all.** It
 * cannot tell a legal move from an illegal one. Every square it will accept comes from the
 * server's `legalMoves`, so the claim that the client is never authoritative is structural
 * here rather than a convention someone could break.
 *
 * <p>Swapping in a library later is a single component change; nothing else depends on it.
 */
export function Board({ fen, orientation, legalMoves, interactive, lastMoveUci, onMove }: BoardProps) {
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

  const ranks = orientation === 'WHITE' ? [8, 7, 6, 5, 4, 3, 2, 1] : [1, 2, 3, 4, 5, 6, 7, 8];
  const files = orientation === 'WHITE' ? FILES : [...FILES].reverse();

  const lastFrom = lastMoveUci?.slice(0, 2);
  const lastTo = lastMoveUci?.slice(2, 4);

  function clickSquare(square: string) {
    if (!interactive) return;

    if (selected && destinations.has(square)) {
      // A promotion is detectable without any chess knowledge: the plain move is absent
      // from legalMoves while the five-character forms are present. No rules engine, no
      // special-casing the eighth rank.
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
    setSelected(squares[square] ? square : null);
  }

  return (
    <div className="board-wrapper">
      <div className="board">
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
          ].filter(Boolean).join(' ');

          return (
            <button key={square} className={classes} onClick={() => clickSquare(square)}
                    aria-label={square} type="button">
              {piece ? (
                <span className={`piece ${piece === piece.toUpperCase() ? 'white' : 'black'}`}>
                  {GLYPHS[piece.toLowerCase()]}
                </span>
              ) : null}
            </button>
          );
        }))}
      </div>

      {pendingPromotion && (
        <div className="promotion" role="dialog" aria-label="Choose a promotion piece">
          {/* No default to queen. Underpromotion to a knight is a real tactic, and
              guessing would be silently wrong roughly one game in a thousand. */}
          {PROMOTIONS.map((option) => (
            <button key={option.code} type="button" onClick={() => {
              onMove(pendingPromotion.from, pendingPromotion.to, option.name);
              setPendingPromotion(null);
            }}>{option.label}</button>
          ))}
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
