import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

// No Node dependency or generated client is needed. Redocly separately validates
// schemas/examples; this check prevents delivered route inventory from drifting.
const root = fileURLToPath(new URL('../..', import.meta.url));
const services = {
  'api-gateway': [
    'src/main/java/com/group5/apigateway/session/SessionController.java',
  ],
  'user-service': [
    'src/main/java/com/group5/userservice/iam/AuthController.java',
    'src/main/java/com/group5/userservice/iam/UserController.java',
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
  ],
  'notification-service': [
    'src/main/java/com/group5/notificationservice/notification/NotificationController.java',
    'src/main/java/com/group5/notificationservice/health/SystemHealthController.java',
    'src/main/java/com/group5/notificationservice/delivery/DeliveryController.java',
    'src/main/java/com/group5/notificationservice/eventlog/EventLogController.java',
    'src/main/java/com/group5/notificationservice/eventlog/AuditLogController.java',
  ],
  'payment-service': [
    'src/main/java/com/group5/paymentservice/payment/controller/PaymentController.java',
    'src/main/java/com/group5/paymentservice/payment/controller/RefundController.java',
    'src/main/java/com/group5/paymentservice/payment/controller/SepayWebhookController.java',
  ],
  'inventory-service': [
    'src/main/java/com/group5/inventoryservice/inventory/InventoryController.java',
  ],
  'auction-service': [
    'src/main/java/com/group5/auctionservice/auction/AuctionController.java',
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
    const methods = source.matchAll(/@(Get|Post|Put|Patch|Delete)Mapping(?:\("([^"]*)"\))?/g);
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
