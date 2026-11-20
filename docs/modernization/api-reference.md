# API and CLI Reference (modern)

Reference for the modern HTTP service (`masymos-service`) and the command line interface
(`masymos-cli`). The service listens on port **8080**, the API base path is `/api/v1`, and
errors are RFC 7807 problem details served as `application/problem+json`.

The OpenAPI document is available at `GET /v3/api-docs`. There is deliberately **no Swagger UI**.

Every endpoint below exists in `modern/masymos-service/src/main/java/org/masymos/service/api/`
and every command in `modern/masymos-cli/src/main/java/org/masymos/cli/`, verified against the
working tree on 2026-11-12.

Scope caveat: the surface has no `PUT`/`PATCH` anywhere. The model lifecycle is complete as of
2026-11-12 (SUR-4): insert, read, download, delete by UID with confirmation, delete by file, plus
version-aware reads (latest, full history, one specific version — STO-1 and STO-3). The stored
source document is captured at index time (STO-2).

## 1. Service endpoints

| Method | Path | Controller | Purpose |
| --- | --- | --- | --- |
| GET | `/api/v1/health` | `HealthController` | Liveness plus a storage probe: `{"status":"UP","storage":"UP"}`, `storage:"disabled"` when no store is configured, or 503 with `storage:"DOWN"` when the store does not answer within 2 s |
| GET | `/api/v1/stats` | `StatisticsController` | Counts in one read: documents (versions), models, models per kind, persons, publications, annotations, archives; 503 when storage is unavailable |
| GET | `/api/v1/indexes` | `IndexCatalogController` | The store's own index catalogue (`SHOW INDEXES`): name, kind, entity type, labels or types, properties, state |
| GET | `/api/v1/query-fields` | `QueryMetadataController` | List the `QueryField` enum names usable in query clauses |
| GET | `/api/v1/query-fields/{type}` | `QueryMetadataController` | The fields meaningful for one entity: `model`, `sedml`, `annotation`, `person`, `publication`, `archive` |
| POST | `/api/v1/models` | `ModelController` | Insert a model reference; returns its UID, `versionId` and `legacyUid` (201); `enforceUniqueFileId` turns a duplicate `fileId` into 409 |
| GET | `/api/v1/models/{uid}` | `ModelController` | One stored document: identity, metadata map, whether content was captured, truncation facts; 404 if unknown |
| GET | `/api/v1/models/{uid}/document` | `ModelController` | Download the stored source document byte for byte; 404 if unknown or no content |
| GET | `/api/v1/models/{fileId}/latest` | `ModelController` | Current version of a model file; 404 if unknown |
| GET | `/api/v1/models/{fileId}/versions` | `ModelController` | Every stored version, oldest first; empty list if unknown |
| GET | `/api/v1/models/{fileId}/versions/{versionId}` | `ModelController` | One version by its external label; 404 if unknown |
| DELETE | `/api/v1/models/{uid}` | `ModelController` | Delete a model with the nodes it owns; optional `fileId`/`versionId` confirmations (409 on mismatch); 200 with the removed counts, 404 if unknown |
| DELETE | `/api/v1/models/by-file/{fileId}` | `ModelController` | Delete every version of a file; 200 with the removed counts, 404 if unknown |
| POST | `/api/v1/indexes/sbml` | `SbmlIndexController` | Index SBML sources |
| POST | `/api/v1/indexes/cellml` | `CellmlIndexController` | Index CellML sources, optional `externalCmeta` |
| POST | `/api/v1/indexes/sedml` | `SedmlIndexController` | Index SED-ML sources, then link references |
| POST | `/api/v1/indexes/owl` | `OwlIndexController` | Index OWL ontologies |
| POST | `/api/v1/indexes/combine` | `CombineIndexController` | Index a COMBINE archive (OMEX) |
| POST | `/api/v1/maintenance/sedml/links` | `SedmlMaintenanceController` | Re-resolve SED-ML links; returns counters |
| POST | `/api/v1/maintenance/annotations/index` | `AnnotationMaintenanceController` | Rebuild the annotation keyword index |
| POST | `/api/v1/maintenance/units-backfill` | `UnitBackfillController` | Backfill the unit layer of documents indexed before `CML-1`; dry run unless `apply=true` |
| POST | `/api/v1/queries/models` | `QueryController` | Search models |
| POST | `/api/v1/queries/annotations` | `QueryController` | Search annotations |
| POST | `/api/v1/queries/persons` | `QueryController` | Search persons |
| POST | `/api/v1/queries/publications` | `QueryController` | Search publications |
| POST | `/api/v1/queries/sedml` | `TypedQueryController` | Search SED-ML descriptions; returns the description plus the model sources it references |
| POST | `/api/v1/queries/archives` | `TypedQueryController` | Search COMBINE archives; returns the archive and its document count |
| POST | `/api/v1/queries/aggregated` | `AggregatedQueryController` | Rank-fused search across the model, annotation, person and publication rankers; one row per model file |
| GET | `/api/v1/queries/strategies` | `AggregatedQueryController` | List the fusion strategies with `producesScore` and a description |
| GET | `/api/v1/ontologies/{ontology}/terms/{id}` | `OntologyController` | One ontology term: its id and string properties; 404 when the term is not indexed |
| GET | `/api/v1/ontologies/{ontology}/root` | `OntologyController` | The ontology's root term, whose id is `owl:<ontology>`; 404 when the ontology is not indexed |
| GET | `/api/v1/ontologies/{ontology}/terms/{id}/depth` | `OntologyController` | `isA` hops from the term to the root; `depth` is `null` when the term exists but no path was found within `maxDepth` (1-64, default 32) |
| GET | `/api/v1/ontologies/{ontology}/similarity` | `OntologyController` | Similarity of `first` and `second` through their lowest common ancestor; the four derived fields are `null` when the two share none |
| GET | `/v3/api-docs` | springdoc (`springdoc-openapi-starter-webmvc-api`) | OpenAPI document, no UI |

All index endpoints return **201** with one entry per indexed document; maintenance and query
endpoints return **200**.

