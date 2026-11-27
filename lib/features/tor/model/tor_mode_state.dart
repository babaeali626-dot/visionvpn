/// Independent connection mode for Vision VPN.
/// [normal] preserves 100% of upstream Hiddify v5.9.15 behavior.
/// [tor] activates the standalone Tor VPN pipeline (Tor Android service -> live sing-box -> fail-closed VPN).
enum VisionConnectionMode {
  normal,
  tor,
}

/// Pluggable Transport selection for standalone Tor mode.
enum TorTransportMode {
  automatic('automatic', 'Automatic'),
  snowflake('snowflake', 'Snowflake'),
  obfs4('obfs4', 'obfs4');

  const TorTransportMode(this.key, this.label);

  final String key;
  final String label;

  static TorTransportMode fromKey(String? raw) {
    final normalized = raw?.trim().toLowerCase();
    for (final mode in values) {
      if (mode.key == normalized) return mode;
    }
    return TorTransportMode.automatic;
  }
}

/// High-level lifecycle phase of Vision VPN's standalone Tor VPN mode.
enum TorVpnPhase {
  stopped,
  startingTor,
  bootstrapping,
  verifyingSocks,
  startingVpn,
  connected,
  failClosed,
  recovering,
  stopping,
  error,
}

/// Snapshot emitted by the native Android TorEngineManager over EventChannel / MethodChannel.
class TorSnapshot {
  const TorSnapshot({
    required this.state,
    required this.bootstrap,
    this.bootstrapSummary,
    this.socksHost,
    this.socksPort,
    this.transportMode = 'automatic',
    this.activeTransport = 'direct',
    this.transportStatus,
    this.transportError,
    this.snowflakeAvailable = false,
    this.obfs4Available = false,
    this.failClosed = false,
    this.error,
  });

  final String state;
  final int bootstrap;
  final String? bootstrapSummary;
  final String? socksHost;
  final int? socksPort;
  final String transportMode;
  final String activeTransport;
  final String? transportStatus;
  final String? transportError;
  final bool snowflakeAvailable;
  final bool obfs4Available;
  final bool failClosed;
  final String? error;

  bool get isTorReady =>
      state == 'CONNECTED' &&
      bootstrap >= 100 &&
      (socksHost == '127.0.0.1' || socksHost == '::1') &&
      socksPort != null &&
      socksPort! >= 1 &&
      socksPort! <= 65535;

  factory TorSnapshot.fromMap(Map<Object?, Object?> map) => TorSnapshot(
    state: map['state'] as String? ?? 'STOPPED',
    bootstrap: (map['bootstrap'] as num?)?.toInt() ?? 0,
    bootstrapSummary: map['bootstrapSummary'] as String?,
    socksHost: map['socksHost'] as String?,
    socksPort: (map['socksPort'] as num?)?.toInt(),
    transportMode: map['transportMode'] as String? ?? 'automatic',
    activeTransport: map['activeTransport'] as String? ?? 'direct',
    transportStatus: map['transportStatus'] as String?,
    transportError: map['transportError'] as String?,
    snowflakeAvailable: map['snowflakeAvailable'] as bool? ?? false,
    obfs4Available: map['obfs4Available'] as bool? ?? false,
    failClosed: map['failClosed'] as bool? ?? false,
    error: map['error'] as String?,
  );
}

class TorModeState {
  const TorModeState({
    this.connectionMode = VisionConnectionMode.normal,
    this.transportMode = TorTransportMode.automatic,
    this.phase = TorVpnPhase.stopped,
    this.snapshot = const TorSnapshot(state: 'STOPPED', bootstrap: 0),
    this.vpnActive = false,
    this.statusMessage,
    this.lastError,
  });

  final VisionConnectionMode connectionMode;
  final TorTransportMode transportMode;
  final TorVpnPhase phase;
  final TorSnapshot snapshot;
  final bool vpnActive;
  final String? statusMessage;
  final String? lastError;

  bool get isTorModeSelected => connectionMode == VisionConnectionMode.tor;

  bool get isBusy =>
      phase == TorVpnPhase.startingTor ||
      phase == TorVpnPhase.bootstrapping ||
      phase == TorVpnPhase.verifyingSocks ||
      phase == TorVpnPhase.startingVpn ||
      phase == TorVpnPhase.recovering ||
      phase == TorVpnPhase.stopping;

  TorModeState copyWith({
    VisionConnectionMode? connectionMode,
    TorTransportMode? transportMode,
    TorVpnPhase? phase,
    TorSnapshot? snapshot,
    bool? vpnActive,
    String? statusMessage,
    bool clearStatusMessage = false,
    String? lastError,
    bool clearLastError = false,
  }) => TorModeState(
    connectionMode: connectionMode ?? this.connectionMode,
    transportMode: transportMode ?? this.transportMode,
    phase: phase ?? this.phase,
    snapshot: snapshot ?? this.snapshot,
    vpnActive: vpnActive ?? this.vpnActive,
    statusMessage: clearStatusMessage ? null : (statusMessage ?? this.statusMessage),
    lastError: clearLastError ? null : (lastError ?? this.lastError),
  );
}
