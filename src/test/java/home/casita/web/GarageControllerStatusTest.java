package home.casita.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import home.casita.garage.DoorControl;
import home.casita.garage.DoorDevice;
import home.casita.garage.DoorRoster;
import home.casita.garage.DoorState;
import home.casita.garage.Garage;
import home.casita.garage.GarageProperties;
import home.casita.garage.InMemoryActivityLog;

/**
 * {@code /api/doors} reads Ford and Toyota side by side and answers within its bound: a door whose opener doesn't
 * answer is shown {@code UNKNOWN} (nothing offered), and never holds up the other door or the app.
 */
class GarageControllerStatusTest {

    private static final Duration TRAVEL = Duration.ofSeconds(6);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Boolean> sent = new ArrayList<>();

    @AfterEach
    void releaseHungReads() {
        release.countDown();
    }

    /** Reads "closed" after {@code delayMillis}, or never answers (until the test ends) when {@code hangs}. */
    private DoorDevice door(long delayMillis, boolean hangs) {
        return new DoorDevice() {
            @Override
            public Reading read() {
                try {
                    if (hangs) release.await(30, TimeUnit.SECONDS);
                    else if (delayMillis > 0) Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new Reading(false, null);
            }

            @Override
            public void command(boolean open) {
                sent.add(open);
            }
        };
    }

    private GarageController controller(DoorDevice ford, DoorDevice toyota, Duration wait) {
        GarageProperties properties = new GarageProperties(TRAVEL, Duration.ofSeconds(2), true);
        Garage garage = new Garage(new DoorRoster(List.of(new DoorRoster.Door("Ford", ford, DoorControl.SIMULATED),
                new DoorRoster.Door("Toyota", toyota, DoorControl.SIMULATED))), new InMemoryActivityLog(), message -> {},
                (when, task) -> {}, clock, properties);
        return new GarageController(garage, new InMemoryActivityLog(), properties, null, wait);
    }

    @Test
    void aDoorWhoseOpenerDoesntAnswerIsUnknownAndTheOtherIsShownOnTime() {
        GarageController controller = controller(door(0, false), door(0, true), Duration.ofMillis(500));
        long started = System.nanoTime();
        GarageController.Doors doors = controller.doors();
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(tookMs).isLessThan(2000);
        assertThat(doors.doors().get(0).door()).isEqualTo(DoorState.CLOSED);
        assertThat(doors.doors().get(0).status().canOpen()).isTrue();
        GarageController.DoorView toyota = doors.doors().get(1);
        assertThat(toyota.door()).isEqualTo(DoorState.UNKNOWN);
        assertThat(toyota.status().canOpen()).isFalse();
        assertThat(toyota.status().canClose()).isFalse();
        assertThat(sent).isEmpty();
    }

    @Test
    void aHungOpenerGetsOneReadAtATimeAndNeverStarvesTheOtherDoor() {
        java.util.concurrent.atomic.AtomicInteger toyotaReads = new java.util.concurrent.atomic.AtomicInteger();
        DoorDevice hung = door(0, true);
        DoorDevice counted = new DoorDevice() {
            @Override
            public Reading read() throws home.casita.garage.DoorException {
                toyotaReads.incrementAndGet();
                return hung.read();
            }

            @Override
            public void command(boolean open) {
                sent.add(open);
            }
        };
        GarageController controller = controller(door(0, false), counted, Duration.ofMillis(500));
        assertThat(controller.doors().doors().get(1).door()).isEqualTo(DoorState.UNKNOWN); // the first poll waits once
        for (int i = 0; i < 8; i++) { // more polls than the pool has threads
            long started = System.nanoTime();
            GarageController.Doors doors = controller.doors();
            long tookMs = (System.nanoTime() - started) / 1_000_000;
            assertThat(tookMs).as("poll " + i + " doesn't wait again for the hung read").isLessThan(200);
            assertThat(doors.doors().get(0).door()).as("poll " + i).isEqualTo(DoorState.CLOSED);
            assertThat(doors.doors().get(1).door()).isEqualTo(DoorState.UNKNOWN);
        }
        assertThat(toyotaReads.get()).isEqualTo(1);
    }

    @Test
    void bothDoorsAreReadSideBySide() {
        GarageController controller = controller(door(700, false), door(700, false), Duration.ofSeconds(5));
        long started = System.nanoTime();
        GarageController.Doors doors = controller.doors();
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(tookMs).isLessThan(1300); // not 700 + 700
        assertThat(doors.doors()).extracting(GarageController.DoorView::door).containsExactly(DoorState.CLOSED, DoorState.CLOSED);
    }
}
