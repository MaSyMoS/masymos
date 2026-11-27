# API and CLI Reference (modern)

Reference for the modern HTTP service (`masymos-service`) and the command line interface
(`masymos-cli`). The service listens on port **8080**, the API base path is `/api/v1`, and
errors are RFC 7807 problem details served as `application/problem+json`.

The OpenAPI document is available at `GET /v3/api-docs`. There is deliberately **no Swagger UI**.

Every endpoint below exists in `modern/masymos-service/src/main/java/org/masymos/service/api/`
and every command in `modern/masymos-cli/src/main/java/org/masymos/cli/`, verified against the
working tree on 2026-11-12; the `groupByVersion` and exact-match citations were re-read on 2026-11-25 (`CITR-2`),
and the `matchedFields`, full-text-attribution and flat-projection spans on 2026-11-26 (`CITR-2`'s
continuation: each opened inside the previous statement or closed in a following method's javadoc).
The CLI table gained `query sedml`, `query archives` and `query aggregated` on 2026-11-26 (`OPN-4`), one
row per endpoint and each option the endpoint documents.

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
| POST | `/api/v1/maintenance/units-backfill` | `UnitBackfillController` | Backfill the unit layer of documents indexed before `CML-1`; dry run unless `apply=true`; bounded by `limit` and resumable from `cursor` |
| POST | `/api/v1/maintenance/re-derive` | `ReDeriveController` | Report which already indexed documents the current mapper would produce differently, or repair them with `apply=true`; bounded by `limit`, resumable from `cursor`, writes no new version |
| POST | `/api/v1/queries/models` | `QueryController` | Search models; one page plus the cursor to continue from |
| POST | `/api/v1/queries/annotations` | `QueryController` | Search annotations; one page plus the cursor to continue from |
| POST | `/api/v1/queries/persons` | `QueryController` | Search persons; one page plus the cursor to continue from |
| POST | `/api/v1/queries/publications` | `QueryController` | Search publications; one page plus the cursor to continue from |
| POST | `/api/v1/queries/sedml` | `TypedQueryController` | Search SED-ML descriptions; returns one page of descriptions, each with the model sources it references, plus the cursor to continue from |
| POST | `/api/v1/queries/archives` | `TypedQueryController` | Search COMBINE archives; returns one page of archives, each with its document count, plus the cursor to continue from |
| POST | `/api/v1/queries/aggregated` | `AggregatedQueryController` | Rank-fused search across the model, annotation, person and publication rankers; one page of rows, one per model file, plus the cursor to continue from |
| GET | `/api/v1/queries/strategies` | `AggregatedQueryController` | List the fusion strategies with `producesScore` and a description |
| GET | `/api/v1/ontologies/{ontology}/terms/{id}` | `OntologyController` | One ontology term: its id and string properties; 404 when the term is not indexed |
| GET | `/api/v1/ontologies/{ontology}/root` | `OntologyController` | The ontology's root term, whose id is `owl:<ontology>`; 404 when the ontology is not indexed |
| GET | `/api/v1/ontologies/{ontology}/terms/{id}/depth` | `OntologyController` | `isA` hops from the term to the root; `depth` is `null` when the term exists but no path was found within `maxDepth` (1-64, default 32) |
| GET | `/api/v1/ontologies/{ontology}/similarity` | `OntologyController` | Similarity of `first` and `second` through their lowest common ancestor; the four derived fields are `null` when the two share none |
| GET | `/v3/api-docs` | springdoc (`springdoc-openapi-starter-webmvc-api`) | OpenAPI document, no UI |

All index endpoints return **201** with one entry per indexed document; maintenance and query
endpoints return **200**.

**Every index request is bounded** (task `T-018`, finding `GAP02-002`): a body may carry at most
`masymos.index.max-sources-per-request` sources (default **100**), and a longer list is refused whole
with `400 invalid-argument` **before** the first document is read, so nothing is written. The cap
counts the **sources in one body**, not the documents they produce — one COMBINE archive counts once
and can bring many documents.

A request is **not atomic across its sources**, and since `C04-1` (decision 49) the answer says so. A run
indexes every source it can; if any source was not indexed the whole request answers `502 partial-index`,
whose `outcomes` array names what **every** source did, in request order: the models it wrote, the reason
it failed, or `NOT_ATTEMPTED` for a source a store outage kept from being read. Three rules decide it: a
failure that is a fact about one document (unreadable, unparsable, an archive that cannot be opened) does
not stop the run; a store outage does, because every remaining source would fail for the same reason; and
an outage on the **first** source writes nothing, so that answer stays `503 storage-unavailable` rather
than a report about a run that did not happen. A caller with more than 100 sources sends several
requests; re-indexing an already indexed document appends a version (decision 36), so a retry names the
`FAILED` and `NOT_ATTEMPTED` sources of one request and nothing else.

**Exposure and trust boundary** (finding `GAP02-003`; closed as `C04-3` by **design decision 48**). The
service can require **one** credential, and it requires none unless an operator sets one. Setting
`masymos.security.shared-secret` (`MASYMOS_SECURITY_SHARED_SECRET`: at least 16 characters and not blank,
and either violation is refused at startup) makes every request carry it in an `X-MaSyMoS-Token` header;
a request
without the right value answers `401 unauthenticated` before a handler runs. There is **no exemption**:
the health probe and `/v3/api-docs` are behind it exactly as every `/api/v1/**` route is, so a probe must
send the header. The CLI sends it when it is given `--token` (default `MASYMOS_SERVICE_TOKEN`). The
OpenAPI document declares the `apiKey` scheme **only when the secret is set**, so a document generated
from an unprotected instance claims no security it does not have.

Left unset, the service checks no credential: anyone who can reach the port can index, re-index, delete
and run the maintenance namespace (`/api/v1/maintenance/**`), and the unit backfill and annotation run
rewrite the graph. That default is the local, single-tenant deployment this project documents, and it
makes two operator actions part of the deployment: bind the listener to loopback
(`server.address=127.0.0.1`; `application.yml` sets the port and not the address) or put an
authenticating reverse proxy in front of it. Exposing port 8080 to a network you do not trust is not a
supported configuration without one of the three. The proxy remains the stronger control - per-user
identity, rotation, an audit trail; a shared secret is a bearer credential and is only as private as the
transport it travels in, so use it over TLS.

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
Each response entry carries `linkStatus` and, when the link step ran, its counters — `COMPLETED`
after a successful linking run, `SKIPPED` only when automatic linking is off (`auto-link=false`).
If the store becomes unavailable during the linking step the call answers `503
storage-unavailable` instead (`T-017`, finding `GAP02-005`): the documents indexed before the
outage remain written, and `POST /api/v1/maintenance/sedml/links` repeats the link step.

`POST /api/v1/indexes/combine` takes a COMBINE archive (OMEX zip with `manifest.xml`) and indexes
the contained SBML, CellML and SED-ML entries. Entries that cannot be indexed (metadata, archive
root) appear under `skipped`:

```json
[{
  "sourceUri": "file:///tmp/becker.omex",
  "indexedEntries": 2,
  "models": [{ "uid": "…", "fileId": "becker1.sbml", "sourceUri": "file:///…" }],
  "skipped": ["metadata.rdf: unsupported entry kind METADATA (format http://identifiers.org/combine.specifications/rdf-metadata)"]
}]
```

A skip reason names the raw `format` URI it rejected, so a manifest typo (`.../sbmlx`) is
distinguishable from a deliberate non-model entry (a results PDF). The kind is decided by the
specification token of that URI, matched exactly: a URI that merely contains `sbml` - a
`sbml-notes` page, a `sbml.docs` segment - is `OTHER`, not an SBML entry (`T-009`).

**The `fileId` of an archive entry** (`T-018`, `DESIGN-C02`): an entry at the archive root keeps its
bare file name (`becker1.sbml`), and an entry in a directory is `fileName@directory` —
`models/a/x.sbml` becomes `x.sbml@models/a`, so two entries named `x.sbml` in different directories
of one archive get distinct identities instead of colliding on the `fileId` guard. The path is the
normalised entry path (segments resolved, case kept as written, trailing slash dropped), the archive's
own path is never part of `fileId` (a copied archive is not a new set of models), and `@` cannot occur
in a bare file name, so an entry never collapses with a standalone file. The same rule produces
`versionId = "sha256-…"` per entry (below), which is what keeps a re-imported archive's changed
entry distinguishable from the old one.

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

Every query endpoint answers **one page** of its result set: the body is
`{"hits": [...], "nextCursor": ...}`, and `nextCursor` is the opaque position to send back as the
query parameter `cursor` (`POST /api/v1/queries/models?cursor=...`). `nextCursor` is present exactly
when the store proved that more rows follow - the statement reads one row beyond `top` and drops it -
so a page that omits it is the end, never a page the service did not look past. The cursor belongs to
the request that minted it: `top`, the keyword, the clauses and the filters are part of its
fingerprint, so replaying it against a different request is refused with `400`; so is a cursor whose
row is gone because the store changed under the sweep. Omit `cursor` to start at the beginning. The
order a cursor continues is total - `score` descending with the row's natural key as the tiebreak
(`uri` for annotations, `familyName`+`givenName` for persons, `pubId`+title for publications, the
document `uid` for SED-ML descriptions, `uid`/`sourceUri` for archives) - and the exact-only lanes,
whose hits all carry the constant score `1.0`, continue on that key alone. The aggregated endpoint
pages the same way, after its per-file collapse; its position is the last answered file (`OPN-6`,
decision 53).

`groupByVersion` is an optional boolean, default `false`, and only `POST /api/v1/queries/models`
acts on it (`QueryModelsRequest.java:28-32`, `QueryController.java:34-67`). With `true` the
response carries one row per model `fileId` instead of one row per matching version: the row
keeps the usual fields, and its `versions` array lists every matching version of that file as
`{uid, versionId, score}`, ordered by score descending and then by `versionId` ascending, with a
version label that is `null` (the source carried none) ordered last
(`Neo4jModelRepository.java:297-301`, `:303-305`). The grouping happens in storage before the limit, so
`top` then counts groups, not hits (`Neo4jModelRepository.java:519-524`, `:447-452`). The
group's own fields (`uid`, `modelId`, `modelName`, `documentUri`, `filename`) and its `score` come
from the highest-scoring version of the group — the group score is the **maximum** member score,
not a sum or an average — and groups are ordered by that score descending
(`Neo4jModelRepository.java:531-552`, ordered by `:523`). `filename` carries the `fileId`, so the group key is
visible. A grouped row reports the **representative version's** document facts and version label —
`versionId`, `documentStored`, `documentBytes` and `documentTruncated` all come from the
highest-scoring member, selected with the same `reduce` (`Neo4jModelRepository.java:677-682`).
A version with no stored document therefore shows `documentStored: false` on the group when it is
the representative, even if a sibling version of the same file has one; the per-version list
carries only `{uid, versionId, score}`, not per-version document facts. `groupByVersion` absent or `false` returns exactly the previous rows per version with an
empty `versions` array. The annotation, person and publication endpoints accept the field but
ignore it (`QueryController.java:54`, `:74`, `:94`), and the exact-only grouped form keeps the
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
{
  "hits": [{
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
    "indexSource": "modelFulltextV6",
    "documentStored": true,
    "documentBytes": 26481,
    "documentTruncated": false
  }],
  "nextCursor": null
}
```

The response is a **page** (`OPN-6`): `hits` carries the rows and `nextCursor` the position to continue
from, or `null` when this page reached the end. The statement reads one row beyond `top` and drops it, so a
`null` cursor means "nothing left", never "the service did not look". To read on, send that value back as
the query parameter `cursor` — it is a position in the result set, not part of the body, and it belongs to
the request that produced it: replaying it against a different request is refused with `400`. The order is
`score` descending with the tie broken by the row's node identity, which is what makes the position exact.

`minScore` is an optional absolute relevance floor, a non-negative number (`@PositiveOrZero`,
`QueryModelsRequest.java:23-27`): a hit whose score is below it is dropped before `top` is
applied (`WHERE node:<label> AND score >= $minScore` right after the full-text call,
`Neo4jResourceQueryRepository.java:233`, `:274`, `:322`). Omitting it means no cut - the
parameter is then bound as `0.0` (`Neo4jResourceQueryRepository.java:187-188`). It applies to
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
`ModelSearchResultResponse.java:28-50` and defined by the domain record
(`ModelSearchResult.java:12-26`); a client written before QRY-2 simply ignores them.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `versionId` | string, nullable | The hit's external version label (`coalesce(document.versionId, node.versionId)`); `null` when the source carried none |
| `matchedFields` | array of strings | The **request's** fields — request provenance, not a per-hit explanation (see below) |
| `indexSource` | string | `modelFulltextV6` for a full-text hit, `exact` for an exact-only hit |
| `documentStored` | boolean | Whether the source document is stored at all (`xmlDoc` present) |
| `documentBytes` | number, nullable | The **original** size of the source document in bytes (`xmlDocBytes`), not the size of the stored copy; `null` when nothing is stored |
| `documentTruncated` | boolean | Whether the stored copy is truncated (`xmlDocTruncated`) |

`matchedFields` is **request provenance**, not an explanation of the hit. It names the fields the
caller constrained — the clause fields in request order, plus `KEYWORD` when the free-text
`keyword` was used, duplicates collapsed
(`Neo4jModelRepository.java:243-249`). It deliberately does **not** say which field produced the
score: Neo4j's `db.index.fulltext.queryNodes` returns only the node and its score, with no
per-term or per-field breakdown (`Neo4jModelRepository.java:519-520`), so a per-hit
reconstruction would be a guess dressed as a fact. Legacy's `explanation` field named the index,
not a field, and is not restored as such; `indexSource` carries that information instead.
A consequence worth knowing: an exact-only hit and a full-text hit for the same request report the
same `matchedFields`, and two hits in one response always do, because the list describes the
request, not the row.

`documentBytes` reports the original size and never the text: the bytes themselves stay behind
`GET /api/v1/models/{uid}/document`. That endpoint returns the stored copy, so when
`documentTruncated` is `true` the download is shorter than `documentBytes`. Note the flat Cypher
reads these four columns from `coalesce(document, node)`
(`Neo4jModelRepository.java:693-708`), so a metadata-only `:Document:Model` node reports its own
document facts rather than an unrelated sibling's.

For a version-grouped request (`groupByVersion: true`) the row reports the representative
version's document facts and version label, i.e. those of the highest-scoring member; the
`versions` array itself stays `{uid, versionId, score}`
(`Neo4jModelRepository.java:531-552`). `matchedFields` and `indexSource` describe the request
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
(`ParsedModelGraphCypher.java:48-59`). It matches the whole stored value only — a path
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
hit carries the constant score `1.0` (`Neo4jModelRepository.java:459-481`,
`Neo4jResourceQueryRepository.java:248-259`, `:293-307`, `:355-378`). Consequences: an exact-only request
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
endpoints are unchanged: they return the same rows and the same native scores as before. The answer
is one page of that list (`{"hits": [...], "nextCursor": ...}`); the position is the last answered
file, so following the cursor reads on in the strategy's own order.

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
  "rowLookupTruncated": false,
  "documentUrisTruncated": false,
  "rankerDepthTruncated": false,
  "fusedScore": 0.31,
  "sources": [
    { "type": "model", "rank": 2, "score": 0.91 },
    { "type": "annotation", "rank": 1, "score": 2.24 }
  ]
}]
```

- **`documentTruncated` is not a search bound.** It says the graph stores a truncated copy of the
  model's source (the 10 MiB ceiling, STO-2). The three search bounds are their own fields, and they
  are the only way to tell a short answer from a complete one (`REL-007`, `C03-2`, `C03-5`):
  `rowLookupTruncated` is `true` when the document-URI to model-row lookup answered its 2 000-row
  bound while more rows matched, `documentUrisTruncated` is `true` when a resource ranker reached
  its 500-distinct-URI cap, and `rankerDepthTruncated` is `true` when a ranker held more rows than the
  per-ranker depth (`5 * top`, clamped to 200) and the answer is therefore shorter than the requested
  `top`. All three describe the **request**, so every row of one answer carries the same
  value; `false` means nothing was cut. `rankerDepthTruncated` is deliberately narrower than the other
  two: it is not reported on a page that reaches `top` even when the depth was reached, because such a
  page left nothing out, so `true` names the depth as a candidate reason for a short answer rather than
  proving that more files exist. A `true` is not an error — the answer is valid, it is just
  shorter than the request named, and there is no paging to resume it (see the pagination row in
  [`query-parity.md`](query-parity.md)).
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
`sourceUri`); a bare `keyword` searches both. `groupByVersion` and `modelTypes` are accepted and
ignored here, exactly as on `/queries/sedml`: an archive is not a model kind. The index is
`archiveFulltextV1`, created with the other indexes and populated from existing archive nodes, so an
archive indexed before this change is searchable without a re-index. `documentCount` is the number of
documents the archive bundles; an archive with none reports `0`.

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

