package chat.secretchat.admin;

/** Published synchronously during the transaction retiring a device. */
public record DeviceRetired(String accountId, String deviceId) {}
