"use strict";
(function () {
  const query = new URLSearchParams(window.location.search);
  const message = document.getElementById("message");
  if (query.has("error")) message.textContent = "Hmm, that didn't work. Try Face ID again?";
  if (query.has("signed-out")) message.textContent = "You're signed out. See you soon!";
  // Spring Security's passkey script: asks Face ID, then signs in and comes back to the app.
  window.setupLogin(csrfHeaders(), "", document.getElementById("signin"));
})();