No input. Creates `REFERENCES_SIMULATION_MODEL` (SED-ML), `OBSERVES` (SBML), `OBSERVES`
(CellML) and `CHANGES_ENTITY` (a change to the species, reaction, compartment or parameter its
`target` names, for `changeAttribute` and `computeChange`) links and returns counters per
relationship type:

```bash
curl -X POST http://localhost:8080/api/v1/maintenance/sedml/links
# -> 200 {"modelReferenceLinks": n, "sbmlObservationLinks": n, "cellmlObservationLinks": n,
#         "changeLinks": n}
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
`failed`. A run whose caller is interrupted stops there: the resources it never reached are
`skipped` with the reason `interrupted`, so they stay pending and the next run retries them.
Per-resource status is stored in `indexStatus`, `indexedAt` and `indexFailure`; a skipped
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
curl -X POST "http://localhost:8080/api/v1/maintenance/units-backfill?apply=false&limit=500"
# -> 200 {"applied": false, "documentsScanned": n, "documentsNotScanned": n,
#         "documentsBackfilled": n,
#         "documentsAlreadyComplete": n, "skippedWithoutStoredDocument": n,
#         "skippedTruncatedDocument": n, "skippedUnsupportedType": n, "skippedUnparseable": n,
#         "unitsCreated": n, "usesUnitCreated": n, "hasUnitCreated": n, "compositionCreated": n,
#         "relationshipsSkipped": n, "writes": n, "complete": false,
#         "nextCursor": "<uid>", "startedFromCursor": false}
```

