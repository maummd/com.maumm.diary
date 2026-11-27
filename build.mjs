// web/index.html 한 파일로 묶습니다: node build.mjs  (LOCAL=1 이면 서버 없이 → web/local.html)
import { build } from "esbuild";
import { readFileSync, writeFileSync, mkdirSync, readdirSync, copyFileSync } from "node:fs";
const local = !!process.env.LOCAL;
const out = await build({
  entryPoints: ["src/cloud.js"], bundle: true, minify: true, format: "iife", target: ["es2020"], write: false, legalComments: "none",
  plugins: local ? [{ name: "local", setup(b) { b.onLoad({ filter: /config\.js$/ }, () => ({ contents: "export const FIREBASE_CONFIG = null;", loader: "js" })); } }] : [],
});
const js = out.outputFiles[0].text.replace(/<\/script/gi, "<\\/script");
const html = readFileSync("src/index.template.html", "utf8").replace("/*CLOUD*/", () => js);
mkdirSync("web", { recursive: true });
const f = local ? "web/local.html" : "web/index.html";
writeFileSync(f, html);
console.log(f, Math.round(html.length / 1024), "KB");

// 파일 읽기 도구 (PDF · 워드 · 엑셀 · 한글 · 사진)
mkdirSync("web/lib", { recursive: true });
for (const f of readdirSync("lib")) copyFileSync("lib/" + f, "web/lib/" + f);
