// Real nginx/TLS locally, fixture upstreams. This is not STAGING acceptance.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import http from "node:http";
import https from "node:https";
import { spawn, execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const work = fs.mkdtempSync(path.join(os.tmpdir(), "nexus-nginx-"));
const nginx = process.env.NGINX_BINARY || "nginx";
const openssl = process.env.OPENSSL_BINARY || "openssl";
const servers = [];
let child;
let assertions = 0;
const verify = (actual, expected) => { assert.deepEqual(actual, expected); assertions++; };
const request = (url, options = {}) => new Promise((resolve, reject) => {
  const protocol = url.startsWith("https:") ? https : http;
  const req = protocol.request(url, { agent: false, ...options }, (res) => {
    let body = "";
    res.on("data", chunk => { body += chunk; });
    res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, body }));
  });
  req.on("error", reject);
  req.end();
});
try {
  fs.mkdirSync(path.join(work, "logs"));
  fs.mkdirSync(path.join(work, "temp"));
  fs.mkdirSync(path.join(work, "acme/.well-known/acme-challenge"), { recursive: true });
  fs.writeFileSync(path.join(work, "acme/.well-known/acme-challenge/probe"), "acme-fixture");
  execFileSync(openssl, ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
    "-keyout", path.join(work, "test.key"), "-out", path.join(work, "test.crt"),
    "-subj", "/CN=staging.example.test", "-addext", "subjectAltName=DNS:staging.example.test"], { stdio: "ignore", windowsHide: true });
  const ports = {};
  for (const name of ["gateway", "frontend", "identity"]) {
    const server = http.createServer((req, res) => {
      res.statusCode = req.url === "/api/v1/webhooks/sepay" ? 401 : 200;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ name, url: req.url, headers: req.headers }));
    });
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    servers.push(server);
    ports[name] = server.address().port;
  }
  // Reserve ephemeral listen ports, then release them immediately before nginx.
  for (const name of ["http", "https"]) {
    const reserve = http.createServer();
    await new Promise(resolve => reserve.listen(0, "127.0.0.1", resolve));
    ports[name] = reserve.address().port;
    await new Promise(resolve => reserve.close(resolve));
  }
  const absolute = value => value.replaceAll("\\", "/");
  let config = fs.readFileSync(path.join(dir, "nginx.conf"), "utf8")
    .replaceAll("${DOMAIN}", "staging.example.test")
    .replace("listen 80;", `listen 127.0.0.1:${ports.http};`)
    .replace("listen 443 ssl;", `listen 127.0.0.1:${ports.https} ssl;`)
    .replaceAll("/var/www/acme", absolute(path.join(work, "acme")))
    .replace("/etc/letsencrypt/live/staging.example.test/fullchain.pem", absolute(path.join(work, "test.crt")))
    .replace("/etc/letsencrypt/live/staging.example.test/privkey.pem", absolute(path.join(work, "test.key")));
  for (const [name, host, port] of [["gateway", "api-gateway", 8080], ["frontend", "frontend", 3000], ["identity", "auth-service", 8080]]) {
    config = config.replace(`http://${host}:${port}`, `http://127.0.0.1:${ports[name]}`);
  }
  fs.writeFileSync(path.join(work, "nginx.conf"), `events {}\nhttp {\n${config}\n}\n`);
  execFileSync(nginx, ["-p", absolute(work) + "/", "-c", "nginx.conf", "-t"], { windowsHide: true, stdio: "pipe" });
  child = spawn(nginx, ["-p", absolute(work) + "/", "-c", "nginx.conf", "-g", "daemon off; master_process off;"], { windowsHide: true, stdio: "pipe" });
  const tls = { ca: fs.readFileSync(path.join(work, "test.crt")), servername: "staging.example.test", headers: { host: "staging.example.test" } };
  const origin = `https://127.0.0.1:${ports.https}`;
  let ready = false;
  for (let attempt = 0; attempt < 50; attempt++) {
    try { await request(origin, tls); ready = true; break; } catch { await new Promise(resolve => setTimeout(resolve, 100)); }
  }
  assert.ok(ready, "nginx did not start");
  for (const [url, target] of [["/", "frontend"], ["/shop?q=phone", "frontend"], ["/api/v1/products?page=0", "gateway"], ["/.well-known/jwks.json", "gateway"], ["/auth/realms/nexus", "identity"]]) {
    const res = await request(origin + url, tls);
    verify(res.status, 200);
    const body = JSON.parse(res.body);
    verify([body.name, body.url], [target, url]);
    verify(body.headers["x-forwarded-proto"], "https");
  }
  const redirect = await request(`http://127.0.0.1:${ports.http}/shop?q=x`, { headers: { host: "staging.example.test" } });
  verify([redirect.status, redirect.headers.location], [308, "https://staging.example.test/shop?q=x"]);
  const acme = await request(`http://127.0.0.1:${ports.http}/.well-known/acme-challenge/probe`);
  verify([acme.status, acme.body], [200, "acme-fixture"]);
  for (const url of ["/actuator/health", "/api/v1/internal/probe", "/auth/admin/nexus/console/"]) {
    verify((await request(origin + url, tls)).status, 404);
  }
  for (const address of ["127.0.0.2", "127.0.0.3"]) {
    const res = await request(origin + "/api/v1/products", { ...tls, localAddress: address,
      headers: { host: "staging.example.test", "x-forwarded-for": "203.0.113.99", forwarded: "for=203.0.113.99", "x-forwarded-prefix": "/forged", "x-forwarded-ssl": "off" } });
    const body = JSON.parse(res.body);
    verify(body.headers["x-forwarded-for"], address);
    verify(body.headers.forwarded, undefined);
    verify(body.headers["x-forwarded-prefix"], undefined);
    verify(body.headers["x-forwarded-ssl"], undefined);
  }
  verify((await request(origin + "/api/v1/webhooks/sepay", { ...tls, method: "POST" })).status, 401);
  console.log(`${assertions} nginx/TLS assertions passed with fixture upstreams; two socket IPs stayed distinct and forged forwarding headers were removed.`);
} finally {
  child?.kill();
  await Promise.all(servers.map(server => new Promise(resolve => server.close(resolve))));
  // Preserve configs/logs for debugging, but remove the disposable private key.
  fs.rmSync(path.join(work, "test.key"), { force: true });
}
