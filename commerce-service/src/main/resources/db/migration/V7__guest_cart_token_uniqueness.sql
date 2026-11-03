-- A guest owner capability must never identify two carts, including retired carts.
CREATE UNIQUE INDEX idx_carts_guest_token_unique ON carts (guest_token)
    WHERE guest_token IS NOT NULL;
