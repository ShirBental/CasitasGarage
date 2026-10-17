"use strict";
(function () {
  // Spring Security's passkey script creates the passkey (Face ID) and saves its public key.
  window.setupRegistration(csrfHeaders(), "", {
    getRegisterButton: () => document.getElementById("register"),
    getLabelInput: () => document.getElementById("label"),
    getSuccess: () => document.getElementById("success"),
    getError: () => document.getElementById("error"),
    getDeleteForms: () => [],
  });
})();
