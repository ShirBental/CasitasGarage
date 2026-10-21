"use strict";
// Tests for static/poller.js, run by home.casita.web.PollerScriptTest under GraalJS (no Node on the build
// machine). Each test gets a fake clock and a fake server; nothing here waits for real time.

const TESTS = {};
const test = (name, fn) => { TESTS[name] = fn; };

async function flush() {
  for (let i = 0; i < 50; i++) await Promise.resolve();
}

function fakeClock() {
  let t = 1000000, seq = 0;
  const timers = new Map();
  return {
    now: () => t,
    setTimeout: (f, ms) => { const id = ++seq; timers.set(id, { at: t + ms, f }); return id; },
    clearTimeout: (id) => { timers.delete(id); },
    async advance(ms) {
      const end = t + ms;
      for (;;) {
        await flush();
        let next = null;
        for (const [id, x] of timers) if (x.at <= end && (!next || x.at < next[1].at)) next = [id, x];
        if (!next) break;
        timers.delete(next[0]);
        t = next[1].at;
        next[1].f();
      }
      t = end;
      await flush();
    },
  };
}

/**
 * A fake server. By default every load answers at once with "settled". respond(...) changes that; hold()
 * makes loads wait until release()/reject() so the test can see what happens while one is out.
 */
function harness(options) {
  const clock = fakeClock();
  const h = {
    clock, loads: 0, inFlight: 0, maxInFlight: 0, applied: 0, failed: 0, aborted: 0,
    settled: true, failing: false, held: [], holding: false, random: 0.5,
  };
  h.poller = CasitaPoller.create(Object.assign({
    now: clock.now, setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout,
    random: () => h.random,
    abortController: () => { const c = { signal: { aborted: false }, abort() { c.signal.aborted = true; h.aborted++; } }; return c; },
    load() {
      h.loads++;
      h.inFlight++;
      h.maxInFlight = Math.max(h.maxInFlight, h.inFlight);
      const done = (v) => { h.inFlight--; return v; };
      if (h.holding) {
        return new Promise((resolve, reject) => h.held.push({
          resolve: (v) => resolve(done(v)), reject: (e) => { done(); reject(e); } }));
      }
      if (h.failing) { done(); return Promise.reject(new Error("HTTP 502")); }
      return Promise.resolve(done({ settled: h.settled }));
    },
    apply(data) { h.applied++; return data.settled; },
    fail() { h.failed++; },
  }, options));
  h.release = () => { const p = h.held.shift(); p.resolve({ settled: h.settled }); return flush(); };
  /** Loads during the next ms of fake time. */
  h.loadsDuring = async (ms) => { const before = h.loads; await clock.advance(ms); return h.loads - before; };
  return h;
}

function eq(actual, expected, what) {
  if (actual !== expected) throw new Error(`${what}: expected ${expected}, got ${actual}`);
}
function between(actual, lo, hi, what) {
  if (actual < lo || actual > hi) throw new Error(`${what}: expected ${lo}..${hi}, got ${actual}`);
}

/* ---------- the normal rhythm ---------- */

test("polls every 5 s while the page is shown and in use", async () => {
  const h = harness();
  await h.poller.refresh();
  eq(h.loads, 1, "first load");
  for (let i = 0; i < 6; i++) {
    h.poller.activity();
    eq(await h.loadsDuring(5000), 1, "loads in 5 s, round " + i);
  }
});

test("polls every second while a door is moving, then back to 5 s", async () => {
  const h = harness();
  h.settled = false;
  await h.poller.refresh();
  eq(await h.loadsDuring(3000), 3, "loads in 3 s while moving");
  h.settled = true;
  await h.clock.advance(1000);            // this poll sees it settled
  eq(await h.loadsDuring(4999), 0, "nothing for just under 5 s once settled");
  eq(await h.loadsDuring(1), 1, "then the 5 s poll");
});

test("polls every second for a while after a command, then back to 5 s", async () => {
  const h = harness();
  await h.poller.refresh();
  h.poller.fastFor(30000);
  eq(await h.loadsDuring(30000), 30, "loads in the 30 s after a command");
  eq(await h.loadsDuring(10000), 2, "loads in the next 10 s");
});

