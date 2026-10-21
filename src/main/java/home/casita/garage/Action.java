package home.casita.garage;

public enum Action {
    OPEN, CLOSE;

    boolean wantsOpen() {
        return this == OPEN;
    }
}
