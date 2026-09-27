package com.newendian.retichat.data.repository

import com.newendian.retichat.service.GroupChatManager

/**
 * Which inbound LXMF messages the app keeps (DISPLAY_NAMES.md §7, "Android
 * privacy filter"): exactly what iOS accepts, iOS ChatRepository.swift
 * `allowlistDecision` and `groupMessagePolicy`.
 *
 * The privacy filter is enforced here, in the app, and the router's own
 * stranger filter is kept off. The router's allowlist can only name a
 * source; iOS's group rule accepts any group message for a group that exists
 * locally whoever relays it, and an allowlist in the router would also have
 * to be rebuilt in memory at every start before the first delivery. Until
 * 2026-09-27 the router filter was on with an allowlist nothing filled, and
 * every router-delivered message was dropped, contacts' included.
 */
object DeliveryPolicy {
    /**
     * A direct message, or a group invite's source: allowed when the filter is
     * off, or when [contactExists] and the contact is [allowlisted].
     */
    fun allowlisted(filterStrangers: Boolean, contactExists: Boolean, allowlisted: Boolean): Boolean =
        !filterStrangers || (contactExists && allowlisted)

    /**
     * iOS `groupMessagePolicy`: an invite follows its source's allowlist
     * decision; any other group message is kept when the group exists here.
     */
    fun groupMessage(action: String?, inviterAllowed: Boolean, groupExists: Boolean): Boolean =
        if (action == GroupChatManager.Action.INVITE) inviterAllowed else groupExists
}
