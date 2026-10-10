package com.newendian.retichat.data.db

/**
 * Database 14 → 15: the distro sync proof (RFed SPEC §17.13;
 * DISTRO-SYNC-PROOF-DESIGN.md §7, Android "Room").
 *
 * - `distro_outbox.sealed`: the owed message encrypted to its distro once,
 *   when it was owed (`D_hash | D.encrypt(packed[16..])`), the bytes every
 *   upload of it carries.
 * - `distro_outbox.syncSig`: the distro's signature over those bytes, the
 *   claim's `sig`.
 *
 * Both nullable, and null for every row already here: a message owed before
 * this version keeps everything it had and is built the legacy way, waking
 * the distro's devices once, as it would have. Nothing is lost.
 *
 * Plain SQL, run in order, so the JVM test runs the same statements on
 * SQLite. The columns are written exactly as Room generates them from the
 * entity (`DistroSyncMigrationTest` compares them with the generated schema).
 */
object DistroSyncMigration {
    const val FROM = 14
    const val TO = 15

    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE `distro_outbox` ADD COLUMN `sealed` BLOB",
        "ALTER TABLE `distro_outbox` ADD COLUMN `syncSig` BLOB",
    )
}
