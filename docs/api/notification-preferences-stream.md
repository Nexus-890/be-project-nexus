# Notification preferences, auction alerts and inbox streaming

The delivered machine-readable contract is [notification-service.yaml](openapi/notification-service.yaml).

`GET /api/v1/notifications/preferences` uses the authenticated subject and `NOTIFICATION.VIEW`.
`PUT` requires the existing `NOTIFICATION.MANAGE_PREFERENCE` privilege; inbox read access alone does not grant settings writes.
The matrix contains `EMAIL` and `IN_APP`; unsupported channels/types, duplicate update cells and disabling
a mandatory cell return 400 without partially applying the update. `BID_OUTBID` is optional. Business
transaction notifications, including auction wins and payment deadlines, stay enabled in the API and
the email worker even if a historical database preference is false. `UserRegistered` initializes
defaults idempotently, and the first preferences read initializes historical users. Defaults are true.
An `IN_APP` choice affects future inbox entries; existing inbox history remains visible.

The auction bid consumer snapshots the old leader under the auction row lock. An accepted bid from a
different leader writes `BidOutbid` alongside `BidPlaced` in the same transaction/outbox. First bids,
rejected bids, duplicate command replays and a leader raising their own bid do not write `BidOutbid`.
Topic is `nexus.auction.events`, key/aggregateId is `auctionId`. Payload fields:

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000001",
  "bidId": "00000000-0000-4000-8000-000000000002",
  "outbidUserId": "00000000-0000-4000-8000-000000000003",
  "bidderId": "00000000-0000-4000-8000-000000000004",
  "amount": "1500000.00",
  "currency": "VND",
  "placedAt": "2026-11-19T03:00:00Z"
}
```

All payload fields are required; money is a decimal string. Notification template `BID_OUTBID` targets
only `outbidUserId`. Existing `AuctionWon` events feed `AUCTION_WON`, targeting `winnerId` and displaying
the existing `paymentDueAt`; no new winner event is needed. Domain notification creation uses the
existing processed-event marker and transactional delivery fan-out.

`GET /api/v1/notifications/stream` is private authenticated SSE. Use bearer-authenticated fetch streaming.
The initial `notifications` event and subsequent committed changes contain `{unreadCount, revision}`;
`id` equals the revision. Refetch the inbox and unread count when that event arrives. A database trigger
increments the user's cursor together with visible inserts, read updates or deletes, so changes made
on another service instance are observed. Reconnect always returns the current snapshot, including
when `Last-Event-ID` is stale. A heartbeat arrives every two seconds. Connections close by token expiry
and at most 30 seconds after opening; reconnect with the current token. This provides an in-app bell;
it does not request browser WebPush permission or deliver OS alerts after the page is closed.

`GET /api/v1/notifications/deliveries` and `/deliveries/{deliveryId}` require `ADMIN` or `SUPPORT_STAFF` and
`NOTIFICATION.VIEW`; both endpoints are read-only. The list has stable newest-first pagination, status/channel/time filters and an
`errorCode` filter matching any historical attempt. `from` is inclusive and `to` exclusive. Detail
includes attempt history and symbolic error codes; recipient email, provider IDs and raw error messages
are excluded. Existing `/{notificationId}/deliveries` remains owner-only.

Migration `V10__notification_preferences_and_inbox_stream.sql` adds the inbox visibility snapshot,
durable per-user cursor, `IN_APP` enum value and indexes. Existing notification history stays visible.

Verification uses the isolated PostgreSQL fixture via `TEST_POSTGRES_ADMIN_URL`,
`TEST_POSTGRES_USERNAME`, `TEST_POSTGRES_PASSWORD`. With JDK 21, run notification-service Maven tests
`NotificationMarkReadIntegrationTest,NotificationStreamRegistryTest,NotificationTemplateTest,DomainEventConsumerTest,NotificationApiArchitectureTest,NotificationResponseContractTest,RetryPolicyTest,EmailSenderClassifyTest`.
The selected suite covers preferences/default event replay, atomic mandatory validation, worker claim
opt-outs, inbox isolation/cursors, private SSE authentication/reconnect snapshot/session expiry,
admin list/detail authorization and the existing owner scope. SMTP delivery still uses the repository's
Mailpit integration suite when that fixture is available.