**Ontology traversal** (`OWL-4`) reads the hierarchy an indexed ontology wrote. `{ontology}` is the
label the parser derives from the document (`SBOOntology`, `KISAOOntology`, `GOOntology`, …); term
ids are taken as written, so `SBO:0000001` needs no encoding. `maxDepth` bounds **every** leg of a
traversal — the distance between two terms, the ancestor match on both sides and the ancestor's depth
— and is capped at 64 because it reaches a variable-length Cypher pattern. When no common ancestor is
found within that bound, `/similarity` answers 200 with four null fields: that is a limit of the
request, not a claim that the terms share no ancestor. A term is its own ancestor, so
`similarity(parent, child)` names the parent as the lowest common ancestor and `similarity(x, x)`
answers distance 0 — legacy required at least one hop on each side and so disagreed on exactly those
two cases. Every number is computed over the `isA` edges the parser extracted — the `rdfs:subClassOf`
edges a document states, plus the named conjunct of an `intersectionOf` definition, marked
`derivedFrom = equivalentClass` (`OWL-5`) — and no reasoner is applied, so a depth or a score describes
the hierarchy as indexed. That is not a loss against legacy: `OWL-1` closed as a measured non-gap
(decision 37) after running the reasoner legacy used, whose direct-superclass edge set equals what the
parser extracts on both shipped ontologies, and the one place legacy knew more — a definition whose
genus is not asserted — has been read directly since `OWL-5`.

## 2. Request examples

### 2.1 Indexing

Every index endpoint except CellML accepts a list of source URIs:

```json
{
  "sources": ["<uri>"]
}
```

CellML additionally accepts `externalCmeta`, either `none` (default when omitted or blank) or
`pmr` for legacy PMR `@@cmeta` enrichment:

```json
{
  "sources": ["<uri>"],
  "externalCmeta": "none"
}
```

```bash
curl -X POST http://localhost:8080/api/v1/indexes/sbml \
  -H "Content-Type: application/json" \
  -d '{"sources": ["<uri>"]}'
```

`POST /api/v1/indexes/sedml` automatically resolves the cross-document SED-ML links after
indexing when `masymos.sedml.links.auto-link` / `MASYMOS_SEDML_AUTOLINK` is `true` (the default).
Each response entry carries `linkStatus` (`COMPLETED` or `SKIPPED`) and, on success, the counters
of the linking run. If storage is unavailable during the linking step, indexing still succeeds
and `linkStatus` reports `SKIPPED`.

`POST /api/v1/indexes/combine` takes a COMBINE archive (OMEX zip with `manifest.xml`) and indexes
the contained SBML, CellML and SED-ML entries. Entries that cannot be indexed (metadata, archive
root) appear under `skipped`:

```json
[{
  "sourceUri": "file:///tmp/becker.omex",
  "indexedEntries": 2,
  "models": [{ "uid": "…", "fileId": "becker1.sbml", "sourceUri": "file:///…" }],
  "skipped": ["metadata.rdf: unsupported entry kind METADATA"]
}]
```

The manifest states level and version per entry in the `format` URI
(`sbml.level-2.version-4`), so an archive brings its own schema versions. That is the *specification*
version, not the document's version label: each indexed entry additionally gets
`versionId = "sha256-" + the first 16 hex characters of the SHA-256 of its bytes`, so re-importing an
archive whose entry content changed produces a new document version that can be told apart from the
old one, while an unchanged entry keeps its label (SBML-1).

### 2.2 Inserting, reading the current version and deleting a model

```bash
curl -X POST http://localhost:8080/api/v1/models \
  -H "Content-Type: application/json" \
  -d '{"fileId": "BIOMD0000000001", "url": "<uri>", "modelType": "SBML", "versionId": "2014-01-01"}'
# -> 201 {"uid": "...", "fileId": "BIOMD0000000001", "versionId": "2014-01-01", "legacyUid": 1}

curl http://localhost:8080/api/v1/models/BIOMD0000000001/latest
# -> 200 {"uid": "...", "fileId": "BIOMD0000000001", "versionId": "2014-01-01", "legacyUid": 1}
# -> 404 when the file identifier is unknown

curl http://localhost:8080/api/v1/models/BIOMD0000000001/versions
# -> 200 [{"uid": "...", "versionId": "2013-06-01", "legacyUid": 1}, {..., "versionId": "2014-01-01", ...}]
#     oldest first; [] when the file identifier is unknown

curl http://localhost:8080/api/v1/models/BIOMD0000000001/versions/2014-01-01
# -> 200 {"uid": "...", "versionId": "2014-01-01", ...}
# -> 404 when that label does not exist for the file

curl http://localhost:8080/api/v1/models/<uid>
# -> 200 {"uid": "...", "fileId": "...", "versionId": "...", "legacyUid": 1,
#         "metadata": {"submitter": "..."}, "hasDocument": true,
#         "originalBytes": 18432, "truncated": false}

curl -OJ http://localhost:8080/api/v1/models/<uid>/document
# -> 200 the indexed bytes, Content-Disposition: attachment; filename="BIOMD0000000001"
#         X-Masymos-Document-Truncated: true|false
#         X-Masymos-Document-Original-Bytes: <size before truncation>
#         X-Masymos-Document-Storage-Encoding: UTF-8|ISO-8859-1
# -> 404 when the document has no stored content (inserted by reference only)

curl -X DELETE "http://localhost:8080/api/v1/models/<uid>?fileId=BIOMD0000000001&versionId=2014-01-01"
# -> 200 {"documents": 1, "nodes": 12}; 409 when a confirmation does not match the stored document
# -> 404 when the UID is unknown

curl -X DELETE "http://localhost:8080/api/v1/models/by-file/BIOMD0000000001"
# -> 200 {"documents": 3, "nodes": 41}; 404 when no document carries that fileId

curl -X POST http://localhost:8080/api/v1/models \
  -H "Content-Type: application/json" \
  -d '{"fileId": "BIOMD0000000001", "url": "<uri>", "modelType": "SBML", "enforceUniqueFileId": true}'
# -> 409 when a document with that fileId already exists and nothing is written
```

