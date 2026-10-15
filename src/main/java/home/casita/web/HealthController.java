package home.casita.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness check for systemd and monitoring. Reveals nothing about the door. */
@RestController
class HealthController {

    @GetMapping("/healthz")
    String health() {
        return "ok";
    }
}
