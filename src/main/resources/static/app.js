"use strict";
// What the home screen shows and does. Every word comes from voice.js; every rule about the door lives
// in GarageService on the server: this page only offers what /api/doors says is allowed right now.
(function () {
  const $ = (id) => document.getElementById(id);
  const HOLD_MS = 900;            // press and hold to move the door (design doc, section 3)
  const POLL_MS = 5000;
  const FAST_POLL_MS = 1000;      // just after a command, while the door may be moving

  let doors = null;
  let fastUntil = 0;
  let replyAt = 0;
  let timer = null;

  /* ---------- drawing ---------- */

  function draw() {
    const control = doors.control;
    const main = doors.doors.find((d) => d.controllable);
    const status = main.status;
    const [words, tone] = VOICE.state(main.door);

    $("mode").textContent = VOICE.modes[control] ?? "";
    $("mode").classList.toggle("shown", !!VOICE.modes[control]);
    $("tucked").textContent = doors.doors.every((d) => d.door === "CLOSED") ? VOICE.allClosed() : "";
    $("door-name").textContent = main.name;
    $("door-state").textContent = words;
    $("door-since").textContent = VOICE.since(main);
    $("door").className = "door main " + tone;
    $("held").classList.toggle("shown", status.held);
    // The reply to a command is for that moment; clear it once the door has settled.
    const moving = ["OPENING", "CLOSING", "CHECKING"].includes(main.door);
    if (!moving && replyAt && Date.now() - replyAt > 15000) { $("reply").textContent = ""; replyAt = 0; }

    // One action: whichever the door allows right now. Real control stays off unless the server says live.
    const canAct = control === "simulated" || control === "live";
    const say = VOICE.action;
    if (status.held) {
      setAct(false, "", say.held);
    } else if (status.canOpen) {
      setAct(canAct, "open", canAct ? say.open : say.openOff);
    } else if (status.canClose) {
      setAct(canAct, "close", canAct ? say.close : say.closeOff);
    } else {
      setAct(false, "", main.door === "UNKNOWN" ? say.cantSee : say.wait);
    }

    $("watched").replaceChildren(...doors.doors.filter((d) => !d.controllable).map((d) => {
      const [stateText, stateTone] = VOICE.state(d.door);
      const card = document.createElement("section");
      card.className = "door small " + stateTone;
      const name = document.createElement("p"); name.className = "name"; name.textContent = d.name;
      const state = document.createElement("p"); state.className = "state"; state.textContent = stateText;
      const ro = document.createElement("span"); ro.className = "ro"; ro.textContent = "Watching only";
      const left = document.createElement("div"); left.append(name, state);
      card.append(left, ro);
      return card;
    }));
  }

  function setAct(enabled, action, label) {
    const act = $("act");
    if (act.classList.contains("holding")) return;   // never change the button under a finger
    act.disabled = !enabled;
    act.dataset.action = enabled ? action : "";
    $("act-label").textContent = label;
  }

  function drawActivity(events) {
    const name = doors.doors.find((d) => d.controllable).name;
    if (events.length === 0) {
      const li = document.createElement("li");
      li.textContent = VOICE.quiet();
      $("activity").replaceChildren(li);
      return;
    }
    $("activity").replaceChildren(...events.slice(0, 4).map((e) => {
      const li = document.createElement("li");
      const text = document.createElement("span"); text.textContent = VOICE.event(e, name);
      const when = document.createElement("time"); when.dateTime = e.at; when.textContent = VOICE.time(e.at);
      li.append(text, when);
      return li;
    }));
  }

  async function refresh() {
    try {
      doors = await api("GET", "/api/doors");
      draw();
      drawActivity(await api("GET", "/api/activity"));
    } catch (err) {
      // Can't reach Casita: say so, and offer nothing to press.
      $("door-state").textContent = VOICE.state("UNKNOWN")[0];
      $("door-since").textContent = VOICE.unreachable();
      $("door").className = "door main unknown";
      $("tucked").textContent = "";
      setAct(false, "", VOICE.action.cantSee);
    }
    schedule();
  }

  function schedule() {
    clearTimeout(timer);
    if (document.hidden) return;
    timer = setTimeout(refresh, Date.now() < fastUntil ? FAST_POLL_MS : POLL_MS);
  }

  /* ---------- press and hold ---------- */

  let holdTimer = null;
  let restLabel = "";
  function startHold(event) {
    const act = $("act");
    if (act.disabled || !act.dataset.action) return;
    event.preventDefault();
    act.classList.add("holding");
    restLabel = $("act-label").textContent;
    $("act-label").textContent = VOICE.action.holding;
    holdTimer = setTimeout(async () => {
      act.classList.remove("holding");
      const action = act.dataset.action;
      setAct(false, "", VOICE.action.wait);   // one command per hold; the next refresh decides what's allowed
      $("reply").textContent = "";
      try {
        const result = await api("POST", "/api/" + action);
        $("reply").textContent = result.message;
      } catch (err) {
        $("reply").textContent = VOICE.noReply();
      }
      replyAt = Date.now();
      fastUntil = Date.now() + 30000;
      refresh();
    }, HOLD_MS);
  }
  function cancelHold() {
    const act = $("act");
    if (!act.classList.contains("holding")) return;
    clearTimeout(holdTimer);
    act.classList.remove("holding");
    $("act-label").textContent = restLabel;
  }

  const act = $("act");
  act.addEventListener("pointerdown", startHold);
  ["pointerup", "pointerleave", "pointercancel"].forEach((e) => act.addEventListener(e, cancelHold));
  act.addEventListener("contextmenu", (e) => e.preventDefault());

  $("resume").addEventListener("click", async () => {
    if (!window.confirm(VOICE.confirmChecked())) return;
    try {
      await api("POST", "/api/resume");
    } catch (err) { /* refresh shows the state */ }
    refresh();
  });

  $("signout").addEventListener("click", async () => {
    await fetch("/logout", { method: "POST", headers: csrfHeaders(), credentials: "same-origin" });
    window.location.href = "/login?signed-out";
  });

  document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });

  function greet() {
    $("greeting").textContent = VOICE.greeting();
    $("about").textContent = VOICE.about();
  }

  greet();
  api("GET", "/api/brand").then((b) => { VOICE.setBrand(b); greet(); if (doors) refresh(); }).catch(() => {});
  api("GET", "/api/me").then((me) => $("setup").classList.toggle("shown", me.passkeys === 0)).catch(() => {});
  refresh();
})();
