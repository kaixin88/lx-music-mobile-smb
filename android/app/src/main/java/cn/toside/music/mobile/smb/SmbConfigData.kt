package cn.toside.music.mobile.smb

import com.facebook.react.bridge.ReadableMap

/** SMB 连接配置 */
data class SmbConfigData(
    val host: String,
    val port: Int,
    val share: String,
    val username: String?,
    val password: String?,
    val domain: String?,
) {
    fun cacheKey(): String = "$host:$port/$share|$username|$domain"

    companion object {
        fun fromMap(map: ReadableMap?): SmbConfigData {
            val host = if (map?.hasKey("host") == true) map.getString("host") ?: "" else ""
            val port = if (map?.hasKey("port") == true && !map.isNull("port")) map.getInt("port") else 445
            val share = if (map?.hasKey("share") == true) map.getString("share") ?: "" else ""
            val username = if (map?.hasKey("username") == true) map.getString("username") else null
            val password = if (map?.hasKey("password") == true) map.getString("password") else null
            val domain = if (map?.hasKey("domain") == true) map.getString("domain") else null
            return SmbConfigData(
                host = host.trim(),
                port = if (port <= 0) 445 else port,
                share = share.trim().trim('/'),
                username = username,
                password = password,
                domain = domain,
            )
        }
    }
}
