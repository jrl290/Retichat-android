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

    val ALL = listOf(TCP_CLIENT)

    fun forType(type: String): InterfaceTypeSpec? = ALL.find { it.type == type }
}
