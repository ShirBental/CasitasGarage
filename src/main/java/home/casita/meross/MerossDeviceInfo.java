package home.casita.meross;

/** One device on the Meross account, from the device list. */
record MerossDeviceInfo(String uuid, String name, String type, boolean online) {

    /** Shows only the last four characters of the uuid, so device lists and errors can be logged. */
    @Override
    public String toString() {
        return "MerossDeviceInfo[name=" + name + ", type=" + type + ", online=" + online + ", uuid=" + masked(uuid) + "]";
    }

    static String masked(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return String.valueOf(uuid);
        }
        return uuid.length() <= 4 ? "…" : "…" + uuid.substring(uuid.length() - 4);
    }
}
