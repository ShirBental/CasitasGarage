package home.casita.meross;

/** One device on the Meross account, from the device list. */
record MerossDeviceInfo(String uuid, String name, String type, boolean online) {}
