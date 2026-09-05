# common — paging contract

How `common/page` (`CursorPage`/`OffsetPage`/`CursorCodec`/`PageSupport`) is used across layers.
Who may depend on whom lives in the root `AGENTS.md` and is ArchUnit-enforced.
Envelope usage in controllers is `apps/AGENTS.md`.
The reviewer judges this file's rules as `CONV-13` — change one, check the other
(`.claude/review/sapari-reviewer-methodology.md`).

Layer split: **repository returns `List<Domain>`** · **service assembles the page** ·
**controller wraps it in the envelope**. `CursorPage`/`OffsetPage` are allowed in `-api` use-case
return types (the one foundation package `-api` may depend on).

### Cursor — infinite scroll

```java
int size = PageSupport.normalizeSize(query.size());          // null/<=0 → 15, cap 100
Cursor c = (query.cursor() == null) ? null : CursorCodec.decode(query.cursor());
List<LiveRoom> rows = repository.findPopular(                // ← size + 1, always
        c == null ? null : c.sortKeyAsLong(), c == null ? null : c.idAsUuid(), size + 1);
return CursorPage.of(rows.stream().map(LiveListView::from).toList(), size,
        v -> CursorCodec.encode(String.valueOf(v.viewerCount()), v.id().toString()));
```

- **Fetch `size + 1`.** `CursorPage.of` derives `hasNext` from `rows.size() > size`; fetching
  exactly `size` pins `hasNext` to `false` and the scroll dies with no error.
- **Size goes through `PageSupport.normalizeSize`.** Passing a client value straight to the query
  is an unbounded read.
- **Keyset `WHERE` must mirror `orderBy`, tie-break included:**
  `sortKey < :last OR (sortKey = :last AND id < :lastId)`, ordered by the same two keys.
  Dropping the tie-break duplicates or loses rows wherever the sort key ties.
  The matching **composite index** (`sort_key desc, id desc`) is part of the change.
- **Decode with `CursorCodec` + `Cursor.sortKeyAs*/idAsUuid`.** They raise `InvalidCursorException`
  (→ 400). Hand-rolled `UUID.fromString`/`Long.parseLong` on the raw cursor turns bad input into 500.
  The cursor is opaque to clients: two parts (sort key + id), Base64url, 512 chars max.
- Cursor decoding and the composite `WHERE` differ per sort order — they stay in each domain's
  repository, not in a shared helper.

### Offset — numbered pages

```java
List<LiveRoom> rows = repository.findBySeller(sellerId, page * size, size);
long total = repository.countBySeller(sellerId);
return OffsetPage.of(rows.stream().map(LiveListView::from).toList(), page, size, total);
```

The count query must carry **the same filters** as the data query, or `totalPages` lies.
Offset paging is for page-number UIs only; lists that scroll use the cursor form.
