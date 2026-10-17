// Casita's words, in one place. app.js decides what to show; this file only says it. The family's own
// words (what Casita calls them, the dog's name) come from the server: casita.brand, /api/brand.
// Personality never bends the facts: "not closed" is never "open", and an unsure move says so.
"use strict";

const VOICE = (function () {
  let brand = { household: "", companion: "Watson", aboutLine: "" };

  function time(iso) {
    const d = new Date(iso);
    const today = new Date().toDateString() === d.toDateString();
    return today ? d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })
                 : d.toLocaleDateString([], { weekday: "short", hour: "numeric", minute: "2-digit" });
  }

  return {
    setBrand(b) { brand = { ...brand, ...b }; },
    time,

    greeting() { return brand.household ? `Welcome home, ${brand.household}.` : "Welcome home."; },
    /** Under the greeting, only when every door the app shows is closed. */
    allClosed() { return "All tucked in."; },
    about() { return brand.aboutLine; },
    quiet() { return `Nothing yet. ${brand.companion}'s keeping an eye on things.`; },

    modes: {
      simulated: "Practice garage: a pretend door. Nothing real moves.",
      off: "I can show you the doors, but moving them is switched off for now.",
      "dry-run": "Rehearsal: I check everything, but I don't send anything to the door.",
      live: "",
    },

    /** [words, tone] for a door state. The sensor only knows closed and not closed. */
    state(door) {
      switch (door) {
        case "CLOSED": return ["Closed", "closed"];
        case "NOT_CLOSED": return ["Not closed", "not-closed"];
        case "OPENING": return ["Opening…", "moving"];
        case "CLOSING": return ["Closing…", "moving"];
        case "CHECKING": return ["Checking…", "unknown"];
        default: return ["I can't see it", "unknown"];
      }
    },

    since(view) {
      if (view.door === "UNKNOWN") return "Nothing will move until I can see it again.";
      if (view.door === "CHECKING") return `I'm checking on ${view.name}. Give me a moment to be sure.`;
      if (view.door === "NOT_CLOSED") return (view.since ? "Since " + time(view.since) + ". " : "") + "I can't tell how far open.";
      return view.since ? "Since " + time(view.since) : "";
    },

    unreachable() { return "I can't reach home right now. Nothing will move until I can."; },

    action: {
      open: "Hold to open", close: "Hold to close", holding: "Keep holding…",
      openOff: "Opening is off", closeOff: "Closing is off",
      held: "Waiting for a check", cantSee: "Can't see the door", wait: "One moment…",
    },

    noReply() { return "I couldn't reach home. Nothing was sent."; },
    confirmChecked() { return "Have you checked the garage door yourself, and is it safe to move it again?"; },

    /** One line of history, in Casita's voice. */
    event(e, doorName) {
      e = { ...e, who: e.who ? e.who.charAt(0).toUpperCase() + e.who.slice(1) : "Someone" };
      const did = e.action === "OPEN" ? "open" : "close";
      switch (e.outcome) {
        case "MOVING": return `${e.who} asked me to ${did} ${doorName}`;
        case "UNCONFIRMED": return `${e.who} asked me to ${did} ${doorName}; I wasn't sure it got through`;
        case "OPEN_INFERRED": return `${doorName} isn't closed anymore`;   // not closed, never more: the sensor can't tell how far
        case "CLOSED_CONFIRMED": return `I closed ${doorName}`;
        case "STUCK": return `${doorName} didn't ${did}`;
        case "LOST": return `I lost sight of ${doorName}`;
        case "HELD": return `I waited for someone to check ${doorName}`;
        case "RESUMED": return `${e.who} checked on ${doorName}`;
        case "ALREADY": return e.action === "OPEN" ? `${doorName} was already not closed` : `${doorName} was already closed`;
        case "BUSY": return `${e.who} asked while ${doorName} was busy`;
        case "DISABLED": return "Closing from the app is off";
        case "ERROR": return `Nothing was sent to ${doorName}`;
        default: return `I couldn't see ${doorName}`;
      }
    },
  };
})();
