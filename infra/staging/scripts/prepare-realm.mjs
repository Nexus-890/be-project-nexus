import fs from "node:fs";

const domain = process.env.DOMAIN ?? "";
if (!/^(?=.{1,253}$)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$/i.test(domain) || !domain.includes(".")) {
  throw new Error("DOMAIN must be a public DNS hostname without a scheme or port");
}
const realm = JSON.parse(fs.readFileSync("/input/realm.json", "utf8"));
const client = realm.clients.find((item) => item.clientId === "nexus-web");
if (!client) throw new Error("nexus-web missing from the source realm");
client.redirectUris = [`https://${domain}/*`];
client.webOrigins = [`https://${domain}`];
client.attributes["post.logout.redirect.uris"] = `https://${domain}/*`;
realm.smtpServer = {
  host: process.env.MAIL_HOST,
  port: process.env.MAIL_PORT || "587",
  from: process.env.MAIL_FROM,
  fromDisplayName: "Nexus STAGING",
  auth: process.env.MAIL_AUTH || "true",
  starttls: process.env.MAIL_STARTTLS || "true",
  ssl: "false",
  user: process.env.MAIL_USERNAME || "",
  password: process.env.MAIL_PASSWORD || "",
};
fs.mkdirSync("/output", { recursive: true });
fs.writeFileSync("/output/nexus-realm.json", JSON.stringify(realm, null, 2) + "\n", { mode: 0o600 });
fs.chownSync("/output/nexus-realm.json", 1000, 0);
console.log("Prepared realm import with STAGING origin and host SMTP settings");
