# Demo catalog images (#123)

These are illustrative demo photos, not exact product model photography. The
three phone products intentionally share the phone illustration. JPEG files are
checked in so provisioning never depends on the photo host being available.

Sources (Unsplash, resized to width 640, JPEG quality 80):

- phone.jpg: https://images.unsplash.com/photo-1511707171634-5f897ff02aa9
- shirt.jpg: https://images.unsplash.com/photo-1521572163474-6864f9cf17ab
- sneaker.jpg: https://images.unsplash.com/photo-1600269452121-4f2416e55c28

Run `../provision-product-images.ps1` with `SUPABASE_URL`,
`SUPABASE_SERVICE_KEY`, and optional `SUPABASE_BUCKET` in the environment (or
local-dev/.env). It creates only the five deterministic object keys listed in
manifest.json. Existing matching bytes are accepted; a different existing file
causes a failure rather than an overwrite. Public GET bytes are verified.

V7 seeds public URLs in the team's development bucket. They contain no secrets.
In another environment, deleting a seed image removes only its local database
row and logs a warning; it never calls the team's development storage. To host
the images in a different project/bucket, provision that bucket and add a separate
migration to rewrite the seed URLs; do not edit an already applied V7. Never run
the destructive delete demo against these shared seed IDs on the team's storage:
upload a test image on a disposable product instead.