| Parameter | Default | Meaning |
| --- | --- | --- |
| `apply` | `false` | Dry run: the counters are what the run *would* write. `true` performs the writes |
| `limit` | `500` | Maximum documents with a stored source this run looks at; `0` means unbounded. The rest are reported as `documentsNotScanned` |
| `cursor` | none | Start after this position — the `nextCursor` of a previous run. Omitted, the run starts at the beginning of the population |

`documentsScanned` counts the documents the run **looked at**; `documentsNotScanned` counts the ones
with a stored source that lie after this page. A bounded run is **resumable** (`C04-2`, decision 50):
`nextCursor` names the position to pass back as `cursor`, and it is absent when the page reached the
end — so a loop over bounded requests sweeps a corpus, and no single request holds a thread for it. The
cursor is a keyset on the document `uid`, which the graph constrains to be unique, so it is a position
in the population and not a count of finished work: a dry run's cursor starts an applied run at the
same place, and the order is arbitrary (`uid` is a UUID), so a page is a random subset rather than the
oldest documents. `startedFromCursor` says whether the run read the head of the population; `complete`
is `false` whenever a skip counter is non-zero, whenever anything was left for a later run, **or**
whenever the run started from a cursor — a run never claims a corpus it did not read. The skip counters
say why a document was left alone. `writes` is the sum of the four created counters, so "a second run
reports `writes=0`" is the idempotency check. `skippedWithoutStoredDocument` is a document with no
`xmlDoc` at all (indexed before `STO-2`); such a document is invisible to the unit filter until it is
re-indexed, which is the one case where re-indexing is the right tool. `limit=0` is still accepted as an
explicit whole-tail run for a small store; it is no longer the only way to reach a large one.

