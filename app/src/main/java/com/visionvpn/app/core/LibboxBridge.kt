package com.visionvpn.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.visionvpn.app.BuildConfig
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.SplitTunnelMode
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

class LibboxBridge(private val context: Context) {

    companion object {
        private const val TAG = "LibboxBridge"
        const val SUPPORTED_SING_BOX_VERSION = BuildConfig.SING_BOX_PINNED_VERSION
        private const val LIBBOX_CLASS = "io.nekohasekai.libbox.Libbox"
        private const val PLATFORM_INTERFACE_CLASS = "io.nekohasekai.libbox.PlatformInterface"
        private const val STRING_ITERATOR_CLASS = "io.nekohasekai.libbox.StringIterator"
        private const val NETWORK_INTERFACE_CLASS = "io.nekohasekai.libbox.NetworkInterface"
        private const val NETWORK_INTERFACE_ITERATOR_CLASS = "io.nekohasekai.libbox.NetworkInterfaceIterator"

        /**
         * Result of validating per-app split-tunnel configuration before calling `VpnService.Builder.establish()`.
         */
        data class SplitTunnelPlan(
            val allowedPackages: List<String> = emptyList(),
            val disallowedPackages: List<String> = emptyList()
        )

        /**
         * Validates and resolves the exact Android `VpnService.Builder` allow/disallow package lists.
         *
         * - [SplitTunnelMode.DISABLED]: disallows [selfPackageName] so Sing-box and child local
         *   engines (`libtor.so`, `libpsiphon.so`, which inherit the app UID) never loop into `tun0`.
         * - [SplitTunnelMode.BYPASS_SELECTED]: disallows [selfPackageName] plus valid external packages.
         * - [SplitTunnelMode.ONLY_SELECTED]: deduplicates, removes [selfPackageName], and verifies
         *   installed packages (when [installedPackages] is provided). FAILS CLOSED if zero valid
         *   external packages remain instead of falling back to `addDisallowedApplication` (which
         *   on Android would silently widen the tunnel to all other apps).
         */
        fun resolveSplitTunnelPlan(
            mode: SplitTunnelMode,
            configuredPackages: Set<String>,
            selfPackageName: String,
            installedPackages: Set<String>? = null
        ): Result<SplitTunnelPlan> = runCatching {
            val cleaned = configuredPackages
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != selfPackageName }
                .distinct()
                .filter { pkg -> installedPackages == null || pkg in installedPackages }
                .toList()

            when (mode) {
                SplitTunnelMode.DISABLED -> SplitTunnelPlan(
                    disallowedPackages = listOf(selfPackageName)
                )
                SplitTunnelMode.BYPASS_SELECTED -> SplitTunnelPlan(
                    disallowedPackages = (listOf(selfPackageName) + cleaned).distinct()
                )
                SplitTunnelMode.ONLY_SELECTED -> {
                    require(cleaned.isNotEmpty()) {
                        "Only Selected split-tunnel mode requires at least one valid installed external app. " +
                            "Select an app in Settings or switch Split Tunneling to Off/Bypass."
                    }
                    SplitTunnelPlan(allowedPackages = cleaned)
                }
            }
        }

    }

    private val lock = Any()
    private var activeService: Any? = null
    private var activeLeaseToken: Long? = null
    private var activeTunFd: ParcelFileDescriptor? = null
    /** Old descriptors retained during a successful Android VPN-interface handover until closed. */
    private val retiredTunFds = mutableListOf<ParcelFileDescriptor>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var currentDefaultNetwork: Network? = null

    fun startCore(
        configJson: String,
        vpnService: VpnService,
        settings: AppSettings
    ): Result<Unit> = runCatching {
        synchronized(lock) {
            check(LibboxRuntime.currentMode() == LibboxLeaseMode.IDLE) {
                "Libbox runtime is quarantined or still owns a protected tunnel. Explicitly Disconnect before reconnecting."
            }
            stopCoreLocked().getOrThrow()
            val leaseToken = LibboxRuntime.tryAcquireVpnLease()
                ?: error("Cannot start VPN core while another Libbox operation (such as latency testing) is active.")
            activeLeaseToken = leaseToken
            try {
                val libboxClass = runCatching { Class.forName(LIBBOX_CLASS) }.getOrElse {
                    error("Sing-box native library (libbox.aar v$SUPPORTED_SING_BOX_VERSION) is not bundled in app/libs/.")
                }
                val platformInterfaceClass = Class.forName(PLATFORM_INTERFACE_CLASS)
                validatePlatformContract(libboxClass, platformInterfaceClass)
                LibboxRuntime.ensureInitialized(context, libboxClass)

                val platformProxy = Proxy.newProxyInstance(
                    platformInterfaceClass.classLoader,
                    arrayOf(platformInterfaceClass)
                ) { _, method, args ->
                    handlePlatformCall(method, args, vpnService, settings)
                }

                val newServiceMethod = libboxClass.getMethod(
                    "newService",
                    String::class.java,
                    platformInterfaceClass
                )
                val boxService = newServiceMethod.invoke(null, configJson, platformProxy)
                    ?: error("Libbox.newService returned null for Sing-box v$SUPPORTED_SING_BOX_VERSION")

                // Register ownership BEFORE invoking native start(). If start() throws after the
                // native object was created, stopCoreLocked() must still close that exact object
                // or quarantine the lease instead of releasing a possibly live native service.
                activeService = boxService
                boxService.javaClass.getMethod("start").invoke(boxService)
                check(LibboxRuntime.markVpnLeaseActive(leaseToken)) {
                    "Unable to promote the current Libbox VPN lease to ACTIVE."
                }
                Log.i(TAG, "Sing-box libbox v$SUPPORTED_SING_BOX_VERSION started")
            } catch (t: Throwable) {
                val tunWasEstablished = activeTunFd?.let { it.fileDescriptor.valid() } == true
                if (settings.failClosedMode && tunWasEstablished) {
                    // Libbox.start() can fail after its PlatformInterface.openTun() callback has
                    // already established Android's TUN. Never let the generic start-error path
                    // close that interface before Controller can react. Try an atomic blackhole
                    // handover here; if establish() fails, enterFailClosedBlackhole() quarantines
                    // the runtime but leaves the previous TUN/service owned for explicit cleanup.
                    if (LibboxRuntime.currentMode() == LibboxLeaseMode.VPN_STARTING) {
                        LibboxRuntime.markVpnLeaseActive(leaseToken)
                    }
                    if (LibboxRuntime.currentMode() == LibboxLeaseMode.VPN_ACTIVE) {
                        val handover = enterFailClosedBlackhole(vpnService, settings)
                        handover.exceptionOrNull()?.let(t::addSuppressed)
                    } else {
                        LibboxRuntime.markFaulted(
                            leaseToken,
                            blockingTunIsInstalled = LibboxRuntime.isBlockingTunInstalled()
                        )
                    }
                } else {
                    // No Android TUN was established, so there is no protected interface to
                    // preserve. Close any created native object and release only on confirmed cleanup.
                    stopCoreLocked().exceptionOrNull()?.let(t::addSuppressed)
                }
                throw t
            }
        }
    }

    /** True while the bridge still owns an open Android VPN descriptor (normal or blocking). */
    fun hasOpenTunInterface(): Boolean = synchronized(lock) {
        activeTunFd?.let { it.fileDescriptor.valid() } == true
    }

    /**
     * Atomically hands over the existing VPN interface to a non-forwarding blackhole TUN.
     * Android documents that a newly established interface deactivates the old interface only
     * after the new interface has been created successfully; if establish() fails, the old
     * interface and descriptor remain untouched. Therefore the old Libbox core/TUN are NOT
     * stopped before establish().
     */
    fun enterFailClosedBlackhole(
        vpnService: VpnService,
        settings: AppSettings
    ): Result<Unit> = runCatching {
        synchronized(lock) {
            val token = activeLeaseToken
                ?: error("Cannot enter Fail-Closed mode without an active VPN lease.")
            check(LibboxRuntime.beginBlackholeTransition(token)) {
                "The current VPN lease is not eligible for a blackhole handover."
            }

            // Fail-Closed is an emergency override: deliberately do NOT copy the user's
            // split-tunnel allow/disallow list here. The replacement interface captures every
            // package (including VisionVPN's own UID) and has no forwarding engine, so neither
            // a configured bypass app nor an app-owned socket can fall through to the physical
            // network while the protected state is active. Explicit Disconnect restores normal
            // routing and the user's split-tunnel preference remains unchanged in storage.
            val replacementPfd = try {
                val builder = vpnService.Builder()
                    .setSession("VisionVPN (Fail-Closed Lockdown)")
                    .setMtu(settings.mtu.coerceIn(1280, 9000))
                    .addAddress("172.19.0.1", 30)
                    .addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("172.19.0.2")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
                // No addAllowedApplication()/addDisallowedApplication(): capture all UIDs.
                builder.establish()
                    ?: error("Unable to establish Fail-Closed blocking TUN; the previous VPN interface was retained.")
            } catch (t: Throwable) {
                LibboxRuntime.markFaulted(token, blockingTunIsInstalled = false)
                throw t
            }

            // The system has switched routing to the new descriptor at this point. Keep it in
            // activeTunFd BEFORE closing the old service so a cleanup error cannot release it.
            val previousPfd = activeTunFd
            activeTunFd = replacementPfd
            if (previousPfd != null && previousPfd !== replacementPfd) {
                retiredTunFds += previousPfd
            }
            check(LibboxRuntime.markBlackholeActive(token)) {
                LibboxRuntime.markFaulted(token, blockingTunIsInstalled = true)
                "Blocking TUN was established, but the Libbox lease transition failed."
            }

            // Close the old native forwarder only AFTER Android has installed the replacement.
            val oldService = activeService
            if (oldService != null) {
                try {
                    oldService.javaClass.getMethod("close").invoke(oldService)
                    activeService = null
                } catch (t: Throwable) {
                    // The new blocking TUN is already active. Keep the owner lease quarantined and
                    // retain the service reference so an explicit Disconnect can retry cleanup.
                    LibboxRuntime.markFaulted(token, blockingTunIsInstalled = true)
                    Log.e(TAG, "Blackhole TUN active; native service close will require retry", t)
                    return@synchronized Unit
                }
            }

            val closeFailures = mutableListOf<Throwable>()
            val oldDescriptors = retiredTunFds.toList()
            for (oldFd in oldDescriptors) {
                try {
                    closeTunDescriptor(oldFd)
                    retiredTunFds.remove(oldFd)
                } catch (t: Throwable) {
                    closeFailures += t
                }
            }
            if (closeFailures.isNotEmpty()) {
                LibboxRuntime.markFaulted(token, blockingTunIsInstalled = true)
                Log.e(TAG, "Blackhole TUN active; ${closeFailures.size} old descriptor(s) could not be closed")
            }
            Log.w(TAG, "Entered Fail-Closed blocking TUN state until user disconnects")
        }
    }

    fun stopCore(): Result<Unit> = synchronized(lock) { stopCoreLocked() }

    /**
     * Close the service first, then all active/retired TUN descriptors, and release the lease
     * ONLY after every close has succeeded. Any failure leaves the lease quarantined.
     */
    private fun stopCoreLocked(): Result<Unit> {
        unregisterDefaultInterfaceMonitor()
        val token = activeLeaseToken
        try {
            activeService?.let { svc ->
                svc.javaClass.getMethod("close").invoke(svc)
            }
            activeService = null
        } catch (t: Throwable) {
            token?.let { LibboxRuntime.markFaulted(it, LibboxRuntime.isBlockingTunInstalled()) }
            return Result.failure(
                LibboxCleanupException("Libbox native service could not be confirmed closed; its runtime lease was retained.", t)
            )
        }

        val descriptors = buildList {
            activeTunFd?.let(::add)
            addAll(retiredTunFds)
        }.distinct()
        for (fd in descriptors) {
            try {
                closeTunDescriptor(fd)
                if (fd === activeTunFd) {
                    activeTunFd = null
                    token?.let { LibboxRuntime.clearBlockingTun(it) }
                }
                retiredTunFds.remove(fd)
            } catch (t: Throwable) {
                token?.let { LibboxRuntime.markFaulted(it, LibboxRuntime.isBlockingTunInstalled()) }
                return Result.failure(
                    LibboxCleanupException("A VPN TUN descriptor could not be confirmed closed; its runtime lease was retained.", t)
                )
            }
        }

        if (token != null && !LibboxRuntime.releaseVpnLease(token)) {
            LibboxRuntime.markFaulted(token, blockingTunIsInstalled = false)
            return Result.failure(LibboxCleanupException("Libbox lease ownership changed before cleanup completed."))
        }
        activeLeaseToken = null
        return Result.success(Unit)
    }

    /** Native Libbox may close the raw fd it received from openTun(); accept EBADF as already closed. */
    private fun closeTunDescriptor(pfd: ParcelFileDescriptor) {
        if (!pfd.fileDescriptor.valid()) return
        try {
            pfd.close()
        } catch (t: Throwable) {
            val message = generateSequence(t) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" ")
                .lowercase()
            val alreadyClosed = message.contains("bad file descriptor") ||
                message.contains("ebadf") || message.contains("errno 9")
            if (!alreadyClosed) throw t
        }
    }

    private fun validatePlatformContract(
        libboxClass: Class<*>,
        platformInterfaceClass: Class<*>
    ) {
        runCatching {
            libboxClass.getMethod("newService", String::class.java, platformInterfaceClass)
        }.getOrElse {
            error("Incompatible libbox.aar: Libbox.newService(String, PlatformInterface) signature mismatch (expected v$SUPPORTED_SING_BOX_VERSION)")
        }

        val methods = platformInterfaceClass.methods
        fun requireExact(name: String, paramTypes: List<Class<*>>, returnType: Class<*>? = null) {
            val match = methods.firstOrNull { m ->
                m.name == name &&
                    m.parameterTypes.toList() == paramTypes &&
                    (returnType == null || m.returnType == returnType)
            }
            requireNotNull(match) {
                "Incompatible libbox.aar: PlatformInterface.$name(${paramTypes.joinToString { it.simpleName }}) mismatch for Sing-box v$SUPPORTED_SING_BOX_VERSION"
            }
        }

        fun requireByNameAndArity(name: String, arity: Int, returnType: Class<*>? = null) {
            val match = methods.firstOrNull { m ->
                m.name == name && m.parameterCount == arity &&
                    (returnType == null || m.returnType == returnType)
            }
            requireNotNull(match) {
                "Incompatible libbox.aar: PlatformInterface.$name (arity=$arity) mismatch for Sing-box v$SUPPORTED_SING_BOX_VERSION"
            }
        }

        requireExact("usePlatformAutoDetectInterfaceControl", emptyList(), Boolean::class.javaPrimitiveType)
        requireExact("usePlatformDefaultInterfaceMonitor", emptyList(), Boolean::class.javaPrimitiveType)
        requireExact("usePlatformInterfaceGetter", emptyList(), Boolean::class.javaPrimitiveType)
        requireExact("autoDetectInterfaceControl", listOf(Int::class.javaPrimitiveType!!))
        requireByNameAndArity("openTun", 1, Int::class.javaPrimitiveType)
        requireByNameAndArity("startDefaultInterfaceMonitor", 1)
        requireByNameAndArity("closeDefaultInterfaceMonitor", 1)
        requireExact(
            "findConnectionOwner",
            listOf(
                Int::class.javaPrimitiveType!!,
                String::class.java,
                Int::class.javaPrimitiveType!!,
                String::class.java,
                Int::class.javaPrimitiveType!!
            ),
            Int::class.javaPrimitiveType
        )
        requireExact("packageNameByUid", listOf(Int::class.javaPrimitiveType!!), String::class.java)
        requireExact("uidByPackageName", listOf(String::class.java), Int::class.javaPrimitiveType)
    }

    private fun handlePlatformCall(
        method: Method,
        args: Array<out Any?>?,
        vpnService: VpnService,
        settings: AppSettings
    ): Any? = when (method.name) {
        "usePlatformAutoDetectInterfaceControl" -> true
        "usePlatformDefaultInterfaceMonitor" -> true
        "usePlatformInterfaceGetter" -> true
        "useProcFS" -> false
        "underNetworkExtension" -> false
        "includeAllNetworks" -> false
        "openTun" -> openAndroidTun(vpnService, settings, args?.firstOrNull())
        "autoDetectInterfaceControl" -> {
            val fd = (args?.firstOrNull() as? Number)?.toInt() ?: -1
            if (fd < 0 || !vpnService.protect(fd)) {
                throw IllegalStateException("VpnService.protect($fd) failed; refusing unprotected outbound socket.")
            }
            null
        }
        "startDefaultInterfaceMonitor" -> {
            val listener = args?.firstOrNull()
                ?: throw IllegalArgumentException("startDefaultInterfaceMonitor called with null listener")
            registerDefaultInterfaceMonitor(listener)
            null
        }
        "closeDefaultInterfaceMonitor" -> {
            unregisterDefaultInterfaceMonitor()
            null
        }
        "findConnectionOwner" -> findConnectionOwnerUid(args)
        "packageNameByUid" -> {
            val uid = (args?.firstOrNull() as? Number)?.toInt() ?: -1
            context.packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
        }
        "uidByPackageName" -> {
            val pkg = args?.firstOrNull() as? String ?: ""
            runCatching { context.packageManager.getPackageUid(pkg, 0) }.getOrDefault(-1)
        }
        "getInterfaces" -> buildNetworkInterfaceIterator()
        "systemCertificates" -> buildSystemCertificatesIterator()
        "writeLog" -> {
            val msg = args?.firstOrNull() as? String
            if (!msg.isNullOrBlank()) {
                Log.d(TAG, "[sing-box] ${LogRedactor.redact(msg)}")
            }
            null
        }
        "readWIFIState", "localDNSTransport" -> null
        "clearDNSCache", "sendNotification" -> null
        "toString" -> "VisionPlatformInterface(sing-box $SUPPORTED_SING_BOX_VERSION)"
        "hashCode" -> System.identityHashCode(this)
        "equals" -> false
        else -> throw UnsupportedOperationException(
            "Unhandled mandatory PlatformInterface method '${method.name}' in Sing-box v$SUPPORTED_SING_BOX_VERSION"
        )
    }

    private fun buildNetworkInterfaceIterator(): Any {
        val niClass = Class.forName(NETWORK_INTERFACE_CLASS)
        val iterClass = Class.forName(NETWORK_INTERFACE_ITERATOR_CLASS)
        val items = mutableListOf<Any>()

        val enumIfs = NetworkInterface.getNetworkInterfaces()
        while (enumIfs != null && enumIfs.hasMoreElements()) {
            val nif = enumIfs.nextElement()
            if (!nif.isUp || nif.isLoopback || nif.name == "tun0") continue
            val cidrs = nif.interfaceAddresses.mapNotNull { ia ->
                val host = ia.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                "$host/${ia.networkPrefixLength}"
            }
            if (cidrs.isEmpty()) continue

            val obj = niClass.getDeclaredConstructor().newInstance()
            niClass.getMethod("setIndex", Int::class.javaPrimitiveType).invoke(obj, nif.index)
            niClass.getMethod("setMTU", Int::class.javaPrimitiveType).invoke(obj, nif.mtu)
            niClass.getMethod("setName", String::class.java).invoke(obj, nif.name)

            var flags = 1
            if (nif.supportsMulticast()) flags = flags or 32
            if (nif.isPointToPoint) flags = flags or 16
            runCatching { niClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(obj, flags) }

            val addrIter = buildStringIterator(cidrs)
            runCatching {
                val setAddr = niClass.methods.firstOrNull { it.name == "setAddresses" && it.parameterCount == 1 }
                setAddr?.invoke(obj, addrIter)
            }
            items += obj
        }

        var cursor = 0
        return Proxy.newProxyInstance(iterClass.classLoader, arrayOf(iterClass)) { _, m, _ ->
            when (m.name) {
                "hasNext" -> cursor < items.size
                "next" -> {
                    if (cursor >= items.size) throw NoSuchElementException("NetworkInterfaceIterator exhausted")
                    items[cursor++]
                }
                "len" -> items.size
                else -> throw UnsupportedOperationException("Unexpected NetworkInterfaceIterator.${m.name}")
            }
        }
    }

    private fun buildSystemCertificatesIterator(): Any {
        val certs = mutableListOf<String>()
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val cert = ks.getCertificate(aliases.nextElement()) as? X509Certificate ?: continue
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
            certs += "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----"
        }
        return buildStringIterator(certs)
    }

    private fun buildStringIterator(values: List<String>): Any {
        val iterClass = Class.forName(STRING_ITERATOR_CLASS)
        var cursor = 0
        return Proxy.newProxyInstance(iterClass.classLoader, arrayOf(iterClass)) { _, m, _ ->
            when (m.name) {
                "hasNext" -> cursor < values.size
                "next" -> {
                    if (cursor >= values.size) throw NoSuchElementException("StringIterator exhausted")
                    values[cursor++]
                }
                "len" -> values.size
                else -> throw UnsupportedOperationException("Unexpected StringIterator.${m.name}")
            }
        }
    }

    private fun registerDefaultInterfaceMonitor(listener: Any) {
        unregisterDefaultInterfaceMonitor()
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentDefaultNetwork = network
                notifyInterfaceChanged(cm, network, listener)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (currentDefaultNetwork != null && currentDefaultNetwork != network) return
                val ifName = linkProperties.interfaceName ?: return
                if (ifName == "tun0") return
                val ifIndex = runCatching { NetworkInterface.getByName(ifName)?.index ?: -1 }.getOrDefault(-1)
                invokeUpdateDefaultInterface(listener, ifName, ifIndex)
            }

            override fun onLost(network: Network) {
                if (currentDefaultNetwork != network) return
                currentDefaultNetwork = null
                invokeUpdateDefaultInterface(listener, "", -1)
            }
        }

        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
        cm.activeNetwork?.let { active ->
            currentDefaultNetwork = active
            notifyInterfaceChanged(cm, active, listener)
        }
    }

    private fun unregisterDefaultInterfaceMonitor() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        currentDefaultNetwork = null
    }

    private fun notifyInterfaceChanged(cm: ConnectivityManager, network: Network, listener: Any) {
        val lp = cm.getLinkProperties(network) ?: return
        val ifName = lp.interfaceName ?: return
        if (ifName == "tun0") return
        val ifIndex = runCatching { NetworkInterface.getByName(ifName)?.index ?: -1 }.getOrDefault(-1)
        invokeUpdateDefaultInterface(listener, ifName, ifIndex)
    }

    private fun invokeUpdateDefaultInterface(listener: Any, ifName: String, ifIndex: Int) {
        runCatching {
            val m = listener.javaClass.methods.firstOrNull { it.name == "updateDefaultInterface" } ?: return
            when (m.parameterCount) {
                2 -> m.invoke(listener, ifName, ifIndex)
                4 -> m.invoke(listener, ifName, ifIndex, false, false)
            }
        }
    }

    private fun findConnectionOwnerUid(args: Array<out Any?>?): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || args == null || args.size < 5) return -1
        return runCatching {
            val protocol = (args[0] as Number).toInt()
            val srcIp = args[1] as String
            val srcPort = (args[2] as Number).toInt()
            val dstIp = args[3] as String
            val dstPort = (args[4] as Number).toInt()
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return -1
            cm.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(srcIp, srcPort),
                InetSocketAddress(dstIp, dstPort)
            )
        }.getOrDefault(-1)
    }

    /**
     * Consumes incoming Sing-box v1.10.7 `TunOptions` (`getMTU`, `getInet4Address`,
     * `getInet6Address`, `getDNSServerAddress`) and applies them to `VpnService.Builder`.
     *
     * Always excludes `context.packageName` (this app's UID, which is also inherited by child
     * processes `libtor.so` and `libpsiphon.so`) from TUN capture so upstream engine sockets
     * never loop back into `tun0`.
     */
    private fun openAndroidTun(vpnService: VpnService, settings: AppSettings, tunOptions: Any?): Int {
        val mtuFromOptions = runCatching {
            tunOptions?.javaClass?.getMethod("getMTU")?.invoke(tunOptions) as? Number
        }.getOrNull()?.toInt()?.takeIf { it in 1280..9000 }

        val dnsFromOptions = runCatching {
            val boxed = tunOptions?.javaClass?.getMethod("getDNSServerAddress")?.invoke(tunOptions)
            boxed?.javaClass?.getMethod("getValue")?.invoke(boxed) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val v4Prefixes = readRoutePrefixes(tunOptions, "getInet4Address")
        val v6Prefixes = readRoutePrefixes(tunOptions, "getInet6Address")

        val builder = vpnService.Builder()
            .setSession("VisionVPN")
            .setMtu(mtuFromOptions ?: settings.mtu.coerceIn(1280, 9000))

        if (v4Prefixes.isNotEmpty()) {
            v4Prefixes.forEach { (addr, prefix) -> builder.addAddress(addr, prefix) }
        } else {
            builder.addAddress("172.19.0.1", 30)
        }

        if (v6Prefixes.isNotEmpty()) {
            v6Prefixes.forEach { (addr, prefix) -> builder.addAddress(addr, prefix) }
        } else {
            builder.addAddress("fdfe:dcba:9876::1", 126)
        }

        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("::", 0)
        builder.addDnsServer(dnsFromOptions ?: "172.19.0.2")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val installedPackages = runCatching {
            context.packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        }.getOrNull()
        val splitPlan = resolveSplitTunnelPlan(
            mode = settings.splitTunnelMode,
            configuredPackages = settings.splitTunnelPackages,
            selfPackageName = context.packageName,
            installedPackages = installedPackages
        ).getOrThrow()

        splitPlan.allowedPackages.forEach { pkg ->
            builder.addAllowedApplication(pkg)
        }
        splitPlan.disallowedPackages.forEach { pkg ->
            builder.addDisallowedApplication(pkg)
        }

        val pfd = builder.establish()
            ?: error("Android VpnService.Builder.establish() returned null (VPN consent revoked or busy).")
        activeTunFd?.close()
        activeTunFd = pfd
        return pfd.fd
    }

    private fun readRoutePrefixes(tunOptions: Any?, getterName: String): List<Pair<String, Int>> {
        if (tunOptions == null) return emptyList()
        return runCatching {
            val iter = tunOptions.javaClass.getMethod(getterName).invoke(tunOptions) ?: return emptyList()
            val hasNext = iter.javaClass.getMethod("hasNext")
            val next = iter.javaClass.getMethod("next")
            val result = mutableListOf<Pair<String, Int>>()
            while (hasNext.invoke(iter) == true) {
                val item = next.invoke(iter) ?: continue
                val addr = item.javaClass.getMethod("address").invoke(item) as? String ?: continue
                val prefix = (item.javaClass.getMethod("prefix").invoke(item) as? Number)?.toInt() ?: continue
                result += addr to prefix
            }
            result
        }.getOrDefault(emptyList())
    }
}