/* ---------- one request at a time ---------- */

test("never has two requests out, and collapses triggers during one into a single follow-up", async () => {
  const h = harness();
  h.holding = true;
  const first = h.poller.refresh();
  await flush();
  eq(h.loads, 1, "loads after the first trigger");
  const a = h.poller.refresh();
  const b = h.poller.show();
  h.poller.fastFor(30000);
  const c = h.poller.refresh();
  await h.clock.advance(5000);           // a timer firing mid-request must not start another either
  eq(h.inFlight, 1, "in flight while held");
  eq(h.poller.state().queued, true, "one follow-up queued");
  await h.release();                      // the first answers; exactly one follow-up starts
  eq(h.loads, 2, "loads after the first answers");
  eq(h.inFlight, 1, "in flight: the follow-up only");
  await h.release();
  await Promise.all([first, a, b, c]);
  eq(h.loads, 2, "loads in total");
  eq(h.maxInFlight, 1, "most requests out at once");
  eq(h.applied, 2, "answers drawn");
});

test("a refresh while idle and nothing is out starts exactly one request", async () => {
  const h = harness();
  await Promise.all([h.poller.refresh(), h.poller.refresh(), h.poller.refresh()]);
  eq(h.loads, 2, "one request, plus one follow-up for the triggers that came while it was out");
  eq(h.maxInFlight, 1, "most requests out at once");
});

/* ---------- hidden and shown ---------- */

test("stops polling while hidden", async () => {
  const h = harness();
  await h.poller.refresh();
  h.poller.hide();
  eq(h.poller.state().scheduled, false, "a poll still scheduled after hide");
  eq(await h.loadsDuring(30 * 60000), 0, "loads in 30 hidden minutes");
});

test("stops polling while hidden even with a door moving or right after a command", async () => {
  const h = harness();
  h.settled = false;
  await h.poller.refresh();
  h.poller.fastFor(30000);
  h.poller.hide();
  eq(await h.loadsDuring(60000), 0, "loads in a hidden minute");
});

test("a request that answers after the page was hidden schedules nothing", async () => {
  const h = harness();
  h.holding = true;
  h.poller.refresh();
  await flush();
  h.poller.hide();
  await h.release();
  eq(h.applied, 1, "the answer is still drawn");
  eq(h.poller.state().scheduled, false, "a poll scheduled while hidden");
  h.holding = false;
  eq(await h.loadsDuring(10 * 60000), 0, "loads in 10 hidden minutes");
});

test("refreshes straight away when shown again, then the usual rhythm", async () => {
  const h = harness();
  await h.poller.refresh();
  h.poller.hide();
  await h.clock.advance(10 * 60000);
  h.poller.show();
  await flush();
  eq(h.loads, 2, "loads right after show, with no time passing");
  eq(await h.loadsDuring(5000), 1, "then every 5 s");
});

test("activity while hidden doesn't poll", async () => {
  const h = harness();
  await h.poller.refresh();
  h.poller.hide();
  await h.clock.advance(10 * 60000);
  h.poller.activity();
  await flush();
  eq(h.loads, 1, "loads");
});

/* ---------- idle ---------- */

test("slows to once a minute after three idle minutes", async () => {
  const h = harness();
  await h.poller.refresh();
  eq(await h.loadsDuring(3 * 60000), 36, "loads in the first three minutes");
  eq(h.poller.state().idle, true, "idle after three untouched minutes");
  eq(await h.loadsDuring(10 * 60000), 10, "loads in the next ten idle minutes");
});

test("the next touch after an idle spell refreshes straight away and restores the 5 s rhythm", async () => {
  const h = harness();
  await h.poller.refresh();
  await h.clock.advance(3 * 60000 + 20000);   // idle, between two minute-polls
  const before = h.loads;
  h.poller.activity();
  await flush();
  eq(h.loads, before + 1, "loads right after the touch");
  eq(await h.loadsDuring(5000), 1, "then every 5 s");
});

