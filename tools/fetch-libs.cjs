// Downloads the compile-time libraries into mcbot/lib (Paper API + Adventure).
const https = require("node:https");
const fs = require("node:fs");
const path = require("node:path");

const LIB = path.resolve(__dirname, "..", "lib");
fs.mkdirSync(LIB, { recursive: true });

const PAPER_REPO = "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api";
const CENTRAL = "https://repo1.maven.org/maven2";
const MC_TARGET = "26.1.2";

function get(url, binary, depth = 0) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https
      .get({ host: u.host, path: u.pathname + u.search, headers: { "user-agent": "codex-ops/1.0" } }, (res) => {
        if ([301, 302, 307, 308].includes(res.statusCode) && res.headers.location && depth < 5) {
          res.resume();
          return get(new URL(res.headers.location, u).toString(), binary, depth + 1).then(resolve, reject);
        }
        if (res.statusCode !== 200) {
          res.resume();
          return reject(new Error("HTTP " + res.statusCode + " for " + url));
        }
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => {
          const buf = Buffer.concat(chunks);
          resolve(binary ? buf : buf.toString("utf8"));
        });
      })
      .on("error", reject);
  });
}

async function paperApiJar() {
  const versions = String(await get(PAPER_REPO + "/maven-metadata.xml"));
  const list = [...versions.matchAll(/<version>([^<]+)<\/version>/g)].map((m) => m[1]);
  const exact = list.filter((v) => v.startsWith(MC_TARGET));
  const fallback = list.filter((v) => v.startsWith("1.21.1"));
  const chosen = (exact.length ? exact : fallback).pop();
  if (!chosen) throw new Error("no paper-api version found");
  const dir = `${PAPER_REPO}/${chosen}`;
  let jar = `paper-api-${chosen}.jar`;
  if (chosen.includes("SNAPSHOT")) {
    const meta = String(await get(dir + "/maven-metadata.xml"));
    const snapshot = (meta.match(/<snapshotVersion>\s*<extension>jar<\/extension>\s*<value>([^<]+)<\/value>/) || [])[1];
    if (snapshot) jar = `paper-api-${snapshot}.jar`;
  }
  const buf = await get(`${dir}/${jar}`, true);
  fs.writeFileSync(path.join(LIB, "paper-api.jar"), buf);
  console.log(`paper-api ${chosen} -> ${jar} (${(buf.length / 1048576).toFixed(2)} MB)`);
}

async function central(rel, name) {
  const buf = await get(`${CENTRAL}/${rel}`, true);
  fs.writeFileSync(path.join(LIB, name), buf);
  console.log(`${name} (${(buf.length / 1024).toFixed(0)} KB)`);
}

(async () => {
  await paperApiJar();
  await central("net/kyori/adventure-api/4.17.0/adventure-api-4.17.0.jar", "adventure-api.jar");
  await central("net/kyori/adventure-key/4.17.0/adventure-key-4.17.0.jar", "adventure-key.jar");
  await central("net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar", "examination-api.jar");
  await central("net/md-5/bungeecord-chat/1.20-R0.2/bungeecord-chat-1.20-R0.2.jar", "bungeecord-chat.jar");
  console.log("done. libs in", LIB);
})().catch((e) => {
  console.error("FAILED:", e.message);
  process.exit(1);
});