A whole-corpus sweep is the loop the cursor exists for:

```bash
cursor=''
while :; do
  page=$(curl -sS -X POST \
    "http://localhost:8080/api/v1/maintenance/units-backfill?apply=true&limit=500${cursor:+&cursor=$cursor}")
  echo "$page"
  cursor=$(printf '%s' "$page" | sed -n 's/.*"nextCursor":"\([^"]*\)".*/\1/p')
  [ -z "$cursor" ] && break
done
```

### 3.4 `POST /api/v1/maintenance/re-derive`

Reports which already indexed documents the current mapper would produce differently. It re-parses
each stored source document and compares what the mapper yields with what the graph holds. Without
`apply` it is **read only**: it writes nothing, and the report is the list a repair runs against. With
`apply=true` it is the **repair** (`OPN-7`, second slice, 2026-11-27): every stale document's derived
graph is re-derived from its stored source and replaced, in one transaction per document, and the run
writes **no new version** — the document keeps its `uid`, `legacyUid`, `versionId`, chain position and
stored bytes. A truncated source is skipped with that reason, never partially rewritten, and a document
the scan calls `current` is not touched, so repeating the command over the same page applies nothing.

Two signals, because the causes differ. `missingByLabel` is a node **shortfall** per label set (the
write path gained a construct since the document was indexed). `missingDerivedKeys` counts owned nodes
that carry no `derivedKey` — the identity the write path stamps since 2026-11-26, and the one a
pre-repair document lacks.