`fileId` must not be blank, `url` must be a URI and `modelType` is required
(`InsertModelRequest`). `versionId` is optional and opaque — a BioModels release name, a PMR
commit hash, or nothing at all; it is never parsed or ordered. `metadata` is an optional string map
stored with the document (legacy `META`). Re-inserting a `fileId` that is already stored does not
fail by default: the new document is linked as the successor of the current one, exactly as legacy
`addModelVersion` did, so `/latest` moves to the new document. `enforceUniqueFileId: true` restores
the legacy guard for that request and answers 409 instead.

Delete is ownership-scoped (STO-4): it removes the document and the nodes written for it, relinks
the version chain around a deleted middle version, and purges `RESOURCE`/`PUBLICATION`/`PERSON`
nodes that lost their last relationship. `RESOURCE`, `PERSON` and `PUBLICATION` are deliberately not
owned — they are the nodes another document could share — so they are only ever purged, never
deleted as part of a document. The response reports documents and nodes removed; the legacy
relationship count is not reproduced. A `uid` may be the numeric legacy uid. A `CombineArchive` node
survives a delete of all its documents (it is not owned by them). A document indexed before STO-4
carries no ownership tags, so deleting it removes the document node and reports `nodes: 1`; re-index
such a store once.

The download is the indexed copy. It is byte-identical for a document that fitted the ceiling
(`masymos.storage.document.max-bytes`, 10 MiB by default); above it the copy is cut at a character
boundary and the truncation headers say so, while `originalBytes` keeps the true source size. The
storage-encoding header names the encoding the graph copy uses (UTF-8, or ISO-8859-1 when the bytes
are not valid UTF-8 — in that case the bytes are preserved 1:1 and are not the document's declared
charset).

`legacyUid` is the numeric identifier legacy MaSyMoS minted from its counter node. It is still
accepted by lookups, so a client holding an old numeric uid keeps working; `uid` stays the
canonical identifier either way.

### 2.3 Queries

A query request holds clauses and a result cap. Valid field names come from
`GET /api/v1/query-fields`; alternatively every query endpoint accepts a free `keyword`:

```json
{
  "clauses": [{ "field": "SCHEMA_VERSION", "keyword": "2.0" }],
  "top": 10
}
```

`top` defaults to `10` and must be positive. At least one of `keyword` or a non-empty `clauses`
list is required, otherwise validation fails with 400. The four query endpoints share the same
request shape (`QueryModelsRequest`).

`groupByVersion` is an optional boolean, default `false`, and only `POST /api/v1/queries/models`
acts on it (`QueryModelsRequest.java:28`-`:32`, `QueryController.java:32`-`:46`). With `true` the
response carries one row per model `fileId` instead of one row per matching version: the row
keeps the usual fields, and its `versions` array lists every matching version of that file as
`{uid, versionId, score}`, ordered by score descending and then by `versionId` ascending, with a
version label that is `null` (the source carried none) ordered last
(`Neo4jModelRepository.java:231`-`:245`). The grouping happens in storage before the limit, so
`top` then counts groups, not hits (`Neo4jModelRepository.java:415`-`:430`, `:445`-`:457`). The
group's own fields (`uid`, `modelId`, `modelName`, `documentUri`, `filename`) and its `score` come
from the highest-scoring version of the group — the group score is the **maximum** member score,
not a sum or an average — and groups are ordered by that score descending
(`Neo4jModelRepository.java:520`-`:543`). `filename` carries the `fileId`, so the group key is
visible. A grouped row reports the **representative version's** document facts and version label —
`versionId`, `documentStored`, `documentBytes` and `documentTruncated` all come from the
highest-scoring member, selected with the same `reduce` (`Neo4jModelRepository.java:535`-`:541`).
A version with no stored document therefore shows `documentStored: false` on the group when it is
the representative, even if a sibling version of the same file has one; the per-version list
carries only `{uid, versionId, score}`, not per-version document facts. `groupByVersion` absent or `false` returns exactly the previous rows per version with an
empty `versions` array. The annotation, person and publication endpoints accept the field but
ignore it (`QueryController.java:48`, `:63`, `:78`), and the exact-only grouped form keeps the
documented constant score `1.0` per version with identity as its tie-break. The CLI has no
flag for it yet, so `masymos query models` always sends an ungrouped request.

```json
{
  "keyword": "glycolysis",
  "top": 5,
  "groupByVersion": true
}
```

```json
[{
  "uid": "<uid of the best-scoring version>",
  "modelId": "BIOMD0000000001",
  "modelName": "Example model",
  "documentUri": "<uri>",
  "filename": "BIOMD0000000001",
  "score": 0.91,
  "versions": [
    { "uid": "<uid>", "versionId": "2014-01-01", "score": 0.91 },
    { "uid": "<uid>", "versionId": "2013-06-01", "score": 0.72 }
  ],
  "versionId": "2014-01-01",
  "matchedFields": ["NAME", "KEYWORD"],
  "indexSource": "modelFulltextV5",
  "documentStored": true,
  "documentBytes": 26481,
  "documentTruncated": false
}]
```

`minScore` is an optional absolute relevance floor, a non-negative number (`@PositiveOrZero`,
`QueryModelsRequest.java:23`-`:27`): a hit whose score is below it is dropped before `top` is
applied (`WHERE node:<label> AND score >= $minScore` right after the full-text call,
`Neo4jResourceQueryRepository.java:158`, `:199`, `:249`). Omitting it means no cut — the
parameter is then bound as `0.0` (`Neo4jResourceQueryRepository.java:113`-`:115`). It applies to
`/queries/annotations`, `/queries/persons` and `/queries/publications`; `/queries/models` ignores
it, because legacy had no model cut. The value is absolute in Neo4j full-text score units, not a
fraction of the best hit, so a portable value needs tuning per index — legacy's own default was
no cut at all (`threshold = -1f`, `AnnotationQuery.java:48` in `code/masymos-core`).

