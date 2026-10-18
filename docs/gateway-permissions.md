# Gateway permissions

| Method | Endpoint | Required privilege |
| --- | --- | --- |
| GET | /api/v1/products | Public |
| GET | /api/v1/products/mine | PRODUCT.LIST |
| POST | /api/v1/products | PRODUCT.CREATE |
| PUT | /api/v1/products/{id} | PRODUCT.UPDATE |
| GET | /api/v1/products/{id} | Public |
| GET | /api/v1/carts/me | Authenticated |
| POST | /api/v1/carts/me/items | Authenticated |

The gateway must forward Authorization to catalog-service. The service validates
the JWT using common-security and checks PRODUCT.CREATE. sellerId comes from the
validated token subject; new products always start as DRAFT.

PUT replaces name, description, categoryId and auctionEnable (required for PUT).
Only the owner or an ADMIN with PRODUCT.UPDATE can update a non-deleted product.
Ownership, status, order flags and createdAt are preserved. Hibernate manages updatedAt.

Public GET always filters ACTIVE and deleted_at IS NULL, including for owners and
admins. It returns product details with skus and images; empty collections are [].
Images are ordered by sortOrder, then id. SKU fields follow SKU-01's response contract.
Product has no price field. Product lists remain in #99.

Source: TaiLieu/nexus-api-endpoints.xlsx, Endpoints PRD-02 / PRD-03 (rows 57–58).
