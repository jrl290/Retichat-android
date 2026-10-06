/**
 * How much of the oracle, Retichat-js/lib/photo_metadata.js, the vectors reach.
 * From the Android repo's root, with the web checkout beside it:
 *
 *   node --test --experimental-test-coverage \
 *        --test-coverage-include='../Retichat-js/lib/photo_metadata.js' \
 *        --test-coverage-lines=99 --test-coverage-branches=99 --test-coverage-functions=100 \
 *        scripts/photo_location_coverage.test.mjs
 *
 * builds every vector (the oracle runs on each), runs what only its own types
 * show, and reports line, branch (V8's block ranges) and function coverage of
 * the oracle; it fails below the thresholds. Nothing is written. The vectors
 * reach all of it but three block ranges: 99.88 % of the lines (the one line
 * missed is the `return null` after four passes) and 99.35 % of the branches.
 * No input can reach those three:
 *
 *   - applyEdits: `throw new Error("edits overlap or overrun")` and
 *     `throw new Error("a replacement changes the length")`. The edits each
 *     format makes come in order, inside the file, and every replacement is as
 *     long as what it replaces, by construction. They are the guard for a
 *     mistake in a later change, which withoutLocation then turns into null.
 *   - tiffWithoutGps: the `return null` after four passes. A pass gives a block
 *     only when reading it again (`check`) finds nothing of DROPPED_TAGS, and
 *     the next pass reads those very bytes: from the second pass on nothing is
 *     dropped. A pass that only zeroes bytes no IFD references changes nothing
 *     the layout reads, so the next finds the same layout and settles, and a
 *     thumbnail once cleaned is clean (JPEG cleaning is idempotent: a search
 *     over half a million JPEGs changed at random found none cleaned
 *     differently the second time).
 *     At most three passes change a block (anything, a thumbnail, bytes no IFD
 *     references left by the thumbnail), the fourth never does. What can show
 *     only after the first pass (an IFD whose count lives in a word that moves,
 *     "GPS that shows once IFD0 is cleaned" among the vectors) is caught by that
 *     `check` instead, and the block goes whole.
 *
 * The `catch` of withoutLocation and its `instanceof` check are reached by
 * oracleOnlyChecks (a Uint8Array whose indexOf throws, and values that are no
 * Uint8Array): no bytes make withoutLocation fail, the oracle gives null for
 * none of the vectors.
 */
import assert from "node:assert/strict";
import test from "node:test";
import { buildVectors, checkVectors, oracleOnlyChecks, toJson } from "./photo_location_vectors.mjs";

test("the oracle on every vector, and on what only its own types show", () => {
    oracleOnlyChecks();
    const vectors = buildVectors();
    const summary = checkVectors(vectors);
    assert.ok(summary.count > 1000, `${summary.count} vectors`);
    assert.equal(toJson(vectors), toJson(buildVectors()), "the vectors are the same every time");
});
