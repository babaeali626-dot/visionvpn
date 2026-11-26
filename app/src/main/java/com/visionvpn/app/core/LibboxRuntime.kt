package com.visionvpn.app.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Exclusive ownership states for the single process-wide Hiddify Core native runtime. */
enum class LibboxLeaseMode {
    IDLE,
    VPN_STARTING,
    VPN_ACTIVE,
    BLACKHOLE_STARTING,
    BLACKHOLE_ACTIVE,
    HEADLESS_PROBE,
    FAULTED
}

/**
 * Process-wide lifecycle coordinator for Hiddify Core (`Libbox` v5).
 * A lease is retained until the owner confirms that every native service and descriptor is closed.
 * Cleanup failures quarantine the runtime in [FAULTED] instead of admitting another native service.
 */
object LibboxRuntime {

    private const val MOBILE_CLASS = "com.hiddify.core.mobile.Mobile"
    private const val SETUP_OPTIONS_CLASS = "com.hiddify.core.mobile.SetupOptions"

    private val setupLock = Any()
    private val stateLock = Any()
    private val probeAdmissionMutex = Mutex()

    @Volatile
    private var initialized: Boolean = false

    private var mode: LibboxLeaseMode = LibboxLeaseMode.IDLE
    private var activeLeaseToken: Long = 0L
    private var nextTokenCounter: Long = 1L
    private var blockingTunInstalled: Boolean = false

    fun isInitialized(): Boolean = initialized

    fun currentMode(): LibboxLeaseMode = synchronized(stateLock) { mode }

    fun isVpnCoreActive(): Boolean = synchronized(stateLock) {
        mode == LibboxLeaseMode.VPN_ACTIVE
    }

    fun isVpnCoreActiveOrStarting(): Boolean = synchronized(stateLock) {
        mode == LibboxLeaseMode.VPN_STARTING || mode == LibboxLeaseMode.VPN_ACTIVE
    }

    /** True when the Android TUN is intentionally retained as a no-forwarding blackhole. */
    fun isBlockingTunInstalled(): Boolean = synchronized(stateLock) { blockingTunInstalled }

    /** Records that the currently owned blocking descriptor was explicitly closed. */
    fun clearBlockingTun(token: Long): Boolean = synchronized(stateLock) {
        if (activeLeaseToken != token) return false
        blockingTunInstalled = false
        true
    }

    fun tryAcquireVpnLease(): Long? = synchronized(stateLock) {
        if (mode != LibboxLeaseMode.IDLE) return null
        val token = nextTokenCounter++
        mode = LibboxLeaseMode.VPN_STARTING
        activeLeaseToken = token
        blockingTunInstalled = false
        token
    }

    fun markVpnLeaseActive(token: Long): Boolean = synchronized(stateLock) {
        if (activeLeaseToken != token || mode != LibboxLeaseMode.VPN_STARTING) return false
        mode = LibboxLeaseMode.VPN_ACTIVE
        true
    }

    /**
     * Begins an atomic handover from the live VPN interface to a replacement blocking TUN.
     * Headless probes and new VPN starts are denied from this point onward.
     */
    fun beginBlackholeTransition(token: Long): Boolean = synchronized(stateLock) {
        if (activeLeaseToken != token || mode != LibboxLeaseMode.VPN_ACTIVE) return false
        mode = LibboxLeaseMode.BLACKHOLE_STARTING
        blockingTunInstalled = false
        true
    }

    fun markBlackholeActive(token: Long): Boolean = synchronized(stateLock) {
        if (activeLeaseToken != token || mode != LibboxLeaseMode.BLACKHOLE_STARTING) return false
        mode = LibboxLeaseMode.BLACKHOLE_ACTIVE
        blockingTunInstalled = true
        true
    }

    /**
     * Quarantines a lease after native cleanup or a protected TUN transition fails.
     * [blockingTunIsInstalled] must reflect the actual descriptor currently owned by the bridge.
     */
    fun markFaulted(token: Long, blockingTunIsInstalled: Boolean? = null): Boolean = synchronized(stateLock) {
        if (activeLeaseToken != token || mode == LibboxLeaseMode.IDLE) return false
        mode = LibboxLeaseMode.FAULTED
        if (blockingTunIsInstalled != null) {
            this.blockingTunInstalled = blockingTunIsInstalled
        }
        true
    }

    /** Release only after owner has closed the native service and all TUN descriptors. */
    fun releaseVpnLease(token: Long): Boolean = synchronized(stateLock) {
        if (activeLeaseToken != token) return false
        // A blackhole interface is the protection itself. Its owner may release the VPN lease
        // only after LibboxBridge has confirmed that the blocking descriptor is closed.
        if (blockingTunInstalled) return false
        if (mode !in setOf(
                LibboxLeaseMode.VPN_STARTING,
                LibboxLeaseMode.VPN_ACTIVE,
                LibboxLeaseMode.BLACKHOLE_STARTING,
                LibboxLeaseMode.BLACKHOLE_ACTIVE,
                LibboxLeaseMode.FAULTED
            )
        ) return false
        mode = LibboxLeaseMode.IDLE
        activeLeaseToken = 0L
        blockingTunInstalled = false
        true
    }

