"use strict";
(function () {
  const query = new URLSearchParams(window.location.search);
  const message = document.getElementById("message");
  if (query.has("error")) message.textContent = "Hmm, that didn't work. Try Face ID again?";
  if (query.has("signed-out")) message.textContent = "You're signed out. See you soon!";
  if (query.has("device-unverified")) {
    message.textContent = "I can't tell which device this is. Open Casita through Tailscale on this phone and try again; your invite still works.";
  }
  if (query.has("device-already-set-up")) {
    message.textContent = "This device is already set up. Sign in with Face ID; your invite still works for another device.";
  }
  if (query.has("device-not-available")) {
    message.textContent = "This device belongs to someone else in Casita. Ask for it to be released first; your invite still works.";
  }
  // Spring Security's passkey script: asks Face ID, then signs in and comes back to the app.
  window.setupLogin(csrfHeaders(), "", document.getElementById("signin"));
})();
