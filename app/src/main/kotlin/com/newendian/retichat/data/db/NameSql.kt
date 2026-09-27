package com.newendian.retichat.data.db

/**
 * The name-slot writes whose guards are the DISPLAY_NAMES.md §5.2 order,
 * kept as constants so the DAOs use them and the JVM test runs the same
 * statements on SQLite (NameSqlTest). The app decides with the pure rules in
 * [com.newendian.retichat.names.DisplayNames]; the guard repeats the order
 * in the write itself, so two deliveries handled at once cannot let the
 * older one land last.
 */
object NameSql {
    /**
     * §5.2 accept: set or clear `messageName` from the message at [at] (LXMF
     * seconds) only if it is newer than `messageNameAt`; with `onlyIfNone`
     * (source unknown) only if the slot is empty. Accepting drops
     * `legacyName` (§5.1).
     *
     * Only a validated message records its time. A source-unknown one fills
     * the empty slot but leaves `messageNameAt` as it was: nobody vouches for
     * its timestamp, and recording it would let anyone who claims the
     * sender's hash, with a far-future timestamp, block every later
     * validated name from the real sender (§5.2 "validated → messageName = s").
     */
    const val ACCEPT_MESSAGE_NAME =
        "UPDATE contacts SET messageName = :name, " +
            "messageNameAt = CASE WHEN :onlyIfNone = 0 THEN :at ELSE messageNameAt END, legacyName = NULL " +
            "WHERE destHashHex = :hex AND (messageNameAt IS NULL OR messageNameAt < :at) " +
            "AND (:onlyIfNone = 0 OR messageName IS NULL)"

    /**
     * §5.1 announce: replaces `announceName` (null clears it); an announce
     * carrying a name also drops `legacyName`.
     */
    const val SET_ANNOUNCE_NAME =
        "UPDATE contacts SET announceName = :name, " +
            "legacyName = CASE WHEN :name IS NULL THEN legacyName ELSE NULL END " +
            "WHERE destHashHex = :hex"

    /** §5.2 channel names: the same order per (channel, sender), with the post timestamp (ms). */
    const val SET_SENDER_CHANNEL_NAME =
        "UPDATE channel_senders SET channelName = :name, nameAt = :postTimestampMs " +
            "WHERE channelId = :channelId AND senderHex = :senderHex AND nameAt < :postTimestampMs"
}
