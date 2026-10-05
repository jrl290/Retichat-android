package com.newendian.retichat.data.db

/**
 * Database 11 → 12, both on `contacts`.
 *
 * - `isContact`: a contact exists only because the user added it (James,
 *   2026-10-02: "Just prevent adding contacts that aren't explicitly
 *   added. The group and channel member messages are only accepted by
 *   association."). Every other row is hidden: it holds what the app learned
 *   about a peer (a group member's key, a sender's names) and is listed
 *   nowhere ([ContactSql.LISTED]). The rows already here stay contacts
 *   (James: no cleanup), so the column defaults to 1 for them; the app
 *   writes it on every insert.
 * - DISPLAY_NAMES.md §5.4, the pass for databases migrated (10 → 11) before
 *   the old web node defaults and the old web announce suffix were
 *   placeholders: a `localName` or `legacyName` that is an old web node
 *   default is cleared; a `localName`, `legacyName` or `announceName` that
 *   carries the suffix with the contact's own hash loses it, and what is left
 *   came from an announce, so it is judged as a name that was not typed
 *   (dropped when a placeholder, in whichever slot). White space is §3's
 *   (`trim(x, WS)`). The same rules in Kotlin are
 *   [com.newendian.retichat.names.DisplayNames.isWebNodeDefault] and
 *   [com.newendian.retichat.names.DisplayNames.stripOwnHashSuffix].
 *
 * Plain SQL, run in order, so the JVM test runs the same statements on SQLite.
 */
object ContactsMigration {
    const val FROM = 11
    const val TO = 12

    private fun stripSuffix(column: String): String {
        val left = NamesMigration.withoutOwnHashSuffixSql("`$column`")
        return "UPDATE `contacts` SET `$column` = CASE WHEN ${NamesMigration.placeholderSql(left)} THEN NULL " +
            "ELSE $left END WHERE `$column` IS NOT NULL AND ${NamesMigration.ownHashSuffixSql("`$column`", "destHashHex")}"
    }

    private fun clearWebNodeDefault(column: String): String =
        "UPDATE `contacts` SET `$column` = NULL WHERE `$column` IS NOT NULL AND ${NamesMigration.webNodeDefaultSql("`$column`")}"

    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE `contacts` ADD COLUMN `isContact` INTEGER NOT NULL DEFAULT 1",
        clearWebNodeDefault("localName"),
        clearWebNodeDefault("legacyName"),
        stripSuffix("localName"),
        stripSuffix("legacyName"),
        stripSuffix("announceName"),
    )
}
