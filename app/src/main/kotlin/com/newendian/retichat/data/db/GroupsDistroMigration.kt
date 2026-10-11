package com.newendian.retichat.data.db

/**
 * Database 15 → 16: groups for distro holders (RFed SPEC §17.14;
 * GROUPS-DISTRO-DESIGN.md §8.1, §6.5, §18.2 item 3).
 *
 * - `chats.ownMember`: the address the user acts as in a group (§8.1): the
 *   distro D for a group created on a device that held D, admitted from an
 *   invite that lists D, or built from a group sync; the device otherwise.
 *   Nullable, and null for every row already here: a group held before this
 *   version is "held at upgrade" and stays on the device (ruling 1, no
 *   migration), which null reads as.
 * - `group_arrivals`: the held arrivals of §6.5, a group message or a `post`
 *   group sync that reached this device through the distro before the group
 *   was joined here, kept as the fan-out blob it came in, with its group, its
 *   LXMF source and hash, a digest of the blob (a copy with another signature
 *   is another blob, so it never shadows one held), whether its signature
 *   validated, the distro it came through, and whether it is a `post` sync.
 *   It starts empty. `rowId` is the arrival order the replay keeps.
 *
 * Plain SQL, run in order, so the JVM test runs the same statements on
 * SQLite. The column and table are written exactly as Room generates them from
 * the entities (`GroupsDistroMigrationTest` compares them with the generated
 * schema).
 */
object GroupsDistroMigration {
    const val FROM = 15
    const val TO = 16

    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE `chats` ADD COLUMN `ownMember` TEXT",
        "CREATE TABLE IF NOT EXISTS `group_arrivals` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` TEXT NOT NULL, `srcHex` TEXT NOT NULL, `msgHash` TEXT NOT NULL, `blobDigest` TEXT NOT NULL, `validated` INTEGER NOT NULL, `distro` TEXT NOT NULL, `isPostSync` INTEGER NOT NULL, `blob` BLOB NOT NULL)",
    )
}
