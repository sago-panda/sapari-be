# db-core — persistence foundation

- Domain JPA entities use the shared entity bases in `com.sapari.storage.db.entity`.
- Schema changes belong to Flyway (`infra/AGENTS.md`); Hibernate does not own DDL.
- Live entity/domain mapping conventions are in `modules/live/AGENTS.md` (§Persistence & cache).