```json
{
  "clauses": [{ "field": "RESOURCE_URI", "keyword": "KISAO:0000019" }],
  "top": 10,
  "minScore": 0.5
}
```

Model result projection (QRY-2): every `/queries/models` row carries six fields beyond the
identity and score, added by QRY-2 without removing or renaming anything. They are documented in
`ModelSearchResultResponse.java:28`-`:50` and defined by the domain record
(`ModelSearchResult.java:12`-`:26`); a client written before QRY-2 simply ignores them.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `versionId` | string, nullable | The hit's external version label (`coalesce(document.versionId, node.versionId)`); `null` when the source carried none |
| `matchedFields` | array of strings | The **request's** fields — request provenance, not a per-hit explanation (see below) |
| `indexSource` | string | `modelFulltextV5` for a full-text hit, `exact` for an exact-only hit |
| `documentStored` | boolean | Whether the source document is stored at all (`xmlDoc` present) |
| `documentBytes` | number, nullable | The **original** size of the source document in bytes (`xmlDocBytes`), not the size of the stored copy; `null` when nothing is stored |
| `documentTruncated` | boolean | Whether the stored copy is truncated (`xmlDocTruncated`) |

`matchedFields` is **request provenance**, not an explanation of the hit. It names the fields the
caller constrained — the clause fields in request order, plus `KEYWORD` when the free-text
`keyword` was used, duplicates collapsed
(`Neo4jModelRepository.java:201`-`:208`). It deliberately does **not** say which field produced the
score: Neo4j's `db.index.fulltext.queryNodes` returns only the node and its score, with no
per-term or per-field breakdown (`Neo4jModelRepository.java:358`-`:373`), so a per-hit
reconstruction would be a guess dressed as a fact. Legacy's `explanation` field named the index,
not a field, and is not restored as such; `indexSource` carries that information instead.
A consequence worth knowing: an exact-only hit and a full-text hit for the same request report the
same `matchedFields`, and two hits in one response always do, because the list describes the
request, not the row.

`documentBytes` reports the original size and never the text: the bytes themselves stay behind
`GET /api/v1/models/{uid}/document`. That endpoint returns the stored copy, so when
`documentTruncated` is `true` the download is shorter than `documentBytes`. Note the flat Cypher
reads these four columns from `coalesce(document, node)`
(`Neo4jModelRepository.java:550`-`:565`), so a metadata-only `:Document:Model` node reports its own
document facts rather than an unrelated sibling's.

For a version-grouped request (`groupByVersion: true`) the row reports the representative
version's document facts and version label, i.e. those of the highest-scoring member; the
`versions` array itself stays `{uid, versionId, score}`
(`Neo4jModelRepository.java:520`-`:543`). `matchedFields` and `indexSource` describe the request
and are therefore identical on every row of a response.

Term handling (QRY-5): a term that contains a separator (`_ : . - @ /`), a camelCase boundary or a
letter/digit boundary is sent as an OR group of the typed value and its fragments, so
`species_1` matches both `species_1` and `species`, and `KISAO:0000019` matches its exact form as
well as `kisao`/`0000019`. Values shorter than five characters (`a.b`, `2.0`) stay single terms.
Terms are clamped to the legacy 32 766-byte ceiling, so an oversized identifier cannot fail a
query. The indexed side is additive too: the stored aggregate keeps its exact form, and
identity-bearing values (`modelSource`, `outputType`, `simKisao`, `simType`, `dataLabel`, `math`,
`resourceUri`, e-mail, organisation) are indexed exactly as given.

Exact-match clauses (QRY-8): a clause on one of the identity fields below is not a full-text
match but an equality test against a normalized copy the indexer writes for every node
(`ParsedModelGraphCypher.java:34`-`:46`). It matches the whole stored value only — a path
fragment of a URI, or a single token of an ID, no longer finds the node through a field clause
(a `keyword` search still does).

| Endpoint | Field | Matched property | Case rule |
| --- | --- | --- | --- |
| `/models` | `URI` | `sourceUriExact` | lowercased, trimmed |
| `/models` | `ID` | `modelIdExact` | lowercased, trimmed |
| `/annotations` | `RESOURCE_URI` | `uriExact` | **case-sensitive**, trimmed |
| `/annotations` | `URI` | `uriExact` | **case-sensitive**, trimmed |
| `/persons` | `GIVENNAME` | `givenNameExact` | lowercased, trimmed |
| `/persons` | `FAMILYNAME` | `familyNameExact` | lowercased, trimmed |
| `/publications` | `YEAR` | `yearExact` | lowercased, trimmed |
| `/publications` | `ID` | `pubIdExact` | lowercased, trimmed |

The one normalization rule lives in `ExactText` (`lowercase` = trim plus `Locale.ROOT`
lowercasing, `identity` = trim only), so the query side cannot drift from the write side. Every
other field stays a text clause with the behaviour described above.

A mixed request keeps both parts: text clauses and `keyword` are OR-ed inside the full-text
expression as before, and each exact clause is AND-ed onto it, i.e. `(textA OR textB) AND
exact1 AND exact2`. A request with only exact clauses (no `keyword`, no text clause) runs a
plain match on the label, ordered deterministically by identity instead of by score, and every
hit carries the constant score `1.0` (`Neo4jModelRepository.java:388`-`:400`,
`Neo4jResourceQueryRepository.java:189`, `:239`, `:305`). Consequences: an exact-only request
with `minScore` above `1.0` returns an empty list, and exact-only scores are not comparable with
full-text scores. A `URI` clause that resolves through the MIRIAM resolver (`URN:` ↔
`identifiers.org`) also accepts every alternative form, so a URN-typed value finds a
URL-stored annotation without an extra clause.

Re-index note: documents written before this change carry no `*Exact` property and are
invisible to exact clauses until they are indexed again. There is no backfill tool; re-index the
affected sources once (the same trap as `ownerUid` before STO-4).

```bash
curl -X POST http://localhost:8080/api/v1/queries/models \
  -H "Content-Type: application/json" \
  -d '{"clauses": [{"field": "<field>", "keyword": "<term>"}], "top": 10}'
```