```bash
curl -X POST "http://localhost:8080/api/v1/maintenance/re-derive?limit=500"
# -> 200 {"documentsScanned": n, "documentsStale": n, "documentsCurrent": n,
#         "documentsNotScanned": n, "skippedWithoutStoredDocument": n,
#         "skippedTruncatedDocument": n, "skippedUnsupportedType": n, "skippedUnparseable": n,
#         "missingDerivedKeys": n, "missingByLabel": {"ANNOTATION": n},
#         "nextCursor": "<uid>", "startedFromCursor": false, "applied": 0}
curl -X POST "http://localhost:8080/api/v1/maintenance/re-derive?apply=true&limit=500"
# -> 200 {... "applied": n}  # n documents rewritten in place; sweep on with nextCursor
```

| Parameter | Default | Meaning |
| --- | --- | --- |
| `limit` | `500` | Maximum documents with a stored source this run looks at; `0` means unbounded. The rest are reported as `documentsNotScanned` |
| `cursor` | none | Start after this position — the `nextCursor` of a previous run |
| `apply` | `false` | Repair instead of only report: each stale document's derived graph is rewritten from its stored source, one transaction per document, no new version; `applied` counts them |

The bound and the cursor are the `C04-2` contract §3.3 documents, unchanged. A document is `stale` when
it has a node shortfall **or** an owned node without `derivedKey`; `current` when it has neither.
`skippedUnsupportedType` counts the model types this run does not re-parse (OWL, whose serialisations
have a conversion path of their own, and P2M, which is a mapping file). Shared nodes (`RESOURCE`,
`PERSON`, `PUBLICATION`, `UNIT`) are not compared: no document owns them, and the unit layer has its own
backfill in §3.3. A repair merges a shared node another document still references on its natural key
(`RESOURCE` on `uri`, `PUBLICATION` on `pubId`, `PERSON` on name), so repairing one document cannot
double a node a second one holds.

