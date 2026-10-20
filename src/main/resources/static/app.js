"use strict";
// What the home screen shows and does: two doors, each with its own status and one round control. Every
// word comes from voice.js; every rule about a door lives on the server (GarageService), which refuses a
// second command while one is unchecked (BUSY). A door's control is enabled only when /api/doors says that
// door can be commanded right now. A door the server doesn't control stays disabled, with the reason.
(function () {
  const $ = (id) => document.getElementById(id);
  const FAST_AFTER_COMMAND_MS = 30000;   // poll every second this long after a command (poller.js)
  const COMMAND_TIMEOUT_MS = 10000;
  const CHECKS = ["CLOSED_CONFIRMED", "OPEN_INFERRED", "STUCK", "LOST"];
  const MOVING = ["OPENING", "CLOSING", "CHECKING"];

  let doors = null;               // /api/doors
  let events = [];                // /api/activity, newest first
  let offline = false;
  const local = {};               // per door: { sending, watchAfter, resultAt }

  /* ---------- reading /api/doors (works with the one-controlled-door API and the per-door one) ---------- */

  const keyOf = (d) => d.key || d.name.toLowerCase();
  const controlOf = (d) => d.control || doors.control;
  const travelOf = (d) => d.travelTimeSeconds || doors.travelTimeSeconds;
  const first = () => doors.doors[0];
  const stateOf = (k) => (local[k] ||= { sending: false, watchAfter: null, resultAt: 0 });

  /** Where a command for this door goes: per-door endpoints when the server has them, else Ford's. */
  function commandPath(d, action) {
    return d.key ? `/api/doors/${encodeURIComponent(d.key)}/${action}` : `/api/${action}`;
  }

  /** Activity for one door. Entries without a door are from the one-controlled-door API: the first door's. */
  const eventsFor = (d) => events.filter((e) => (e.door ? e.door === keyOf(d) || e.door === d.name : d === first()));

  /* ---------- drawing ---------- */

  function draw() {
    if (offline || !doors) {
      // ET phone home: no stale status, no history, nothing to press.
      $("doors").hidden = true;
      $("companion").classList.remove("is-excited");
      $("spare-above").hidden = $("spare-below").hidden = true;
      $("history-open").hidden = true;
      $("offline").hidden = false;
      $("offline-word").textContent = VOICE.offline[0];
      $("offline-detail").textContent = VOICE.offline[1];
      $("offline-button").textContent = VOICE.offlineButton;
      return;
    }
    $("doors").hidden = false;
    $("spare-above").hidden = $("spare-below").hidden = false;
    $("history-open").hidden = false;
    $("offline").hidden = true;

    const mode = VOICE.modes[doors.control] ?? "";
    $("mode").textContent = mode;
    $("mode").classList.toggle("shown", !!mode);

    [0, 1].forEach((i) => {
      const section = $("door-" + i);
      const d = doors.doors[i];
      section.hidden = !d;
      if (d) drawDoor(section, d);
    });

    // Watson gets excited while any door is opening or closing, as the server reports it: never from a tap,
    // a pending request or a guess. He is idle again once movement ends, fails or held (STUCK, LOST: the
    // server then reports a settled state with held), or becomes unknown (CHECKING, UNKNOWN, offline). His
    // expression is decoration, never evidence of what a door is doing.
    const moving = (d) => (d.door === "OPENING" || d.door === "CLOSING") && !(d.status && d.status.held);
    $("companion").classList.toggle("is-excited", doors.doors.some(moving));
  }

  function drawDoor(section, d) {
    if (!section.firstElementChild) section.append($("door-template").content.cloneNode(true));
    const q = (sel) => section.querySelector(sel);
    const k = keyOf(d);
    const me = stateOf(k);
    const status = d.status;                       // null: the server doesn't control this door
    const held = !!(status && status.held);
    const [word, tone] = VOICE.state(d, held);

    section.className = "door " + tone + (d.controllable ? "" : " unavailable");
    section.dataset.key = k;
    q(".name").textContent = d.name;
    q(".word").textContent = word;
    q(".detail").textContent = VOICE.detail(d, held);

    // Moving: an estimate from the configured travel time, labelled so. Never a sensor reading.
    const moving = (d.door === "OPENING" || d.door === "CLOSING") && d.since && !held;
    q(".estimate").classList.toggle("shown", !!moving);
    if (moving) {
      const travel = travelOf(d);
      const elapsed = (Date.now() - new Date(d.since).getTime()) / 1000;
      q(".fill").style.width = Math.max(0, Math.min(100, Math.round((elapsed / travel) * 100))) + "%";
      q(".detail").textContent = VOICE.secondsLeft(Math.ceil(travel - elapsed));
    }

    // The round control: the door's one action, or disabled with the reason.
    const control = controlOf(d);
    const canAct = d.controllable && (control === "simulated" || control === "live" || d.live === true);
    const go = q(".go");
    let verb = "", action = "", note = "";
    if (!d.controllable) {
      verb = VOICE.control.open;
      note = VOICE.notSetUp(d.name);
    } else if (me.sending || MOVING.includes(d.door)) {
      verb = VOICE.control.busy;
    } else if (held) {
      verb = VOICE.control.open;
    } else if (status.canOpen) {
      verb = VOICE.control.open; action = canAct ? "open" : "";
      if (!canAct) note = VOICE.controlOff(d.name);
    } else if (status.canClose) {
      verb = VOICE.control.close; action = canAct ? "close" : "";
      if (!canAct) note = VOICE.controlOff(d.name);
    } else {
      verb = d.door === "NOT_CLOSED" ? VOICE.control.close : VOICE.control.open;
    }
    go.textContent = verb;
    go.disabled = !action;
    go.dataset.action = action;
    go.setAttribute("aria-label", VOICE.control.label(verb === VOICE.control.busy ? word : verb, d.name));
    q(".note").textContent = note;

    // After a stuck or lost movement: a person checks the door before it moves again.
    const checked = q(".checked");
    checked.hidden = !held;
    checked.textContent = VOICE.held;
  }

  function drawHistory() {
    const nameOf = (e) => {
      const d = e.door ? doors.doors.find((x) => keyOf(x) === e.door || x.name === e.door) : first();
      return d ? d.name : e.door;
    };
    const latest = events[0];
    $("last-event").textContent = latest ? VOICE.event(latest, nameOf(latest)) : VOICE.noHistory();
    $("last-when").textContent = latest ? VOICE.time(latest.at) : "";
    $("activity").replaceChildren(...events.map((e) => {
      const li = document.createElement("li");
      const text = document.createElement("span"); text.textContent = VOICE.event(e, nameOf(e));
      const when = document.createElement("time"); when.dateTime = e.at; when.textContent = VOICE.time(e.at);
      li.append(text, when);
      return li;
    }));
  }

  /** Once a command is out, say how it ended only when that door's sensor check is in the history. */
  function reportChecks() {
    for (const d of doors.doors) {
      const me = stateOf(keyOf(d));
      if (!me.watchAfter) continue;
      const after = new Date(me.watchAfter).getTime();
      const check = eventsFor(d).find((e) => CHECKS.includes(e.outcome) && new Date(e.at).getTime() > after);
      if (check) {
        showResult(d, VOICE.checked(check), check.outcome === "STUCK" || check.outcome === "LOST");
        me.watchAfter = null;
      }
    }
  }

  function showResult(d, text, alert) {
    const section = document.querySelector(`.door[data-key="${CSS.escape(keyOf(d))}"]`);
    if (!section) return;
    const result = section.querySelector(".result");
    result.textContent = text;
    result.classList.toggle("alert", !!alert);
    stateOf(keyOf(d)).resultAt = text ? Date.now() : 0;
  }

  /* ---------- talking to the server ---------- */

  // When to ask: poller.js (one request at a time; nothing while hidden; slower when nobody's using the page;
  // backoff after failures). What to do with the answer: here.
  const poller = CasitaPoller.create({
    async load(signal) {
      const d = await api("GET", "/api/doors", signal);
      const e = await api("GET", "/api/activity", signal);
      return { doors: d, events: e };
    },
    apply(data) {
      doors = data.doors;
      events = data.events;
      offline = false;
      draw();
      drawHistory();
      reportChecks();
      let settled = true;
      for (const d of doors.doors) {
        const me = stateOf(keyOf(d));
        const doorSettled = !me.watchAfter && !MOVING.includes(d.door);
        if (doorSettled && me.resultAt && Date.now() - me.resultAt > 20000) showResult(d, "");
        settled = settled && doorSettled;
      }
      return settled;
    },
    fail() {
      // ET phone home: a failed or late poll never shows a door's state (draw() hides the doors).
      offline = true;
      draw();
    },
  });
  const refresh = () => poller.refresh();

  /** One tap, one command, for one door. Its control is disabled before the request leaves. */
  async function command(d, action) {
    const me = stateOf(keyOf(d));
    me.sending = true;
    showResult(d, "");
    const mine = eventsFor(d);
    me.watchAfter = mine[0] ? mine[0].at : new Date(0).toISOString();
    draw();
    const abort = new AbortController();
    const timeout = setTimeout(() => abort.abort(), COMMAND_TIMEOUT_MS);
    try {
      const response = await fetch(commandPath(d, action), {
        method: "POST",
        headers: { Accept: "application/json", ...csrfHeaders() },
        credentials: "same-origin",
        signal: abort.signal,
      });
      if (response.status === 401) { window.location.href = "/login"; return; }
      if (!response.ok) throw new Error("HTTP " + response.status);
      const result = await response.json();
      if (result.outcome === "UNCONFIRMED") showResult(d, VOICE.unconfirmed(), true);
      else if (result.outcome !== "MOVING") { showResult(d, result.message); me.watchAfter = null; }   // refused: nothing was sent
    } catch (err) {
      // Timed out or lost: the command may or may not have arrived. Keep watching for a check.
      showResult(d, VOICE.noAnswer(), true);
    } finally {
      clearTimeout(timeout);
      me.sending = false;
      poller.fastFor(FAST_AFTER_COMMAND_MS);
      refresh();
    }
  }

  /** Which door a tap inside a door section is for. */
  function doorFor(el) {
    const section = el.closest(".door");
    return section && doors ? doors.doors.find((d) => keyOf(d) === section.dataset.key) : null;
  }

  $("doors").addEventListener("click", async (e) => {
    const go = e.target.closest(".go");
    const checked = e.target.closest(".checked");
    const d = doorFor(e.target);
    if (!d) return;
    if (go && !go.disabled && go.dataset.action && !stateOf(keyOf(d)).sending) {
      command(d, go.dataset.action);
    } else if (checked) {
      if (!window.confirm(VOICE.confirmChecked())) return;
      try { await api("POST", commandPath(d, "resume")); } catch (err) { /* refresh shows the state */ }
      refresh();
    }
  });

  /* ---------- sheets ---------- */

  document.querySelectorAll("dialog").forEach((dialog) => {
    dialog.addEventListener("click", (e) => { if (e.target === dialog || e.target.hasAttribute("data-close")) dialog.close(); });
  });
  $("history-open").addEventListener("click", () => $("history").showModal());
  $("account-open").addEventListener("click", () => $("account").showModal());

  $("signout").addEventListener("click", async () => {
    await fetch("/logout", { method: "POST", headers: csrfHeaders(), credentials: "same-origin" });
    window.location.href = "/login?signed-out";
  });

  // Polling only while the page is shown; straight back to the real status when it is shown again.
  document.addEventListener("visibilitychange", () => (document.hidden ? poller.hide() : poller.show()));
  window.addEventListener("pagehide", () => poller.hide());
  window.addEventListener("pageshow", (e) => { if (e.persisted && !document.hidden) poller.show(); });
  // Someone using the page keeps the 5 s rhythm; after a few idle minutes it slows, and a touch refreshes.
  for (const type of ["pointerdown", "keydown", "touchstart", "wheel", "scroll"]) {
    document.addEventListener(type, () => poller.activity(), { capture: true, passive: true });
  }

  function drawBrand() {
    $("household").textContent = VOICE.household();
    $("about").textContent = VOICE.about();
    // The companion's approved illustration, once casita.brand.illustration names it. No stand-in before that.
    const brand = VOICE.brand();
    if (brand.appName) {
      $("app-name").textContent = brand.appName;
      document.title = brand.appName;
    }
    if (brand.illustration) {
      $("companion-art").src = brand.illustration;
      $("companion-art").alt = brand.companion;
      $("companion").hidden = false;
      if (brand.illustrationExcited) {
        $("companion-excited").src = brand.illustrationExcited;
        $("companion-excited").hidden = false;
      }
    }
  }

  drawBrand();
  api("GET", "/api/brand").then((b) => { VOICE.setBrand(b); drawBrand(); }).catch(() => {});
  api("GET", "/api/me").then((me) => { $("signed-in").textContent = VOICE.signedIn(me); }).catch(() => {});
  if (document.hidden) poller.hide();
  refresh();
})();
