package com.newendian.retichat.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * What this device still owes its distro D: the messages it uploads for D
 * through the propagation node, each kept until the node proves it has it
 * (RFed-rust SPEC.md §17.12 "On the sending device", §17.11, "The phones'
 * outbox"). Mirrors Retichat-js 90640bf (`lib/distro_outbox.js`,
 * `lib/distro_upload.js`, and `_oweDistro`, `_sendDistroOutbox`,
 * `_unprovedSince`, `_distroAttemptOpen`, `_uploadOwed`, `_stillOwed`,
 * `_dropMembershipOwedToOtherDistros` in `app.js`). Pure logic: the Room
 * store, the JNI link and the clock are ports, so the JVM tests drive it with
 * fake link and upload events.
 *
 * Two kinds are owed: the §17.12 membership message C, the newest action per
 * channel only (a later join or leave of the channel replaces the one still
 * owed: its id is the channel's), and the §17.11 sent-copy of a message sent as
 * D, one per message. Each entry is the LXMF message exactly as D packed and
 * signed it when the user acted, never packed again, so every upload of it is
 * one and the same message and a sibling that gets it twice holds the second
 * as a repeat.
 *
 * An entry is written before anything can yield. It is uploaded at once when
 * the propagation link is up, and otherwise when the link next comes up. It
 * leaves only when the node proves the upload. An upload that is not proved
 * (its packet reported lost, its Resource failed, the connection stopped) is
 * uploaded again when the propagation link next comes up. That second upload is
 * a send retry, which DESIGN_PRINCIPLES §3 forbids, allowed here only by the
 * exception James decided on 2026-10-03 ("what a device owes its distro") and
 * for these two messages alone. His rulings of that day, and of 2026-10-04:
 *
 *  - The link comes up when a new propagation link is established
 *    ([onLinkEstablished]), or when a STALE link recovers because the node is
 *    heard from on it again ([onLinkRecovered]).
 *  - An upload is never sent again on the strength of its own failure alone: a
 *    failure waits for the next coming-up, even when a flush is already running
 *    on the link that is up ([unproved], [propComingUps]).
 *  - A STALE link replaced by a new one decides nothing: the node may still
 *    prove the upload over it. When that upload is then decided not proved
 *    while a newer propagation link is up, that decision is the event and the
 *    entry goes once on the newer link at once ([uploadOwed]). On the phones the
 *    decision is the upload's own loss: the packet receipt's timeout (LXMF
 *    `__link_packet_timed_out`, which also tears the link down, so the close
 *    that follows is the same event) or the Resource's failure. A failure of
 *    that upload on the newer link waits for the next coming-up like any other.
 *  - When the device forgets or replaces its distro, the membership messages it
 *    owed that distro are dropped. Its sent-copies are still owed, and still
 *    uploaded to that distro, the same bytes.
 *  - An upload that never left the device, because no interface could carry it
 *    (the packet could not be queued on the link), is not a failure: nothing was
 *    sent. It goes when an interface comes back online ([onInterfaceOnline]), an
 *    event like the others, as well as at the next coming-up. A loss after the
 *    packet left still waits for the next coming-up, as above (James,
 *    2026-10-06: on staging a short network drop left the propagation link up,
 *    so no coming-up followed, and such an upload waited 35 s for an unrelated
 *    close). Mirrors Retichat-js 80c74ad and 136d13d (`_sendDistroNeverLeft`).
 *
 * At most one upload of an entry is in flight at a time: while one has left and
 * is not decided, no flush uploads it, on its link or on a newer one.
 *
 * Every event runs to completion before the next one begins (the runtime runs
 * them on one thread), so what an event decides is ordered before anything the
 * next does; the only suspensions are the store's writes and building an upload
 * (its stamp can take a while), after which an entry is checked again. No
 * timer, no retry loop (DESIGN_PRINCIPLES §3, §5).
 */

enum class OwedKind { CHANNEL, SENT }

/**
 * One thing owed. [id] is `channel:<channel hash>` or `sent:<LXMF message
 * hash>`; [msgHash] is the LXMF message hash of [packed], which is what tells
 * one message from another under the same id. [distro] is D's `lxmf.delivery`
 * hash and [distroKey] D's public key (hex), so a sent-copy is still encrypted
 * to its own distro after the device gives that distro up. A channel entry
 * adds [op], [name] and [atMs]; a sent entry [toHex].
 */
class OwedEntry(
    val id: String,
    val kind: OwedKind,
    val distro: String,
    val distroKey: String,
    val msgHash: String,
    val packed: ByteArray,
    val label: String,
    val op: String? = null,
    val name: String? = null,
    val atMs: Long? = null,
    val toHex: String? = null,
) {
    /** The same message, as packed (not another action under the same id). */
    fun sameMessage(other: OwedEntry?): Boolean = other != null && id == other.id && msgHash == other.msgHash

    override fun equals(other: Any?): Boolean = other is OwedEntry && sameMessage(other) && distro == other.distro
    override fun hashCode(): Int = 31 * id.hashCode() + msgHash.hashCode()
    override fun toString(): String = "OwedEntry($id, $label)"
}

/** The distro this device holds: its `lxmf.delivery` hash and public key (hex). */
data class HeldDistro(val delivery: String, val publicKey: String)

/** The propagation link the stack holds: its id (hex) and whether it is ACTIVE. */
data class HeldLink(val id: String, val active: Boolean)

/** An upload that left: the link (hex) it went on, and whether it is a Resource. */
data class UploadLeft(val linkId: String, val resource: Boolean)

/** Where an upload's outcome is reported, on the outbox's thread. */
interface UploadSink {
    /** The node proved the packet, or the Resource completed. */
    fun proved()

    /** The packet's receipt timed out, or the Resource failed: it will not be proved. */
    fun lost()
}

/** Where owed entries are kept across a restart (Room). */
interface OutboxStore {
    suspend fun loadAll(): List<OwedEntry>

    /** Keep [entry], replacing the one under its id. False when storage refused it. */
    suspend fun put(entry: OwedEntry): Boolean

    /** The message [msgHash] under [id] is owed no more; a later one under the same id stays. */
    suspend fun remove(id: String, msgHash: String)

    /** Whatever is under [id]: a replaced message when the write of its replacement was refused. */
    suspend fun removeId(id: String)
}

/** The propagation link and the uploads on it (the JNI). */
interface PropagationPort {
    /** The link held for the propagation node as it is now, or null. A read, never a wait. */
    fun held(): HeldLink?

    /**
     * Build the propagation upload of [entry]: its packed message encrypted to
     * [OwedEntry.distroKey] and no other key, stamped for the node. Suspends
     * while the stamp is mined; throws when it cannot be built.
     */
    suspend fun build(entry: OwedEntry): ByteArray

    /**
     * Hand [upload] to the held ACTIVE link. Null when it did not leave (no
     * active link, or it could not be queued, which is what no interface being
     * able to carry it comes to): [sink] is then never called, and the outbox
     * takes that for an upload that never left the device, which is no failure
     * ([DistroOutbox.onInterfaceOnline]). Otherwise [sink] hears its proof or its
     * loss, each at most once, and a proof can follow a loss.
     */
    fun upload(upload: ByteArray, sink: UploadSink): UploadLeft?
}

interface OutboxLog {
    fun info(message: String)
    fun warn(message: String)

    /** An error the client can show the user: an upload that failed while still owed, a write storage refused. */
    fun error(message: String)
}

/**
 * The messages owed to the distro whose last upload, or its build, was decided
 * not proved, each with the propagation link's coming-up it was decided at
 * ([DistroOutbox.propComingUps]). A flush taken at a coming-up no later than
 * that does not upload the message, and the next coming-up does: an upload is
 * never sent again on the strength of its own failure alone.
 *
 * A record belongs to one message: the entry's id and the message packed under
 * it. One id (a channel's) may name several messages over time, a join and
 * then a leave, and the upload of each is decided on its own, in any order: the
 * older one's on the link it went on, which may close long after the newer
 * one's upload was reported lost. So recording, reading or forgetting one
 * message's record never replaces, reads or removes another's. What is recorded
 * is this process's alone (its coming-up counts start at zero): it lives in
 * memory, never in storage, and the first coming-up after a start sends
 * everything owed.
 *
 * Apart from those failures, and held here so that [forget] ends both, the
 * messages whose last upload never left the device (James, 2026-10-06; the
 * ruling in [DistroOutbox]): nothing was sent, so that is no failure, no
 * coming-up is recorded for it, and a flush at a coming-up sends it like a
 * message not yet uploaded. An interface coming back online sends it too
 * ([DistroOutbox.onInterfaceOnline]), and only while the record stands: an
 * upload begun for the message ends it ([clearNeverLeft]), whatever becomes of
 * that upload, a loss being then a failure's record and waiting for the next
 * coming-up.
 */
class UnprovedUploads {
    private val byId = HashMap<String, HashMap<String, Long>>()
    private val neverLeftById = HashMap<String, HashSet<String>>()

    /** The upload of the message [entry] was decided not proved at coming-up [at]. */
    fun record(entry: OwedEntry, at: Long) {
        byId.getOrPut(entry.id) { HashMap() }[entry.msgHash] = at
    }

    /** The coming-up the last failure of [entry] was decided at, or null. */
    fun at(entry: OwedEntry): Long? = byId[entry.id]?.get(entry.msgHash)

    /** Whether the last failure of [entry] was decided at coming-up [comingUp] or later. */
    fun since(entry: OwedEntry, comingUp: Long): Boolean {
        val at = at(entry) ?: return false
        return at >= comingUp
    }

    /**
     * [entry] is owed no more (proved, replaced by a later action, dropped with
     * its distro): its records go, the failure's and the never-left one, and
     * only its own.
     */
    fun forget(entry: OwedEntry) {
        clearNeverLeft(entry)
        val messages = byId[entry.id] ?: return
        if (messages.remove(entry.msgHash) == null) return
        if (messages.isEmpty()) byId.remove(entry.id)
    }

    /** How many messages have a failure record. */
    val size: Int get() = byId.values.sumOf { it.size }

    /**
     * The last upload of the message [entry] never left the device: nothing was
     * sent, so it is no failure and no coming-up is recorded for it ([record] is
     * for a failure, and is not called).
     */
    fun recordNeverLeft(entry: OwedEntry) {
        neverLeftById.getOrPut(entry.id) { HashSet() }.add(entry.msgHash)
    }

    /** Whether the last upload of the message [entry] never left the device. */
    fun neverLeft(entry: OwedEntry): Boolean = neverLeftById[entry.id]?.contains(entry.msgHash) == true

    /** An upload of the message [entry] begins: its last one is no longer the one that never left. Only that message's record. */
    fun clearNeverLeft(entry: OwedEntry) {
        val messages = neverLeftById[entry.id] ?: return
        if (messages.remove(entry.msgHash) && messages.isEmpty()) neverLeftById.remove(entry.id)
    }

    /** How many messages have a never-left record. */
    val neverLeftSize: Int get() = neverLeftById.values.sumOf { it.size }
}

class DistroOutbox(
    private val store: OutboxStore,
    private val port: PropagationPort,
    private val heldDistro: () -> HeldDistro?,
    private val log: OutboxLog,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** DESIGN_PRINCIPLES §1: the longest the propagation node may take to prove an upload packet. */
        const val PROOF_LIVE_MS = 5_000L
    }

    private enum class Settled { PROVED, LOST }

    /** One upload from its leaving until it is decided: the web's `DistroUploads.track`. */
    private class Upload(val label: String, var linkId: String, var how: String) {
        var leftAt: Long? = null
        var settled: Settled? = null
        var lostWhy: String = ""
        var lateProof = false
        var onLateProof: (() -> Unit)? = null
        /** Told in the task of the event that decides a loss: records the failure and notes a newer link. */
        var onLost: (() -> Unit)? = null
        var onOutcomeProved: (() -> Unit)? = null
        var onOutcomeLost: ((String) -> Unit)? = null
    }

    /** What came of handing a built upload to the link ([uploadFor]). */
    private sealed class Handoff {
        /** It left; its proof or its loss decides it. */
        class Left(val upload: Upload) : Handoff()

        /** [uploadFor]'s link is no longer the propagation link, or no longer up: nothing was handed over, and nothing is decided. */
        object LinkGone : Handoff()

        /** The link was up and the port did not take the packet (it could not be queued): it never left the device. */
        object NeverLeft : Handoff()
    }

    /** An attempt at an entry, from its build until it is decided. */
    private class Flight(val linkId: String, val msgHash: String) {
        var upload: Upload? = null
    }

    /** What is owed, oldest first: the mirror of what the store holds, which this outbox is the only writer of. */
    private val entries = LinkedHashMap<String, OwedEntry>()

    /** Entries whose write is still under way: a flush leaves them to their own [owe]. */
    private val writing = HashSet<String>()

    /** id -> the attempt at that entry, whose upload (once it has left) is not yet decided. */
    private val inFlight = HashMap<String, Flight>()

    /** Every upload not yet decided, for [cut]. */
    private val open = LinkedHashSet<Upload>()

    /**
     * How many times the propagation link has come up in this process: each
     * established link and each STALE link's recovery (James, 2026-10-03: both
     * are the link coming up). Each runs one flush of what the distro is owed,
     * which takes the new value ([sendDistroOutbox]).
     */
    var propComingUps: Long = 0
        private set

    /** The messages owed whose last upload (or its build) was decided not proved, with the coming-up it was at. */
    val unproved = UnprovedUploads()

    private var loaded = false
    private var stopped = false

    /** What is owed now, oldest first. */
    fun owed(): List<OwedEntry> = entries.values.toList()

    // ---- Start and stop ----

    /**
     * Load what a previous run left owed (the first start only) and begin to
     * take events again (after a stop). Membership messages owed to a distro
     * this device does not hold are dropped here, as on every change of the
     * distro held (James, 2026-10-03).
     */
    suspend fun start() {
        if (!loaded) {
            for (entry in store.loadAll()) entries[entry.id] = entry
            loaded = true
        }
        stopped = false
        dropMembershipOwedToOtherDistros()
    }

    /**
     * The stack stopped: no proof can come for an upload still open, so each is
     * decided not proved, and none is sent again by this (the stack is gone,
     * and what is owed goes on the first link of the next start). A proof that
     * comes all the same is a late proof.
     */
    fun onStop() {
        stopped = true
        cut("the connection stopped")
    }

    // ---- Owing ----

    /**
     * Owe the distro [entry] (a §17.11 sent-copy or a §17.12 membership
     * message, packed and signed by D): written first, before anything can
     * yield, then uploaded at once if the propagation link is up (on its own
     * coroutine: the user's action never waits for a stamp), and otherwise
     * when the link next comes up. One already owed under its id (an earlier
     * join or leave of the same channel) is replaced and never goes, even if its
     * upload is being built. It is the user's own action, and reads no failure
     * record: only the user's own send, join or leave calls it.
     */
    suspend fun owe(entry: OwedEntry) {
        // The message this one replaces is owed no more: its failure record, if
        // any, goes with it, and only its own.
        val replaced = entries[entry.id]
        if (replaced != null && !replaced.sameMessage(entry)) unproved.forget(replaced)
        entries.remove(entry.id)
        entries[entry.id] = entry
        val key = "${entry.id}|${entry.msgHash}"
        writing.add(key)
        val kept = try {
            store.put(entry)
        } catch (e: CancellationException) {
            writing.remove(key)
            throw e
        } catch (e: Exception) {
            false
        }
        writing.remove(key)
        if (!kept) {
            // Storage refused it: this process holds it, and it goes when the
            // propagation link is up while it runs, but nothing keeps it past
            // that. The message it replaced is taken out of storage with a
            // write no larger than what storage holds, so a later run never
            // sends an action the user has since undone. Said, never silent.
            log.error("${entry.label} could not be kept in storage: this device holds it, and it goes when the propagation link is up while the app runs; a restart loses it")
            if (replaced != null && !replaced.sameMessage(entry)) {
                try {
                    store.removeId(entry.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Storage refuses even a smaller write: it still holds ${replaced.label}, owed no more, which a later run would send")
                }
            }
        }
        // Replaced again, or proved, while the write ran: owed no more.
        if (!entry.sameMessage(entries[entry.id])) return
        val held = port.held()
        if (held != null && held.active) {
            uploadOwedAtOnce(held.id, entry)
            return
        }
        log.info("${entry.label} is owed to the distro: it goes when the propagation link is up (§17.11, §17.12)")
    }

    // ---- The propagation link comes up ----

    /**
     * A new propagation link [linkId] was established (James, 2026-10-03):
     * upload on it what is owed, oldest first. Each call is one coming-up, and
     * counts it: only this and [onLinkRecovered] start a flush, so a failed
     * upload never goes again on its own failure.
     */
    suspend fun onLinkEstablished(linkId: String) = sendDistroOutbox(linkId, "established")

    /**
     * A STALE propagation link [linkId] recovered because the node was heard
     * from on it again (James, 2026-10-03): the link comes up, and what is owed
     * goes on it. Only while it is still the propagation link: a STALE link
     * that a new one has replaced is no longer the propagation link (it keeps
     * only its grace for a late proof), so its recovery runs no flush and what
     * is owed waits for the current link's next coming-up. This section's
     * reading of the first and third rulings, not James's words.
     */
    suspend fun onLinkRecovered(linkId: String) {
        if (port.held()?.id != linkId) return
        sendDistroOutbox(linkId, "recovered")
    }

    /**
     * Upload on [linkId], oldest first, what is still owed to the distro
     * ([trigger]: "established" or "recovered"). Each call is one coming-up and
     * takes the new count. An entry is uploaded only when no attempt of it is
     * open and its last failure, if any, came before this coming-up:
     *  - one being built for this link is left to that build, and one whose
     *    upload has left and is not yet decided, on this link or on one it
     *    replaced, is left to its proof or to the event that decides it;
     *  - one whose upload was decided not proved at this coming-up or later (the
     *    user's own upload, made while this flush ran, lost while it built an
     *    earlier entry; an upload on a newer link after an old one was lost; a
     *    newer flush's) waits for the next coming-up.
     * The uploads are built one after the other (a stamp yields), so each entry
     * is checked again when its turn comes, and again once it is built
     * ([uploadOwed]). Once the link has gone the rest wait for the next one.
     * Never throws.
     */
    private suspend fun sendDistroOutbox(linkId: String, trigger: String) {
        val comingUp = ++propComingUps
        try {
            dropMembershipOwedToOtherDistros()
            val owed = entries.values.filter { "${it.id}|${it.msgHash}" !in writing }
            if (owed.isEmpty()) return
            log.info("Propagation link $trigger: ${owed.size} upload(s) owed to the distro (§17.11, §17.12)")
            for (entry in owed) {
                if (!linkUp(linkId)) {
                    log.info("Propagation link gone mid-flush: what the distro is still owed waits for the next one")
                    return
                }
                // Read when the flush began, and every build before this one
                // yielded: a later action on the channel may have replaced it, an
                // upload made meanwhile been proved, or the distro changed.
                if (!stillOwed(entry)) continue
                if (distroAttemptOpen(entry, linkId)) continue
                if (unprovedSince(entry, comingUp)) {
                    log.info("${entry.label} was not proved since this flush began: it waits for the propagation link's next coming-up (DESIGN_PRINCIPLES §3, the distro exception)")
                    continue
                }
                uploadOwed(linkId, entry, comingUp)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Sending what the distro is owed failed: ${e.message}")
        }
    }

    // ---- An interface comes back online ----

    /**
     * An interface came online ([name]: Transport's up-edge, an interface that
     * was offline is online; James, 2026-10-06, DESIGN_PRINCIPLES §3, what a
     * device owes its distro): upload on the propagation link, if it is up, what
     * is owed to the distro and never left the device, because no interface
     * could carry its packet ([unproved], [Handoff.NeverLeft]). Nothing was
     * sent, so it was no failure and waits for no coming-up. A short outage
     * leaves the propagation link up, so none would follow (on staging the
     * upload waited 35 s for an unrelated close). It is an event like the
     * others: no timer, and only the runtime's interface callback calls it
     * ([DistroOutboxContractTest] and [DistroOutboxWiringTest] pin that).
     *
     * It is not a coming-up and counts none ([propComingUps]), so no failure is
     * released by it: it sends only what is recorded as never left, and a loss
     * after the packet left still waits for the next coming-up. With the
     * propagation link not up (down, STALE, or coming up) there is nothing to
     * do: its coming-up sends these with everything else owed ([sendDistroOutbox]:
     * a never-left message has no failure record). Each is checked again when its
     * turn comes, as a flush's are (a build yields): a flush at a coming-up that
     * began meanwhile may have uploaded it, or another up-edge begun before this
     * one ended it ([uploadOwed] ends the record when an upload of the message
     * begins), so none goes twice, and one with an attempt open is left to it
     * ([distroAttemptOpen]). Never throws.
     */
    suspend fun onInterfaceOnline(name: String) {
        if (stopped) return
        val held = port.held()
        if (held == null || !held.active) return
        try {
            val owed = entries.values.filter { unproved.neverLeft(it) }
            if (owed.isEmpty()) return
            log.info("Interface $name came online: ${owed.size} upload(s) that never left the device go now (§17.11, §17.12)")
            for (entry in owed) {
                if (stopped || !linkUp(held.id)) {
                    log.info("Propagation link gone mid-flush: what never left the device waits for the next coming-up or the next interface to come online")
                    return
                }
                if (!unproved.neverLeft(entry) || !stillOwed(entry) || distroAttemptOpen(entry, held.id)) continue
                uploadOwed(held.id, entry)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Sending what never left the device failed: ${e.message}")
        }
    }

    private fun linkUp(linkId: String): Boolean = port.held()?.let { it.id == linkId && it.active } == true

    /**
     * Whether an attempt at the owed [entry] is open for a flush on [linkId]:
     * being built for that link, or uploaded on any link and not yet decided. A
     * build for another link never leaves ([uploadFor]), so it holds nothing back.
     */
    private fun distroAttemptOpen(entry: OwedEntry, linkId: String): Boolean {
        val flight = inFlight[entry.id] ?: return false
        return flight.msgHash == entry.msgHash &&
            (flight.linkId == linkId || (flight.upload != null && flight.upload!!.settled == null))
    }

    /**
     * Whether the last upload of the owed message [entry] (its build included)
     * was decided not proved at coming-up [comingUp] or later: a flush taken at
     * [comingUp] does not upload it, and the next coming-up does. Only that
     * message's own record is read, never that of another action under the
     * same id.
     */
    private fun unprovedSince(entry: OwedEntry, comingUp: Long): Boolean = unproved.since(entry, comingUp)

    // ---- One upload ----

    /** The user's own action, and the third ruling's send: an upload on its own coroutine, begun in this task. */
    private fun uploadOwedAtOnce(linkId: String, entry: OwedEntry) {
        // UNDISPATCHED: its in-flight record is made now, before any other event can run (a JS async call).
        scope.launch(start = CoroutineStart.UNDISPATCHED) { uploadOwed(linkId, entry) }
    }

    /**
     * Upload the owed [entry] on [linkId]: build its propagation upload (it is
     * encrypted to the entry's distro and stamped for the node anew each time;
     * the LXMF message inside is the one packed when the user acted) and hand it
     * to the link. [comingUp] is the flush's coming-up when a flush uploads it
     * ([sendDistroOutbox]), null for the user's own action and for a replaced
     * link's decision. Returns once it has left, or could not leave; never
     * throws. Its proof makes it owed no more. If it is not proved, or its build
     * fails, that is recorded with the coming-up it was decided at ([unproved]),
     * and it is uploaded again on the propagation link's next coming-up, never on
     * the failure alone. One exception, also James's: an upload on a STALE link
     * that a newer propagation link replaced, decided not proved while the newer
     * link is up, goes once on the newer link at once: that decision is the
     * event. A failure of that upload is like any other. Each outcome is said
     * ([distroOwedOutcome]).
     *
     * Its callers are four and no other: the coming-up pass
     * ([sendDistroOutbox]), the user's own action ([owe]), that send on a
     * replaced link's decision, below, and an interface coming back online,
     * which sends what never left the device ([onInterfaceOnline]; James,
     * 2026-10-06: nothing was sent, so no failure is retried). Called without a
     * coming-up it reads no failure record, so any other caller would send an
     * upload again on its own failure; the interface's reads none because it
     * sends only a message whose last upload never left, and an upload made
     * after a failure was made only on an event that let that failure go
     * ([DistroOutboxContractTest] pins them).
     */
    private suspend fun uploadOwed(linkId: String, entry: OwedEntry, comingUp: Long? = null) {
        val flight = Flight(linkId, entry.msgHash)
        inFlight[entry.id] = flight
        // An upload of this message begins, so that its last one never left the
        // device decides nothing from now on (James, 2026-10-06): what this one
        // comes to is what is recorded then, a failure's record, its proof, or
        // the never-left record again.
        unproved.clearNeverLeft(entry)
        fun landed() {
            if (inFlight[entry.id] === flight) inFlight.remove(entry.id)
        }
        // A failure is recorded when it is decided, with the coming-up it was
        // decided at: a flush begun after it, in the same task, takes it. That is
        // the coming-up current then, never the one of the flush that made this
        // upload: a later flush, under way on the same link when the failure is
        // decided, must leave it for the next coming-up. It is this message's
        // record, and only while this message is the one owed under its id: a
        // decision about it never replaces or clears the record of another action
        // on the channel.
        fun recordUnproved() {
            if (entries[entry.id]?.msgHash == entry.msgHash) unproved.record(entry, propComingUps)
        }
        // Proved (late, too): owed no more, its record goes, and only its own.
        fun forgetUnproved() = unproved.forget(entry)

        val handoff: Handoff
        try {
            val bytes = port.build(entry)
            // Building it yielded: a later action on the same channel may have
            // replaced it meanwhile, or another upload of it been proved, or the
            // distro changed. Then it is not sent.
            if (!stillOwed(entry)) {
                landed()
                log.info("${entry.label} was replaced by a later action, proved, or left behind by a change of distro while it was built: not sent")
                return
            }
            // Nor is it sent by a flush when an upload of it was decided not
            // proved since that flush's coming-up: that waits for the next.
            if (comingUp != null && unprovedSince(entry, comingUp)) {
                landed()
                log.info("${entry.label} was not proved since this flush began: built, not sent; it waits for the propagation link's next coming-up")
                return
            }
            handoff = uploadFor(linkId, bytes, entry.label)
        } catch (e: CancellationException) {
            landed()
            throw e
        } catch (e: Exception) {
            // A build that throws is a failure: said, recorded as an upload decided
            // not proved, so no send pass already under way builds it again.
            landed()
            recordUnproved()
            distroOwedOutcome(entry, null, e.message ?: e.toString())
            return
        }
        val upload = when (handoff) {
            is Handoff.Left -> handoff.upload
            Handoff.LinkGone -> {
                // Building the upload (its stamp) yields, and can take seconds: the
                // link went down meanwhile. Nothing left, so nothing is decided: it
                // stays owed and goes when the link is next up.
                landed()
                log.info("The propagation link went down while ${entry.label} was built: nothing left, and it goes when the link is next up")
                return
            }
            Handoff.NeverLeft -> {
                // The link was up and the packet could not be queued on it: nothing
                // was sent, so this is no failure and records no coming-up. The record
                // only says which message that is, while it is the one owed under its
                // id, as a failure's does. An interface coming back online
                // ([onInterfaceOnline]) or the next coming-up sends it. It takes no
                // part in the third ruling's send on a newer link: no upload is open.
                landed()
                if (entries[entry.id]?.msgHash == entry.msgHash) unproved.recordNeverLeft(entry)
                distroOwedNeverLeft(entry)
                return
            }
        }
        // It left: until it is decided, no flush uploads this entry again, on this
        // link or on one that replaces it.
        flight.upload = upload
        // Told in the task of the event that decides it (the loss, the stop),
        // before anything else runs: its failure is recorded then, and so is the
        // newer propagation link that had replaced its link while STALE, if any.
        // If that link is up and still the propagation link once the decision is
        // made, this decision is the event, and the entry goes once on it. On the
        // current link, or with no newer link up, it waits for the next coming-up.
        var goesOn: String? = null
        upload.onLost = {
            recordUnproved()
            val newer = port.held()?.id
            if (newer != null && upload.linkId != newer) goesOn = newer
        }
        upload.onOutcomeProved = {
            landed()
            forgetUnproved()
            settle(entry)
            distroOwedOutcome(entry, upload.how, null)
        }
        upload.onOutcomeLost = { why ->
            landed()
            val newer = goesOn
            val held = port.held()
            if (newer != null && !stopped && held != null && held.id == newer && held.active &&
                stillOwed(entry) && !distroAttemptOpen(entry, newer)
            ) {
                distroOwedOutcome(entry, upload.how, why, goesNow = true)
                uploadOwedAtOnce(newer, entry)
            } else {
                distroOwedOutcome(entry, upload.how, why)
            }
        }
        // A proof after the upload was reported lost (the node had the packet
        // after all) is the truth all the same: the node has it, so it is owed
        // no more, and is not sent again.
        upload.onLateProof = {
            forgetUnproved()
            settle(entry)
            distroOwedOutcome(entry, upload.how, null)
        }
        // Decided before the handlers were set (a synchronous sink): the same
        // handlers run for it now.
        when (upload.settled) {
            Settled.LOST -> { upload.onLost?.invoke(); upload.onOutcomeLost?.invoke(upload.lostWhy) }
            Settled.PROVED -> upload.onOutcomeProved?.invoke()
            null -> Unit
        }
    }

    /**
     * Hand [bytes] to the propagation link [linkId], never started here.
     * [Handoff.LinkGone] when [linkId] is no longer the propagation link or no
     * longer up: nothing left, nothing decided. [Handoff.NeverLeft] when the
     * link was up and the port did not take the packet: it could not be queued,
     * nothing was sent, and that is no failure. Otherwise the upload, decided by
     * the node's proof or by its loss. A packet's proof has a §1 watch: a proof
     * later than 5 s is logged as the violation it is, and it settles the entry
     * all the same.
     */
    private fun uploadFor(linkId: String, bytes: ByteArray, label: String): Handoff {
        if (!linkUp(linkId)) return Handoff.LinkGone
        val upload = Upload(label, linkId, "packet")
        val sink = object : UploadSink {
            override fun proved() { uploadProved(upload) }
            override fun lost() { uploadLost(upload, if (upload.how == "resource") "its Resource failed" else "its packet was reported lost (the receipt timed out)") }
        }
        open.add(upload)
        val left = try {
            port.upload(bytes, sink)
        } catch (e: Exception) {
            open.remove(upload)
            throw e
        }
        if (left == null) {
            open.remove(upload)
            return Handoff.NeverLeft
        }
        upload.linkId = left.linkId
        upload.how = if (left.resource) "resource" else "packet"
        // Its proof has its own §1 watch for a Resource (its progress is watched
        // in the stack); a packet's clock starts when it has left.
        if (!left.resource && upload.settled == null) upload.leftAt = now()
        return Handoff.Left(upload)
    }

    /** The node proved the packet of [upload]. True when this decided it. */
    private fun uploadProved(upload: Upload): Boolean {
        val waited = upload.leftAt?.let { now() - it } ?: 0L
        if (waited > PROOF_LIVE_MS) {
            // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
            log.error("§1 VIOLATION: the propagation node proved ${upload.label} $waited ms after its upload (a late success is a failure)")
        }
        if (upload.settled == Settled.LOST) {
            log.warn("${upload.label} was proved after all, after it was reported lost")
            if (!upload.lateProof) {
                upload.lateProof = true
                upload.onLateProof?.invoke()
            }
            return false
        }
        if (upload.settled != null) return false
        upload.settled = Settled.PROVED
        open.remove(upload)
        upload.onOutcomeProved?.invoke()
        return true
    }

    /** [upload] will never be proved: [why]. True when this decided it; [Upload.onLost] is told first. */
    private fun uploadLost(upload: Upload, why: String): Boolean {
        if (upload.settled != null) return false
        upload.settled = Settled.LOST
        upload.lostWhy = why
        open.remove(upload)
        upload.onLost?.invoke()
        upload.onOutcomeLost?.invoke(why)
        return true
    }

    /** The proof of every upload not yet decided can no longer come: [why]. Each is lost. */
    private fun cut(why: String): Int {
        var cut = 0
        for (upload in open.toList()) if (uploadLost(upload, why)) cut++
        return cut
    }

    /** The upload of [entry] was proved: it is owed no more, and only that message. */
    private fun settle(entry: OwedEntry) {
        if (entries[entry.id]?.msgHash == entry.msgHash) entries.remove(entry.id)
        persistRemoval(entry)
    }

    private fun persistRemoval(entry: OwedEntry) {
        scope.launch {
            try {
                store.remove(entry.id, entry.msgHash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Storage did not take the removal of ${entry.label}: ${e.message}")
            }
        }
    }

    /**
     * Whether [entry] is still owed as it is: the message owed under its id now
     * (not replaced by a later action, not yet proved), and, for a membership
     * message, owed to the distro this device holds now. A sent-copy is owed to
     * the distro it was made for whatever this device holds now (James,
     * 2026-10-03). What a flush listed, or an upload was built from, may no
     * longer be, since a build yields.
     */
    private fun stillOwed(entry: OwedEntry): Boolean {
        if (entries[entry.id]?.msgHash != entry.msgHash) return false
        if (entry.kind != OwedKind.CHANNEL) return true
        return heldDistro()?.delivery == entry.distro
    }

    // ---- A distro given up ----

    /**
     * James, 2026-10-03 (DESIGN_PRINCIPLES §3, what a device owes its distro):
     * when this device forgets its distro D or replaces it, the membership
     * messages it owed D are dropped, with a log line for each. One is never kept
     * in case D comes back: it would go then, after changes the user made without
     * D that no message reported (a leave of the same channel, say), and the
     * siblings would join a channel the user had left. Nor is it ever sent under
     * another distro. Its sent-copies are not dropped: each is already packed and
     * signed as D, with D's key in its entry, and is still uploaded to D, the same
     * bytes, on later comings-up, never addressed to another distro or packed
     * again. Runs on every change of the distro held ([onDistroChanged]), at
     * [start], and before each flush.
     *
     * The line says what is true: this device sends it no more. One whose upload
     * has already left and is not decided cannot be taken back, and the line says
     * that too, because the node may prove that upload and fan it out.
     */
    fun dropMembershipOwedToOtherDistros() {
        val held = heldDistro()?.delivery
        val dropped = entries.values.filter { it.kind == OwedKind.CHANNEL && it.distro != held }
        for (entry in dropped) {
            entries.remove(entry.id)
            unproved.forget(entry)
            persistRemoval(entry)
            val flight = inFlight[entry.id]
            val onTheWire = flight?.msgHash == entry.msgHash && flight.upload != null && flight.upload!!.settled == null
            log.warn(
                "${entry.label} was owed to ${entry.distro.take(8)}, a distro this device no longer holds — dropped: this device sends it no more" +
                    if (onTheWire) {
                        "; one upload of it has already left on the propagation link and cannot be taken back: if the propagation node proves it, the distro's devices get it (§17.12)"
                    } else {
                        " (§17.12)"
                    },
            )
        }
    }

    /** The distro this device holds changed (forgotten, generated or imported). */
    fun onDistroChanged() = dropMembershipOwedToOtherDistros()

    // ---- Saying how it ended ----

    /**
     * Say that the upload of the owed [entry] never left the device (the link was
     * up and the packet could not be queued on it): nothing was sent, so this is
     * no failure. The log says so, as a line and not a warning or an error: the
     * client shows the user an error only for an upload that failed, and none is
     * due. The entry stays owed, and goes when an interface comes back online
     * ([onInterfaceOnline]) or at the propagation link's next coming-up, whichever
     * is first (James, 2026-10-06, DESIGN_PRINCIPLES §3, what a device owes its
     * distro). One owed no more by now (proved by another upload, replaced by a
     * later action, or dropped with its distro) says that instead.
     */
    private fun distroOwedNeverLeft(entry: OwedEntry) {
        if (!stillOwed(entry)) {
            log.info("An upload of ${entry.label} never left the device, and it is owed no more (proved by another upload, replaced by a later action, or dropped with its distro): nothing goes again")
            return
        }
        log.info("${entry.label} never left the device (its packet could not be queued on the propagation link): nothing was sent, so it is no failure — it goes when an interface comes back online, or when the propagation link next comes up, whichever is first (DESIGN_PRINCIPLES §3, the distro exception)")
    }

    /**
     * Say how the upload of the owed [entry] ended: proved ([error] null, sent
     * as [how], "packet" or "resource"), or not ([error]). The log says
     * "propagated" only for a proof (CHECK_THESE_THINGS_FIRST §14), and an
     * upload lost or cut while the entry is still owed is an error the client
     * can show. It then goes on the propagation link's next coming-up, or, with
     * [goesNow], at once on a newer link that replaced the one it was on. One
     * whose entry is owed no more by then (another upload of it was proved, a
     * later action replaced it, or its membership message was dropped with its
     * distro) failed no one: a log line says so, and neither "still owed" nor an
     * error.
     */
    private fun distroOwedOutcome(entry: OwedEntry, how: String?, error: String?, goesNow: Boolean = false) {
        if (error != null && !stillOwed(entry)) {
            log.info("An upload of ${entry.label} was not proved ($error), but it is owed no more (proved by another upload, replaced by a later action, or dropped with its distro): nothing goes again")
            return
        }
        if (error != null) {
            log.error(
                "${entry.label} was not proved by the propagation node: $error — still owed: " +
                    if (goesNow) {
                        "the propagation link it went on was replaced, and the newer one is up, so it goes once on that one now (DESIGN_PRINCIPLES §3, the distro exception)"
                    } else {
                        "it goes when the propagation link is next up (DESIGN_PRINCIPLES §3, the distro exception)"
                    },
            )
            return
        }
        if (entry.kind == OwedKind.CHANNEL) {
            log.info("The ${entry.op} of #${entry.name} (at ${entry.atMs}) propagated to the distro as a $how: the propagation node proved it (§17.12)")
        } else {
            log.info("Sent-copy for ${entry.toHex?.take(8)} propagated to the distro as a $how: the propagation node proved it (§17.11)")
        }
    }
}
