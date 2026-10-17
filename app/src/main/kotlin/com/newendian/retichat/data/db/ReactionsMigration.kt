package com.newendian.retichat.data.db

/**
 * Database 16 → 17: reactions (REACTIONS-DESIGN.md §6). Three nullable
 * columns of `messages`, null for every row already here:
 *
 * - `lxmfHash`: the LXMF hash where the row's id is not it (an outgoing direct
 *   message, a Distro Fanout message, a Distro Sync of a send), so a reaction
 *   in a direct chat can find its target;
 * - `postId`: a Group post's id, which a Group reaction names;
 * - `reactionsJson`: the reactions to the message.
 *
 * Plain SQL, run in order, so the JVM test runs the same statements on SQLite.
 * The columns are written exactly as Room generates them from the entity
 * (`ReactionsMigrationTest` compares them with the generated schema).
 */
object ReactionsMigration {
    const val FROM = 16
    const val TO = 17

    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE `messages` ADD COLUMN `lxmfHash` TEXT",
        "ALTER TABLE `messages` ADD COLUMN `postId` TEXT",
        "ALTER TABLE `messages` ADD COLUMN `reactionsJson` TEXT",
    )
}
