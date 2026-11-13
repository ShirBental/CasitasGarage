package home.casita.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import home.casita.garage.Action;
import home.casita.garage.DoorCommandRejectedException;
import home.casita.garage.DoorControl;
import home.casita.garage.DoorDevice;
import home.casita.garage.DoorException;
import home.casita.garage.DoorRoster;
import home.casita.garage.DoorState;
import home.casita.garage.Garage;
import home.casita.garage.GarageProperties;
import home.casita.garage.InMemoryActivityLog;
import home.casita.garage.Outcome;
import home.casita.garage.SimulatedDoor;
import home.casita.security.store.SecurityStore;
import home.casita.security.store.SecurityStore.ShortcutToken;
import home.casita.security.store.TestClock;

/**
 * What a Shortcut is told, for each way a command can go, with stand-in doors (nothing here can reach Meross). The point:
 * an accepted command is never an open door, and a door that isn't live is never described as the real one moving.
 */
class ShortcutControllerTest {

    private static final Duration TRAVEL = Duration.ofSeconds(10);
    private static final Duration GRACE = Duration.ofSeconds(2);

    private final TestClock clock = new TestClock();
    private final InMemoryActivityLog activity = new InMemoryActivityLog();
    private final List<Boolean> sent = new ArrayList<>();
    private final ShortcutToken token = new ShortcutToken("hash", "st-00000000000a", "shir", "Shir's iPhone", "ford", "OPEN",
            SecurityStore.Status.ACTIVE, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"), null);

    /** A door that reads {@code open} and handles a command as {@code onCommand} says. */
    private DoorDevice door(boolean open, Behaviour onCommand) {
        return new DoorDevice() {
            @Override
            public Reading read() {
                return new Reading(open, clock.instant().minus(Duration.ofHours(1)));
            }

            @Override
            public void command(boolean wantOpen) throws DoorException {
                onCommand.run(wantOpen);
            }
        };
    }

    interface Behaviour {
        void run(boolean wantOpen) throws DoorException;
    }

    private ShortcutController controller(DoorDevice ford, DoorControl control) {
        Garage garage = new Garage(new DoorRoster(List.of(new DoorRoster.Door("Ford", ford, control),
                new DoorRoster.Door("Toyota", new SimulatedDoor("Toyota", clock, TRAVEL), DoorControl.SIMULATED))),
                activity, message -> {}, (when, task) -> {}, clock, new GarageProperties(TRAVEL, GRACE, true));
        return new ShortcutController(garage, new GarageProperties(TRAVEL, GRACE, true));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.20");
        return request;
    }

    private ShortcutController.CommandAnswer open(ShortcutController controller) {
        ResponseEntity<?> response = controller.command("ford", "open", token, request());
        return (ShortcutController.CommandAnswer) response.getBody();
    }

    @Test
    void aLiveAcknowledgedOpenIsAcceptedNotConfirmed() {
        ShortcutController controller = controller(door(false, sent::add), DoorControl.LIVE);
        ResponseEntity<?> response = controller.command("ford", "open", token, request());
        ShortcutController.CommandAnswer answer = (ShortcutController.CommandAnswer) response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(sent).containsExactly(true);
        assertThat(answer.result()).isEqualTo("accepted");
        assertThat(answer.outcome()).isEqualTo(Outcome.MOVING);
        assertThat(answer.live()).isTrue();
        assertThat(answer.control()).isEqualTo("live");
        assertThat(answer.doorState()).isEqualTo(DoorState.OPENING);
        assertThat(answer.confirmed()).isFalse();
        assertThat(answer.checkAfterSeconds()).isEqualTo(12);
        assertThat(answer.speak()).doesNotContain("Practice").contains("isn't confirmed");
        assertThat(activity.recent(1)).singleElement().satisfies(e -> {
            assertThat(e.who()).isEqualTo("shir via Shortcut");
            assertThat(e.action()).isEqualTo(Action.OPEN);
            assertThat(e.outcome()).isEqualTo(Outcome.MOVING);
        });
    }

    @Test
    void aSimulatedOpenSaysItIsThePracticeDoor() {
        ShortcutController controller = controller(new SimulatedDoor("Ford", clock, TRAVEL), DoorControl.SIMULATED);
        ShortcutController.CommandAnswer answer = open(controller);
        assertThat(answer.result()).isEqualTo("accepted");
        assertThat(answer.live()).isFalse();
        assertThat(answer.control()).isEqualTo("simulated");
        assertThat(answer.speak()).startsWith("Practice door, not the real one.");
    }

    @Test
    void aDryRunSendsNothingAndSaysSo() {
        ShortcutController controller = controller(door(false, wantOpen -> {
            throw new DoorCommandRejectedException("Dry run: the open for door channel 1 was checked and built but not sent; nothing moved");
        }), DoorControl.DRY_RUN);
        ResponseEntity<?> response = controller.command("ford", "open", token, request());
        ShortcutController.CommandAnswer answer = (ShortcutController.CommandAnswer) response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(answer.result()).isEqualTo("failed");
        assertThat(answer.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(answer.live()).isFalse();
        assertThat(answer.control()).isEqualTo("dry-run");
        assertThat(answer.doorState()).isEqualTo(DoorState.CLOSED);
        assertThat(answer.speak()).startsWith("Dry run, nothing goes to the real door.");
        assertThat(answer.checkAfterSeconds()).isNull();
    }

    @Test
    void anUnacknowledgedOpenIsUnconfirmed() {
        ShortcutController controller = controller(door(false, wantOpen -> {
            throw new DoorException("no acknowledgement within 8s");
        }), DoorControl.LIVE);
        ResponseEntity<?> response = controller.command("ford", "open", token, request());
        ShortcutController.CommandAnswer answer = (ShortcutController.CommandAnswer) response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(answer.result()).isEqualTo("unconfirmed");
        assertThat(answer.confirmed()).isFalse();
        assertThat(answer.speak()).contains("not sure");
    }

    @Test
    void aDoorThatIsntClosedIsLeftAlone() {
        ShortcutController controller = controller(door(true, sent::add), DoorControl.LIVE);
        controller.status("ford", token, request()); // Casita watches the open door long enough to call it settled
        clock.advance(TRAVEL.plusSeconds(1));
        ResponseEntity<?> response = controller.command("ford", "open", token, request());
        ShortcutController.CommandAnswer answer = (ShortcutController.CommandAnswer) response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(sent).isEmpty();
        assertThat(answer.result()).isEqualTo("already");
        assertThat(answer.doorState()).isEqualTo(DoorState.NOT_CLOSED);
        assertThat(answer.confirmed()).isTrue();
    }

    @Test
    void aDoorSwitchedOffRefuses() {
        ShortcutController controller = controller(door(false, sent::add), DoorControl.OFF);
        ResponseEntity<?> response = controller.command("ford", "open", token, request());
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(((ShortcutController.CommandAnswer) response.getBody()).result()).isEqualTo("refused");
        assertThat(sent).isEmpty();
    }

    @Test
    void aSecondOpenWhileTheFirstIsUncheckedIsRefused() {
        ShortcutController controller = controller(door(false, sent::add), DoorControl.LIVE);
        open(controller);
        ResponseEntity<?> again = controller.command("ford", "open", token, request());
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(((ShortcutController.CommandAnswer) again.getBody()).outcome()).isEqualTo(Outcome.BUSY);
        assertThat(sent).containsExactly(true);
    }

    @Test
    void onlyTheTokensDoorAndAction() {
        ShortcutController controller = controller(door(false, sent::add), DoorControl.LIVE);
        assertThat(controller.command("toyota", "open", token, request()).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.command("garage", "open", token, request()).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.command("ford", "close", token, request()).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.command("ford", "resume", token, request()).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.status("toyota", token, request()).getStatusCode().value()).isEqualTo(403);
        assertThat(sent).isEmpty();
        assertThat(activity.recent(10)).isEmpty();
        assertThat(controller.command("FORD", "OPEN", token, request()).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void theStatusSaysOpenOnlyOnceTheSensorHasSettled() {
        ShortcutController controller = controller(door(false, sent::add), DoorControl.LIVE);
        ShortcutController.StatusAnswer closed = (ShortcutController.StatusAnswer) controller.status("ford", token, request()).getBody();
        assertThat(closed.doorState()).isEqualTo(DoorState.CLOSED);
        assertThat(closed.confirmed()).isTrue();
        assertThat(closed.speak()).isEqualTo("Ford is closed.");

        open(controller);
        ShortcutController.StatusAnswer opening = (ShortcutController.StatusAnswer) controller.status("ford", token, request()).getBody();
        assertThat(opening.confirmed()).isFalse();
        assertThat(opening.doorState()).isEqualTo(DoorState.OPENING);
        assertThat(opening.speak()).isEqualTo("Ford is opening; it isn't confirmed yet.");
    }
}
