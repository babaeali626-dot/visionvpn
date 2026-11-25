package com.visionvpn.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.visionvpn.app.core.crypto.AndroidKeystoreCrypto
import com.visionvpn.app.core.crypto.SecretCipher
import com.visionvpn.app.core.crypto.SecureStorageException
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.SavedTorBridge
import com.visionvpn.app.data.model.SubscriptionSource
import com.visionvpn.app.data.model.TorBridgeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.visionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vision_vpn_store"
)

enum class StorageMigrationState {
    NOT_STARTED,
    RUNNING,
    COMPLETE,
    RECOVERY_REQUIRED,
    FAILED
}

/**
 * Race-safe encrypted DataStore repository backed by Android Keystore AES-256-GCM with per-key AAD.
 *
 * - Always verifies that existing encrypted records decrypt & parse cleanly even when
 *   `storage_schema_version` is already current.
 * - Transitions to [StorageMigrationState.RECOVERY_REQUIRED] on corrupt/invalidated ciphertext
 *   and blocks destructive writes until the user takes explicit recovery action.
 * - Preserves coroutine [CancellationException] without converting it into a storage failure.
 */
class VisionPreferencesRepository(
    private val context: Context,
    private val secretCipher: SecretCipher = AndroidKeystoreCrypto.createCipher()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private object Keys {
        val SCHEMA_VERSION = intPreferencesKey("storage_schema_version")
        val PROFILES = stringPreferencesKey("profiles_json")
        val SUBSCRIPTIONS = stringPreferencesKey("subscriptions_json")
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val CHAIN_CONFIG = stringPreferencesKey("chain_config_json")
        val APP_SETTINGS = stringPreferencesKey("app_settings_json")
        const val CURRENT_SCHEMA_VERSION = 2
    }

    private val migrationMutex = Mutex()
    private var migrationGate = CompletableDeferred<Boolean>()

    private val _migrationState = MutableStateFlow(StorageMigrationState.NOT_STARTED)
    val migrationState: StateFlow<StorageMigrationState> = _migrationState.asStateFlow()

    private val _storageErrorFlow = MutableStateFlow<String?>(null)
    val storageErrorFlow: StateFlow<String?> = _storageErrorFlow.asStateFlow()

    val profilesFlow: Flow<List<ProxyProfile>> = context.visionDataStore.data.map { prefs ->
        prefs[Keys.PROFILES]?.let { raw ->
            decodeEncryptedOrReport<List<ProxyProfile>>(raw, Keys.PROFILES.name)
        } ?: emptyList()
    }

    val subscriptionsFlow: Flow<List<SubscriptionSource>> = context.visionDataStore.data.map { prefs ->
        prefs[Keys.SUBSCRIPTIONS]?.let { raw ->
            decodeEncryptedOrReport<List<SubscriptionSource>>(raw, Keys.SUBSCRIPTIONS.name)
        } ?: emptyList()
    }

    val activeProfileIdFlow: Flow<String?> = context.visionDataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_PROFILE_ID]
    }

    val chainConfigFlow: Flow<ChainConfig> = context.visionDataStore.data.map { prefs ->
        val decoded = prefs[Keys.CHAIN_CONFIG]?.let { raw ->
            decodeEncryptedOrReport<ChainConfig>(raw, Keys.CHAIN_CONFIG.name)
        } ?: ChainConfig()
        migrateLegacyTorBridgeConfig(decoded)
    }

    val appSettingsFlow: Flow<AppSettings> = context.visionDataStore.data.map { prefs ->
        prefs[Keys.APP_SETTINGS]?.let { raw ->
            runCatching { json.decodeFromString<AppSettings>(raw) }
                .onFailure { _storageErrorFlow.value = "Corrupted app settings record: ${it.message}" }
                .getOrDefault(AppSettings())
        } ?: AppSettings()
    }

    suspend fun runDurableMigrationIfNeeded() {
        migrationMutex.withLock {
            if (_migrationState.value == StorageMigrationState.COMPLETE) return
            if (migrationGate.isCompleted) {
                migrationGate = CompletableDeferred()
            }
            _migrationState.value = StorageMigrationState.RUNNING
            try {
                context.visionDataStore.edit { prefs ->
                    val currentVer = prefs[Keys.SCHEMA_VERSION] ?: 1

                    // Always verify decryptability & JSON integrity even when schema version is already current
                    prefs[Keys.PROFILES]?.let { raw ->
                        val plain = secretCipher.decrypt(raw, Keys.PROFILES.name)
                        json.decodeFromString<List<ProxyProfile>>(plain)
                        if (currentVer < Keys.CURRENT_SCHEMA_VERSION || !raw.startsWith("enc:v2:")) {
                            prefs[Keys.PROFILES] = secretCipher.encrypt(plain, Keys.PROFILES.name)
                        }
                    }

                    prefs[Keys.SUBSCRIPTIONS]?.let { raw ->
                        val plain = secretCipher.decrypt(raw, Keys.SUBSCRIPTIONS.name)
                        json.decodeFromString<List<SubscriptionSource>>(plain)
                        if (currentVer < Keys.CURRENT_SCHEMA_VERSION || !raw.startsWith("enc:v2:")) {
                            prefs[Keys.SUBSCRIPTIONS] = secretCipher.encrypt(plain, Keys.SUBSCRIPTIONS.name)
                        }
                    }

                    prefs[Keys.CHAIN_CONFIG]?.let { raw ->
                        val plain = secretCipher.decrypt(raw, Keys.CHAIN_CONFIG.name)
                        val parsed = json.decodeFromString<ChainConfig>(plain)
                        val migrated = migrateLegacyTorBridgeConfig(parsed)
                        if (currentVer < Keys.CURRENT_SCHEMA_VERSION || !raw.startsWith("enc:v2:") || migrated != parsed) {
                            prefs[Keys.CHAIN_CONFIG] = secretCipher.encrypt(
                                json.encodeToString(migrated),
                                Keys.CHAIN_CONFIG.name
                            )
                        }
                    }

                    if (currentVer < Keys.CURRENT_SCHEMA_VERSION) {
                        prefs[Keys.SCHEMA_VERSION] = Keys.CURRENT_SCHEMA_VERSION
                    }
                }
                _migrationState.value = StorageMigrationState.COMPLETE
                _storageErrorFlow.value = null
                migrationGate.complete(true)
            } catch (ce: CancellationException) {
                _migrationState.value = StorageMigrationState.NOT_STARTED
                throw ce
            } catch (se: SecureStorageException) {
                _migrationState.value = StorageMigrationState.RECOVERY_REQUIRED
                _storageErrorFlow.value =
                    "Encrypted VPN data could not be decrypted (Keystore invalidated or tampered). Writes paused to protect your data."
                migrationGate.complete(false)
            } catch (err: Throwable) {
                _migrationState.value = StorageMigrationState.FAILED
                _storageErrorFlow.value = "Secure storage verification failed; writes paused: ${err.message}"
                migrationGate.complete(false)
            }
        }
    }

    private suspend fun awaitMigrationBarrier() {
        if (_migrationState.value == StorageMigrationState.NOT_STARTED) {
            runDurableMigrationIfNeeded()
        }
        val ok = migrationGate.await()
        if (!ok) {
            throw SecureStorageException(
                "Encrypted storage requires recovery (${_migrationState.value}); refusing destructive write."
            )
        }
    }

    suspend fun saveProfiles(profiles: List<ProxyProfile>) {
        awaitMigrationBarrier()
        val serialized = json.encodeToString(profiles)
        context.visionDataStore.edit { prefs ->
            prefs[Keys.PROFILES] = secretCipher.encrypt(serialized, Keys.PROFILES.name)
        }
        _storageErrorFlow.value = null
    }

    suspend fun saveSubscriptions(subscriptions: List<SubscriptionSource>) {
        awaitMigrationBarrier()
        val serialized = json.encodeToString(subscriptions)
        context.visionDataStore.edit { prefs ->
            prefs[Keys.SUBSCRIPTIONS] = secretCipher.encrypt(serialized, Keys.SUBSCRIPTIONS.name)
        }
    }

    suspend fun setActiveProfileId(profileId: String?) {
        awaitMigrationBarrier()
        context.visionDataStore.edit { prefs ->
            if (profileId == null) {
                prefs.remove(Keys.ACTIVE_PROFILE_ID)
            } else {
                prefs[Keys.ACTIVE_PROFILE_ID] = profileId
            }
        }
    }

    suspend fun saveChainConfig(config: ChainConfig) {
        awaitMigrationBarrier()
        val serialized = json.encodeToString(config)
        context.visionDataStore.edit { prefs ->
            prefs[Keys.CHAIN_CONFIG] = secretCipher.encrypt(serialized, Keys.CHAIN_CONFIG.name)
        }
        _storageErrorFlow.value = null
    }

    suspend fun clearSavedTorBridges() {
        awaitMigrationBarrier()
        context.visionDataStore.edit { prefs ->
            val raw = prefs[Keys.CHAIN_CONFIG] ?: return@edit
            val plaintext = secretCipher.decrypt(raw, Keys.CHAIN_CONFIG.name)
            val current = json.decodeFromString<ChainConfig>(plaintext)
            val cleaned = current.copy(
                torConfig = current.torConfig.copy(
                    bridgesEnabled = false,
                    useBridges = false,
                    bridgeMode = TorBridgeMode.DIRECT,
                    savedBridges = emptyList(),
                    customBridgeLine = ""
                )
            )
            prefs[Keys.CHAIN_CONFIG] = secretCipher.encrypt(
                json.encodeToString(cleaned),
                Keys.CHAIN_CONFIG.name
            )
        }
    }

    suspend fun saveAppSettings(settings: AppSettings) {
        awaitMigrationBarrier()
        context.visionDataStore.edit { prefs ->
            prefs[Keys.APP_SETTINGS] = json.encodeToString(settings)
        }
    }

    fun clearStorageError() {
        _storageErrorFlow.value = null
    }

    private inline fun <reified T> decodeEncryptedOrReport(raw: String, keyName: String): T? {
        return try {
            val plaintext = secretCipher.decrypt(raw, keyName)
            json.decodeFromString<T>(plaintext)
        } catch (e: SecureStorageException) {
            _migrationState.value = StorageMigrationState.RECOVERY_REQUIRED
            _storageErrorFlow.value =
                "Unable to decrypt saved VPN data ($keyName). Keystore key may have been invalidated."
            null
        } catch (e: Throwable) {
            _storageErrorFlow.value = "Corrupted VPN data record ($keyName): ${e.message}"
            null
        }
    }

    private fun migrateLegacyTorBridgeConfig(config: ChainConfig): ChainConfig {
        val tor = config.torConfig
        if (tor.savedBridges.isEmpty() && tor.customBridgeLine.isNotBlank()) {
            val migrated = tor.customBridgeLine.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapIndexed { idx, line ->
                    val transport = line.substringBefore(" ").lowercase()
                    SavedTorBridge(
                        label = "Imported Bridge ${idx + 1}",
                        bridgeLine = line,
                        transport = transport,
                        enabled = true
                    )
                }
                .toList()
            return config.copy(
                torConfig = tor.copy(
                    bridgesEnabled = tor.useBridges || tor.bridgesEnabled,
                    bridgeMode = if (tor.useBridges) TorBridgeMode.CUSTOM else tor.bridgeMode,
                    savedBridges = migrated,
                    customBridgeLine = ""
                )
            )
        }
        return config
    }
}
