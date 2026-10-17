// Casita's words, in one place. app.js decides what to show; this file only says it. The family's own
// words (what Casita calls them, the dog's name) come from the server: casita.brand, /api/brand.
//
// Measured versus inferred: the sensor measures only "closed" or "not closed". Anything else (how far
// a door is, where it is while moving) is an estimate, and the words say so.
"use strict";

const VOICE = (function () {
  let brand = { household: "", companion: "Watson", aboutLine: "" };

  function time(iso) {
    const d = new Date(iso);
    const today = new Date().toDateString() === d.toDateString();
    return today ? d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })
                 : d.toLocaleDateString([], { weekday: "short", hour: "numeric", minute: "2-digit" });
  }

  const name = (who) => (who ? who.charAt(0).toUpperCase() + who.slice(1) : "Someone");

  return {
    setBrand(b) { brand = { ...brand, ...b }; },
    time,
    name,

    household() { return brand.household ? `For the ${brand.household}.` : ""; },
    about() { return brand.aboutLine; },
    noHistory() { return "No history yet"; },

    modes: {
      simulated: "Practice door",
      off: "Door control off",
      "dry-run": "Rehearsal: nothing is sent",
      live: "",
    },

    /** [big word, tone] for the controlled door. The sensor can't measure how far a door is up. */
    state(view, held) {
      if (held) return ["Needs a check", "alert"];
      switch (view.door) {
        case "CLOSED": return ["Closed", "closed"];
        case "NOT_CLOSED": return ["Not closed", "not-closed"];
        case "OPENING": return ["Opening…", "moving"];
        case "CLOSING": return ["Closing…", "moving"];
        case "CHECKING": return ["Checking…", "quiet"];
        default: return ["Can't see", "quiet"];
      }
    },

    /** The sentence under the big word: what the sensor actually reads, and since when. */
    detail(view, held) {
      const since = view.since ? " since " + time(view.since) : "";
      if (held) return "The last move didn't finish as expected. Look at the door before it moves again.";
      switch (view.door) {
        case "CLOSED": return `Sensor reads closed${since}.`;
        case "NOT_CLOSED": return `Sensor reads not closed${since}. It can't tell how far up.`;
        case "OPENING": return "Command acknowledged. Waiting for the sensor check before calling it done.";
        case "CLOSING": return "Command acknowledged. Sensor still reads not closed.";
        case "CHECKING": return "Not sure whether it's moving. Nothing else is sent until the sensor is checked.";
        default: return "The sensor isn't answering. Nothing will be sent until it does.";
      }
    },

    watched(door) {
      switch (door) {
        case "CLOSED": return "Closed";
        case "NOT_CLOSED": return "Not closed";
        default: return "Can't see";
      }
    },

    estimate(seconds) { return `Estimate from the usual ${seconds}-second travel time, not a sensor reading`; },

    unreachable() { return ["Can't reach home", "Showing nothing rather than an old reading. Trying again…"]; },

    button: {
      open: (door) => `Open ${door}`, close: (door) => `Close ${door}`, sending: "Sending…",
      held: "I've checked the door", checking: "Checking…",
      cantSee: "Unavailable for now", offline: "Waiting for connection", wait: "One moment…",
      openOff: "Opening is switched off", closeOff: "Closing is switched off",
    },

    /** What a command's answer means. Facts only: success waits for the sensor. */
    sent(door) { return `Sent. ${door} acknowledged; waiting for the sensor.`; },
    unconfirmed(door) { return `${door} didn't confirm it got that. Checking the sensor before anything else is sent.`; },
    noAnswer() { return "No answer from home. The command may or may not have arrived, so nothing else is sent until the door is checked."; },
    checked(e, door) {
      switch (e.outcome) {
        case "CLOSED_CONFIRMED": return "✓ Checked: the sensor confirms it's closed.";
        case "OPEN_INFERRED": return "✓ Checked: it left the closed position.";
        case "STUCK": return `${door} didn't ${e.action === "OPEN" ? "open" : "close"}. Please look at it.`;
        default: return `Lost sight of ${door}. Please look at it.`;
      }
    },
    confirmChecked() { return "Have you looked at the garage door, and is it safe to move again?"; },

    /** One line of history. */
    event(e, door) {
      const who = name(e.who);
      const did = e.action === "OPEN" ? "open" : "close";
      switch (e.outcome) {
        case "MOVING": return `${who} asked to ${did} ${door}`;
        case "UNCONFIRMED": return `${who} asked to ${did} ${door}, unconfirmed`;
        case "OPEN_INFERRED": return `Sensor: ${door} not closed`;
        case "CLOSED_CONFIRMED": return `Sensor: ${door} closed`;
        case "STUCK": return `${door} didn't ${did}`;
        case "LOST": return `Lost sight of ${door}`;
        case "HELD": return `${who}'s ${did} held for a check`;
        case "RESUMED": return `${who} checked on ${door}`;
        case "ALREADY": return e.action === "OPEN" ? `${door} was already not closed` : `${door} was already closed`;
        case "BUSY": return `${who} asked while ${door} was busy`;
        case "DISABLED": return "Closing from the app is off";
        case "ERROR": return `Nothing was sent to ${door}`;
        default: return `Couldn't see ${door}`;
      }
    },
  };
})();