## 4. Error responses

Errors are RFC 7807 problem details (`application/problem+json`) with `type`, `title`,
`detail` and `status`, produced by `ApiExceptionHandler`:

| Status | `type` suffix | Trigger |
| --- | --- | --- |
| 400 | `validation-error` | Bean validation failed; extra `errors` array of `{field, messages}` |
| 400 | `invalid-argument` | `IllegalArgumentException`, e.g. `externalCmeta` other than `none`/`pmr` |
| 401 | `unauthenticated` | `masymos.security.shared-secret` is set and the request carries no valid `X-MaSyMoS-Token` header. Refused by `SharedSecretFilter` before a handler is chosen and rendered here through the servlet exception resolver, so the `type` surface stays one producer (decision 48, closed row `C04-3`) |
| 404 | `not-found` | Unknown document UID on `GET /api/v1/models/{uid}` or `DELETE /api/v1/models/{uid}`, unknown `fileId` on `GET /api/v1/models/{fileId}/latest` or `DELETE /api/v1/models/by-file/{fileId}`, unknown `fileId`+`versionId` on `GET /api/v1/models/{fileId}/versions/{versionId}`, or a document without stored content on `GET /api/v1/models/{uid}/document` |
| 409 | `model-conflict` | A delete confirmation (`fileId`/`versionId`) does not match the stored document, or `enforceUniqueFileId` was set and the `fileId` already exists; nothing is changed |
| 409 | `provider-unavailable` | `resolverMode=providers` was requested and no ontology term provider is configured; `detail` and `missingVariables` name the environment variables to set |
| 500 | `write-failed` | An `IOException` raised by the local **write** path (a full disk, a read-only directory); `detail` names what could not be written |
| 502 | `model-source-unavailable` | An `IOException` while **reading** a model source: an unreadable `file:` path, a refused or timed-out HTTP source |
| 502 | `partial-index` | An index request did not index every source. `detail` counts them and names the first failure; `outcomes` names what **every** source did - the models it wrote, the reason it failed, or `NOT_ATTEMPTED` after a store outage ended the run (decision 49, closed row `C04-1`) |
| 503 | `storage-unavailable` | Storage disabled (`masymos.neo4j.enabled=false`) or Neo4j unreachable. `detail` is the constant `the graph store is not available` — a driver message can name a bolt URI, and this response is readable without a credential unless `masymos.security.shared-secret` is set (`T-017`, finding `A04-03`) |

