// Shared by every Casita page. No inline scripts anywhere: the content security policy forbids them.
"use strict";

/** The CSRF header Spring Security expects, from the XSRF-TOKEN cookie it sets (single-page-app mode). */
function csrfHeaders() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? { "X-XSRF-TOKEN": decodeURIComponent(match[1]) } : {};
}

/** Calls Casita's API. Not signed in any more: back to the sign-in page. signal: an optional AbortSignal. */
async function api(method, path, signal) {
  const response = await fetch(path, {
    method,
    headers: { Accept: "application/json", ...(method === "GET" ? {} : csrfHeaders()) },
    credentials: "same-origin",
    signal,
  });
  if (response.status === 401) {
    window.location.href = "/login";
    throw new Error("signed out");
  }
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}
