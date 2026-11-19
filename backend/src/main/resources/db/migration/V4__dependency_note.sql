-- Free-text note about how a dependency version was (not) determined, or where it is declared
-- (e.g. "managed by parent org.springframework.boot:spring-boot-starter-parent:3.3.1 (not resolved)",
-- "declared in profile ci").
ALTER TABLE dependency ADD COLUMN note varchar(512);