Types are absolute URIs of the form `https://masymos.org/problems/<suffix>`.

One further row is the framework's, not this advice's: with `spring.mvc.problemdetails.enabled=true`
(`application.yml`, decision D4) an exception no handler here maps — `405` for a wrong method, `415`
for an unsupported media type, `406` for an unacceptable one, and an unreadable request body — answers
a problem detail too. Its `type` is the framework's default rather than a `masymos.org` URI, so a
client should key on `status` and treat an unknown `type` as unmapped (`T-017`, finding `GAP02-004`,
deferred row `C04-5`).

## 5. CLI (`masymos`)

The CLI is a thin HTTP client: every command posts to the service and exits `0` on a 2xx
response, `1` otherwise; the response body is printed as-is, and a failure line goes to the **error
stream**, so a script's standard output stays the report it expects. A bare group command — `index`,
`query` or `maintenance` without its subcommand — prints its usage to the error stream and exits
`2`, the usage code, so a mistyped invocation cannot read as a completed run. The root options
`--api-url` (default `http://localhost:8080`), `--token` (default: the `MASYMOS_SERVICE_TOKEN`
environment variable; sent as `X-MaSyMoS-Token`, and no header at all when neither is set),
`--connect-timeout` (default `5` seconds) and `--request-timeout` (default `60` seconds) must precede the
subcommand.
`mixinStandardHelpOptions` provides `-h`/`--help` and `--version`.

