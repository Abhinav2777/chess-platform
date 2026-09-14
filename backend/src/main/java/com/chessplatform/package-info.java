/**
 * Modular monolith. Module dependency direction, strictly one-way:
 *
 * <pre>
 *   common &lt;- platform &lt;- identity &lt;- chess &lt;- game &lt;- {realtime, matchmaking, rating}
 * </pre>
 *
 * <p>Arrows point toward the dependency; nothing points backwards. Enforced by ArchUnit
 * in {@code com.chessplatform.architecture.ModuleBoundaryTest} — not by convention,
 * because a boundary nobody checks stops being a boundary within a month.
 *
 * <p>A module is entered only through its {@code *Facade}. Its {@code internal} package
 * is unreachable from anywhere else.
 *
 * @see <a href="../../../../docs/adr/ADR-001-modular-monolith.md">ADR-001</a>
 */
package com.chessplatform;
