// Casita's words, in one place. app.js decides what to show; this file only says it. The family's own
// words and pictures come from the server: casita.brand, /api/brand.
//
// Measured versus estimated: the sensor measures only "closed" or "not closed". How far a door is up,
// or where it is while moving, is never claimed; travel progress is labelled as an estimate.
"use strict";

const VOICE = (function () {
  let brand = { household: "", companion: "Watson", aboutLine: "", illustration: "" };

  function time(iso) {
    const d = new Date(iso);
    const today = new Date().toDateString() === d.toDateString();
    return today ? d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })
                 : d.toLocaleDateString([], { weekday: "short", hour: "numeric", minute: "2-digit" });
  }

  const name = (who) => (who ? who.charAt(0).toUpperCase() + who.slice(1) : "Someone");

  return {
    setBrand(b) { brand = { ...brand, ...b }; },
    brand() { return brand; },
    time,
    name,

    household() { return brand.household ? `For the ${brand.household}.` : ""; },
    about() { return brand.aboutLine; },
    signedIn(me) { return "Signed in as " + name(me.name) + (me.device ? " on " + me.device : ""); },
    noHistory() { return "No history yet"; },

    modes: {
      simulated: "Simulation mode",
      off: "Door control off",
      "dry-run": "Rehearsal: nothing is sent",
      live: "",
    },

    /** [big word, tone] for the controlled door. */
    state(view, held) {
      if (held) return ["Needs a check", "alert"];
      switch (view.door) {
        case "CLOSED": return ["Closed", "closed"];
        case "NOT_CLOSED": return ["Not closed", "not-closed"];
        case "OPENING": return ["Opening…", "moving"];
        case "CLOSING": return ["Closing…", "moving"];
        case "CHECKING": return ["Checking…", "quiet"];
        default: return ["Unknown", "quiet"];
      }
    },

    /** One short line under the big word. Moving doors get an estimate instead (see secondsLeft). */
    detail(view, held) {
      if (held) return "The last move didn't finish. Look at the door first.";
      switch (view.door) {
        case "CLOSED":
        case "NOT_CLOSED": return view.since ? "Since " + time(view.since) : "";
        case "CHECKING": return "Waiting for the sensor";
        case "UNKNOWN": return `The sensor can't confirm where ${view.name} is.`;
        default: return "";
      }
    },

    /** Under "Opening…": an estimate from the configured travel time, never a sensor reading. */
    secondsLeft(s) {
      if (s <= 0) return "Checking with the sensor";
      return s === 1 ? "About 1 second" : `About ${s} seconds`;
    },

    watched(door) {
      switch (door) {
        case "CLOSED": return "Closed";
        case "NOT_CLOSED": return "Not closed";
        default: return "Unknown";
      }
    },

    offline: ["ET phone home", "Can't reach home. Trying again…"],

    /** Each door's round control: a short verb inside, the full action for screen readers. */
    control: {
      open: "Open", close: "Close", busy: "…",
      label: (verb, door) => `${verb} ${door}`,
    },
    held: "I've checked the door",
    offlineButton: "Waiting for connection",

    /** Why a door's control is unavailable, when it isn't obvious from the door's state. */
    notSetUp(door) { return `${door} control not set up yet`; },
    controlOff(door) { return `${door} control is off`; },
    closeOff() { return "Closing from the app is off"; },

    /** After a command: only what needs saying. Success waits for the sensor check. */
    unconfirmed() { return "No confirmation from the door. Checking the sensor."; },
    noAnswer() { return "No answer. Checking the sensor before anything else is sent."; },
    checked(e) {
      switch (e.outcome) {
        case "CLOSED_CONFIRMED": return "✓ Sensor confirmed closed";
        case "OPEN_INFERRED": return "✓ Sensor confirmed not closed";
        case "STUCK": return e.action === "OPEN" ? "It didn't open. Please look at the door." : "It didn't close. Please look at the door.";
        default: return "Lost sight of the door. Please look at it.";
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
