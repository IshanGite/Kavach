package com.kavach.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppEntry(val `package`: String, val name: String)

/**
 * Which apps are payment apps, remote-access apps, VoIP call apps, and what a PIN screen looks like.
 * Lives in payment_apps.json so a new app can be added on the demo phone without a code change.
 */
@Serializable
data class AppConfig(
    @SerialName("payment_apps") val paymentApps: List<AppEntry> = emptyList(),
    @SerialName("remote_access_apps") val remoteAccessApps: List<AppEntry> = emptyList(),
    @SerialName("voip_call_apps") val voipCallApps: List<AppEntry> = emptyList(),
    @SerialName("pin_screen_hints") val pinScreenHints: List<String> = emptyList(),
) {
    private val payment = paymentApps.associate { it.`package` to it.name }
    private val remote = remoteAccessApps.associate { it.`package` to it.name }
    private val voip = voipCallApps.associate { it.`package` to it.name }

    fun paymentAppName(pkg: String?): String? = payment[pkg]
    fun remoteAccessName(pkg: String?): String? = remote[pkg]
    fun voipName(pkg: String?): String? = voip[pkg]

    companion object {
        private val json = Json { ignoreUnknownKeys = true } // "_comment" keys are allowed here, unlike risk_config

        fun parse(text: String): AppConfig = json.decodeFromString(serializer(), text)

        fun loadDefault(): AppConfig =
            AppConfig::class.java.classLoader.getResourceAsStream("payment_apps.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { parse(it.readText()) } ?: AppConfig()
    }
}
