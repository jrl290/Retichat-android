package com.newendian.retichat.data.repository

import com.newendian.retichat.data.db.entity.MessageEntity

/**
 * Reactions (REACTIONS-DESIGN.md): what a message's `reactionsJson` holds,
 * `apply_reaction`'s ordering rule, the chips under a bubble, and which
 * messages can take a reaction. Pure logic, so the JVM tests run it;
 * ChatRepository reads and writes the rows.
 *
 * One reaction per reactor per message (James, 2026-10-17): picking another
 * replaces it, picking the same one again removes it. A removal is kept as
 * `"e": ""` so that an older reaction arriving late cannot bring it back.
 */
object Reactions {
    /** The quick reactions the popup offers (§7). Any received one (up to 64 bytes) is shown as it is. */
    val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    /** A reactor's reaction: [emoji] ("" a removal) as of [atMs], the reacting message's LXMF timestamp. */
    data class Entry(val emoji: String, val atMs: Long)

    /**
     * A reaction a send carries (§2, §4): field 0x40 with [to], the target's
     * 32-byte LXMF hash (in a Group its 16-byte post id), and [content], ""
     * removing the sender's one. Written with RetichatBridge.messageSetReaction.
     */
    class Outgoing(val to: ByteArray, val content: String)

    /** A chip under a bubble: [emoji] with the [count] of its reactors, [mine] when one of them is the user. */
    data class Chip(val emoji: String, val count: Int, val mine: Boolean)

    private val HEX32 = Regex("^[0-9a-f]{32}$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /**
     * `apply_reaction(target, reactor, emoji, at)` (§3): the reactions [json]
     * with [reactor]'s set to [emoji] as of [atMs], or null when nothing
     * changes because the reactor already has one as new or newer (`old.at >=
     * at`: an older or repeated reaction is ignored). [emoji] "" is kept as a
     * removal.
     */
    fun apply(json: String?, reactor: String, emoji: String, atMs: Long): String? {
        val map = decode(json).toMutableMap()
        val key = reactor.lowercase()
        val old = map[key]
        if (old != null && old.atMs >= atMs) return null
        map[key] = Entry(emoji, atMs)
        return encode(map)
    }

    /** [reactor]'s current reaction in [json], "" when none (or removed). */
    fun of(json: String?, reactor: String): String = decode(json)[reactor.lowercase()]?.emoji.orEmpty()

    /** What the user's pick of [picked] makes theirs, from [current]: the same one again removes it. */
    fun toggled(current: String, picked: String): String = if (current == picked) "" else picked

    /**
     * The chips under a bubble (§3 "Display"): the reactions grouped by
     * non-empty emoji with their counts, in the order each emoji was first
     * given, [Chip.mine] when one of its reactors is in [me] (any address the
     * user sends this chat as).
     */
    fun chips(json: String?, me: Set<String>): List<Chip> {
        val mine = me.map { it.lowercase() }.toSet()
        return decode(json).entries
            .filter { it.value.emoji.isNotEmpty() }
            .groupBy { it.value.emoji }
            .map { (emoji, reactors) ->
                Triple(Chip(emoji, reactors.size, reactors.any { it.key in mine }), reactors.minOf { it.value.atMs }, emoji)
            }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }
    }

    /**
     * The LXMF hash a direct-chat reaction names [msg] by (§2): its
     * [MessageEntity.lxmfHash], or its id where the id is the hash (a received
     * direct message). Null when it has neither: an outgoing message whose send
     * has no hash yet, or a row from before database 17 filed under a derived id.
     */
    fun targetHash(msg: MessageEntity): String? =
        msg.lxmfHash?.lowercase()?.takeIf { HEX64.matches(it) } ?: msg.id.takeIf { HEX64.matches(it) }

    /**
     * Whether [msg] gets the reaction row (§7): never a system message; in a
     * Group only a post with a post id; in a direct chat only a message a hash
     * can name ([targetHash]). Channel posts are another table and never get it.
     */
    fun canReact(isGroup: Boolean, msg: MessageEntity): Boolean = when {
        msg.systemKind != null -> false
        isGroup -> msg.postId?.let { HEX32.matches(it) } == true
        else -> targetHash(msg) != null
    }

    /** A new Group post id (§4): 16 random bytes from [random], 32 lowercase hex. */
    fun newPostId(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---- The stored form: {"<reactor 32 hex>": {"e": "👍", "t": <ms>}} ----

    /** The reactions [json] holds, by reactor. Anything unreadable reads as none: a reaction is never worth a crash. */
    fun decode(json: String?): Map<String, Entry> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val parsed = Json(json).document() as? Map<*, *> ?: return emptyMap()
            buildMap {
                for ((reactor, value) in parsed) {
                    val o = value as? Map<*, *> ?: continue
                    val e = o["e"] as? String ?: continue
                    val t = (o["t"] as? Number)?.toLong() ?: continue
                    put((reactor as String).lowercase(), Entry(e, t))
                }
            }
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    /** [map] as stored, its reactors in order so the same reactions are the same text. */
    fun encode(map: Map<String, Entry>): String = buildString {
        append('{')
        map.entries.sortedBy { it.key }.forEachIndexed { i, (reactor, entry) ->
            if (i > 0) append(',')
            string(reactor)
            append(":{\"e\":")
            string(entry.emoji)
            append(",\"t\":").append(entry.atMs).append('}')
        }
        append('}')
    }

    private fun StringBuilder.string(s: String) {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * A small JSON reader for the stored form (org.json is not on the JVM the
     * tests run on): objects, arrays, strings with their escapes, numbers,
     * true, false and null. Malformed text throws IllegalArgumentException.
     */
    private class Json(private val s: String) {
        private var i = 0

        fun document(): Any? {
            val v = value()
            space()
            require(i == s.length) { "text after the value" }
            return v
        }

        private fun space() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun value(): Any? {
            space()
            require(i < s.length) { "no value" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        private fun word(w: String, v: Any?): Any? {
            require(s.startsWith(w, i)) { "bad literal" }
            i += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            space()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                space()
                require(i < s.length && s[i] == '"') { "a key" }
                val k = str()
                space()
                require(i < s.length && s[i] == ':') { "a colon" }
                i++
                out[k] = value()
                space()
                require(i < s.length) { "an unclosed object" }
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("a comma or a brace")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            space()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                space()
                require(i < s.length) { "an unclosed array" }
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("a comma or a bracket")
                }
            }
        }

        private fun str(): String {
            i++
            val out = StringBuilder()
            while (true) {
                require(i < s.length) { "an unclosed string" }
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(i < s.length) { "an escape" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "a \\u escape" }
                                out.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException("a \\u escape"))
                                i += 4
                            }
                            else -> throw IllegalArgumentException("an escape")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw IllegalArgumentException("a number")
        }
    }
}
