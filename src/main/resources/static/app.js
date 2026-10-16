"use strict";
(function () {
  const $ = (id) => document.getElementById(id);
  const HOLD_MS = 900;            // press and hold to move the door (design doc, section 3)
  const POLL_MS = 5000;
  const FAST_POLL_MS = 1000;      // just after a command, while the door may be moving
  const MODES = {
    simulated: "Practice garage: this is a pretend door, nothing real moves.",
    off: "Door control is off. I can show you the doors, but I won't move them.",
    "dry-run": "Dry run: I check everything, but nothing is sent to the door.",
    live: "",
  };

  let doors = null;
  let fastUntil = 0;
  let replyAt = 0;
  let timer = null;

  /* ---------- words ---------- */

  function stateWords(door) {
    switch (door) {
      case "CLOSED": return ["Closed", "closed"];
      case "NOT_CLOSED": return ["Not closed", "not-closed"];  // the sensor only knows closed / not closed
      case "OPENING": return ["Opening…", "not-closed"];
      case "CLOSING": return ["Closing…", "not-closed"];
      case "CHECKING": return ["Checking…", "unknown"];
      default: return ["I can't see it", "unknown"];
    }
  }

  function sinceWords(view) {
    if (view.door === "UNKNOWN") return "Nothing will move until I can see it again.";
    if (view.door === "CHECKING") return "Give me a moment to be sure.";
    if (view.door === "NOT_CLOSED") return (view.since ? "Since " + time(view.since) + ". " : "") + "I can't tell how far open.";
    return view.since ? "Since " + time(view.since) : "";
  }

  function time(iso) {
    const d = new Date(iso);
    const today = new Date().toDateString() === d.toDateString();
    return today ? d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })
                 : d.toLocaleDateString([], { weekday: "short", hour: "numeric", minute: "2-digit" });
  }

  function eventWords(e, doorName) {
    const did = e.action === "OPEN" ? "open" : "close";
    switch (e.outcome) {
      case "MOVING": return `${e.who} asked me to ${did} ${doorName}`;
      case "UNCONFIRMED": return `${e.who} asked me to ${did}; I wasn't sure it got through`;
      case "OPEN_INFERRED": return `${doorName} isn't closed any more`;   // the sensor can't confirm "fully open"
      case "CLOSED_CONFIRMED": return `${doorName} closed`;
      case "STUCK": return `${doorName} didn't ${did}`;
      case "LOST": return `I lost sight of ${doorName}`;
      case "HELD": return `I waited for someone to check ${doorName}`;
      case "RESUMED": return `${e.who} checked ${doorName}`;
      case "ALREADY": return `${doorName} was already ${e.action === "OPEN" ? "open" : "closed"}`;
      case "BUSY": return `${e.who} asked while ${doorName} was busy`;
      case "DISABLED": return "Closing from the app is off";
      case "ERROR": return `Nothing was sent to ${doorName}`;
      default: return `I couldn't see ${doorName}`;
    }
  }

  /* ---------- drawing ---------- */

  function draw() {
    const control = doors.control;
    const main = doors.doors.find((d) => d.controllable);
    const status = main.status;
    const [words, tone] = stateWords(main.door);

    $("mode").textContent = MODES[control] ?? "";
    $("mode").classList.toggle("shown", !!MODES[control]);
    $("door-name").textContent = main.name;
    $("door-state").textContent = words;
    $("door-since").textContent = sinceWords(main);
    $("door").className = "door " + tone;
    $("held").classList.toggle("shown", status.held);
    // The reply to a command is for that moment; clear it once the door has settled.
    const moving = ["OPENING", "CLOSING", "CHECKING"].includes(main.door);
    if (!moving && replyAt && Date.now() - replyAt > 15000) { $("reply").textContent = ""; replyAt = 0; }

    // One action: whichever the door allows right now. Real control stays off unless the server says live.
    const canAct = control === "simulated" || control === "live";
    const act = $("act");
    if (status.held) {
      act.disabled = true; act.dataset.action = ""; $("act-label").textContent = "Waiting for a check";
    } else if (status.canOpen) {
      act.disabled = !canAct; act.dataset.action = "open"; $("act-label").textContent = canAct ? "Hold to open" : "Opening is off";
    } else if (status.canClose) {
      act.disabled = !canAct; act.dataset.action = "close"; $("act-label").textContent = canAct ? "Hold to close" : "Closing is off";
    } else {
      act.disabled = true; act.dataset.action = ""; $("act-label").textContent = main.door === "UNKNOWN" ? "Can't see the door" : "One moment…";
    }

    const watched = $("watched");
    watched.replaceChildren(...doors.doors.filter((d) => !d.controllable).map((d) => {
      const card = document.createElement("section");
      card.className = "door " + stateWords(d.door)[1];
      const name = document.createElement("p"); name.className = "name"; name.textContent = d.name;
      const state = document.createElement("p"); state.className = "state";
      state.textContent = stateWords(d.door)[0];
      const ro = document.createElement("span"); ro.className = "ro"; ro.textContent = "Watching only";
      const left = document.createElement("div"); left.append(name, state);
      card.append(left, ro);
      return card;
    }));
  }

  async function refresh() {
    try {
      doors = await api("GET", "/api/doors");
      draw();
      const events = await api("GET", "/api/activity");
      const name = doors.doors.find((d) => d.controllable).name;
      if (events.length === 0) {
        const li = document.createElement("li");
        li.textContent = "Nothing yet. I'm keeping an eye on things.";
        $("activity").replaceChildren(li);
      } else $("activity").replaceChildren(...events.slice(0, 4).map((e) => {
        const li = document.createElement("li");
        const text = document.createElement("span"); text.textContent = eventWords(e, name);
        const when = document.createElement("time"); when.dateTime = e.at; when.textContent = time(e.at);
        li.append(text, when);
        return li;
      }));
    } catch (err) {
      $("door-state").textContent = "I can't see it";
      $("door").className = "door unknown";
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
  function startHold(event) {
    const act = $("act");
    if (act.disabled || !act.dataset.action) return;
    event.preventDefault();
    act.classList.add("holding");
    holdTimer = setTimeout(async () => {
      act.classList.remove("holding");
      const action = act.dataset.action;
      act.disabled = true;
      $("reply").textContent = "";
      try {
        const result = await api("POST", "/api/" + action);
        $("reply").textContent = result.message;
        replyAt = Date.now();
      } catch (err) {
        $("reply").textContent = "I couldn't reach the server. Nothing was sent.";
        replyAt = Date.now();
      }
      fastUntil = Date.now() + 30000;
      refresh();
    }, HOLD_MS);
  }
  function cancelHold() {
    clearTimeout(holdTimer);
    $("act").classList.remove("holding");
  }

  const act = $("act");
  act.addEventListener("pointerdown", startHold);
  ["pointerup", "pointerleave", "pointercancel"].forEach((e) => act.addEventListener(e, cancelHold));
  act.addEventListener("contextmenu", (e) => e.preventDefault());

  $("resume").addEventListener("click", async () => {
    if (!window.confirm("Have you checked the garage door yourself, and is it safe to move it again?")) return;
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

  api("GET", "/api/me").then((me) => $("setup").classList.toggle("shown", me.passkeys === 0)).catch(() => {});
  refresh();
})();