    fun tryAcquireHeadlessProbeLease(): Long? = synchronized(stateLock) {
        if (mode != LibboxLeaseMode.IDLE) return null
        val token = nextTokenCounter++
        mode = LibboxLeaseMode.HEADLESS_PROBE
        activeLeaseToken = token
        blockingTunInstalled = false
        token
    }

    fun releaseHeadlessProbeLease(token: Long): Boolean = synchronized(stateLock) {
        if (activeLeaseToken != token || mode != LibboxLeaseMode.HEADLESS_PROBE) return false
        mode = LibboxLeaseMode.IDLE
        activeLeaseToken = 0L
        true
    }

    /** An unrecoverable Headless `close()` failure keeps its lease and blocks all future Libbox use. */
    fun quarantineHeadlessProbeLease(token: Long): Boolean = synchronized(stateLock) {
        if (activeLeaseToken != token || mode != LibboxLeaseMode.HEADLESS_PROBE) return false
        mode = LibboxLeaseMode.FAULTED
        blockingTunInstalled = false
        true
    }

    suspend fun <T> withHeadlessProbeLease(block: suspend (token: Long) -> T): Result<T> {
        return probeAdmissionMutex.withLock {
            val token = tryAcquireHeadlessProbeLease()
                ?: return@withLock Result.failure(
                    IllegalStateException(
                        "Libbox is busy or quarantined. Disconnect the VPN or restart VisionVPN after a native cleanup failure."
                    )
                )
            try {
                Result.success(block(token))
            } catch (ce: CancellationException) {
                releaseHeadlessProbeLease(token)
                throw ce
            } catch (fatal: LibboxCleanupException) {
                quarantineHeadlessProbeLease(token)
                Result.failure(fatal)
            } catch (error: Error) {
                quarantineHeadlessProbeLease(token)
                throw error
            } catch (t: Throwable) {
                releaseHeadlessProbeLease(token)
                Result.failure(t)
            } finally {
                // Normal results and ordinary probe failures release the lease. A fatal cleanup
                // exception has already quarantined it, so this token-checked release is rejected.
                releaseHeadlessProbeLease(token)
            }
        }
    }

    fun ensureInitialized(
        context: Context,
        platformInterfaceClass: Class<*>,
        platformInterface: Any
    ) {
        if (initialized) return
        synchronized(setupLock) {
            if (initialized) return
            val baseDir = File(context.filesDir, "hiddify-core").apply { mkdirs() }
            val workDir = File(baseDir, "work").apply { mkdirs() }
            val tempDir = File(context.cacheDir, "hiddify-core_tmp").apply { mkdirs() }

            val optionsClass = Class.forName(SETUP_OPTIONS_CLASS)
            val mobileClass = Class.forName(MOBILE_CLASS)
            val options = optionsClass.getDeclaredConstructor().newInstance()
            optionsClass.getMethod("setBasePath", String::class.java).invoke(options, baseDir.absolutePath)
            optionsClass.getMethod("setWorkingDir", String::class.java).invoke(options, workDir.absolutePath)
            optionsClass.getMethod("setTempDir", String::class.java).invoke(options, tempDir.absolutePath)
            // SetupMode.OLD is the zero-value default. It keeps this direct-libbox integration
            // local and does not start Hiddify's optional gRPC control listener. The Android
            // platform adapter is still supplied because Mobile.setup requires it in Core v5.
            // This flag is part of com.hiddify.core.mobile.SetupOptions in Core v5.
            // It must be applied through Mobile.setup(), not the removed legacy Libbox.setup().
            optionsClass.getMethod("setFixAndroidStack", Boolean::class.javaPrimitiveType!!)
                .invoke(options, true)
            mobileClass.getMethod("setup", optionsClass, platformInterfaceClass)
                .invoke(null, options, platformInterface)
            initialized = true
        }
    }

    internal fun ensureInitializedWithRunner(setupAction: () -> Unit) {
        if (initialized) return
        synchronized(setupLock) {
            if (initialized) return
            setupAction()
            initialized = true
        }
    }

    internal fun resetForTesting() {
        synchronized(setupLock) { initialized = false }
        synchronized(stateLock) {
            mode = LibboxLeaseMode.IDLE
            activeLeaseToken = 0L
            blockingTunInstalled = false
            nextTokenCounter = 1L
        }
    }
}

/** Signals that a native Libbox service could not be confirmed closed; its lease must be retained. */
class LibboxCleanupException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