| Command | Purpose |
| --- | --- |
| `index sbml --source <uri>` | Index SBML sources (repeat `--source` for several) |
| `index cellml --source <uri> [--external-cmeta none\|pmr]` | Index CellML sources (default `none`) |
| `index sedml --source <uri>` | Index SED-ML sources |
| `index owl --source <uri>` | Index OWL ontologies |
| `index combine --source <uri>` | Index a COMBINE archive |
| `maintenance sedml-links` | Re-resolve SED-ML links |
| `maintenance annotations-index [--drop-existing] [--limit <n>] [--retry-failed] [--max-concurrency <n>] [--resolver-mode providers\|direct\|off] [--refresh-terms]` | Rebuild the annotation index (`--resolver-mode` defaults to `direct`; `--refresh-terms` is a flag) |
| `maintenance units-backfill [--apply] [--limit <n>] [--cursor <uid>]` | Backfill the unit layer of documents indexed before `CML-1`; a dry run unless `--apply` is given. `--limit` (default 500, `0` unbounded) bounds one run; `--cursor` starts after a position a previous run printed as `nextCursor`, so a corpus is swept by repeating the command until it prints no cursor |
| `maintenance re-derive [--limit <n>] [--cursor <uid>] [--apply]` | Report which indexed documents the current mapper would produce differently; `--apply` repairs them in place (no new version, one transaction per document) and the report counts them as `applied`. `--limit` (default 500, `0` unbounded) and `--cursor` follow the units-backfill contract |
| `query models --keyword <term> [--top <n>] [--cursor <c>]` | Search models (default `top` 10); `--cursor` sends back a page`'`s `nextCursor` to read on |
| `query annotations --keyword <term> [--top <n>] [--cursor <c>]` | Search annotations; `--cursor` reads the next page |
| `query persons --keyword <term> [--top <n>] [--cursor <c>]` | Search persons; `--cursor` reads the next page |
| `query publications --keyword <term> [--top <n>] [--cursor <c>]` | Search publications; `--cursor` reads the next page |
| `query sedml --keyword <term> [--top <n>] [--cursor <c>]` | Search SED-ML descriptions, each with the model sources it declares (`POST /api/v1/queries/sedml`) |
| `query archives --keyword <term> [--top <n>] [--cursor <c>]` | Search COMBINE archives, each with the number of documents it bundles (`POST /api/v1/queries/archives`) |
| `query aggregated --keyword <term> --strategy <id> [--top <n>] [--min-score <x>] [--types <a,b>] [--model-types <SBML>] [--weights <ranker=weight>] [--cursor <c>]` | Run a fused query over the selected rankers (`POST /api/v1/queries/aggregated`); `--strategy` is required because the endpoint has no default, `--types`/`--model-types`/`--weights` take comma-separated lists, and `--weights` applies to `bordaCombMnz` only. A malformed `ranker=weight` is refused before anything is sent |
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
masymos query sedml --keyword "<term>"
masymos query archives --keyword "<archive name or uri>"
masymos query aggregated --keyword "<term>" --strategy bordaCombMnz --types model,annotation
# sweep a result set: send each page's nextCursor back as --cursor, and stop when a page prints none
masymos query models --keyword "<term>" --top 50 --cursor "<nextCursor>"
masymos delete --uid "<uid>" --file-id "<fileId>"
masymos stats
masymos analyze
```

Notes on the current CLI surface:

- `--api-url`, `--connect-timeout` and `--request-timeout` are root-level options and must precede
  the subcommand. Every command builds its HTTP client in one place (`ServiceHttp`), so the two
  timeouts bound every request the CLI makes: a service that accepts a connection and then stops
  answering fails a command instead of holding it for ever.
- The index commands accept `--source <uri>` (repeatable) and/or `--directory <dir>`; at least one is
  required, and a directory is scanned recursively for `.xml`, `.sbml`, `.cellml`, `.sedml` and
  `.owl` files, which are sent as absolute `file:` URIs — so the service must see the same paths.
- `--dump-path <file>` writes the response body to that file (parent directories are created),
  `--quiet` suppresses the printed body; both apply to the index commands only. A body above
  52,428,800 characters is refused: one line on the error stream, exit `1`, and nothing is written.
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
| `MASYMOS_INDEX_MAX_SOURCES_PER_REQUEST` | `100` | Sources one index request may carry; a longer list is refused whole with `400 invalid-argument` before the first write (`T-018`) |
| `MASYMOS_INDEX_MAX_RESPONSE_BYTES` | `52428800` | The declared ceiling for a response body a caller dumps to disk (50 MiB: five document ceilings). Nothing enforces it today — the service answers what the source cap allows, and the CLI's `--dump-path` has no bound yet (task `T-020`); it is declared here so the bound lives in one place |
| `MASYMOS_SECURITY_SHARED_SECRET` | none (off) | The one credential the service can require (decision 48); at least 16 characters and not blank, or the service refuses to start. When set, every request must carry it in an `X-MaSyMoS-Token` header, including `/api/v1/health` and `/v3/api-docs` |
| `BIOPORTAL_BASE_URL` | none | Base URL of the BioPortal REST API; with `BIOPORTAL_API_KEY` it makes BioPortal a usable term provider |
| `BIOPORTAL_API_KEY` | none | BioPortal key (secret); sent in an `Authorization: apikey token=<key>` header only (the documented form; without `token=` the provider answers 401), never in a URL, a log or the graph |
| `OLS_BASE_URL` | none | Base URL of the OLS4 API; no key needed. Without BioPortal, `resolverMode=providers` runs OLS4-only and says so in `providersAvailable` |

Three further settings have a default and no environment variable: `masymos.annotations.terms.ttl`
(`P30D`), `masymos.annotations.providers.batch.max` (`20`, terms per BioPortal request) and the
provider property prefixes above. A provider counts as configured only when everything it needs is
set — a BioPortal key without a base URL is not a provider.

The CLI needs no environment variable: `--api-url` configures it, and `MASYMOS_SERVICE_TOKEN` is only the
default for `--token`, which sends the `X-MaSyMoS-Token` header a protected service requires.

### Credentials held in `.env` (repo root, gitignored)

`.env` in the repository root is ignored (`*.key`, `*-key.txt`, `.env`, `.env.*` all match in
`.gitignore`) and must stay untracked. It holds the annotation-provider credentials, and since
`ANNO-1a` the service **reads** them — nothing else in the build does:

| Variable | Meaning | Status |
| --- | --- | --- |
| `BIOPORTAL_API_KEY` | BioPortal API key (secret) | read by the service; makes BioPortal the primary term provider. Sent as `Authorization: apikey token=<key>` |
| `BIOPORTAL_BASE_URL` | base URL of the BioPortal REST API | read by the service |
| `OLS_BASE_URL` | base URL of the OLS API (no key needed) | read by the service; the keyless fallback provider |
| `MASYMOS_SECURITY_SHARED_SECRET` | the shared secret a non-loopback deployment requires (decision 48) | read by the service; empty or absent leaves the filter off. `docker compose --env-file .env up` forwards it, so the same file configures both sides of a protected local stack |

Load them for a shell with `Get-Content .env | ForEach-Object { ... }` — or set them as user
environment variables; the project never requires a particular loading mechanism. Never echo the
key into a log, an issue or a commit message.
