/**
 * DuoShield — App Check static-guard suite (Suite C, S08-H5 item 4d)
 *
 * Neither Suite A nor Suite B catches the most likely future regression: a new
 * `allow` clause added to a gated path WITHOUT `appCheckVerified()`. Suite A
 * forces the gate open, and Suite B only checks the specific clauses it happens
 * to exercise. This suite parses firestore.rules and asserts a structural
 * invariant: every non-`false` `allow` inside a gated match block carries the
 * gate. It needs no emulator.
 *
 * "Gated" paths (S08-H5 item 4a):
 *   /recovery/{uid}
 *   /backups/{userId}                 (+ /messages, /contacts, /groups subcols)
 *   /duressEligibility/{accountId}
 *   /accountLock/{accountId}
 *   /users/{uid}/devices/{deviceId}
 *
 * `allow …: if false;` clauses are intentionally OUT OF SCOPE — a total deny is
 * strictly stronger than an App Check gate, so requiring the gate on top of it
 * would be dead code (see the firestore.rules header comment).
 */

const { readFileSync } = require('fs');
const { resolve } = require('path');

const RULES_PATH = resolve(__dirname, '../firestore.rules');

/** match-path segments whose own block is gated. */
const GATED_SELF = new Set([
  '/recovery/{uid}',
  '/backups/{userId}',
  '/duressEligibility/{accountId}',
  '/accountLock/{accountId}',
  '/devices/{deviceId}',
]);

/** Any block whose ancestry contains this path is gated (backups subcollections). */
const GATED_ANCESTOR = '/backups/{userId}';

/**
 * Parses firestore.rules into a flat list of allow clauses, each tagged with the
 * match-path stack it belongs to. Handles multi-line allow statements (the
 * accountLock create clause spans several lines) by accumulating until `;`.
 *
 * Brace tracking is GENERIC: every `{` pushes a frame and every `}` pops one, so
 * non-match blocks (the `service` block, `function appCheckVerified() { … }`,
 * etc.) are balanced correctly and cannot corrupt the match-path stack. A frame
 * is labelled with its match path when it was opened by a `match X {` line and
 * is null otherwise; the gating stack is the labelled frames in order.
 */
function parseAllowClauses(text) {
  const lines = text.split('\n');
  const frames = []; // each entry: match-path string, or null for a plain block
  const clauses = [];

  let buffer = null; // accumulating a multi-line allow

  const matchPathStack = () => frames.filter((f) => f !== null);

  for (let i = 0; i < lines.length; i++) {
    const line = stripComment(lines[i]).trim();
    if (line === '') continue;

    if (buffer !== null) {
      buffer.text += ' ' + line;
      if (line.includes(';')) {
        clauses.push(buffer);
        buffer = null;
      }
      continue;
    }

    // allow lines carry no braces in this file, so capture scope before any
    // brace bookkeeping and move on.
    if (line.startsWith('allow ')) {
      const clause = { text: line, stack: matchPathStack() };
      if (line.includes(';')) clauses.push(clause);
      else buffer = clause;
      continue;
    }

    const matchDecl = line.match(/^match\s+(\S+)\s*\{/);
    if (matchDecl) {
      frames.push(matchDecl[1]); // opening brace of the match block
      // Any additional braces on the same line (none in this file) are handled
      // by the generic pass below.
      const extraOpens = countChar(line, '{') - 1;
      const closes = countChar(line, '}');
      applyBraces(frames, extraOpens, closes);
      continue;
    }

    applyBraces(frames, countChar(line, '{'), countChar(line, '}'));
  }

  return clauses;
}

/** Push `opens` null frames, then pop `closes` frames. */
function applyBraces(frames, opens, closes) {
  for (let n = 0; n < opens; n++) frames.push(null);
  for (let n = 0; n < closes; n++) frames.pop();
}

function stripComment(line) {
  const idx = line.indexOf('//');
  return idx === -1 ? line : line.slice(0, idx);
}

function countChar(s, c) {
  let n = 0;
  for (const ch of s) if (ch === c) n++;
  return n;
}

function isGated(stack) {
  const self = stack[stack.length - 1];
  if (GATED_SELF.has(self)) return true;
  return stack.includes(GATED_ANCESTOR);
}

/** True when the clause is `allow …: if false;` (a total deny). */
function isTotalDeny(clauseText) {
  return /:\s*if\s+false\s*;/.test(clauseText);
}

describe('App Check static guard (Suite C)', () => {
  const text = readFileSync(RULES_PATH, 'utf8');
  const clauses = parseAllowClauses(text);
  const gated = clauses.filter((c) => isGated(c.stack));

  test('firestore.rules parsed at least the expected gated clauses', () => {
    // Sanity: if the parser silently matched nothing, the assertions below would
    // vacuously pass. Recovery alone has 2 allows; backups has 12; there are
    // more. Require a floor so a broken parser fails loudly.
    expect(gated.length).toBeGreaterThanOrEqual(20);
  });

  test('every non-false allow on a gated path carries appCheckVerified()', () => {
    const offenders = gated
      .filter((c) => !isTotalDeny(c.text))
      .filter((c) => !c.text.includes('appCheckVerified()'))
      .map((c) => `  [${c.stack.join(' > ')}]  ${c.text}`);

    expect(offenders).toEqual([]);
  });

  test('appCheckVerified() helper is defined exactly once', () => {
    const defs = text.match(/function\s+appCheckVerified\s*\(/g) || [];
    expect(defs.length).toBe(1);
  });
});
