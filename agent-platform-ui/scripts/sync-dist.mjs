// 将 Vite 构建产物 dist/ 同步到 core 的静态资源目录，
// 使 Spring Boot 直接在 http://localhost:8081/ 托管仪表盘。
import { cpSync, rmSync, existsSync, mkdirSync } from 'node:fs';
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
rmSync(target, { recursive: true, force: true });
mkdirSync(target, { recursive: true });
cpSync(dist, target, { recursive: true });
console.log('已同步 dist -> agent-platform-core/src/main/resources/static');