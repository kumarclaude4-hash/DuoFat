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
  `${MAIN}/WatchTogetherPlayerView.java`,
  `${MAIN}/WatchTogetherActivity.java`,
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

// ── CallActivity ⇄ WatchTogetherActivity integration checks ──────────────────
// These assert the feature is actually reachable from the call UI: the control-bar
// button is declared in the layout, bound + revealed + launched in CallActivity,
// the Activity declares the extras CallActivity passes, and the manifest declares
// the Activity. Any one of these missing means the feature silently does nothing.

function readFileOrFail(rel, label) {
  const abs = path.join(ROOT, rel);
  if (!fs.existsSync(abs)) {
    fail(`${label} not found at ${rel}`);
    return null;
  }
  return fs.readFileSync(abs, 'utf8');
}

const callLayout = readFileOrFail('app/src/main/res/layout/activity_call.xml', 'activity_call.xml');
const callActivity = readFileOrFail(
  'app/src/main/java/com/duoshield/app/call/CallActivity.java', 'CallActivity.java');
const watchActivity = sources['WatchTogetherActivity.java'] || '';
const manifest = readFileOrFail('app/src/main/AndroidManifest.xml', 'AndroidManifest.xml');
const watchLayout = 'app/src/main/res/layout/activity_watch_together.xml';

// 1. Control-bar button ids present in the call layout.
if (callLayout) {
  for (const id of ['@+id/btnWatchLayout', '@+id/btnWatch']) {
    if (!callLayout.includes(id)) fail(`activity_call.xml missing id "${id}"`);
  }
  if (callLayout.includes('@+id/btnWatchLayout') && callLayout.includes('@+id/btnWatch')) {
    pass('activity_call.xml declares btnWatchLayout + btnWatch');
  }
}

