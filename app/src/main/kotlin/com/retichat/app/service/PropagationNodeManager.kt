package com.retichat.app.service

import android.util.Log

/**
 * Manages a randomized list of LXMF propagation nodes with failover.
 *
 * On construction the node list is shuffled. The first node is the primary.
 * If a node becomes unreachable, [reportFailure] moves it to the back
 * of the list and advances to the next.
 */
class PropagationNodeManager {

    companion object {
        private const val TAG = "PropNodeManager"

        /** Known propagation node destination hashes (hex, 16 bytes each). */
        private val NODE_HASHES = listOf(
            "aaeebb110e42b4df1ed15a6f99b855fa",
            "fea3cd23a8f6354f0b3ea12b604f050e",
            "5005acdb06dda566acccd599b25dc886",
            "96dbd9d7f2e9b0c37ccb61cad4196719",
            "7b707b4de03d144eecc023bb2241327c",
            "521c87a83afb8f29e4455e77930b973b",
            "60863323592a15d2a036c61d2c970f31",
            "0196e8bac082854147ba0bec49cb5926",
            "8e6b99e5ee384a85caf76c45650f73fd",
            "7e23da247a06099f21fbdd88b419e7ca",
            "8d87449ad6bda5c3f627ac01219c2b4d",
            "8033985094ee08a65bdb7a1cb82fd11c",
            "774b4cf1ca23035491d858132e242967",
            "486facd9b8fe5c79d47a236ce42eae23",
            "9f6793f48649c03f8d93f6a5cce2cc3f",
            "54ab8cbc6e7ec4c25c6651508c8d5b52",
            "9dc24ee854b1d0809f545e5273bf9226",
        )
    }

    /** Mutable ordered list — index 0 is always the current primary. */
    private val nodes: MutableList<ByteArray>

    init {
        // Deduplicate and shuffle
        val unique = NODE_HASHES.distinct().map { hexToBytes(it) }
        nodes = unique.shuffled().toMutableList()
        Log.i(TAG, "Initialised with ${nodes.size} nodes, primary=${bytesToHex(nodes.first())}")
    }

    /** The current primary propagation node hash (16 bytes). */
    val primaryNode: ByteArray
        get() = nodes.first()

    /** Number of available nodes. */
    val size: Int
        get() = nodes.size

    /**
     * Report that the current primary node failed.
     * Moves it to the back of the list and returns the new primary.
     */
    fun reportFailure(): ByteArray {
        if (nodes.size > 1) {
            val failed = nodes.removeAt(0)
            nodes.add(failed)
            Log.w(TAG, "Node ${bytesToHex(failed)} failed, rotating to ${bytesToHex(nodes.first())}")
        } else {
            Log.w(TAG, "Only one node available — cannot rotate")
        }
        return nodes.first()
    }

    /** Report success — the current primary stays at index 0. */
    fun reportSuccess() {
        Log.d(TAG, "Node ${bytesToHex(nodes.first())} succeeded")
    }

    // ---- Hex helpers ----

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
