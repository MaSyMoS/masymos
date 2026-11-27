# Release and migration notes: legacy MaSyMoS to modern

Verification date: 2026-11-23 (§7 gained the root-namespace refusal, decision 45; previously verified
2026-11-13, and §2's re-index bullet was extended on 2026-11-22 with the SED-ML change id). Written for someone who runs legacy MaSyMoS (the Java library, the
MORRE Neo4j server plugin and the `Main*` command classes) and has to move. It is one page on
purpose: what breaks, what to change, and where the detail lives. Per-capability evidence is in the
parity documents of the migration documentation; endpoints and CLI in [`api-reference.md`](api-reference.md).

## 1. The deployment model changed

Legacy ran *inside* a Neo4j server: the MORRE plugin attached to a `GraphDatabaseService`, and the
CLI opened a graph directory with `-dbPath`. Modern has three roles and no embedded database:

- a **Neo4j server** (reached over Bolt; nothing is installed into it — the container lane starts
  `neo4j:2026.05`),
- **`masymos-service`**, a Spring Boot HTTP service that owns extraction, storage and query,
- **`masymos-cli`** (or your own HTTP client), which only talks to the service.

There is therefore **no offline mode** (decision D6): every command needs a reachable service, and
the legacy `-dbPath` workflows are gone for extract, query, delete, stats and web. The build is a
Maven reactor with four modules: `masymos-core` (domain, parsers, archive support, SPIs),
`masymos-neo4j` (Cypher, indexes, repositories), `masymos-service` (HTTP), `masymos-cli`.

## 2. Identity: your old uids still resolve

- The primary identifier is a **UUID**. The legacy numeric uid is preserved as the `legacyUid`
  property on documents and models and is accepted by every lookup, delete and document download
  (decision D1), so a stored legacy uid does not become unresolvable.
- `VERSIONID` is now **`versionId`**: the external version label, opaque and never ordered (a
  BioModels release name, a PMR commit hash, a synthetic identifier). It lives on `Document` and
  `Model`, and documents are chained with `HAS_SUCCESSOR`/`HAS_PREDECESSOR` when a `fileId` is
  indexed again; "latest" is the document with no successor.
- **A re-index is required for data written before 2026-11.** Documents indexed earlier carry no
  `*Exact` copies (exact clauses cannot see them), no `ownerUid` (ownership-scoped delete cannot see
  their nodes) and no kind label (`modelTypes` and `/queries/sedml` ignore them). There is no
  backfill tool; re-index the files, or wipe the disposable instance. A SED-ML change's derived id
  also changed on 2026-11-22 (`setValue-<taskId>-<n>`, `T-024`), so a graph indexed before it holds
  one `SEDML_CHANGE` node where the document declares two; a re-index fixes it.

## 3. API paths: `/query/*` became `/api/v1/*`

| Legacy MORRE entry point | Modern endpoint |
| --- | --- |
| `POST /query/model_query`, `/query/sbml_model_query`, `/query/cellml_model_query` | `POST /api/v1/queries/models` (`clauses`, `modelTypes`) |
| `POST /query/aggregated_model_query` | `POST /api/v1/queries/aggregated` (strategy over four rankers) |
| `POST /query/{annotation,person,publication}_query` | `POST /api/v1/queries/{annotations,persons,publications}` |
| `POST /query/simple_sedml_query` | `POST /api/v1/queries/sedml` |
| `GET /model_crawler_service/get_model*`, `get_model_history` | `GET /api/v1/models/{uid}`, `/document`, `/{fileId}/latest`, `/{fileId}/versions` |
| `POST /model_crawler_service/add_model*`, `/model_update_service/add_model*` | `POST /api/v1/models` |
| `POST /model_{crawler,update}_service/delete_model`, `delete_model_by_fileid` | `DELETE /api/v1/models/{uid}`, `/by-file/{fileId}` |
| `POST /model_{crawler,update}_service/create_annotation_index` | `POST /api/v1/maintenance/annotations/index` |
| `Diagnose.is_model_manager_alive`, `list_index_available` | `GET /api/v1/health`, `GET /api/v1/indexes` |

Shapes differ, so a client needs a new mapping layer even where the semantics are preserved:

- Responses are JSON DTOs, not Gson dumps of legacy result sets. Errors are RFC 7807
  `ProblemDetail` (`type`, `title`, `detail`), with 400/404/409/503.
- A delete answers **200 with `{"documents": n, "nodes": n}`**, not 204. A `fileId`/`versionId`
  confirmation that does not match is 409 and removes nothing.
- `GET /api/v1/health` reports `{"status":"UP","storage":"UP"|"DOWN"|"disabled"}` and answers 503
  when the store does not reply within 2 s.
