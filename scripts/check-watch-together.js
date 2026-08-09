#!/usr/bin/env node
/**
 * Static sanity checks for the Watch Together sources.
 *
 * The Android toolchain (JDK + Gradle) is not always available in a lightweight
 * dev container, so this script provides fast structural validation of the new
 * Java sources without compiling: balanced braces/parens, correct package
 * declarations, and that every symbol the unit tests reference actually exists in
 * the production sources.
 *
 * This is a smoke check, NOT a substitute for `./gradlew :app:testDebugUnitTest`
 * or `:app:assembleDebug`, which remain the authoritative validation in CI.
 *
 * Usage: node scripts/check-watch-together.js
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const MAIN = 'app/src/main/java/com/duoshield/app/call/watch';
const TEST = 'app/src/test/java/com/duoshield/app/call/watch';
const EXPECTED_PACKAGE = 'com.duoshield.app.call.watch';

const FILES = [
  `${MAIN}/WatchTogetherState.java`,
  `${MAIN}/YouTubeUrlParser.java`,
  `${MAIN}/WatchTogetherRepository.java`,
  `${TEST}/WatchTogetherStateTest.java`,
  `${TEST}/YouTubeUrlParserTest.java`,
];

let failures = 0;

function fail(msg) {
  console.log(`FAIL  ${msg}`);
  failures++;
}

function pass(msg) {
  console.log(`OK    ${msg}`);
}

/**
 * Removes comments and string/char literals so delimiter counting is accurate.
 *
 * Uses a single-pass state machine rather than chained regexes. A regex approach
 * gets this wrong on real Java: an apostrophe inside a comment (`the user's URL`)
 * makes a char-literal pattern swallow everything up to the next apostrophe,
 * eating real parentheses along the way and reporting phantom imbalances.
 */
function strip(src) {
  let out = '';
  let i = 0;
  const n = src.length;

  while (i < n) {
    const c = src[i];
    const next = src[i + 1];

    // Block comment
    if (c === '/' && next === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end === -1 ? n : end + 2;
      continue;
    }
    // Line comment
    if (c === '/' && next === '/') {
      const end = src.indexOf('\n', i);
      i = end === -1 ? n : end;
      continue;
    }
    // String literal
    if (c === '"') {
      i++;
      while (i < n && src[i] !== '"') {
        if (src[i] === '\\') i++;
        i++;
      }
      i++;
      out += '""';
      continue;
    }
    // Char literal
    if (c === "'") {
      i++;
      while (i < n && src[i] !== "'") {
        if (src[i] === '\\') i++;
        i++;
      }
      i++;
      out += "''";
      continue;
    }

    out += c;
    i++;
  }

  return out;
}

function balance(src, open, close) {
  let depth = 0;
  for (const ch of src) {
    if (ch === open) depth++;
    else if (ch === close) depth--;
  }
  return depth;
}

// ── Per-file structural checks ───────────────────────────────────────────────

const sources = {};

for (const rel of FILES) {
  const abs = path.join(ROOT, rel);
  const name = path.basename(rel);

  if (!fs.existsSync(abs)) {
    fail(`${name} does not exist at ${rel}`);
    continue;
  }

  const raw = fs.readFileSync(abs, 'utf8');
  sources[name] = raw;
  const clean = strip(raw);

  const braces = balance(clean, '{', '}');
  const parens = balance(clean, '(', ')');
  const pkg = (raw.match(/^package ([\w.]+);/m) || [])[1];

  const problems = [];
  if (braces !== 0) problems.push(`brace imbalance ${braces}`);
  if (parens !== 0) problems.push(`paren imbalance ${parens}`);
  if (pkg !== EXPECTED_PACKAGE) problems.push(`package "${pkg}" != "${EXPECTED_PACKAGE}"`);

  if (problems.length) fail(`${name}: ${problems.join(', ')}`);
  else pass(`${name} structure`);
}

// ── Cross-file symbol checks ─────────────────────────────────────────────────
// Every production symbol the tests call must exist, so a rename cannot silently
// break the test sources before CI runs.

const state = sources['WatchTogetherState.java'] || '';
const parser = sources['YouTubeUrlParser.java'] || '';

const REQUIRED_STATE_SYMBOLS = [
  'projectedPositionMs',
  'shouldSeek',
  'shouldApply',
  'isPlayable',
  'copy',
  'toMap',
  'fromMap',
  'DRIFT_THRESHOLD_MS',
  'DEFAULT_PLAYBACK_RATE',
  'HEARTBEAT_INTERVAL_MS',
  'F_ACTIVE',
  'F_VIDEO_ID',
  'F_HOST_UID',
  'F_PLAYING',
  'F_POSITION_MS',
  'F_PLAYBACK_RATE',
  'F_UPDATED_AT_MS',
  'F_SEQ',
  'F_LAST_ACTION_BY',
  'F_LAST_ACTION',
  'ACTION_START',
  'ACTION_PLAY',
  'ACTION_PAUSE',
  'ACTION_SEEK',
  'ACTION_RATE',
  'ACTION_STOP',
  'ACTION_HEARTBEAT',
];

for (const sym of REQUIRED_STATE_SYMBOLS) {
  if (!state.includes(sym)) fail(`WatchTogetherState is missing symbol: ${sym}`);
}
pass('WatchTogetherState exposes all symbols referenced by tests');

const REQUIRED_PARSER_SYMBOLS = [
  'extractVideoId',
  'isValid',
  'isValidVideoId',
  'extractStartMs',
];

for (const sym of REQUIRED_PARSER_SYMBOLS) {
  if (!parser.includes(sym)) fail(`YouTubeUrlParser is missing symbol: ${sym}`);
}
pass('YouTubeUrlParser exposes all symbols referenced by tests');

// ── Firestore rules checks ───────────────────────────────────────────────────

const rulesPath = path.join(ROOT, 'firestore.rules');
const rules = fs.readFileSync(rulesPath, 'utf8');

if (balance(rules, '{', '}') !== 0) {
  fail('firestore.rules has unbalanced braces');
} else {
  pass('firestore.rules braces balanced');
}

const callsBlock = (rules.match(/match \/calls\/\{callId\}[\s\S]*?\n {4}\}/) || [])[0];
if (!callsBlock) {
  fail('could not locate the /calls/{callId} block in firestore.rules');
} else if (!/match \/watch\/\{docId\}/.test(callsBlock)) {
  fail('/calls/{callId} block does not contain the /watch/{docId} rule');
} else {
  pass('/watch/{docId} rule is nested inside /calls/{callId}');
}

// The watch subcollection must be swept when a call ends.
const repoPath = path.join(ROOT, 'app/src/main/java/com/duoshield/app/call/CallSignalRepository.java');
const repo = fs.readFileSync(repoPath, 'utf8');
if (!/"watch"/.test(repo)) {
  fail('CallSignalRepository.deleteCallDoc does not sweep the "watch" subcollection');
} else {
  pass('CallSignalRepository sweeps the "watch" subcollection');
}

// ── Result ───────────────────────────────────────────────────────────────────

console.log('');
if (failures > 0) {
  console.log(`${failures} check(s) FAILED`);
  process.exit(1);
}
console.log('All Watch Together static checks passed.');
console.log('NOTE: run ./gradlew :app:testDebugUnitTest :app:assembleDebug for authoritative validation.');
