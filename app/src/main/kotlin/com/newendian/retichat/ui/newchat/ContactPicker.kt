package com.newendian.retichat.ui.newchat

import com.newendian.retichat.data.model.Contact

/**
 * The people New Chat and New Group offer: the user's contacts
 * (ChatRepository.contacts, never a hidden row) that pass the privacy filter,
 * never the user itself. The New Group picker offers these and nothing else
 * (James, 2026-10-02: contacts are only what the user adds).
 */
fun pickableContacts(contacts: List<Contact>, selfHex: String): List<Contact> =
    contacts.filter { it.isAllowlisted && it.destHashHex != selfHex }
