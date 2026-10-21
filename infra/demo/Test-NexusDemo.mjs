#!/usr/bin/env node
// Node 20+. Runs real HTTP checks against the managed, isolated demo stack.
// Demo registration and cart additions persist; temporary catalog rows are removed.
import { randomBytes, randomInt, randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const stateDir = path.join(process.env.LOCALAPPDATA || path.join(os.homedir(), ".local", "share"), "NexusDemo");
const options = {
  baseUrl: "http://127.0.0.1:8080",
  credentialsFile: path.resolve(scriptDir, "../staging/.env.example"),
  output: path.join(stateDir, "smoke-results.json"),
};
for (let i = 2; i < process.argv.length; i++) {
  const flag = process.argv[i];
  if (flag === "--help") {
    console.log("node Test-NexusDemo.mjs [--base-url http://127.0.0.1:8080] [--credentials-file path] [--output path]");
    process.exit(0);
  }
  const key = { "--base-url": "baseUrl", "--credentials-file": "credentialsFile", "--output": "output" }[flag];
  if (!key || !process.argv[i + 1]) throw new Error(`Unknown or incomplete option: ${flag}`);
  options[key] = process.argv[++i];
}
const baseUrl = new URL(options.baseUrl);
const results = {
  startedAt: new Date().toISOString(),
  target: baseUrl.origin,
  mode: "real backend through local API gateway; no mock",
  checks: [],
  resources: { registeredUser: null, temporaryProduct: null, temporaryCategory: null },
  notes: [
    "The synthetic registered user is retained in the demo user database; its ID/email are listed below.",
    "The cart check adds one alternate T-shirt SKU and records only its before/after quantity.",
    "Buyer profile PUT uses the existing editable values. No password, token, or full HTTP body is written to this report.",
  ],
};
const workbookIds = ["AUTH-01", "AUTH-02", "AUTH-07", "USR-01", "USR-02", "USR-05", "USR-06", "CAT-01", "CAT-02", "CAT-03", "CAT-04", "CAT-06", "PRD-01", "PRD-02", "PRD-03", "PRD-04", "PRD-05", "PRD-06", "PRD-07"];
const secrets = new Set();
const tokens = {};
let currentCheck;
let buyer;
let product;
let category;
let sku;
let productDeleted = false;
let categoryDeleted = false;
const seed = {
  buyerId: "68000000-0000-4000-8000-000000000003",
  sellerId: "68000000-0000-4000-8000-000000000002",
  productId: "6d260021-0000-4000-8000-000000000207",
  categoryId: "6d260021-0000-4000-8000-000000000706",
  cartSkuId: "6d260021-0000-4000-8000-000000000314",
};
const unique = `${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`;

function redact(value) {
  let text = String(value);
  for (const secret of secrets) if (secret) text = text.split(secret).join("[redacted]");
  return text.replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, "[redacted-token]");
}
function expect(condition, message) {
  if (!condition) throw new Error(message);
  currentCheck.assertions.push(message);
}
function uuid(value) {
  return typeof value === "string" && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value);
}
function publicUser(body, expectedId) {
  expect(body && uuid(body.id) && (!expectedId || body.id === expectedId), "User has the expected UUID");
  expect(typeof body.email === "string" && typeof body.fullName === "string", "User has email and full name");
  expect(Array.isArray(body.roles) && body.status === "ACTIVE", "User has roles and ACTIVE status");
  expect(!["password", "passwordHash", "password_hash", "identityNumber"].some(key => key in body), "User response omits password and identity fields");
}
function page(body) {
  expect(body && Array.isArray(body.content), "Page contains a content array");
  expect(Number.isInteger(body.totalElements) && Number.isInteger(body.totalPages), "Page contains integer totals");
}
function cart(body) {
  expect(body && uuid(body.id) && body.userId === seed.buyerId && body.status === "ACTIVE", "Cart belongs to the demo buyer and is ACTIVE");
  expect(Array.isArray(body.items), "Cart contains an items array");
  expect(body.items.every(item => uuid(item.id) && uuid(item.skuId) && uuid(item.productId)
    && Number.isInteger(item.quantity) && item.quantity > 0
    && Number.isFinite(Number(item.unitPrice)) && Number(item.unitPrice) >= 0), "Cart items have valid references, quantities and unit prices");
}
async function request(method, route, role, body) {
  if (role && !tokens[role]) throw new Error(`Missing ${role} login prerequisite`);
  const response = await fetch(new URL(route, baseUrl), {
    method,
    headers: { Accept: "application/json", ...(body ? { "Content-Type": "application/json" } : {}), ...(role ? { Authorization: `Bearer ${tokens[role]}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(20_000),
    redirect: "error",
  });
  const text = await response.text();
  let data = null;
  if (text) { try { data = JSON.parse(text); } catch { data = null; } }
  currentCheck.httpStatus = response.status;
  return { status: response.status, data };
}
async function check(id, name, method, route, role, body, expectedStatus, validate, prerequisite = true, group = "workbook") {
  const entry = { id, name, group, method, path: route, actor: role || "anonymous", expectedStatus, assertions: [], result: "pending" };
  results.checks.push(entry);
  currentCheck = entry;
  if (!prerequisite) {
    entry.result = "skipped";
    entry.reason = "A required prior check failed";
    console.log(`SKIP ${id}`);
    return null;
  }
  const started = performance.now();
  try {
    const response = await request(method, route, role, body);
    expect(response.status === expectedStatus, `HTTP status is ${expectedStatus} (received ${response.status})`);
    if (validate) await validate(response.data, entry);
    entry.result = "passed";
    console.log(`PASS ${id} ${method} ${route}`);
    return response.data;
  } catch (error) {
    entry.result = "failed";
    entry.error = redact(error.message);
    console.log(`FAIL ${id}: ${entry.error}`);
    return null;
  } finally {
    entry.durationMs = Math.round(performance.now() - started);
  }
}
async function login(role, credentials) {
  const id = role === "BUYER" ? "AUTH-02" : `LOGIN-${role}`;
  const data = await check(id, `Login as ${role}`, "POST", "/api/v1/auth/login", null,
    { email: credentials[`DEMO_${role}_EMAIL`], password: credentials[`DEMO_${role}_PASSWORD`] }, 200, body => {
      if (typeof body?.accessToken === "string") secrets.add(body.accessToken);
      expect(typeof body?.accessToken === "string" && body.accessToken.split(".").length === 3, "Login returns a JWT access token");
      expect(body.tokenType === "Bearer" && body.expiresIn > 0, "Login returns Bearer type and a positive lifetime");
    }, true, role === "BUYER" ? "workbook" : "setup");
  if (data) tokens[role] = data.accessToken;
}

try {
  if (baseUrl.protocol !== "http:" || !["localhost", "127.0.0.1", "[::1]"].includes(baseUrl.hostname)
      || baseUrl.port !== "8080" || baseUrl.pathname !== "/" || baseUrl.username || baseUrl.password || baseUrl.search) {
    throw new Error("Smoke writes require the local demo API gateway on port 8080");
  }
  const processes = JSON.parse((await readFile(path.join(stateDir, "processes.json"), "utf8")).replace(/^\uFEFF/, ""));
  for (const [name, database] of Object.entries({ "user-service": "nexus_demo_user_db", "catalog-service": "nexus_demo_catalog_db", "commerce-service": "nexus_demo_commerce_db" })) {
    const managed = processes.find(item => item.name === name && item.database === database);
    if (!managed) throw new Error(`Start-NexusDemo.ps1 must manage ${name} against ${database} before smoke writes`);
    process.kill(managed.pid, 0);
  }
  results.safety = { managedDemoDatabasesVerified: true, databases: ["nexus_demo_user_db", "nexus_demo_catalog_db", "nexus_demo_commerce_db"] };
  const credentials = {};
  for (const line of (await readFile(options.credentialsFile, "utf8")).split(/\r?\n/)) {
    const match = /^([A-Z_]+)=(.*)$/.exec(line);
    if (match) credentials[match[1]] = match[2].trim();
  }
  for (const role of ["ADMIN", "SELLER", "BUYER"]) {
    for (const field of ["EMAIL", "PASSWORD"]) {
      const key = `DEMO_${role}_${field}`;
      credentials[key] = process.env[key] || credentials[key];
      if (!credentials[key]) throw new Error(`Missing ${key}`);
      if (field === "PASSWORD") secrets.add(credentials[key]);
    }
  }
  await check("AUTH-07", "Public signing keys", "GET", "/.well-known/jwks.json", null, null, 200, body => {
    expect(Array.isArray(body?.keys) && body.keys.length > 0, "JWKS contains public signing keys");
    expect(body.keys.every(key => key.kty === "RSA" && key.kid && key.n && key.e), "Each public RSA key has kid, modulus and exponent");
    expect(body.keys.every(key => !["d", "p", "q", "dp", "dq", "qi"].some(field => field in key)), "JWKS omits private RSA material");
  });
  for (const role of ["BUYER", "ADMIN", "SELLER"]) await login(role, credentials);
  const syntheticPassword = randomBytes(24).toString("base64url");
  secrets.add(syntheticPassword);
  const syntheticEmail = `smoke-${unique}@example.test`;
  const registration = await check("AUTH-01", "Register a synthetic demo buyer", "POST", "/api/v1/auth/register", null, {
    email: syntheticEmail, phone: `09${Date.now().toString().slice(-9)}${randomInt(100, 1000)}`,
    password: syntheticPassword, fullName: `Demo Smoke ${unique}`, identityNumber: `9${Date.now()}${randomInt(100, 1000)}`,
    gender: "OTHER", address: "Demo smoke account, Ho Chi Minh City", dateOfBirth: "2000-01-01",
  }, 201, body => {
    publicUser(body);
    expect(body.email === syntheticEmail && body.roles.includes("BUYER"), "Registration returns the new email and default BUYER role");
    results.resources.registeredUser = { id: body.id, email: body.email, retained: true };
  });
  buyer = await check("USR-01", "Read buyer profile", "GET", "/api/v1/users/me", "BUYER", null, 200, body => {
    publicUser(body, seed.buyerId);
    expect(body.email === credentials.DEMO_BUYER_EMAIL, "Profile matches the buyer login email");
  }, !!tokens.BUYER);
  const profileFields = buyer && Object.fromEntries(["fullName", "dateOfBirth", "gender", "address", "email"].filter(key => buyer[key] != null).map(key => [key, buyer[key]]));
  await check("USR-02", "Save supported existing profile values", "PUT", "/api/v1/users/me", "BUYER", profileFields, 200, body => {
    publicUser(body, seed.buyerId);
    expect(Object.entries(profileFields).every(([key, value]) => body[key] === value), "PUT preserves the existing supported profile values");
  }, !!buyer);
  await check("USR-05", "Admin searches buyer users", "GET", `/api/v1/users?q=${encodeURIComponent(credentials.DEMO_BUYER_EMAIL)}&role=BUYER&status=ACTIVE&page=0&size=20&sort=fullName&direction=asc`, "ADMIN", null, 200, body => {
    page(body);
    expect(body.content.some(user => user.id === seed.buyerId), "Filtered user page contains the demo buyer");
    expect(body.content.every(user => user.roles.includes("BUYER") && user.status === "ACTIVE"), "User filters restrict role and status");
    for (const user of body.content) publicUser(user);
  }, !!tokens.ADMIN);
  await check("USR-06", "Admin reads buyer by UUID", "GET", `/api/v1/users/${seed.buyerId}`, "ADMIN", null, 200,
    body => publicUser(body, seed.buyerId), !!tokens.ADMIN);
  category = await check("CAT-01", "Admin creates an empty temporary category", "POST", "/api/v1/categories", "ADMIN", {
    name: `Smoke category ${unique}`, slug: `smoke-${unique}`, description: "Temporary category for real demo smoke checks",
  }, 201, body => {
    expect(uuid(body?.id) && body.slug === `smoke-${unique}` && body.level === 1 && body.parentId == null, "Created category has UUID, slug and root position");
    results.resources.temporaryCategory = { id: body.id, slug: body.slug, deleted: false };
  }, !!tokens.ADMIN);
  await check("CAT-02", "Admin patches temporary category", "PATCH", `/api/v1/categories/${category?.id}`, "ADMIN", { name: `Updated smoke ${unique}` }, 200, body => {
    expect(body?.id === category.id && body.name === `Updated smoke ${unique}` && body.slug === category.slug, "Partial PATCH changes the name and preserves the slug");
  }, !!category);
  await check("CAT-03", "Read category by UUID anonymously", "GET", `/api/v1/categories/${category?.id}`, null, null, 200, body => {
    expect(body?.id === category.id && body.slug === category.slug, "Category read returns the requested identity and slug");
  }, !!category);
  await check("CAT-04", "Read public category tree", "GET", "/api/v1/categories/tree", null, null, 200, body => {
    expect(Array.isArray(body) && body.length > 0, "Category tree has root nodes");
    const flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children || [])]);
    const nodes = flatten(body);
    expect(nodes.some(node => node.id === seed.categoryId), "Tree includes the seeded cotton category");
    expect(nodes.every(node => uuid(node.id) && typeof node.slug === "string" && Array.isArray(node.children)), "Every category tree node has identity, slug and child array");
  });
  product = await check("PRD-01", "Seller creates a temporary DRAFT product", "POST", "/api/v1/products", "SELLER", {
    name: `Smoke product ${unique}`, description: "Temporary product for real demo API checks", categoryId: seed.categoryId, auctionEnable: false,
  }, 201, body => {
    expect(uuid(body?.id) && body.status === "DRAFT" && body.sellerId === seed.sellerId && body.categoryId === seed.categoryId, "Created product is DRAFT and owned by the demo seller");
    results.resources.temporaryProduct = { id: body.id, name: body.name, deleted: false };
  }, !!tokens.SELLER);
  const productRequest = { name: `Updated smoke product ${unique}`, description: "Updated through the real seller API", categoryId: seed.categoryId, auctionEnable: false };
  await check("PRD-02", "Seller updates temporary product", "PUT", `/api/v1/products/${product?.id}`, "SELLER", productRequest, 200, body => {
    expect(body?.id === product.id && body.name === productRequest.name && body.status === "DRAFT", "PUT persists the requested product name and retains DRAFT status");
  }, !!product);
  sku = await check("SKU-CREATE", "Seller creates a temporary SKU", "POST", `/api/v1/products/${product?.id}/skus`, "SELLER", {
    skuCode: `SMOKE-${unique}`, color: "Blue", size: "M", price: 125000,
  }, 201, body => {
    expect(uuid(body?.id) && body.productId === product.id && Number(body.price) === 125000, "SKU is linked to the temporary product with the requested price");
  }, !!product, "extra");
  await check("SKU-UPDATE", "Seller updates temporary SKU price", "PUT", `/api/v1/skus/${sku?.id}`, "SELLER", {
    skuCode: `SMOKE-${unique}`, color: "Blue", size: "M", price: 129000,
  }, 200, body => {
    expect(body?.id === sku.id && Number(body.price) === 129000, "SKU update persists the requested price");
  }, !!sku, "extra");
  await check("PRD-05", "Seller lists owned products", "GET", "/api/v1/products/mine?page=0&size=100", "SELLER", null, 200, body => {
    page(body);
    expect(body.content.length > 0 && body.content.every(item => item.sellerId === seed.sellerId), "Seller page contains only the demo seller's products");
    if (product) expect(body.content.some(item => item.id === product.id && item.status === "DRAFT"), "Seller page exposes the newly created DRAFT product");
  }, !!tokens.SELLER);
  await check("PRD-06", "Seller activates the temporary product", "PATCH", `/api/v1/products/${product?.id}/status`, "SELLER", { status: "ACTIVE" }, 200, body => {
    expect(body?.id === product.id && body.status === "ACTIVE", "Product transitions from DRAFT to ACTIVE");
  }, !!product);
  await check("PRD-03", "Read active product detail anonymously", "GET", `/api/v1/products/${product?.id}`, null, null, 200, body => {
    expect(body?.id === product.id && body.status === "ACTIVE" && body.name === productRequest.name, "Public detail returns the activated product and saved name");
    expect(Array.isArray(body.skus) && Array.isArray(body.images), "Detail contains SKU and image arrays");
    if (sku) expect(body.skus.some(item => item.id === sku.id && Number(item.price) === 129000), "Product detail includes the updated SKU");
  }, !!product);
  await check("PRD-04", "Public product page filters by category", "GET", `/api/v1/products?categoryId=${seed.categoryId}&page=0&size=100`, null, null, 200, body => {
    page(body);
    expect(body.content.length > 0 && body.content.every(item => item.status === "ACTIVE" && item.categoryId === seed.categoryId), "Product page contains ACTIVE products in the requested category");
    if (product) expect(body.content.some(item => item.id === product.id), "Activated temporary product appears in the public page");
  });
  await check("SKU-READ", "Read public SKU by UUID", "GET", `/api/v1/skus/${sku?.id}`, null, null, 200, body => {
    expect(body?.id === sku.id && body.productId === product.id && Number(body.price) === 129000, "Public SKU read returns the updated SKU identity, product and price");
  }, !!sku, "extra");
  await check("SKU-LIST", "List public SKUs of an active product", "GET", `/api/v1/products/${product?.id}/skus`, null, null, 200, body => {
    expect(Array.isArray(body) && body.some(item => item.id === sku.id && item.productId === product.id), "Public SKU list includes the temporary product's SKU");
  }, !!sku, "extra");
  const currentCart = await check("CRT-01", "Read buyer cart", "GET", "/api/v1/carts/me", "BUYER", null, 200, cart, !!tokens.BUYER, "extra");
  const cartSku = await check("CART-SKU", "Read alternate seeded T-shirt SKU", "GET", `/api/v1/skus/${seed.cartSkuId}`, null, null, 200, body => {
    expect(body?.id === seed.cartSkuId && body.productId === seed.productId && Number(body.price) > 0, "Cart test SKU belongs to the seeded T-shirt product");
  }, true, "extra");
  const beforeQuantity = currentCart?.items.find(item => item.skuId === seed.cartSkuId)?.quantity || 0;
  await check("CRT-02", "Add one alternate T-shirt SKU to buyer cart", "POST", "/api/v1/carts/me/items", "BUYER", { skuId: seed.cartSkuId, quantity: 1 }, 201, (body, entry) => {
    cart(body);
    const item = body.items.find(item => item.skuId === seed.cartSkuId);
    expect(item?.quantity === beforeQuantity + 1 && item.productId === seed.productId, "POST adds exactly one to the requested SKU quantity");
    expect(Number(item.unitPrice) === Number(cartSku.price), "Cart price matches the current real catalog SKU price");
    entry.evidence = { skuId: seed.cartSkuId, beforeQuantity, afterQuantity: item.quantity, unitPrice: item.unitPrice };
  }, !!currentCart && !!cartSku, "extra");
  for (const [id, method, route, body] of [
    ["DENY-USERS", "GET", "/api/v1/users", null],
    ["DENY-CATEGORY", "POST", "/api/v1/categories", { name: "Forbidden smoke category", slug: `forbidden-${unique}` }],
    ["DENY-PRODUCT", "POST", "/api/v1/products", productRequest],
  ]) {
    await check(id, "Buyer cannot use elevated administration/seller actions", method, route, "BUYER", body, 403,
      response => expect(response?.status === 403, "Forbidden response identifies HTTP 403"), !!tokens.BUYER, "negative");
  }
} catch (error) {
  results.fatalError = redact(error.message);
  console.error(results.fatalError);
} finally {
  // Only IDs created by this run are ever deleted. Seeded catalog rows are retained.
  if (product?.id && tokens.SELLER) {
    await check("PRD-07", "Seller deletes only the temporary product", "DELETE", `/api/v1/products/${product.id}`, "SELLER", null, 204, () => {
      productDeleted = true;
      results.resources.temporaryProduct.deleted = true;
    });
    if (productDeleted) await check("VERIFY-PRODUCT-DELETE", "Deleted product is no longer public", "GET", `/api/v1/products/${product.id}`, null, null, 404,
      body => expect(body?.status === 404, "Deleted product returns a 404 problem"), true, "cleanup");
  }
  if (category?.id && tokens.ADMIN) {
    await check("CAT-06", "Admin deletes only the empty temporary category", "DELETE", `/api/v1/categories/${category.id}`, "ADMIN", null, 204, () => {
      categoryDeleted = true;
      results.resources.temporaryCategory.deleted = true;
    });
    if (categoryDeleted) await check("VERIFY-CATEGORY-DELETE", "Deleted category is no longer readable", "GET", `/api/v1/categories/${category.id}`, null, null, 404,
      body => expect(body?.status === 404, "Deleted category returns a 404 problem"), true, "cleanup");
  }
  for (const id of workbookIds) {
    if (!results.checks.some(item => item.id === id && item.group === "workbook")) {
      results.checks.push({ id, group: "workbook", result: "skipped", reason: "Required setup did not complete", assertions: [] });
    }
  }
  const workbook = results.checks.filter(item => item.group === "workbook");
  results.summary = {
    workbookTotal: workbookIds.length,
    workbookPassed: workbook.filter(item => item.result === "passed").length,
    workbookFailed: workbook.filter(item => item.result === "failed").length,
    workbookSkipped: workbook.filter(item => item.result === "skipped").length,
    totalChecks: results.checks.length,
    passed: results.checks.filter(item => item.result === "passed").length,
    failed: results.checks.filter(item => item.result === "failed").length,
    skipped: results.checks.filter(item => item.result === "skipped").length,
    assertionsPassed: results.checks.reduce((sum, item) => sum + item.assertions.length, 0),
  };
  results.completedAt = new Date().toISOString();
  results.exitCode = results.fatalError || results.summary.failed || results.summary.skipped ? 1 : 0;
  await mkdir(path.dirname(path.resolve(options.output)), { recursive: true });
  await writeFile(options.output, `${JSON.stringify(results, null, 2)}\n`, "utf8");
  console.log(`Workbook ${results.summary.workbookPassed}/${workbookIds.length}; all checks ${results.summary.passed}/${results.summary.totalChecks}; report ${path.resolve(options.output)}`);
  process.exitCode = results.exitCode;
}
