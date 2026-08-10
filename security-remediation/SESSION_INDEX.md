# SESSION INDEX

> **Read [`../SESSION_PROTOCOL.md`](../SESSION_PROTOCOL.md) before trusting anything below.** This
> file previously marked all three rounds `DONE`. That was false — `SESSION-02.md` and
> `SESSION-03.md` do not exist on disk, and `SESSION-01.md`'s own exit-criteria section states
> "Round 1 is NOT closed." Corrected 2026-08-10 to match verified source state.

The remediation program is executed in **three fixed rounds**. Round numbers 01–03 are fixed slots;
each round's actual work may span many individual working sessions (see the protocol's budget
guidance) — the table below tracks the round, not a session count.

| # | Round | Priority | Log | Status (verified from source, not self-reported) | Findings |
|---|---|---|---|---|---|
| 01 | Stop the bleeding | P0 | [`sessions/SESSION-01.md`](./sessions/SESSION-01.md) | **IN PROGRESS** — code-level work for most findings verified present in source (see `SESSION_PROTOCOL.md` §0); 2 items (`SC-12`, credential rotation) blocked on operator/console access, not closeable by an AI session | S08-C1, SC-02, S08-H1, S03-L1, S07-C1, S07-H1, S02-L1, S06-H1, S02-M1, SC-12, S02-I3(partial) |
| 02 | Advertised guarantees | P1 | [`sessions/SESSION-02.md`](./sessions/SESSION-02.md) | **NOT STARTED** — file does not exist | S03-H1, S06-H2, S06-H3, S06-I2, S08-H5, S07-M1, S04-H1, S04-H2, S04-H3, S08-H4, S05-H1, S05-H3, S05-I1, S08-H2, S08-H3, S10-N2, S07-L4, S10-N3, SC-05, SC-04, SC-01, S04-I2 |
| 03 | P2 batch + HARD STOP | P2 | [`sessions/SESSION-03.md`](./sessions/SESSION-03.md) | **NOT STARTED** — file does not exist | all remaining (see log) |

Relationship to audit sessions: the audit's `SESSION-00…10` are **discovery** sessions (frozen,
immutable). These remediation `SESSION-01…03` are **fix** sessions and are the only ones this
program writes to. They are numbered independently and must not be confused with the audit's.

A round's log file (e.g. `SESSION-01.md`) may itself be revised in place across multiple working
sessions as more of that round's clusters get done — it is not one-log-per-one-sitting. Each
revision must follow `SESSION_PROTOCOL.md` §4 (verify from source before writing a disposition).
