package com.newendian.retichat.data.db

/**
 * Database 12 → 13: channels and the distro (RFed-rust SPEC.md §17.12).
 *
 * - `channel_name_state` is rebuilt per channel and posting identity
 *   (DISPLAY_NAMES.md §4.2): a device that holds a distro signs its channel
 *   posts as the distro, and readers have never had a name from it, so the
 *   send rule's state is kept for each identity a device has posted as. SQLite
 *   cannot change a primary key, so the table is renamed, created again and
 *   copied. The rows already here were made when the device posted as itself,
 *   the only posting identity then; their `postingHex` is empty and the
 *   device adopts them as its own the first time it looks for its state
 *   (`ChannelDao.adoptLegacyNameState`), as the web adopts the entries it
 *   saved before (`ChannelPostNames.adoptLegacy`). Nothing is lost, and no
 *   channel's name is announced again for the migration.
 * - `channel_membership` (rule 5): per channel, the last join or leave this
 *   device made or applied, kept after a leave. It starts empty: a device
 *   that never made or applied a change has none.
 * - `distro_outbox`: what the device owes its distro until the propagation
 *   node proves it ("On the sending device"): a membership message per
 *   channel, and the §17.11 sent-copies. It starts empty.
 *
 * Plain SQL, run in order, so the JVM test runs the same statements on SQLite.
 * The new tables are written exactly as Room generates them from the
 * entities (`DistroChannelsMigrationTest` compares them with the generated
 * schema).
 */
object DistroChannelsMigration {
    const val FROM = 12
    const val TO = 13

    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE `channel_name_state` RENAME TO `channel_name_state_v12`",
        "CREATE TABLE `channel_name_state` (`channelId` TEXT NOT NULL, `postingHex` TEXT NOT NULL, `lastDigestHex` TEXT NOT NULL, `lastIncludedAt` INTEGER NOT NULL, PRIMARY KEY(`channelId`, `postingHex`))",
        "INSERT INTO `channel_name_state` (`channelId`, `postingHex`, `lastDigestHex`, `lastIncludedAt`) " +
            "SELECT `channelId`, '', `lastDigestHex`, `lastIncludedAt` FROM `channel_name_state_v12`",
        "DROP TABLE `channel_name_state_v12`",
        "CREATE TABLE IF NOT EXISTS `channel_membership` (`channelId` TEXT NOT NULL, `op` TEXT NOT NULL, `atMs` INTEGER NOT NULL, PRIMARY KEY(`channelId`))",
        "CREATE TABLE IF NOT EXISTS `distro_outbox` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `distro` TEXT NOT NULL, `distroKey` TEXT NOT NULL, `msgHash` TEXT NOT NULL, `packed` BLOB NOT NULL, `label` TEXT NOT NULL, `op` TEXT, `name` TEXT, `atMs` INTEGER, `toHex` TEXT, `seq` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    )
}
