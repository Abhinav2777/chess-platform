# ADR-026: Tracing with Boot 4's native OpenTelemetry, across the outbox and SQS

**Status:** Accepted (9.1) · **Date:** 2026-10-01
**Builds on:** ADR-008 / ADR-020 (outbox, SQS), ADR-009 (WebSocket protocol).

## Context

Phase 9 needs traces before load tests: when a load test finds a slow path, the trace says where
the time went. The interesting paths cross asynchronous boundaries — a move ends a game, the
outbox row is sent later by a scheduled relay, SQS delivers it to the rating consumer — and a
naive setup yields two unrelated traces. The roadmap asks for exactly this: "trace context
injected into SQS message attributes so traces span the queue".

Owner's decision: **Boot 4 native OpenTelemetry**, not the OpenTelemetry Java agent.

## Decision

- **`spring-boot-starter-opentelemetry`** (Boot-managed: OpenTelemetry 1.62, Micrometer Tracing
  1.7): Micrometer Observation → OpenTelemetry bridge → OTLP. Spring MVC, Lettuce (Boot's
  `LettuceObservationAutoConfiguration`) and Spring Cloud AWS SQS (`observation-enabled`) are
  instrumented by their libraries; JDBC by **datasource-micrometer 2.3.0** (built against Boot
  4.1.1). No bytecode agent: no extra startup time on a JVM that already needs 60–122 s on small
  Fargate CPU, and nothing hidden in class rewriting.
- **WebSocket frames:** nothing instruments them, so `ChessWebSocketHandler` wraps each command in
  an Observation `chess.ws.message` (`type` tag): a span per frame (`ws MOVE`, …) parenting the
  JDBC/Valkey spans it causes, and a timer histogram — the server-side latency Phase 9 sets
  against k6's client-side numbers.
- **The outbox carries the trace** (V8: `outbox.trace_parent`). `Outbox.append` stores the current
  span as a W3C `traceparent`; the relay sends it as the SQS message attribute `traceparent`; the
  listener's observation continues it. Verified from bytecode that Spring Cloud AWS observes only
  the single-message send path — `sendMany` (our batches) is not observed, so nothing overwrites
  the stored header with the relay tick's own trace.
- **Export only when configured.** Boot 4 creates no trace exporter without
  `management.opentelemetry.tracing.export.otlp.endpoint`; OTLP *metrics* export, on by default and
  pointed at localhost:4318, is switched off in `application.yml`. Tests and CI log no exporter
  noise (counted: 0). Sampling 100 % (`CHESS_TRACING_SAMPLING`), parent-based.
- **Local backend:** `grafana/otel-lgtm` 0.34.0 (Grafana, Tempo, Prometheus, collector) under the
  compose profile `observability`.

## Verification

- `TracePropagationIntegrationTest`: real spans via OpenTelemetry's `InMemorySpanExporter`. A game
  ends inside a root span; the outbox row stores that trace; after the relay and SQS, the consumer
  span and the rating update's JDBC spans carry the **same trace ID**. Mutation-checked: without
  the relay's header the consumer span never joins.
- Live: a browser game resigned against `bootRun` → Tempo shows one trace, 1.07 s, 16 spans — `ws
  RESIGN` (28 ms, its queries), then `game-events receive` (40 ms, the Elo queries) at +1.03 s
  (`docs/screenshots/trace-resign-to-rating.png`). Valkey spans (`db.system=redis`) appear in
  matchmaker traces.

## Findings

- **~1 s of the rating push is the relay's poll interval**, not work: the consumer starts ~1.0 s
  after the resign commits and takes 40 ms. Explains the 0.8–1.4 s rating latencies measured since
  Phase 5. A candidate for the Phase 9 optimisation (LISTEN/NOTIFY or a shorter interval), to be
  decided against what the load tests find.
- **Every scheduler tick is a trace** (matchmaker once a second per instance): ~86k mostly-empty
  traces a day at 100 % sampling. Fine locally; production would lower sampling or drop
  scheduler observations with no child work (`ObservationPredicate`). Recorded, not built.
- Pub/sub (Valkey fan-out of RATING_UPDATED to other instances) is not yet a propagated boundary;
  only the queue is. Recorded.

## Interview angle

**Q:** "How do you trace a request across a message queue?"
**A:** The producer puts the W3C traceparent in the message attributes and the consumer continues it.
The twist in my system is the outbox: the message is sent later by a scheduled relay, in its own
trace, so I store the traceparent in the outbox row with the event and the relay forwards it. One
trace now goes from the player's resign click to the rating update — and it showed that a second of
that latency is the relay waiting for its next tick.
