package com.newendian.retichat.data.db

/**
 * Contacts and hidden rows (James, 2026-10-02: "Just prevent adding contacts
 * that aren't explicitly added. The group and channel member messages are
 * only accepted by association."). A row is a contact only when the user
 * added it (Add Contact, New Conversation, a QR code or lxma:// link); every
 * other row (a group member, a stranger's DM with the filter off, a distro
 * sender, a distro sent copy's recipient) is hidden: kept for its names and
 * key, resolved wherever a name is shown, listed nowhere. Constants so the
 * DAO uses them and the JVM test runs them on SQLite (ContactsMigrationTest).
 */
object ContactSql {
    /** The contacts the contact surfaces list (New Chat, New Group): never a hidden row. */
    const val LISTED = "SELECT * FROM contacts WHERE isContact = 1"

    /** The user added the row's peer: it is a contact from now on. */
    const val SET_CONTACT = "UPDATE contacts SET isContact = 1 WHERE destHashHex = :hex"
}