`SCHEMA_VERSION` matches any stored version value (`sbmlLevel`, `sbmlVersion`, `cellmlVersion`,
`sedmlLevel`, `sedmlVersion`), so one clause works regardless of the model format. Per-format
fields are `SBML_LEVEL`, `SBML_VERSION` and `CELLML_VERSION`.

`GET /api/v1/query-fields` currently returns these names:
`ID`, `NAME`, `URI`, `COMPONENT`, `VARIABLE`, `COMPARTMENT`, `SPECIES`, `REACTION`, `PARAMETER`,
`CREATOR`, `AUTHOR`, `TITLE`, `ABSTRACT`, `AFFILIATION`, `JOURNAL`, `YEAR`, `FAMILYNAME`,
`GIVENNAME`, `EMAIL`, `ORGANIZATION`, `MODEL_SOURCE`, `OUTPUT_TYPE`, `DATA_LABEL`, `SIM_KISAO`,
`SIM_TYPE`, `MATH`, `RESOURCE_URI`, `RESOURCE_TEXT`, `NON_RDF`, `SBML_LEVEL`, `SBML_VERSION`,
`CELLML_VERSION`, `SCHEMA_VERSION`, `KEYWORD`, `ARCHIVE_NAME`, `ARCHIVE_URI` (the last two belong to
the `archive` entity, QRY-9; `COMPONENT`, `VARIABLE`, `COMPARTMENT`, `SPECIES`, `REACTION` and
`PARAMETER` are model fields and score at model level — there is no constituent entity, D3/QRY-6).

### 2.4 Aggregated, rank-fused query (QRY-3)

`POST /api/v1/queries/aggregated` runs the model, annotation, person and publication queries for
one request and fuses their rankings into **one ranked list of model files**. The per-entity
endpoints are unchanged: they return the same rows and the same native scores as before.

```json
{
  "keyword": "glycolysis",
  "clauses": [{ "field": "NAME", "keyword": "glycolysis" }],
  "top": 10,
  "minScore": 0.5,
  "strategy": "bordaCombMnz",
  "weights": { "model": 4, "annotation": 3, "person": 1, "publication": 1 }
}
```

```json
[{
  "uid": "<uid of the representative version>",
  "fileId": "BIOMD0000000001",
  "modelId": "BIOMD0000000001",
  "modelName": "Example model",
  "documentUri": "<uri>",
  "filename": "BIOMD0000000001",
  "versionId": "2014-01-01",
  "documentStored": true,
  "documentBytes": 26481,
  "documentTruncated": false,
  "fusedScore": 0.31,
  "sources": [
    { "type": "model", "rank": 2, "score": 0.91 },
    { "type": "annotation", "rank": 1, "score": 2.24 }
  ]
}]
```

- **One row per model file.** `top` counts files, not versions. A file whose versions all match
  collapses to one row; `uid` and `versionId` are its representative version — the row with the most
  sources, then the best fused score, then the lowest `uid` — and `fusedScore` and `sources` are that
  version's, not a union over the file. `fileId` and `filename` carry the same value. A file-wide
  resource hit credits a file's versions in the model ranker's order, so the representative is the
  file's best-scoring version even when another version carries the annotation (D10).
- **`fusedScore` is its own scale.** For `bordaCombMnz` it lies in `(0, 1]` and is defined only
  inside the response it appears in: it is **not** comparable to the per-entity scores, to a legacy
  score, or across strategies or requests (decision D2). `bestRank` produces no score at all and
  every `fusedScore` is `null`; its order alone is defined.
- **`sources` is what happened, not an explanation.** One entry per ranker that returned the row,
  in the fixed order model, annotation, person, publication, each with that ranker's rank after
  de-duplication and its native score. A resource hit that resolves to several versions of one file
  gives them consecutive ranks in that ranker in the model ranker's order — the file's best-scoring
  version first — and only then by document URI and `uid`; versions the model ranker does not know
  keep that document-URI and `uid` order.
- **`strategy` is required.** `GET /api/v1/queries/strategies` lists the ids with
  `producesScore` and a description; an unknown id is a `400` whose detail names the known ids.
- **`weights`** are optional, keyed by `model`, `annotation`, `person` and `publication`, each
  positive, default `1.0`. They affect `bordaCombMnz` only; `bestRank` accepts and ignores them.
  An unknown key or a non-positive value is a `400`.
- **`minScore`** applies to the annotation, person and publication rankers, as on their own
  endpoints, before fusion. The model ranker keeps today's rule and has no floor.
- **Clause scoping.** The model ranker receives the `keyword` and every clause. The three resource
  rankers receive the `keyword` and only `KEYWORD` clauses: a resource query maps a field it does
  not know to a bare full-text term, so a `NAME` clause would otherwise boost any annotation that
  happens to contain the word. A resource request left with no term is not executed. Per-type field
  scoping is QRY-7.
- **Depth.** Each ranker is asked for `max(25, 5 × top)` hits, capped at `200`, because the collapse
  to files shortens the list. That widening is internal and not part of the contract.
- **Resource evidence is per file.** Annotation, person and publication hits carry the model
  document URIs they relate to, and every version of a model file shares that URI. A resource hit
  therefore raises the file it belongs to; it cannot distinguish versions.
- An empty request result is `[]`, not `404`. `503` when storage is disabled.

`GET /api/v1/queries/strategies` returns, sorted by id:

```json
[
  { "id": "bestRank", "producesScore": false, "description": "Orders rows by their best rank across the rankers without computing a score." },
  { "id": "bordaCombMnz", "producesScore": true, "description": "Weighted Borda position scaled by the combMNZ factor, …" }
]
```

### 2.5 Typed query surface: kinds, descriptions, archives (QRY-7, QRY-9)

Three different things are searchable: SBML/CellML **models**, SED-ML **descriptions** and COMBINE
**archives**. They have separate result endpoints; only models take part in the fused aggregated
query.

**Model kinds.** `POST /api/v1/queries/models` accepts an optional `modelTypes` list:

```json
{ "keyword": "glycolysis", "modelTypes": ["SBML"] }
```

Values are `SBML` and `CELLML` (case-insensitive); omitted means **no filter**, i.e. every kind,
exactly as before. `OWL`, `P2M` and an unknown name are a `400` whose detail names the accepted
values. The filter also applies inside the aggregated query's model ranker
(`"modelTypes": ["CELLML"]`), where omitted likewise means no filter.

**SED-ML descriptions.** `POST /api/v1/queries/sedml` takes the same body as the other query
endpoints (`keyword`, `clauses`, `top`, `minScore`) and returns description rows:

```json
[{
  "uid": "…", "fileId": "sim.sedml", "modelName": "Glycolysis simulation",
  "documentUri": "https://example.org/sim.sedml", "versionId": "v1", "score": 0.83,
  "referencedModelUris": ["urn:miriam:biomodels.db:BIOMD0000000001"]
}]
```

`referencedModelUris` lists the sources the description declares, de-duplicated: the resolved source
when it resolved, otherwise the declared `source` as written — a `urn:miriam:` reference is
therefore reported as authored, not dropped. `groupByVersion` and `modelTypes` are accepted and
ignored here. The result is *not* part of `/queries/aggregated`: a description is not model evidence.

**COMBINE archives.** `POST /api/v1/queries/archives` returns:

```json
[{ "uid": "https://example.org/bundles/b.omex", "fileId": "b.omex",
   "sourceUri": "https://example.org/bundles/b.omex", "score": 2.1, "documentCount": 3 }]
```

Two clause fields exist for it: `ARCHIVE_NAME` (the archive's `fileId`) and `ARCHIVE_URI` (its
`sourceUri`); a bare `keyword` searches both. The index is `archiveFulltextV1`, created with the
other indexes and populated from existing archive nodes, so an archive indexed before this change is
searchable without a re-index. `documentCount` is the number of documents the archive bundles; an
archive with none reports `0`.

**Field discovery per entity.** `GET /api/v1/query-fields/{type}` with `type` one of `model`,
`sedml`, `annotation`, `person`, `publication`, `archive` returns the field names that are
meaningful for that entity, in enum order; an unknown type is a `400` naming the six. The flat
`GET /api/v1/query-fields` still returns every name in one list — the two archive fields
(`ARCHIVE_NAME`, `ARCHIVE_URI`) are the newest entries. The per-entity list is **discovery**, not
validation: every endpoint keeps accepting any `QueryField`, as before.

**Aggregated entity selection.** `POST /api/v1/queries/aggregated` accepts `types` to narrow which
rankers run:

```json
{ "keyword": "glycolysis", "strategy": "bordaCombMnz", "types": ["model", "annotation"] }
```

Accepted values are `model`, `annotation`, `person`, `publication`; omitted means all four. `sedml`
and `archive` are a `400` here — they have their own endpoints and cannot be fused. With a selection
the fused score counts only the selected rankers (`k` shrinks), so a request narrowed to `["model"]`
scores on the model ranker alone.

**One trap.** The kind is a **label** (`:SBML_MODEL`, `:CELLML_MODEL`, `:SEDML_MODEL`, `:OWL_MODEL`),
stamped on the write path. Documents indexed before QRY-7 carry the same labels (the parser path
always did); only rows inserted through the *metadata-only* path before this change lack one, so
they are invisible to `modelTypes` until they are re-indexed.

## 3. Maintenance endpoints

### 3.1 `POST /api/v1/maintenance/sedml/links`

No input. Creates `REFERENCES_SIMULATION_MODEL` (SED-ML), `OBSERVES` (SBML) and `OBSERVES`
(CellML) links and returns counters per relationship type:

```bash
curl -X POST http://localhost:8080/api/v1/maintenance/sedml/links
# -> 200 {"modelReferenceLinks": n, "sbmlObservationLinks": n, "cellmlObservationLinks": n}
```

### 3.2 `POST /api/v1/maintenance/annotations/index`

Builds (or rebuilds) the annotation keyword index. It is always started explicitly and never
runs automatically during indexing. All parameters are query parameters:

```bash
curl -X POST "http://localhost:8080/api/v1/maintenance/annotations/index?dropExistingIndex=false&limit=500&retryFailed=false&maxConcurrency=4&resolverMode=direct&refreshTerms=false"
# -> 200 {"scannedResources": n, "indexedResources": n, "skippedResources": n, "failedResources": n,
#         "resolverMode": "direct", "providersAvailable": ["ols4"], "termsRequested": n,
#         "providerRequests": n, "cacheHits": n, "cacheMisses": n, "unresolvedTerms": n,
#         "providersDemoted": [], "pageRequests": n, "cacheFailures": n}
```

| Parameter | Default | Meaning |
| --- | --- | --- |
| `dropExistingIndex` | `false` | First remove `isIndexed`, `resourceText`, `indexStatus`, `indexedAt`, `indexFailure`, `resolutionMode`, `resolvedVia`, `termKey` and the model aggregate |
| `limit` | `500` | Maximum resources processed per run; `0` means unbounded |
| `retryFailed` | `false` | Retry resources with `indexStatus=failed` |
| `maxConcurrency` | `4` | Concurrent annotation page fetches per run |
| `resolverMode` | `direct` | `providers` (BioPortal, then OLS4, then the page), `direct` (page only — and among the five ontological prefixes the page yields entity text for `chebi` alone), `off` (cache only, no network) |
| `refreshTerms` | `false` | Treat this run's cached terms as stale. It refreshes the terms of the resources **this run processes**, because only unindexed resources are selected; a whole-store refresh is `dropExistingIndex=true` together with `refreshTerms=true` under `direct` or `providers` |

The ten fields after `failedResources` are present only when the run measured the term stage, and
they are omitted entirely otherwise — a caller must not read an absent counter as zero.



