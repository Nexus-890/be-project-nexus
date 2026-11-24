import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

// No Node dependency or generated client is needed. Redocly separately validates
// schemas/examples; this check prevents delivered route inventory from drifting.
const root = fileURLToPath(new URL('../..', import.meta.url));
const services = {
  'config-service': [
    'src/main/java/com/group5/configservice/systemconfig/SystemConfigController.java',
  ],
  'api-gateway': [
    'src/main/java/com/group5/apigateway/session/SessionController.java',
  ],
  'user-service': [
    'src/main/java/com/group5/userservice/iam/AdminUserController.java',
    'src/main/java/com/group5/userservice/iam/RoleController.java',
    'src/main/java/com/group5/userservice/iam/AuthController.java',
    'src/main/java/com/group5/userservice/iam/UserController.java',
    'src/main/java/com/group5/userservice/phone/PhoneVerificationController.java',
    'src/main/java/com/group5/userservice/address/AddressController.java',
    'src/main/java/com/group5/userservice/seller/SellerController.java',
    'src/main/java/com/group5/userservice/seller/SellerApplicationController.java',
    'src/main/java/com/group5/userservice/avatar/AvatarController.java',
    'src/main/java/com/group5/userservice/password/PasswordChangeController.java',
    'src/main/java/com/group5/userservice/password/PasswordResetController.java',
  ],
  'catalog-service': [
    'src/main/java/com/group5/catalogservice/category/CategoryController.java',
    'src/main/java/com/group5/catalogservice/product/controller/ProductController.java',
    'src/main/java/com/group5/catalogservice/product/controller/SkuController.java',
    'src/main/java/com/group5/catalogservice/product/ProductImageController.java',
  ],
  'commerce-service': [
    'src/main/java/com/group5/commerceservice/cart/controller/CartController.java',
    'src/main/java/com/group5/commerceservice/checkout/controller/CheckoutController.java',
    'src/main/java/com/group5/commerceservice/order/controller/OrderController.java',
    'src/main/java/com/group5/commerceservice/order/controller/OrderShippingSnapshotController.java',
    'src/main/java/com/group5/commerceservice/order/OrderReceiptController.java',
    'src/main/java/com/group5/commerceservice/order/OrderReceiptConfirmationController.java',
    'src/main/java/com/group5/commerceservice/order/AuctionShippingController.java',
    'src/main/java/com/group5/commerceservice/promotion/MegaSaleEventController.java',
    'src/main/java/com/group5/commerceservice/promotion/VoucherController.java',
  ],
  'notification-service': [
    'src/main/java/com/group5/notificationservice/notification/NotificationController.java',
    'src/main/java/com/group5/notificationservice/health/SystemHealthController.java',
    'src/main/java/com/group5/notificationservice/delivery/DeliveryController.java',
    'src/main/java/com/group5/notificationservice/eventlog/EventLogController.java',
    'src/main/java/com/group5/notificationservice/eventlog/AuditLogController.java',
    'src/main/java/com/group5/notificationservice/delivery/AdminDeliveryController.java',
    'src/main/java/com/group5/notificationservice/preference/NotificationPreferenceController.java',
    'src/main/java/com/group5/notificationservice/stream/NotificationStreamController.java',
  ],
  'payment-service': [
    'src/main/java/com/group5/paymentservice/payment/controller/PaymentController.java',
    'src/main/java/com/group5/paymentservice/payment/controller/RefundController.java',
    'src/main/java/com/group5/paymentservice/payment/controller/SepayWebhookController.java',
    'src/main/java/com/group5/paymentservice/sellerbank/controller/BankAccountController.java',
    'src/main/java/com/group5/paymentservice/payout/controller/PayoutController.java',
    'src/main/java/com/group5/paymentservice/payout/controller/PayoutCallbackController.java',
    'src/main/java/com/group5/paymentservice/payout/controller/ManualPayoutController.java',
    'src/main/java/com/group5/paymentservice/payout/controller/SellerPayoutReceiptController.java',
  ],
  'inventory-service': [
    'src/main/java/com/group5/inventoryservice/inventory/InventoryController.java',
    'src/main/java/com/group5/inventoryservice/warehouse/controller/WarehouseController.java',
    'src/main/java/com/group5/inventoryservice/reservation/controller/ReservationController.java',
    'src/main/java/com/group5/inventoryservice/reservation/controller/InternalReservationController.java',
    'src/main/java/com/group5/inventoryservice/warehouse/controller/InternalWarehouseController.java',
  ],
  'auction-service': [
    'src/main/java/com/group5/auctionservice/offer/SecondChanceOfferController.java',
    'src/main/java/com/group5/auctionservice/auction/AuctionController.java',
    'src/main/java/com/group5/auctionservice/bidding/MyBidController.java',
    'src/main/java/com/group5/auctionservice/bidding/BidController.java',
    'src/main/java/com/group5/auctionservice/stream/AuctionStreamController.java',
  ],
  'shipment-service': [
    'src/main/java/com/group5/shipmentservice/shipping/controller/CarrierController.java',
    'src/main/java/com/group5/shipmentservice/shipping/controller/ShippingController.java',
    'src/main/java/com/group5/shipmentservice/shipping/controller/ShipmentController.java',
    'src/main/java/com/group5/shipmentservice/shipping/controller/ShipmentStatusController.java',
    'src/main/java/com/group5/shipmentservice/shipping/controller/CarrierWebhookController.java',
    'src/main/java/com/group5/shipmentservice/shipping/controller/CarrierPerformanceController.java',
  ],
  'rating-service': [
    'src/main/java/com/group5/ratingservice/rating/controller/RatingController.java',
    'src/main/java/com/group5/ratingservice/reputation/controller/ReputationController.java',
    'src/main/java/com/group5/ratingservice/reputation/controller/ReputationHistoryController.java',
    'src/main/java/com/group5/ratingservice/eligibility/controller/EligibilityController.java',
    'src/main/java/com/group5/ratingservice/reputation/controller/MyReputationController.java',
    'src/main/java/com/group5/ratingservice/moderation/controller/DisputeController.java',
    'src/main/java/com/group5/ratingservice/moderation/controller/RestrictionController.java',
  ],
};
const normalize = (route) => route.replace(/\{[^}]+\}/g, '{}');
let total = 0;
let failed = false;
for (const [service, controllers] of Object.entries(services)) {
  const actual = new Set();
  for (const controller of controllers) {
    const source = readFileSync(resolve(root, service, controller), 'utf8');
    const base = source.match(/@RequestMapping\("([^"]+)"\)/)?.[1] ?? '';
    // Nhận cả value/path và produces để kiểm tra đầy đủ endpoint SSE.
    const methods = source.matchAll(/@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:(?:value|path)\s*=\s*)?"([^"]*)"[^)]*\))?/g);
    for (const method of methods) {
      const key = `${method[1].toUpperCase()} ${normalize(base + (method[2] ?? ''))}`;
      if (actual.has(key)) throw new Error(`Duplicate source route: ${key}`);
      actual.add(key);
    }
  }
  const specification = readFileSync(resolve(root, 'docs/api/openapi', `${service}.yaml`), 'utf8');
  const documented = new Set();
  let currentPath;
  for (const line of specification.split(/\r?\n/)) {
    const path = line.match(/^  (\/.*):$/);
    if (path) currentPath = path[1];
    const method = line.match(/^    (get|post|put|patch|delete):$/);
    if (currentPath && method) {
      const key = `${method[1].toUpperCase()} ${normalize(currentPath)}`;
      if (documented.has(key)) throw new Error(`Duplicate spec route: ${key}`);
      documented.add(key);
    }
  }
  const missing = [...actual].filter((key) => !documented.has(key));
  const stale = [...documented].filter((key) => !actual.has(key));
  if (missing.length || stale.length) {
    failed = true;
    console.error(`${service}: undocumented=${JSON.stringify(missing)}, stale=${JSON.stringify(stale)}`);
  } else {
    console.log(`${service}: ${actual.size} controller operations match the delivered OpenAPI inventory`);
  }
  total += actual.size;
}
if (failed) process.exitCode = 1;
else console.log(`PASS: all ${total} delivered application operations documented; standard JWKS is excluded.`);
