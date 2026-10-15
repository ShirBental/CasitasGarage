package home.casita.garage;

import java.time.Instant;

/** One line of "What I've been up to". {@code who} is a person's name, or "Casita" for its own checks. */
public record ActivityEvent(Instant at, String who, Action action, Outcome outcome) {}
