-- V3: side_to_move CHAR(1) -> VARCHAR(5), storing the enum name.
--
-- Two problems with the original CHAR(1):
--
-- 1. PostgreSQL stores char(n) as blank-padded `bpchar`, which Hibernate reports as
--    Types#CHAR while a mapped String expects varchar. `ddl-auto: validate` rejects it.
--    Identical to the token_hash defect in V2 and the reason ARCHITECTURE.md 4.2.1 exists.
-- 2. 'w'/'b' required an AttributeConverter to reach a Side enum. Storing the enum name
--    lets @Enumerated(STRING) map it with no conversion code at all.
--
-- Fixed forward, not by editing V1. An applied migration is immutable — editing one
-- breaks Flyway's checksum for every environment that already ran it, and "just run
-- flyway repair" in production is how schema drift starts.
--
-- The FEN in games.fen remains the authority for side to move; this column is a
-- denormalisation for querying ("whose turn is it") without parsing FEN.

ALTER TABLE games DROP CONSTRAINT ck_games_side;

ALTER TABLE games
    ALTER COLUMN side_to_move TYPE VARCHAR(5)
    USING CASE TRIM(side_to_move) WHEN 'w' THEN 'WHITE' ELSE 'BLACK' END;

ALTER TABLE games
    ADD CONSTRAINT ck_games_side CHECK (side_to_move IN ('WHITE', 'BLACK'));