// 2. CallActivity binds, reveals, listens on, and launches from the button.
if (callActivity) {
  const requirements = [
    ['binds btnWatch', /findViewById\(R\.id\.btnWatch\)/],
    ['binds btnWatchLayout', /findViewById\(R\.id\.btnWatchLayout\)/],
    ['reveals btnWatchLayout', /btnWatchLayout[\s\S]{0,40}setVisibility\(View\.VISIBLE\)/],
    ['sets a click listener on btnWatch', /btnWatch\.setOnClickListener/],
    ['defines openWatchTogether()', /void\s+openWatchTogether\s*\(/],
    ['launches WatchTogetherActivity', /new\s+Intent\(\s*this\s*,\s*WatchTogetherActivity\.class\s*\)/],
    ['imports WatchTogetherActivity', /import\s+com\.duoshield\.app\.call\.watch\.WatchTogetherActivity;/],
  ];
  let ok = true;
  for (const [label, re] of requirements) {
    if (!re.test(callActivity)) { fail(`CallActivity.java does not ${label}`); ok = false; }
  }
  if (ok) pass('CallActivity binds, reveals, and launches Watch Together');

  // Extras passed by CallActivity must match constants declared on the Activity.
  const passedExtras = callActivity.match(/WatchTogetherActivity\.(EXTRA_[A-Z_]+)/g) || [];
  const uniqueExtras = [...new Set(passedExtras.map((s) => s.split('.')[1]))];
  for (const extra of uniqueExtras) {
    const re = new RegExp(`String\\s+${extra}\\s*=`);
    if (!re.test(watchActivity)) {
      fail(`CallActivity passes WatchTogetherActivity.${extra} but the Activity does not declare it`);
    }
  }
  if (uniqueExtras.length && uniqueExtras.every((e) => new RegExp(`String\\s+${e}\\s*=`).test(watchActivity))) {
    pass(`WatchTogetherActivity declares all extras CallActivity passes (${uniqueExtras.join(', ')})`);
  }
}

// 3. Manifest declares the Activity as a non-exported component.
if (manifest) {
  const activityBlock =
    (manifest.match(/<activity[^>]*\.call\.watch\.WatchTogetherActivity[\s\S]*?\/>/) || [])[0];
  if (!activityBlock) {
    fail('AndroidManifest.xml does not declare .call.watch.WatchTogetherActivity');
  } else if (!/android:exported="false"/.test(activityBlock)) {
    fail('WatchTogetherActivity manifest entry is not exported="false"');
  } else {
    pass('AndroidManifest.xml declares WatchTogetherActivity (exported=false)');
  }
}

// 4. The Watch Together screen layout exists.
if (!fs.existsSync(path.join(ROOT, watchLayout))) {
  fail(`${watchLayout} not found`);
} else {
  pass('activity_watch_together.xml exists');
}

// ── Safety-invariant checks ──────────────────────────────────────────────────
// These guard the three rules the design leans on. They cannot be proven without a
// device, but a regression that deletes them can be caught statically here.

const repository = sources['WatchTogetherRepository.java'] || '';

// 5. Exactly-one-listener lifecycle in WatchTogetherActivity: attach in onStart,
//    remove in BOTH onStop and onDestroy, guarded so it is never double-attached.
if (watchActivity) {
  const listenerReqs = [
    ['attaches the state listener', /stateListener\s*=\s*repo\.listenToState/],
    ['guards against double-attach', /stateListener\s*==\s*null/],
    ['removes the listener (>=2 sites: onStop + onDestroy)',
      (s) => (s.match(/stateListener\.remove\(\)/g) || []).length >= 2],
  ];
  let ok = true;
  for (const [label, matcher] of listenerReqs) {
    const hit = typeof matcher === 'function' ? matcher(watchActivity) : matcher.test(watchActivity);
    if (!hit) { fail(`WatchTogetherActivity does not ${label}`); ok = false; }
  }
  if (ok) pass('WatchTogetherActivity keeps exactly one snapshot listener (attach + double remove)');
}

// 6. Single-writer heartbeat: only the last actor heartbeats, so the two devices
//    never both write, and it uses the dedicated heartbeat action.
if (watchActivity) {
  const hbOk =
    /maybeWriteHeartbeat\s*\(/.test(watchActivity) &&
    /myUid\.equals\(\s*appliedState\.lastActionBy\s*\)/.test(watchActivity) &&
    /ACTION_HEARTBEAT/.test(watchActivity);
  if (hbOk) {
    pass('WatchTogetherActivity heartbeat is single-writer (last-actor guard + ACTION_HEARTBEAT)');
  } else {
    fail('WatchTogetherActivity heartbeat is missing its single-writer guard or ACTION_HEARTBEAT');
  }
}

// 7. Repository gates every Firestore op through FirebaseCostGuard.
if (repository) {
  const guarded =
    /guard\.canWrite/.test(repository) &&
    /guard\.recordWrites/.test(repository) &&
    /guard\.canRead/.test(repository) &&
    /guard\.recordReads/.test(repository);
  if (guarded) pass('WatchTogetherRepository gates reads and writes through FirebaseCostGuard');
  else fail('WatchTogetherRepository is missing a FirebaseCostGuard gate on a read or write path');
}

// 8. CallActivity awareness is a ONE-SHOT read, not a second always-on listener,
//    and never writes state (writes belong to WatchTogetherActivity only).
if (callActivity) {
  const awarenessReqs = [
    ['defines refreshWatchTogetherAwareness()', /void\s+refreshWatchTogetherAwareness\s*\(/],
    ['uses a one-shot fetchState', /\.fetchState\(/],
  ];
  let ok = true;
  for (const [label, re] of awarenessReqs) {
    if (!re.test(callActivity)) { fail(`CallActivity does not ${label}`); ok = false; }
  }
  if (/\.listenToState\(/.test(callActivity)) {
    fail('CallActivity attaches a Watch Together listenToState — awareness must be one-shot fetchState only');
    ok = false;
  }
  if (/repo\w*\.writeState\(|WatchTogetherRepository[\s\S]{0,80}\.writeState\(/.test(callActivity)) {
    fail('CallActivity writes Watch Together state — writes must stay in WatchTogetherActivity');
    ok = false;
  }
  if (ok) pass('CallActivity awareness is one-shot fetchState (no extra listener, no writes)');
}

// ── Result ───────────────────────────────────────────────────────────────────

console.log('');
if (failures > 0) {
  console.log(`${failures} check(s) FAILED`);
  process.exit(1);
}
console.log('All Watch Together static checks passed.');
console.log('NOTE: run ./gradlew :app:testDebugUnitTest :app:assembleDebug for authoritative validation.');
