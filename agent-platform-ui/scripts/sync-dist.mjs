// 将 Vite 构建产物 dist/ 同步到 core 的静态资源目录，
// 使 Spring Boot 直接在 http://localhost:8081/ 托管仪表盘。
//
// 清理策略：**只删除构建产物**（assets/ 目录、顶层 index.html 与散落的旧 hash 资产），
// 保留 static/ 下手工放置的其它文件，避免整目录 rm 误删。
import { cpSync, rmSync, existsSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const dist = path.resolve(here, '..', 'dist');
const target = path.resolve(
  here, '..', '..',
  'agent-platform-core', 'src', 'main', 'resources', 'static'
);

if (!existsSync(dist)) {
  console.error('dist 不存在，请先执行 npm run build');
  process.exit(1);
}

mkdirSync(target, { recursive: true });

// 1) 清掉上一次构建的 hash 资产目录
const stale = [];
const assetsDir = path.join(target, 'assets');
if (existsSync(assetsDir)) {
  stale.push(`assets/ (${readdirSync(assetsDir).length} 个文件)`);
  rmSync(assetsDir, { recursive: true, force: true });
}

// 2) 清掉顶层构建产物 + 历史上散落的 hash 资产（如 index-abc123.js）
const HASH_ASSET = /^index-[\w-]+\.(js|css|js\.map)$/;
for (const name of readdirSync(target)) {
  const full = path.join(target, name);
  const isEntryHtml = name === 'index.html';
  const isStaleHashAsset = statSync(full).isFile() && HASH_ASSET.test(name);
  if (isEntryHtml || isStaleHashAsset) {
    stale.push(name);
    rmSync(full, { recursive: true, force: true });
  }
}

// 3) 复制新产物
cpSync(dist, target, { recursive: true });

const count = existsSync(assetsDir) ? readdirSync(assetsDir).length : 0;
console.log(
  stale.length
    ? `已同步 dist -> agent-platform-core/src/main/resources/static（清理 ${stale.length} 项：${stale.join('、')}；当前 assets ${count} 个文件）`
    : `已同步 dist -> agent-platform-core/src/main/resources/static（无可清理项；当前 assets ${count} 个文件）`
);
