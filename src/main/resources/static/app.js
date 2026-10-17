"use strict";
// What the home screen shows and does. Every word comes from voice.js; every rule about the door lives
// in GarageService on the server, which refuses a second command while one is unchecked (BUSY). This
// page only offers what /api/doors says is allowed right now, and one tap sends one command.
(function () {
  const $ = (id) => document.getElementById(id);
  const POLL_MS = 5000;
  const FAST_POLL_MS = 1000;      // while a command is out, or the door is moving
  const COMMAND_TIMEOUT_MS = 10000;
  const CHECKS = ["CLOSED_CONFIRMED", "OPEN_INFERRED", "STUCK", "LOST"];

  let doors = null;
  let events = [];
  let offline = false;
  let sending = false;
  let watchAfter = null;          // a command is out: report the first sensor check after this time
  let resultAt = 0;
  let fastUntil = 0;
  let timer = null;

  const main = () => doors.doors.find((d) => d.controllable);

  /* ---------- drawing ---------- */

  function draw() {
    if (offline || !doors) {
      const [word, detail] = VOICE.unreachable();
      $("door-state").textContent = word;
      $("door-detail").textContent = detail;
      $("door").className = "ford quiet";
      $("estimate").classList.remove("shown");
      $("watched").replaceChildren();
      $("history-open").hidden = true;   // no old readings while home can't be reached
      setAct(false, "", VOICE.button.offline);
      return;
    }
    const ford = main();
    const status = ford.status;
    $("history-open").hidden = false;
    const [word, tone] = VOICE.state(ford, status.held);

    $("mode").textContent = VOICE.modes[doors.control] ?? "";
    $("mode").classList.toggle("shown", !!VOICE.modes[doors.control]);
    $("door-name").textContent = ford.name;
    $("door-state").textContent = word;
    $("door-detail").textContent = VOICE.detail(ford, status.held);
    $("door").className = "ford " + tone;
    drawEstimate(ford);

    // Toyota and any other watched door: one quiet, read-only line. Never a button.
    $("watched").replaceChildren(...doors.doors.filter((d) => !d.controllable).map((d) => {
      const row = document.createElement("div");
      row.className = "row";
      const name = document.createElement("span"); name.textContent = d.name;
      const state = document.createElement("span"); state.className = "when";
      state.textContent = VOICE.watched(d.door) + " · view only";
      row.append(name, state);
      return row;
    }));

    // The one action. Real control stays off unless the server says live.
    const canAct = doors.control === "simulated" || doors.control === "live";
    const b = VOICE.button;
    if (sending) setAct(false, "", b.sending);
    else if (status.held) setAct(true, "resume", b.held);
    else if (status.canOpen) setAct(canAct, "open", canAct ? b.open(ford.name) : b.openOff);
    else if (status.canClose) setAct(canAct, "close", canAct ? b.close(ford.name) : b.closeOff);
    else if (ford.door === "OPENING" || ford.door === "CLOSING") setAct(false, "", word);
    else if (ford.door === "CHECKING") setAct(false, "", b.checking);
    else if (ford.door === "UNKNOWN") setAct(false, "", b.cantSee);
    else setAct(false, "", b.wait);
  }

  /** Travel progress is only an estimate from the configured travel time: the sensor can't measure it. */
  function drawEstimate(ford) {
    const moving = (ford.door === "OPENING" || ford.door === "CLOSING") && ford.since && !ford.status.held;
    $("estimate").classList.toggle("shown", !!moving);
    if (!moving) return;
    const elapsed = (Date.now() - new Date(ford.since).getTime()) / 1000;
    const pct = Math.max(0, Math.min(100, Math.round((elapsed / doors.travelTimeSeconds) * 100)));
    $("estimate-fill").style.width = pct + "%";
    $("estimate-label").textContent = VOICE.estimate(doors.travelTimeSeconds);
  }

  function setAct(enabled, action, label) {
    const act = $("act");
    act.disabled = !enabled;
    act.dataset.action = enabled ? action : "";
    act.className = "act" + (action === "resume" ? " secondary" : "");
    act.textContent = label;
  }

  function drawHistory() {
    const door = doors ? main().name : "Ford";
    const latest = events[0];
    $("last-event").textContent = latest ? VOICE.event(latest, door) : VOICE.noHistory();
    $("last-when").textContent = latest ? VOICE.time(latest.at) : "";
    $("activity").replaceChildren(...events.map((e) => {
      const li = document.createElement("li");
      const text = document.createElement("span"); text.textContent = VOICE.event(e, door);
      const when = document.createElement("time"); when.dateTime = e.at; when.textContent = VOICE.time(e.at);
      li.append(text, when);
      return li;
    }));
  }

  /** Once a command is out, say how it ended only when the server's sensor check is in the history. */
  function reportCheck() {
    if (!watchAfter) return;
    const after = new Date(watchAfter).getTime();
    const check = events.find((e) => CHECKS.includes(e.outcome) && new Date(e.at).getTime() > after);
    if (check) {
      showResult(VOICE.checked(check, main().name), check.outcome === "STUCK" || check.outcome === "LOST");
      watchAfter = null;
    }
  }

  function showResult(text, alert) {
    $("result").textContent = text;
    $("result").classList.toggle("alert", !!alert);
    resultAt = Date.now();
  }

  /* ---------- talking to the server ---------- */

  async function refresh() {
    try {
      doors = await api("GET", "/api/doors");
      events = await api("GET", "/api/activity");
      offline = false;
    } catch (err) {
      offline = true;
    }
    draw();
    if (!offline) {
      drawHistory();
      reportCheck();
    }
    const settled = !offline && !watchAfter && !["OPENING", "CLOSING", "CHECKING"].includes(main().door);
    if (settled && resultAt && Date.now() - resultAt > 20000) { $("result").textContent = ""; resultAt = 0; }
    schedule(settled);
  }

  function schedule(settled) {
    clearTimeout(timer);
    if (document.hidden) return;
    timer = setTimeout(refresh, !settled || Date.now() < fastUntil ? FAST_POLL_MS : POLL_MS);
  }

  /** One tap, one command. The button is disabled before the request leaves and until the server answers. */
  async function command(action) {
    sending = true;
    draw();
    $("result").textContent = "";
    watchAfter = events[0] ? events[0].at : new Date(0).toISOString();
    const door = main().name;
    const abort = new AbortController();
    const timeout = setTimeout(() => abort.abort(), COMMAND_TIMEOUT_MS);
    try {
      const response = await fetch("/api/" + action, {
        method: "POST",
        headers: { Accept: "application/json", ...csrfHeaders() },
        credentials: "same-origin",
        signal: abort.signal,
      });
      if (response.status === 401) { window.location.href = "/login"; return; }
      if (!response.ok) throw new Error("HTTP " + response.status);
      const result = await response.json();
      if (result.outcome === "MOVING") showResult(VOICE.sent(door));
      else if (result.outcome === "UNCONFIRMED") showResult(VOICE.unconfirmed(door), true);
      else { showResult(result.message); watchAfter = null; }   // refused: nothing was sent
    } catch (err) {
      // Timed out or lost: the command may or may not have arrived. Keep watching for a check.
      showResult(VOICE.noAnswer(), true);
    } finally {
      clearTimeout(timeout);
      sending = false;
      fastUntil = Date.now() + 30000;
      refresh();
    }
  }

  $("act").addEventListener("click", async () => {
    const action = $("act").dataset.action;
    if (sending || !action) return;
    if (action === "resume") {
      if (!window.confirm(VOICE.confirmChecked())) return;
      try { await api("POST", "/api/resume"); } catch (err) { /* refresh shows the state */ }
      refresh();
      return;
    }
    command(action);
  });

  /* ---------- sheets ---------- */

  document.querySelectorAll("dialog").forEach((d) => {
    d.addEventListener("click", (e) => { if (e.target === d || e.target.hasAttribute("data-close")) d.close(); });
  });
  $("history-open").addEventListener("click", () => $("history").showModal());
  $("account-open").addEventListener("click", () => $("account").showModal());

  $("signout").addEventListener("click", async () => {
    await fetch("/logout", { method: "POST", headers: csrfHeaders(), credentials: "same-origin" });
    window.location.href = "/login?signed-out";
  });

  document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });

  function drawBrand() {
    $("household").textContent = VOICE.household();
    $("about").textContent = VOICE.about();
  }

  drawBrand();
  api("GET", "/api/brand").then((b) => { VOICE.setBrand(b); drawBrand(); }).catch(() => {});
  api("GET", "/api/me").then((me) => {
    $("signed-in").textContent = "Signed in as " + VOICE.name(me.name);
    const needsSetup = me.passkeys === 0;
    $("setup").hidden = !needsSetup;
    $("setup-dot").classList.toggle("shown", needsSetup);
  }).catch(() => {});
  refresh();
})();