test("touches while in use don't add requests", async () => {
  const h = harness();
  await h.poller.refresh();
  for (let i = 0; i < 20; i++) { h.poller.activity(); await h.clock.advance(250); }
  eq(h.loads, 2, "loads over 5 s of tapping");
});

test("a door moving while nobody is using the page is watched every 5 s, not every second", async () => {
  const h = harness();
  await h.poller.refresh();
  await h.clock.advance(3 * 60000);
  h.settled = false;                          // someone else opens it
  await h.clock.advance(60000);               // the next idle poll sees it moving
  eq(await h.loadsDuring(10000), 2, "loads in 10 s while it moves");
});

/* ---------- failures ---------- */

test("a failure is shown as a failure, never drawn as a status", async () => {
  const h = harness();
  h.failing = true;
  await h.poller.refresh();
  eq(h.failed, 1, "failures shown");
  eq(h.applied, 0, "answers drawn");
});

test("retries back off exponentially to a minute, and the first success resets the rhythm", async () => {
  const h = harness();
  h.failing = true;
  h.random = 0.999999;                     // the top of each jitter range
  await h.poller.refresh();
  const gaps = [];
  for (let i = 0; i < 8; i++) {
    let waited = 0;
    const before = h.loads;
    while (h.loads === before) { await h.clock.advance(100); waited += 100; }
    gaps.push(waited);
  }
  eq(gaps.join(","), "2000,4000,8000,16000,32000,60000,60000,60000", "gaps between retries");
  h.failing = false;
  h.poller.activity();                     // four minutes of retries: idle, so a touch retries now
  await flush();
  eq(h.poller.state().failures, 0, "failures after a success");
  eq(await h.loadsDuring(5000), 1, "back to every 5 s");
});

test("retries are jittered between half and all of the backoff", async () => {
  // random() picks where in [half, all] of the backoff a retry lands: 2 s, then 4 s, for the first two.
  for (const [random, first, second] of [[0, 1000, 2000], [0.5, 1500, 3000]]) {
    const h = harness();
    h.random = random;
    h.failing = true;
    await h.poller.refresh();                // failure 1
    await h.clock.advance(first);            // failure 2
    eq(h.loads, 2, "retried after " + first + " ms (random " + random + ")");
    await h.clock.advance(second - 1);
    eq(h.loads, 2, "no retry before " + second + " ms (random " + random + ")");
    await h.clock.advance(1);
    eq(h.loads, 3, "a retry at " + second + " ms (random " + random + ")");
  }
});

test("hidden during backoff: no retries until shown, then one straight away", async () => {
  const h = harness();
  h.failing = true;
  await h.poller.refresh();
  h.poller.hide();
  eq(await h.loadsDuring(30 * 60000), 0, "retries while hidden");
  h.failing = false;
  h.poller.show();
  await flush();
  eq(h.applied, 1, "the status drawn right after show");
});

test("a request that takes too long is aborted and shown as a failure; its late answer is thrown away", async () => {
  const h = harness();
  h.holding = true;
  h.poller.refresh();
  await h.clock.advance(9999);
  eq(h.failed, 0, "failures before the time-out");
  await h.clock.advance(1);
  eq(h.failed, 1, "failures at the time-out");
  eq(h.aborted, 1, "requests aborted");
  await h.release();                        // the old answer finally arrives
  eq(h.applied, 0, "late answers drawn");
});

test("a success after failures is drawn and polling carries on", async () => {
  const h = harness();
  h.failing = true;
  await h.poller.refresh();
  h.failing = false;
  await h.clock.advance(2000);
  eq(h.applied, 1, "answers drawn");
  eq(await h.loadsDuring(5000), 1, "every 5 s again");
});

/* ---------- runner ---------- */

const RESULTS = {};
function runTest(name) {
  RESULTS[name] = "running";
  Promise.resolve().then(() => TESTS[name]()).then(
      () => { RESULTS[name] = "passed"; },
      (e) => { RESULTS[name] = "failed: " + (e && e.stack ? e.stack : e); });
}
