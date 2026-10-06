package com.newendian.retichat.data.db

/**
 * The statements behind `channel_name_state`'s legacy rows (database 13), as
 * constants so the JVM test runs the very SQL the DAO runs
 * ([com.newendian.retichat.data.db.dao.ChannelDao.adoptLegacyNameState]).
 */
object NameStateSql {
    /**
     * Rows from before database 13 (empty `postingHex`) were the device's, the
     * only posting identity then: they become `:deviceHex`'s, except where the
     * device already has a row of its own for that channel.
     */
    const val ADOPT_LEGACY =
        "UPDATE channel_name_state SET postingHex = :deviceHex WHERE postingHex = '' AND NOT EXISTS " +
            "(SELECT 1 FROM channel_name_state AS own WHERE own.channelId = channel_name_state.channelId AND own.postingHex = :deviceHex)"

    /** What [ADOPT_LEGACY] left: a legacy row the device had no use for. */
    const val DROP_LEGACY = "DELETE FROM channel_name_state WHERE postingHex = ''"
}
