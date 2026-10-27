# API reference

Owner requests use `Authorization: Bearer <owner-key>`. Shared requests use `Authorization: Bearer <manager-code>`. Do not put either secret into API query strings. Responses contain IDs as strings and timestamps in UTC.

List endpoints accept `page` (0..10000) and `size` (1..100, default 20). They return `{items, page, size, total}`. Sorting includes an ID tie-breaker. Catalog lists sort by name; movements sort by newest first.

## Owner endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/dashboard` | Product, warehouse, unit, low-stock totals and recent history. |
| GET | `/api/v1/products` | Product list; optional `search` and `archived`. |
| POST | `/api/v1/products` | Create a product. |
| PUT | `/api/v1/products/{id}` | Edit an active product. |
| POST | `/api/v1/products/{id}/archive` | Archive a product with zero remaining stock. |
| GET | `/api/v1/suppliers` | Supplier list; optional `search` and `archived`. |
| POST | `/api/v1/suppliers` | Create a supplier. |
| PUT | `/api/v1/suppliers/{id}` | Edit an active supplier. |
| POST | `/api/v1/suppliers/{id}/archive` | Archive a supplier with no active product references. |
| GET | `/api/v1/warehouses` | Warehouse list; optional `search` and `archived`. |
| POST | `/api/v1/warehouses` | Create a warehouse. |
| PUT | `/api/v1/warehouses/{id}` | Edit an active warehouse. |
| POST | `/api/v1/warehouses/{id}/archive` | Archive a warehouse with zero remaining stock. |
| GET | `/api/v1/stock` | Live balances; optional `warehouseId`, `productId`, and `low=true`. |
| POST | `/api/v1/stock/receipts` | Receive stock. Requires `Idempotency-Key`. |
| POST | `/api/v1/stock/issues` | Issue stock. Requires `Idempotency-Key`. |
| POST | `/api/v1/stock/transfers` | Transfer stock. Requires `Idempotency-Key`. |
| GET | `/api/v1/movements` | History; optional `warehouseId`. |
| GET | `/api/v1/autocomplete?prefix=be` | Up to ten active product suggestions. |
| POST | `/api/v1/warehouses/{id}/links` | Create or reuse an active warehouse short link for the label. Returns `link`, `originalUrl`, and `url`. |
| GET | `/api/v1/warehouses/{id}/links` | Link metadata; secrets are omitted. |
| DELETE | `/api/v1/warehouses/{id}/links/{linkId}` | Revoke a link. Repeat revocation succeeds. |
| GET | `/api/v1/system` | Current instance, dependency health, token settings, and cache routing/counters. |

### Input bodies

Product:

```json
{"sku":"BRG-001","name":"Precision bearing","category":"Mechanical","supplierId":"1","reorderLevel":20}
```

Supplier:

```json
{"name":"Northline Supply","email":"orders@example.test","phone":""}
```

Warehouse:

```json
{"code":"WH-NORTH","name":"North warehouse","location":"Chennai"}
```

Receipt or issue:

```json
{"productId":"2","warehouseId":"3","quantity":10,"note":"Optional reference"}
```

Transfer:

```json
{"productId":"2","warehouseId":"3","destinationWarehouseId":"4","quantity":2,"note":"Replenishment"}
```

Link:

```json
{"label":"North warehouse manager"}
```

A link creation response contains `{link, url}`. `url` is returned once and is not stored in plaintext. Reorder levels are 0..1000000000. Operation quantities are 1..1000000000 and must be whole numbers. Unknown JSON fields are rejected. Required fields cannot be blank. Stock notes accept at most 400 characters.

### Request example

Use your actual product and warehouse IDs. Set `OWNER_ACCESS_KEY` in the terminal first. Keep the same request key and body when retrying a stock request.

```sh
curl --fail-with-body http://localhost:8080/api/v1/stock/receipts \
  -H "Authorization: Bearer $OWNER_ACCESS_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: receipt-2026-001' \
  -d '{"productId":"2","warehouseId":"3","quantity":10}'
```

## Shared endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/s/{code}` | Validate a short code and redirect to its original warehouse URL. |
| GET | `/api/shared/warehouse` | Warehouse details and first pages of stock, low-stock balances, and movements. |
| GET | `/api/shared/stock` | Paginated warehouse stock; optional `low=true`. |
| GET | `/api/shared/movements` | Paginated history for the linked warehouse. |

New short codes use the first 22 characters of Base64 URL-encoded SHA-256 of `PUBLIC_BASE_URL/shared.html#warehouse/{warehouseId}/warehouse_link_for_{encoded_label}`. The same URL returns the same active link. Reusing a revoked label or detecting a short-code collision returns HTTP 409. Shared APIs accept the short code or the original URL fragment as the bearer value.

Only `page`, `size`, and `low` query parameters are allowed on shared APIs. Supplying `warehouseId` is rejected. All shared API writes return 405. A manager code on an owner endpoint returns 401. Missing, invalid, revoked, or archived-warehouse codes return 404. There is no public autocomplete or master catalog endpoint.

The public `/health` endpoint returns 200 only when MongoDB and rate-limit Redis respond. Cache nodes are optional for readiness. It does not expose instance or infrastructure details.

## Status codes and headers

| Status | Meaning |
| --- | --- |
| 200 | Successful operation, including create, update, archive, or revoke. |
| 303 | Short-link redirect. |
| 400 | Invalid input, unsupported fields, invalid pagination, or a shared scope override. |
| 401 | Missing or invalid owner key. |
| 404 | Record or shared link unavailable. |
| 405 | Unsupported HTTP method, including shared writes. |
| 409 | Duplicate value, insufficient stock, archive conflict, or conflicting idempotency-key reuse. |
| 429 | Empty token bucket. Wait for `Retry-After` seconds. |
| 503 | Database or required rate-limit service unavailable, or a database operation timed out. |

Application errors use `{status, message}`. API responses include `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-Instance-Id`, and `Cache-Control: no-store`. `Retry-After` is present on 429. Health and static pages do not consume tokens. IP buckets are shared between owner requests, shared requests, and short-link opens.