Ontological prefixes (`go`, `chebi`, `fma`, `sbo`, `kisao`, with `obo.go`, `biomodels.kisao` and
`biomodels.sbo` as their aliases) go through the term stage: one cache read, one provider stage
over the misses, one cache write, then one page fetch per distinct term as the last resort. Only a
prefix whose identifiers.org page is actually readable is asked for it — measured 2026-11-16, that is
`chebi` alone (GO refuses a plain client with 403, the FMA and KiSAO pages are single-page-app
shells, and SBO lands on a deprecation notice for one *resource*). The other four are never fetched.
They are recorded as `no page text for this prefix` — definitive and cached for the TTL — when a
configured provider publishes that prefix's definitions and synonyms; when none does (every `direct`
run, and FMA under an OLS4-only `providers` run, because FMA's definitions are BioPortal's alone) the
answer is the **transient** `no text source configured for this prefix`, which is not cached at all,
so adding the missing source heals the next run. Neither reason is counted in `pageRequests`, because
no request is made.
The other prefixes — KEGG's three, EC-code, Reactome, BioModels, UniProt, PubMed, taxonomy, MIM,
InterPro, MAMO, DOI — have no ontology definition to look up and keep the per-resource page path.
A term answered by a provider is cached in `:ONTOLOGY_TERM` for `masymos.annotations.terms.ttl`;
a failure a provider or the page will plausibly answer next time is stored but never treated as
fresh, so the next run retries it.

Every resource a run touches records `resolutionMode`; `resolvedVia` (`bioportal`, `ols4`, `page`)
is written whenever text was written, and `termKey` (`go:0008150`) exactly when the URI mapped to
an ontological term. A resource that a later run skips loses all three, so provenance cannot
outlive the state it describes.

Three runs are refused before any work:

| Status | Type suffix | Trigger |
| --- | --- | --- |
| 400 | `invalid-argument` | `resolverMode` is not one of `providers`, `direct`, `off` |
| 400 | `invalid-argument` | `dropExistingIndex=true` together with `resolverMode=off`: `off` restores term-backed text only, so the non-ontological resources would lose their text with nothing to restore it |
| 409 | `provider-unavailable` | `resolverMode=providers` with no usable provider; the detail and `missingVariables` name the environment variables to set |

Resources whose URI cannot be resolved count as `skipped`, transport or extraction problems as
`failed`. Per-resource status is stored in `indexStatus`, `indexedAt` and `indexFailure`; a skipped
resource whose term could not be resolved carries `<termKey>: <reason>` there.

Fetched text is index-only enrichment: the original annotation URI stays the durable resource
identity and is never replaced by fetched text, and a cached term is knowledge about an ontology,
not a fact about the model that references it.

### 3.3 `POST /api/v1/maintenance/units-backfill`

Brings documents indexed before `CML-1` up to the current unit layer. It reads the source document
the graph already stores (`xmlDoc`, `STO-2`), re-parses it, and writes **only** the unit layer —
`UNIT` nodes, `COMPOSED_OF`, `HAS_UNIT`, `USES_UNIT`. It never creates a document, a model or a
version, never changes `ownerUid` or an entity, and a second run writes nothing (decision 36, task
`STO-6`). A re-index is deliberately not the answer: it appends a version to the chain and needs the
original source to be reachable.

```bash
curl -X POST "http://localhost:8080/api/v1/maintenance/units-backfill?apply=false"
# -> 200 {"applied": false, "documentsScanned": n, "documentsBackfilled": n,
#         "documentsAlreadyComplete": n, "skippedWithoutStoredDocument": n,
#         "skippedTruncatedDocument": n, "skippedUnsupportedType": n, "skippedUnparseable": n,
#         "unitsCreated": n, "usesUnitCreated": n, "hasUnitCreated": n, "compositionCreated": n,
#         "relationshipsSkipped": n, "writes": n, "complete": false}
```

| Parameter | Default | Meaning |
| --- | --- | --- |
| `apply` | `false` | Dry run: the counters are what the run *would* write. `true` performs the writes |

`documentsScanned` counts every `Document`; the skip counters say why a document was left alone, and
`complete` is `false` whenever any of them is non-zero — a run that skipped a document never claims a
complete corpus. `writes` is the sum of the four created counters, so "a second run reports
`writes=0`" is the idempotency check. `skippedWithoutStoredDocument` is a document with no `xmlDoc`
at all (indexed before `STO-2`); such a document is invisible to the unit filter until it is
re-indexed, which is the one case where re-indexing is the right tool.

## 4. Error responses

Errors are RFC 7807 problem details (`application/problem+json`) with `type`, `title`,
`detail` and `status`, produced by `ApiExceptionHandler`:

| Status | `type` suffix | Trigger |
| --- | --- | --- |
| 400 | `validation-error` | Bean validation failed; extra `errors` array of `{field, messages}` |
| 400 | `invalid-argument` | `IllegalArgumentException`, e.g. `externalCmeta` other than `none`/`pmr` |
| 404 | `not-found` | Unknown document UID on `GET /api/v1/models/{uid}` or `DELETE /api/v1/models/{uid}`, unknown `fileId` on `GET /api/v1/models/{fileId}/latest` or `DELETE /api/v1/models/by-file/{fileId}`, unknown `fileId`+`versionId` on `GET /api/v1/models/{fileId}/versions/{versionId}`, or a document without stored content on `GET /api/v1/models/{uid}/document` |
| 409 | `model-conflict` | A delete confirmation (`fileId`/`versionId`) does not match the stored document, or `enforceUniqueFileId` was set and the `fileId` already exists; nothing is changed |
| 409 | `provider-unavailable` | `resolverMode=providers` was requested and no ontology term provider is configured; `detail` and `missingVariables` name the environment variables to set |
| 502 | `model-source-unavailable` | `IOException` while reading or writing a model source |
| 503 | `storage-unavailable` | Storage disabled (`masymos.neo4j.enabled=false`) or Neo4j unreachable |

Types are absolute URIs of the form `https://masymos.org/problems/<suffix>`.

## 5. CLI (`masymos`)

The CLI is a thin HTTP client: every command posts to the service and exits `0` on a 2xx
response, `1` otherwise; the response body is printed as-is. The root option `--api-url`
defaults to `http://localhost:8080`, and `mixinStandardHelpOptions` provides `-h`/`--help` and
`--version`.

