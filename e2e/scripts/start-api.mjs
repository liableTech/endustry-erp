// Starts a throwaway copy of the API for the tests: its own build folder, its own port and a fresh
// demo database, both in the system temp folder (outside OneDrive). It never touches backend/target
// or backend/data, so the app you run with run-backend.cmd keeps working and keeps its data.
import { spawn, spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const backend = resolve(here, '../../backend');
const work = process.env.E2E_WORK_DIR ?? join(tmpdir(), 'endustry-e2e');
const build = join(work, 'build');
const port = process.env.E2E_API_PORT ?? '18080';
const windows = process.platform === 'win32';

/** Newest modification time under a folder, to tell whether the build is stale. */
function newest(path) {
  const info = statSync(path);
  if (!info.isDirectory()) return info.mtimeMs;
  return Math.max(info.mtimeMs, ...readdirSync(path).map((name) => newest(join(path, name))));
}

function findJar() {
  const target = join(build, 'target');
  if (!existsSync(target)) return null;
  const jar = readdirSync(target).find((name) => name.endsWith('.jar') && !name.endsWith('-plain.jar'));
  return jar ? join(target, jar) : null;
}

let jar = findJar();
const sources = Math.max(newest(join(backend, 'src')), newest(join(backend, 'pom.xml')));
if (!jar || statSync(jar).mtimeMs < sources) {
  console.log(`[e2e] Building the API in ${build} ...`);
  rmSync(build, { recursive: true, force: true });
  mkdirSync(build, { recursive: true });
  for (const name of ['pom.xml', 'src', 'mvnw', 'mvnw.cmd', '.mvn']) {
    if (existsSync(join(backend, name))) cpSync(join(backend, name), join(build, name), { recursive: true });
  }
  // A Maven on the PATH is used when there is one; otherwise the project's wrapper.
  const hasMaven = spawnSync(windows ? 'where' : 'which', ['mvn'], { stdio: 'ignore', shell: windows }).status === 0;
  const maven = hasMaven ? 'mvn' : join(build, windows ? 'mvnw.cmd' : 'mvnw');
  const result = spawnSync(maven, ['-q', '-DskipTests', 'package'], { cwd: build, stdio: 'inherit', shell: windows });
  if (result.status !== 0) process.exit(result.status ?? 1);
  jar = findJar();
}

// A new, empty database every run, so the tests always start from the same demo data.
const run = join(work, 'api');
rmSync(run, { recursive: true, force: true });
mkdirSync(run, { recursive: true });
console.log(`[e2e] Starting the API on port ${port} with a fresh demo database in ${run}`);
const api = spawn('java', ['-jar', jar, `--server.port=${port}`], { cwd: run, stdio: 'inherit' });
const stop = () => api.kill();
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
process.on('exit', stop);
api.on('exit', (code) => process.exit(code ?? 0));
