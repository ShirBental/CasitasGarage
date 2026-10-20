"use strict";
// When the home screen asks the server for the doors' status. Pure scheduling, no DOM and no fetch of its own:
// app.js gives it a load (the requests), an apply (draw what came back) and a fail (draw "can't reach home"),
// and tells it when the page is shown, hidden or touched. The JS tests (src/test/js) drive it with a fake clock.
//
//  - One request at a time. Asking again while one is out queues exactly one more, right after it.
//  - Hidden (or put away): no polling at all. Shown again: refresh straight away.
//  - Nobody has touched the page for a while (idle): one poll a minute instead of every 5 s; the next touch
//    refreshes straight away.
//  - While a door is moving, or just after a command: every second (still one at a time, still only shown).
//  - A failed or late poll is a failure, never a status: the page says it can't reach home, and retries back
//    off exponentially with jitter (up to a minute), back to normal on the first success. A reply that comes in
//    after its time-out is thrown away, so an old answer can never overwrite a newer one.
const CasitaPoller = (function () {
  const DEFAULTS = {
    pollMs: 5000,            // settled, someone using the page
    fastMs: 1000,            // a door moving, a check pending, or just after a command
    idlePollMs: 60000,       // nobody has touched the page for idleAfterMs
    idleAfterMs: 3 * 60000,
    timeoutMs: 10000,        // a poll that takes longer than this has failed
    backoffBaseMs: 2000,     // first retry after a failure, doubling...
    backoffMaxMs: 60000,     // ...up to this
  };

  /**
   * @param o.load   (signal) => Promise of the data; signal (may be undefined) aborts it on time-out
   * @param o.apply  (data) => true when everything is settled (no door moving, no check pending)
   * @param o.fail   (error) => void
   * Optional, for tests: now, setTimeout, clearTimeout, random, abortController (a factory), and DEFAULTS' keys.
   */
  function create(o) {
    const c = Object.assign({}, DEFAULTS, o);
    const now = c.now || (() => Date.now());
    const setT = c.setTimeout || ((f, ms) => setTimeout(f, ms));
    const clearT = c.clearTimeout || ((t) => clearTimeout(t));
    const random = c.random || Math.random;
    const abortable = c.abortController
        || (() => (typeof AbortController === "function" ? new AbortController() : null));

    let hidden = false;
    let inFlight = null;          // the promise of the poll that's out
    let again = null;             // a second poll asked for while one was out: { promise, resolve }
    let timer = null;
    let settled = true;
    let failures = 0;
    let fastUntil = 0;
    let lastActivity = now();
    let polls = 0;                // generation: only the newest poll may draw

    const idle = () => now() - lastActivity >= c.idleAfterMs;

    /** How long until the next poll; null for none. */
    function nextDelay() {
      if (hidden) return null;
      if (failures > 0) {
        const cap = Math.min(c.backoffMaxMs, c.backoffBaseMs * Math.pow(2, failures - 1));
        return Math.round(cap / 2 + random() * (cap / 2));     // jitter: half to all of it
      }
      if (!idle() && (!settled || now() < fastUntil)) return c.fastMs;
      if (!settled) return c.pollMs;   // a door moving while nobody's looking: still watched, not every second
      return idle() ? c.idlePollMs : c.pollMs;
    }

    function schedule() {
      clearT(timer);
      timer = null;
      const delay = nextDelay();
      if (delay !== null) timer = setT(() => { timer = null; poll(); }, delay);
    }

    function runOnce() {
      const mine = ++polls;
      const controller = abortable();
      let timeout;
      const late = new Promise((_, reject) => {
        timeout = setT(() => {
          if (controller) controller.abort();
          reject(new Error("timed out"));
        }, c.timeoutMs);
      });
      return Promise.race([Promise.resolve().then(() => c.load(controller ? controller.signal : undefined)), late])
          .then((data) => {
            if (mine !== polls) return;
            failures = 0;
            settled = c.apply(data) !== false;
          }, (err) => {
            if (mine !== polls) return;
            failures++;
            settled = false;
            c.fail(err);
          })
          .then(() => clearT(timeout));
    }

    /** Polls now, or once more right after the one that's out. Resolves when that poll is drawn. */
    function poll() {
      if (inFlight) {
        if (!again) {
          let resolve;
          const promise = new Promise((r) => { resolve = r; });
          again = { promise, resolve };
        }
        return again.promise;
      }
      clearT(timer);
      timer = null;
      inFlight = runOnce().then(() => {
        inFlight = null;
        if (again) {
          const next = again;
          again = null;
          poll().then(next.resolve);
        } else {
          schedule();
        }
      });
      return inFlight;
    }

    return {
      /** Ask for the status now: on load, after a command, after "I've checked the door". */
      refresh() {
        lastActivity = Math.max(lastActivity, now());
        return poll();
      },
      /** Poll every second for a while: just after a command. */
      fastFor(ms) {
        fastUntil = now() + ms;
        if (!inFlight) schedule();
      },
      /** Someone touched the page. After an idle spell (or while hidden polling was off) that refreshes now. */
      activity() {
        const wasIdle = idle();
        lastActivity = now();
        if (hidden) return;
        if (wasIdle) poll();
      },
      /** visibilitychange to hidden, or pagehide: stop. */
      hide() {
        hidden = true;
        clearT(timer);
        timer = null;
      },
      /** visibilitychange to visible, or pageshow: refresh straight away, then the usual rhythm. */
      show() {
        hidden = false;
        lastActivity = now();
        return poll();
      },
      /** For tests and debugging. */
      state() {
        return { hidden, inFlight: !!inFlight, queued: !!again, scheduled: timer !== null, failures, idle: idle(), settled };
      },
    };
  }

  return { create, DEFAULTS };
})();
