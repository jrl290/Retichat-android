package com.newendian.retichat.data.db

/**
 * Database 18 → 19: offline use of a distro address (RFed SPEC §17.15, James
 * 2026-11-17). A message this device received direct at its offline address
 * is owed to RFed like a Distro Sync of a send, as it came: the sender's sealed
 * message and the sender's stamp, with this device's sync signature.
 *
 * - `distro_outbox.stamp`: the sender's stamp, kept with the entry so its
 *   upload carries it unchanged while the node's cost allows.
 *
 * Nullable, and null for every row already here: none of them is a message
 * received direct. Nothing is lost.
 *
 * Plain SQL, so the JVM test runs the same statement on SQLite. The column is
 * written exactly as Room generates it from the entity
 * (`DirectUploadMigrationTest` compares it with the generated schema).
 */
object DirectUploadMigration {
    const val FROM = 18
    const val TO = 19

    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE `distro_outbox` ADD COLUMN `stamp` BLOB",
    )
}