- Field names are the `QueryField` enum (`NAME`, `SPECIES`, …), discoverable at
  `GET /api/v1/query-fields/{type}`. `SBML_LEVEL`/`SBML_VERSION`/`CELLML_VERSION`/`SCHEMA_VERSION`
  are model fields; constituent properties (`COMPONENT`, `VARIABLE`, …) are model fields too — there
  is no constituent entity or index (decision D3).

## 4. Scores are not comparable

Per-entity queries return Neo4j's native full-text score, which is a different number from the
legacy Lucene score. The fused endpoint defines its own scale (`bordaCombMnz` in `(0,1]`, `bestRank`
order-only with a null score), and no score is comparable across endpoints, strategies or requests
(decision D2). Port a ranking comparison by re-measuring, never by comparing numbers.

## 5. CLI: `Main*` became subcommands

| Legacy | Modern |
| --- | --- |
| `MainExtractor -type SBML|CELLML|SEDML|OWL` | `masymos index sbml|cellml|sedml|owl --source <uri>` |
| `MainExtractor -directory`, `-quiet` | `--directory <dir>`, `--quiet` on every index command |
| `MainQuery` types 1-10 | `masymos query {models,annotations,persons,publications}` |
| `MainQuery -dumpPath` | `--dump-path <file>` (index commands only; a query has no report file) |
| `MainDelete` (by uid) | `masymos delete --uid <uid>` (add `--file-id`/`--version-id` guards) or `--by-file <fileId>` |
| `MainStats` (graph counts) | `masymos stats` |
| `MainAnalyzer` (index inspection) | `masymos analyze` |
| `MainWeb` (JSON insert) | `POST /api/v1/models`; no CLI counterpart |
| `MainIndex` (`-deleteIndex`) | none |

Gone with no replacement: `-dbPath` (offline), `-cache`, `-fileMode`, the interactive menu of the
`Main*` classes, `MainDelete`'s `all`/`random` modes, `MainStats`' concept-depth and
concept-similarity analyses (a hard-coded research harness), and `MainAnalyzer`'s Lucene dry-run
(modern callers cannot write Lucene syntax at all). `-annoOnly` is
`masymos maintenance annotations-index`.

## 6. Index names changed

One model full-text index (`modelFulltextV6`) replaces `modelIndex` and `constituentIndex`; the other
entities keep their own (`annotationFulltextV1`, `personFulltextV1`, `publicationFulltextV1`,
`archiveFulltextV1`), and identity fields have B-tree indexes on `*Exact` copies. A script or plugin
that calls `db.index.fulltext.queryNodes('modelIndex', …)` or the legacy `getModelIndex()` API must
be rewritten; `GET /api/v1/indexes` shows what the store actually has. A new searchable field arrives as
a new generation, never as a widened index (Neo4j cannot widen one in place), so a store built by an
older version answers no hit for that field until its documents are imported again; the older
generations are dropped by the schema initializer.

## 7. Checklist for a migration

1. Provision a Neo4j server and start `masymos-service` with `MASYMOS_NEO4J_*` (see
   [`api-reference.md`](api-reference.md) §6). `GET /api/v1/health` must say `storage:"UP"`.
2. Re-index the corpus with `masymos index …` (the archive path derives an entry `versionId` from
   the entry bytes, so re-imported bundles get distinguishable versions).
3. Move scripts and clients to `/api/v1/…` and to the CLI subcommands above; keep numeric uids where
   they are embedded in links.
4. Re-measure anything that compared scores or ranking quality.
5. Read `GET /api/v1/indexes` once: it is the difference between "the initializer ran" and "the
   index exists".
6. Read the failures of step 2 for a **refused root**: a CellML or SBML document whose root element
   declares a namespace outside its own is now reported as a parse failure naming the source URI,
   where legacy indexed it under whatever namespace it found (decision 45). A file with a foreign
   or mistyped namespace is a corpus problem to fix, not a regression to work around: CellML accepts
   any namespace carrying a `/cellml/<version>` and SBML any `http://www.sbml.org/sbml/level...`
   spelling, so a document outside those two families was never the language it claimed.

## 8. Deliberate non-ports

Each is a decision with a stated reason in the migration plan (§5), not an oversight:
offline mode (D6), the OWL reasoner (D4, closed 2026-11-17 — decision 37 shows it wrote the
asserted-equivalent hierarchy, so nothing is lost), the raw Cypher passthrough, SBGN export, the
Hungarian solver, `repeatedTask` until a fixture exists (DATA-5), the Kemenization and adjacent-pairs
fusion strategies, per-version fused rows, P2M extraction, and the separate constituent index (D3).
