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
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

class LibboxBridge(private val context: Context) {

    companion object {
        private const val TAG = "LibboxBridge"
        const val SUPPORTED_SING_BOX_VERSION = BuildConfig.SING_BOX_PINNED_VERSION
        private const val LIBBOX_CLASS = "io.nekohasekai.libbox.Libbox"
        private const val SETUP_OPTIONS_CLASS = "io.nekohasekai.libbox.SetupOptions"
        private const val PLATFORM_INTERFACE_CLASS = "io.nekohasekai.libbox.PlatformInterface"
        private const val STRING_ITERATOR_CLASS = "io.nekohasekai.libbox.StringIterator"
        private const val NETWORK_INTERFACE_CLASS = "io.nekohasekai.libbox.NetworkInterface"
        private const val NETWORK_INTERFACE_ITERATOR_CLASS = "io.nekohasekai.libbox.NetworkInterfaceIterator"
    }

    private val lock = Any()
    private val initialized = AtomicBoolean(false)
    private var activeService: Any? = null
    private var activeTunFd: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var currentDefaultNetwork: Network? = null

    fun startCore(
        configJson: String,
        vpnService: VpnService,
        settings: AppSettings
    ): Result<Unit> = runCatching {
        synchronized(lock) {
            stopCoreLocked()
            try {
                val libboxClass = runCatching { Class.forName(LIBBOX_CLASS) }.getOrElse {
                    error("Sing-box native library (libbox.aar v$SUPPORTED_SING_BOX_VERSION) is not bundled in app/libs/.")
                }
                val platformInterfaceClass = Class.forName(PLATFORM_INTERFACE_CLASS)
                validatePlatformContract(libboxClass, platformInterfaceClass)
                ensureSetup(libboxClass)

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

                boxService.javaClass.getMethod("start").invoke(boxService)
                activeService = boxService
                Log.i(TAG, "Sing-box libbox v$SUPPORTED_SING_BOX_VERSION started")
            } catch (t: Throwable) {
                stopCoreLocked()
                throw t
            }
        }
    }

    fun stopCore() {
        synchronized(lock) {
            stopCoreLocked()
        }
    }

    private fun stopCoreLocked() {
        unregisterDefaultInterfaceMonitor()

        activeService?.let { svc ->
            runCatching { svc.javaClass.getMethod("close").invoke(svc) }
                .onFailure { Log.w(TAG, "Libbox close error: ${it.message}") }
        }
        activeService = null

        activeTunFd?.let { pfd ->
            runCatching { pfd.close() }
                .onFailure { Log.w(TAG, "TUN close error: ${it.message}") }
        }
        activeTunFd = null
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

    private fun ensureSetup(libboxClass: Class<*>) {
        if (initialized.get()) return
        val baseDir = File(context.filesDir, "singbox").apply { mkdirs() }
        val workDir = File(baseDir, "work").apply { mkdirs() }
        val tempDir = File(context.cacheDir, "singbox_tmp").apply { mkdirs() }

        val optionsClass = Class.forName(SETUP_OPTIONS_CLASS)
        val options = optionsClass.getDeclaredConstructor().newInstance()
        optionsClass.getMethod("setBasePath", String::class.java).invoke(options, baseDir.absolutePath)
        optionsClass.getMethod("setWorkingPath", String::class.java).invoke(options, workDir.absolutePath)
        optionsClass.getMethod("setTempPath", String::class.java).invoke(options, tempDir.absolutePath)
        libboxClass.getMethod("setup", optionsClass).invoke(null, options)

        initialized.set(true)
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
                Log.d(TAG, "[sing-box] ${redactSensitiveLog(msg)}")
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

        when (settings.splitTunnelMode) {
            SplitTunnelMode.DISABLED -> {
                runCatching { builder.addDisallowedApplication(context.packageName) }
            }
            SplitTunnelMode.BYPASS_SELECTED -> {
                runCatching { builder.addDisallowedApplication(context.packageName) }
                settings.splitTunnelPackages.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
            SplitTunnelMode.ONLY_SELECTED -> {
                settings.splitTunnelPackages
                    .filter { it != context.packageName }
                    .forEach { pkg ->
                        runCatching { builder.addAllowedApplication(pkg) }
                    }
            }
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

    private fun redactSensitiveLog(raw: String): String {
        return raw.replace(Regex("(password|uuid|private_key|cert)=([^\\s&,\"]+)"), "$1=<redacted>")
    }
}