| Command | Purpose |
| --- | --- |
| `index sbml --source <uri>` | Index SBML sources (repeat `--source` for several) |
| `index cellml --source <uri> [--external-cmeta none\|pmr]` | Index CellML sources (default `none`) |
| `index sedml --source <uri>` | Index SED-ML sources |
| `index owl --source <uri>` | Index OWL ontologies |
| `index combine --source <uri>` | Index a COMBINE archive |
| `maintenance sedml-links` | Re-resolve SED-ML links |
| `maintenance annotations-index [--drop-existing] [--limit <n>] [--retry-failed] [--max-concurrency <n>] [--resolver-mode providers\|direct\|off] [--refresh-terms]` | Rebuild the annotation index (`--resolver-mode` defaults to `direct`; `--refresh-terms` is a flag) |
| `maintenance units-backfill [--apply]` | Backfill the unit layer of documents indexed before `CML-1`; a dry run unless `--apply` is given |
| `query models --keyword <term> [--top <n>]` | Search models (default `top` 10) |
| `query annotations --keyword <term> [--top <n>]` | Search annotations |
| `query persons --keyword <term> [--top <n>]` | Search persons |
| `query publications --keyword <term> [--top <n>]` | Search publications |
| `delete --uid <uid> [--file-id <fileId>] [--version-id <versionId>]` | Delete one model (guards turn a mismatch into 409) |
| `delete --by-file <fileId>` | Delete every version of a model file |
| `stats` | Print the store statistics (`GET /api/v1/stats`) |
| `analyze` | Print the store's index catalogue (`GET /api/v1/indexes`) |

```bash
masymos --api-url http://localhost:8080 index sbml --source "<uri>"
masymos index cellml --source "<uri>" --external-cmeta none
masymos index sedml --source "<uri>"
masymos index owl --source "<uri>"
masymos index combine --source "<uri>"
masymos index sbml --directory models/ --quiet --dump-path reports/sbml.json
masymos maintenance sedml-links
masymos maintenance annotations-index --limit 500 --max-concurrency 4
masymos query models --keyword "<term>" --top 10
masymos delete --uid "<uid>" --file-id "<fileId>"
masymos stats
masymos analyze
```

Notes on the current CLI surface:

- `--api-url` is a root-level option and must precede the subcommand.
- The index commands accept `--source <uri>` (repeatable) and/or `--directory <dir>`; at least one is
  required, and a directory is scanned recursively for `.xml`, `.sbml`, `.cellml`, `.sedml` and
  `.owl` files, which are sent as absolute `file:` URIs — so the service must see the same paths.
- `--dump-path <file>` writes the response body to that file (parent directories are created),
  `--quiet` suppresses the printed body; both apply to the index commands only.
- `delete` takes exactly one of `--uid` and `--by-file`; `--file-id`/`--version-id` are confirmations
  and only valid with `--uid`. Legacy's interactive `all`/`random` modes are not ported.
- `--drop-existing` and `--retry-failed` take no value (flags); `--limit` and `--max-concurrency`
  take a number.

## 6. Environment variables

Read by the service through `modern/masymos-service/src/main/resources/application.yml`:

| Variable | Default | Meaning |
| --- | --- | --- |
| `MASYMOS_NEO4J_ENABLED` | `true` | Enable/disable the Neo4j storage; `false` makes storage endpoints answer 503 |
| `MASYMOS_NEO4J_URI` | `bolt://localhost:7687` | Connection URI |
| `MASYMOS_NEO4J_USERNAME` | `neo4j` | User name |
| `MASYMOS_NEO4J_PASSWORD` | `password` | Password; the default is a local placeholder and must be overridden |
| `MASYMOS_NEO4J_DATABASE` | `neo4j` | Database name |
| `MASYMOS_SEDML_AUTOLINK` | `true` | Resolve SED-ML links automatically after SED-ML indexing |
| `MASYMOS_STORAGE_DOCUMENT_MAX_BYTES` | `10485760` | Ceiling for the stored source copy of one document, in bytes (10 MiB); above it the copy is truncated and flagged (STO-2) |
| `BIOPORTAL_BASE_URL` | none | Base URL of the BioPortal REST API; with `BIOPORTAL_API_KEY` it makes BioPortal a usable term provider |
| `BIOPORTAL_API_KEY` | none | BioPortal key (secret); sent in an `Authorization: apikey token=<key>` header only (the documented form; without `token=` the provider answers 401), never in a URL, a log or the graph |
| `OLS_BASE_URL` | none | Base URL of the OLS4 API; no key needed. Without BioPortal, `resolverMode=providers` runs OLS4-only and says so in `providersAvailable` |

Three further settings have a default and no environment variable: `masymos.annotations.terms.ttl`
(`P30D`), `masymos.annotations.providers.batch.max` (`20`, terms per BioPortal request) and the
provider property prefixes above. A provider counts as configured only when everything it needs is
set — a BioPortal key without a base URL is not a provider.

The CLI itself needs no environment variable; it is configured with `--api-url`.

### Credentials held in `.env` (repo root, gitignored)

`.env` in the repository root is ignored (`*.key`, `*-key.txt`, `.env`, `.env.*` all match in
`.gitignore`) and must stay untracked. It holds the annotation-provider credentials, and since
`ANNO-1a` the service **reads** them — nothing else in the build does:

| Variable | Meaning | Status |
| --- | --- | --- |
| `BIOPORTAL_API_KEY` | BioPortal API key (secret) | read by the service; makes BioPortal the primary term provider. Sent as `Authorization: apikey token=<key>` |
| `BIOPORTAL_BASE_URL` | base URL of the BioPortal REST API | read by the service |
| `OLS_BASE_URL` | base URL of the OLS API (no key needed) | read by the service; the keyless fallback provider |

Load them for a shell with `Get-Content .env | ForEach-Object { ... }` — or set them as user
environment variables; the project never requires a particular loading mechanism. Never echo the
key into a log, an issue or a commit message.
