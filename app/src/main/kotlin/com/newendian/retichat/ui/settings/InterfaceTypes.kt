package com.newendian.retichat.ui.settings

/**
 * Describes one supported Reticulum interface type and which fields it needs.
 */
data class InterfaceTypeSpec(
    val type: String,
    val label: String,
    val fields: List<FieldSpec>,
)

data class FieldSpec(
    val key: String,
    val label: String,
    val hint: String = "",
    val isNumber: Boolean = false,
    val default: String = "",
)

/** Registry of all interface types the app can configure. */
object InterfaceTypes {

    val TCP_CLIENT = InterfaceTypeSpec(
        type = "TCPClientInterface",
        label = "TCP Client",
        fields = listOf(
            FieldSpec("target_host", "Host", hint = "192.168.1.1"),
            FieldSpec("target_port", "Port", hint = "4242", isNumber = true, default = "4242"),
        ),
    )

    val TCP_SERVER = InterfaceTypeSpec(
        type = "TCPServerInterface",
        label = "TCP Server",
        fields = listOf(
            FieldSpec("listen_ip", "Listen IP", hint = "0.0.0.0", default = "0.0.0.0"),
            FieldSpec("listen_port", "Listen Port", hint = "4242", isNumber = true, default = "4242"),
        ),
    )

    val UDP = InterfaceTypeSpec(
        type = "UDPInterface",
        label = "UDP",
        fields = listOf(
            FieldSpec("listen_ip", "Listen IP", hint = "0.0.0.0", default = "0.0.0.0"),
            FieldSpec("listen_port", "Listen Port", hint = "4242", isNumber = true, default = "4242"),
            FieldSpec("forward_ip", "Forward IP", hint = "255.255.255.255"),
            FieldSpec("forward_port", "Forward Port", hint = "4242", isNumber = true, default = "4242"),
        ),
    )

    val AUTO = InterfaceTypeSpec(
        type = "AutoInterface",
        label = "Auto (LAN Discovery)",
        fields = listOf(
            FieldSpec("group_id", "Group ID", hint = "reticulum", default = "reticulum"),
        ),
    )

    val I2P = InterfaceTypeSpec(
        type = "I2PInterface",
        label = "I2P",
        fields = listOf(
            FieldSpec("peers", "Peers (comma-separated)", hint = "hash.b32.i2p"),
        ),
    )

    val ALL = listOf(TCP_CLIENT, TCP_SERVER, UDP, AUTO, I2P)

    fun forType(type: String): InterfaceTypeSpec? = ALL.find { it.type == type }
}
