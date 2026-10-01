// Cburnett set, CC BY-SA 3.0 — see README.md in this directory.
// Each file is < 1.5 KB, so Vite inlines them as data URIs: no extra requests, and nothing new
// to permit in the backend's static-resource rules (only /assets/** is open).
import wK from './wK.svg';
import wQ from './wQ.svg';
import wR from './wR.svg';
import wB from './wB.svg';
import wN from './wN.svg';
import wP from './wP.svg';
import bK from './bK.svg';
import bQ from './bQ.svg';
import bR from './bR.svg';
import bB from './bB.svg';
import bN from './bN.svg';
import bP from './bP.svg';

/** FEN letter (uppercase = white) → image URL. */
export const PIECE_IMAGES: Record<string, string> = {
  K: wK, Q: wQ, R: wR, B: wB, N: wN, P: wP,
  k: bK, q: bQ, r: bR, b: bB, n: bN, p: bP,
};

export const PIECE_NAMES: Record<string, string> = {
  k: 'king', q: 'queen', r: 'rook', b: 'bishop', n: 'knight', p: 'pawn',
};
