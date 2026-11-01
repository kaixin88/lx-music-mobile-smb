package cn.toside.music.mobile.smb

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.protocol.commons.Factory
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.Authenticator
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File as JavaFile
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

private const val TAG = "SmbManager"
private const val BUFFER_SIZE = 256 * 1024

/**
 * SMB2/SMB3 客户端管理器：负责连接的建立、复用与销毁。
 * 非线程安全的多路复用由调用方串行化即可（模块层使用单线程池）。
 */
class SmbManager {

    private var cacheKey: String? = null
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    @Synchronized
    fun connect(cfg: SmbConfigData): DiskShare {
        val key = cfg.cacheKey()
        val cachedShare = share
        if (cachedShare != null && key == cacheKey && connection?.isConnected == true) {
            return cachedShare
        }
        closeInternal()

        if (cfg.host.isBlank()) {
            throw IllegalArgumentException("SMB 服务器地址不能为空")
        }
        if (cfg.share.isBlank()) {
            throw IllegalArgumentException("SMB 共享名不能为空")
        }

        var lastError: Throwable? = null
        for (attempt in 0..2) {
            try {
                return doConnect(cfg, key, attempt)
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "SMB connect attempt $attempt failed: ${t.message}")
                closeInternal()
            }
        }
        throw lastError ?: IllegalStateException("SMB 连接失败")
    }

    /**
     * 逐级降级连接：
     * 0 - 默认（SPNEGO 协商 + 全部 SMB 版本）
     * 1 - 仅 NTLM 认证 + 全部 SMB 版本
     * 2 - 仅 NTLM 认证 + 仅 SMB2（部分 NAS 的 SMB3 密钥派生在 Android 上会失败）
     */
    private fun doConnect(cfg: SmbConfigData, key: String, attempt: Int): DiskShare {
        val builder = SmbConfig.builder()
            .withTimeout(60L, TimeUnit.SECONDS)
            .withSoTimeout(180L, TimeUnit.SECONDS)
            .withDfsEnabled(false)
        if (attempt >= 1) {
            val ntlmOnly: Iterable<Factory.Named<Authenticator>> =
                listOf(NtlmAuthenticator.Factory())
            builder.withAuthenticators(ntlmOnly)
        }
        if (attempt >= 2) {
            builder.withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
        }
        val newClient = SMBClient(builder.build())
        val newConnection: Connection = newClient.connect(cfg.host, cfg.port)
        val auth = if (cfg.username.isNullOrBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(
                cfg.username,
                (cfg.password ?: "").toCharArray(),
                if (cfg.domain.isNullOrBlank()) null else cfg.domain,
            )
        }
        val newSession: Session = newConnection.authenticate(auth)
        val connectedShare = newSession.connectShare(cfg.share)
        if (connectedShare !is DiskShare) {
            throw IllegalArgumentException("共享 ${cfg.share} 不是可用的磁盘共享")
        }
        client = newClient
        connection = newConnection
        session = newSession
        share = connectedShare
        cacheKey = key
        return connectedShare
    }

    /** 执行一次 SMB 操作，失败时丢弃缓存连接并重试一次 */
    fun <T> execute(cfg: SmbConfigData, action: (DiskShare) -> T): T {
        return try {
            action(connect(cfg))
        } catch (e: Throwable) {
            Log.w(TAG, "SMB operation failed, retry once", e)
            close()
            action(connect(cfg))
        }
    }

    @Synchronized
    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        try {
            share?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close share failed", t)
        }
        try {
            session?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close session failed", t)
        }
        try {
            connection?.close(true)
        } catch (t: Throwable) {
            Log.w(TAG, "close connection failed", t)
        }
        try {
            client?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close client failed", t)
        }
        share = null
        session = null
        connection = null
        client = null
        cacheKey = null
    }

    // ---------- 基础能力 ----------

    fun normalizePath(path: String?): String {
        val trimmed = (path ?: "").trim()
            .replace('\\', '/')
            .trim('/')
        return trimmed.split('/')
            .filter { it.isNotBlank() && it != "." }
            .joinToString("/")
    }

    fun exists(share: DiskShare, path: String): Boolean {
        val p = normalizePath(path)
        if (p.isEmpty()) return true
        return share.fileExists(p) || share.folderExists(p)
    }

    fun mkdirs(share: DiskShare, path: String) {
        val p = normalizePath(path)
        if (p.isEmpty()) return
        val parts = p.split('/')
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current/$part"
            if (!share.folderExists(current)) {
                share.mkdir(current)
            }
        }
    }

    fun listDir(share: DiskShare, path: String): List<Map<String, Any?>> {
        val p = normalizePath(path)
        val result = mutableListOf<Map<String, Any?>>()
        for (item in share.list(p)) {
            val name = item.fileName
            if (name == "." || name == "..") continue
            val attrs = item.fileAttributes
            val isDir = (attrs and 0x10L) != 0L
            result.add(
                mapOf(
                    "name" to name,
                    "path" to (if (p.isEmpty()) name else "$p/$name"),
                    "isDirectory" to isDir,
                    "size" to item.endOfFile,
                    "lastModified" to item.lastWriteTime?.toEpochMillis(),
                ),
            )
        }
        return result
    }

    fun stat(share: DiskShare, path: String): Map<String, Any?> {
        val p = normalizePath(path)
        if (p.isEmpty()) {
            return mapOf("exists" to true, "isDirectory" to true, "size" to 0L)
        }
        val isDir = share.folderExists(p)
        val isFile = share.fileExists(p)
        if (!isDir && !isFile) {
            return mapOf("exists" to false, "isDirectory" to false, "size" to 0L)
        }
        var size = 0L
        if (isFile) {
            try {
                size = share.getFileInformation(p).standardInformation.endOfFile
            } catch (t: Throwable) {
                Log.w(TAG, "stat size failed", t)
            }
        }
        return mapOf("exists" to true, "isDirectory" to isDir, "size" to size)
    }

    fun readText(share: DiskShare, path: String): String {
        val p = normalizePath(path)
        val file: SmbFile = share.openFile(
            p,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        return file.inputStream.use { input ->
            BufferedInputStream(input).use { buffered ->
                buffered.readBytes().toString(Charsets.UTF_8)
            }
        }.also {
            try {
                file.close()
            } catch (t: Throwable) {
                Log.w(TAG, "close file failed", t)
            }
        }
    }

    fun writeText(share: DiskShare, path: String, content: String) {
        val p = normalizePath(path)
        ensureParentDir(share, p)
        val file: SmbFile = share.openFile(
            p,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null,
        )
        try {
            BufferedOutputStream(file.outputStream).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } finally {
            try {
                file.close()
            } catch (t: Throwable) {
                Log.w(TAG, "close file failed", t)
            }
        }
    }

    fun upload(
        share: DiskShare,
        localPath: String,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        val p = normalizePath(remotePath)
        ensureParentDir(share, p)
        val localFile = JavaFile(localPath)
        val total = localFile.length()
        val file: SmbFile = share.openFile(
            p,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null,
        )
        try {
            FileInputStream(localFile).use { rawInput ->
                BufferedInputStream(rawInput, BUFFER_SIZE).use { input ->
                    BufferedOutputStream(file.outputStream, BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var transferred = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            transferred += read
                            onProgress?.invoke(transferred, total)
                        }
                        output.flush()
                    }
                }
            }
        } finally {
            try {
                file.close()
            } catch (t: Throwable) {
                Log.w(TAG, "close file failed", t)
            }
        }
    }

    fun download(
        share: DiskShare,
        remotePath: String,
        localPath: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Long {
        val p = normalizePath(remotePath)
        val localFile = JavaFile(localPath)
        localFile.parentFile?.let {
            if (!it.exists()) it.mkdirs()
        }
        var total = 0L
        try {
            total = share.getFileInformation(p).standardInformation.endOfFile
        } catch (t: Throwable) {
            Log.w(TAG, "get file size failed", t)
        }
        val file: SmbFile = share.openFile(
            p,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        try {
            BufferedInputStream(file.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(localFile, false).use { rawOutput ->
                    BufferedOutputStream(rawOutput, BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var transferred = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            transferred += read
                            onProgress?.invoke(transferred, total)
                        }
                        output.flush()
                    }
                }
            }
        } finally {
            try {
                file.close()
            } catch (t: Throwable) {
                Log.w(TAG, "close file failed", t)
            }
        }
        return localFile.length()
    }

    fun delete(share: DiskShare, path: String) {
        val p = normalizePath(path)
        if (p.isEmpty()) return
        if (share.folderExists(p)) {
            share.rmdir(p, true)
        } else if (share.fileExists(p)) {
            share.rm(p)
        }
    }

    private fun ensureParentDir(share: DiskShare, remotePath: String) {
        val idx = remotePath.lastIndexOf('/')
        if (idx > 0) {
            mkdirs(share, remotePath.substring(0, idx))
        }
    }
}
