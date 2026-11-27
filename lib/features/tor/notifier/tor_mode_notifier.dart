import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:hiddify/core/directories/directories_provider.dart';
import 'package:hiddify/core/preferences/general_preferences.dart';
import 'package:hiddify/core/preferences/preferences_provider.dart';
import 'package:hiddify/features/connection/notifier/connection_notifier.dart';
import 'package:hiddify/features/tor/data/tor_outbound_adapter.dart';
import 'package:hiddify/features/tor/data/tor_service.dart';
import 'package:hiddify/hiddifycore/hiddify_core_service_provider.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

const String _kTorModeEnabledKey = 'visionvpn_tor_mode_enabled';
const String _kTorTransportModeKey = 'visionvpn_tor_transport_mode';

final torModeNotifierProvider = StateNotifierProvider<TorModeNotifier, TorModeState>((ref) {
  return TorModeNotifier(ref);
});

/// Orchestrates Vision VPN's standalone Tor mode while leaving normal VPN mode untouched when
/// [VisionConnectionMode.normal] is active.
class TorModeNotifier extends StateNotifier<TorModeState> {
  TorModeNotifier(this._ref) : super(const TorModeState()) {
    _init();
  }

  final Ref _ref;
  StreamSubscription<TorSnapshot>? _torSub;
  bool _disposed = false;
  int _operationId = 0;

  Future<void> _init() async {
    final prefs = _ref.read(sharedPreferencesProvider).valueOrNull;
    final savedTorEnabled = prefs?.getBool(_kTorModeEnabledKey) ?? false;
    final savedTransport = TorTransportMode.fromKey(prefs?.getString(_kTorTransportModeKey));

    if (!_disposed) {
      state = state.copyWith(
        connectionMode: savedTorEnabled ? VisionConnectionMode.tor : VisionConnectionMode.normal,
        transportMode: savedTransport,
      );
    }

    if (!Platform.isAndroid) return;

    try {
      await TorService.setTorModeEnabled(savedTorEnabled);
      final snap = await TorService.status();
      if (!_disposed) {
        _applySnapshot(snap);
      }
      _torSub = TorService.events.listen(
        (snap) {
          if (!_disposed) _applySnapshot(snap);
        },
        onError: (_) {},
      );
    } catch (_) {
      // Non-Android or test environment without native channel registered.
    }
  }

  void _applySnapshot(TorSnapshot snap) {
    final inVpnStage = state.phase == TorVpnPhase.startingVpn ||
        state.phase == TorVpnPhase.verifyingTunnel ||
        state.phase == TorVpnPhase.connected;

    final nextPhase = switch (snap.state) {
      'STARTING' => TorVpnPhase.startingTor,
      'BOOTSTRAPPING' => TorVpnPhase.bootstrapping,
      'VERIFYING_SOCKS' => TorVpnPhase.verifyingSocks,
      'FAIL_CLOSED' => TorVpnPhase.failClosed,
      'RECOVERING' => TorVpnPhase.recovering,
      'STOPPING' => TorVpnPhase.stopping,
      'ERROR' => TorVpnPhase.error,
      'CONNECTED' => inVpnStage ? state.phase : TorVpnPhase.verifyingSocks,
      _ => state.vpnActive ? TorVpnPhase.failClosed : TorVpnPhase.stopped,
    };

    final keepVpnActive = !snap.failClosed &&
        snap.state == 'CONNECTED' &&
        state.vpnActive &&
        state.tunnelVerified;

    state = state.copyWith(
      snapshot: snap,
      phase: nextPhase,
      vpnActive: keepVpnActive,
      tunnelVerified: keepVpnActive,
      statusMessage: inVpnStage && snap.state == 'CONNECTED'
          ? state.statusMessage
          : (snap.transportStatus ?? snap.bootstrapSummary),
      lastError: snap.error ?? snap.transportError,
      clearLastError: snap.error == null && snap.transportError == null && state.phase != TorVpnPhase.error,
    );
  }

  Future<void> setConnectionMode(VisionConnectionMode mode) async {
    if (state.connectionMode == mode) return;

    // Safely stop any active tunnel before switching modes so normal VPN and Tor never collide.
    if (state.vpnActive || state.isBusy || state.phase == TorVpnPhase.failClosed) {
      await disconnectTorVpn();
    } else if (mode == VisionConnectionMode.tor) {
      await _ref.read(connectionNotifierProvider.notifier).abortConnection();
    }

    final prefs = _ref.read(sharedPreferencesProvider).valueOrNull;
    await prefs?.setBool(_kTorModeEnabledKey, mode == VisionConnectionMode.tor);
    if (Platform.isAndroid) {
      try {
        await TorService.setTorModeEnabled(mode == VisionConnectionMode.tor);
      } catch (_) {}
    }

    if (!_disposed) {
      state = state.copyWith(
        connectionMode: mode,
        phase: TorVpnPhase.stopped,
        vpnActive: false,
        tunnelVerified: false,
        clearLastError: true,
        clearStatusMessage: true,
      );
    }
  }

  Future<void> setTransportMode(TorTransportMode mode) async {
    final prefs = _ref.read(sharedPreferencesProvider).valueOrNull;
    await prefs?.setString(_kTorTransportModeKey, mode.key);
    if (!_disposed) {
      state = state.copyWith(transportMode: mode, clearLastError: true);
    }
    if (Platform.isAndroid) {
      try {
        final snap = await TorService.setTransportMode(mode);
        if (!_disposed) {
          state = state.copyWith(snapshot: snap);
        }
      } catch (_) {}
    }
  }

  /// Full standalone Tor VPN start sequence:
  /// 1. Ensure normal VPN is stopped.
  /// 2. Start Android TorService with selected transport (Automatic / Snowflake / obfs4).
  /// 3. Wait until bootstrap reaches 100% AND native SOCKS5 loopback handshake passes.
  /// 4. Generate & validate the hardened Tor sing-box JSON (zero direct, UDP blocked, DNS over Tor).
  /// 5. Start live sing-box VPN via hiddify-core with `enableRawConfig: true`.
  /// 6. Verify real End-to-End connectivity through the live sing-box mixed proxy -> Tor -> Exit Node
  ///    before transitioning to `TorVpnPhase.connected`.
  Future<void> connectTorVpn({List<String> bridges = const <String>[]}) async {
    if (state.isBusy) return;
    if (!Platform.isAndroid) {
      state = state.copyWith(
        phase: TorVpnPhase.error,
        vpnActive: false,
        tunnelVerified: false,
        lastError: 'Standalone Tor Android Service is only available on Android.',
      );
      return;
    }

    final opId = ++_operationId;

    state = state.copyWith(
      connectionMode: VisionConnectionMode.tor,
      phase: TorVpnPhase.startingTor,
      vpnActive: false,
      tunnelVerified: false,
      statusMessage: 'Starting Tor service (${state.transportMode.label})...',
      clearLastError: true,
    );

    try {
      final prefs = _ref.read(sharedPreferencesProvider).valueOrNull;
      await prefs?.setBool(_kTorModeEnabledKey, true);
      await TorService.setTorModeEnabled(true);
      await _ref.read(connectionNotifierProvider.notifier).abortConnection();
      if (_disposed || opId != _operationId) return;

      await TorService.start(
        bridges: bridges,
        transportMode: state.transportMode,
      );
      if (_disposed || opId != _operationId) return;

      final readySnapshot = await TorService.waitUntilReady(
        timeout: const Duration(seconds: 120),
        onProgress: (snap) {
          if (_disposed || opId != _operationId) return;
          final phase = switch (snap.state) {
            'VERIFYING_SOCKS' => TorVpnPhase.verifyingSocks,
            'BOOTSTRAPPING' => TorVpnPhase.bootstrapping,
            _ => TorVpnPhase.startingTor,
          };
          state = state.copyWith(
            snapshot: snap,
            phase: phase,
            statusMessage: snap.transportStatus ?? snap.bootstrapSummary ?? 'Bootstrapping ${snap.bootstrap}%',
          );
        },
      );
      if (_disposed || opId != _operationId) return;

      await _startSingboxForVerifiedTor(readySnapshot, opId);
    } catch (err) {
      if (_disposed || opId != _operationId) return;
      // Fail-closed: never fall back to direct or normal VPN profile.
      try {
        await _ref.read(hiddifyCoreServiceProvider).stop().run();
      } catch (_) {}
      if (!_disposed && opId == _operationId) {
        state = state.copyWith(
          phase: TorVpnPhase.error,
          vpnActive: false,
          tunnelVerified: false,
          lastError: err.toString(),
          statusMessage: 'Tor VPN connection failed (fail-closed)',
        );
      }
    }
  }

  /// Safe recovery after a Fail-Closed event:
  /// Requires a fresh Tor restart, 100% bootstrap, verified SOCKS5 probe, and live E2E tunnel
  /// verification before re-establishing Connected status.
  Future<void> recoverFromFailClosed({List<String> bridges = const <String>[]}) async {
    if (!Platform.isAndroid) return;
    final opId = ++_operationId;

    state = state.copyWith(
      phase: TorVpnPhase.recovering,
      vpnActive: false,
      tunnelVerified: false,
      statusMessage: 'Recovering Tor & re-verifying SOCKS5...',
      clearLastError: true,
    );

    try {
      await TorService.recover(bridges: bridges);
      if (_disposed || opId != _operationId) return;

      final readySnapshot = await TorService.waitUntilReady(
        timeout: const Duration(seconds: 120),
        onProgress: (snap) {
          if (_disposed || opId != _operationId) return;
          state = state.copyWith(
            snapshot: snap,
            phase: TorVpnPhase.recovering,
            statusMessage: snap.transportStatus ?? 'Recovering Tor (${snap.bootstrap}%)',
          );
        },
      );
      if (_disposed || opId != _operationId) return;

      await _startSingboxForVerifiedTor(readySnapshot, opId);
    } catch (err) {
      if (_disposed || opId != _operationId) return;
      try {
        await _ref.read(hiddifyCoreServiceProvider).stop().run();
      } catch (_) {}
      if (!_disposed && opId == _operationId) {
        state = state.copyWith(
          phase: TorVpnPhase.failClosed,
          vpnActive: false,
          tunnelVerified: false,
          lastError: 'Recovery failed; fail-closed remains active: $err',
        );
      }
    }
  }

  Future<void> _startSingboxForVerifiedTor(TorSnapshot readySnapshot, int opId) async {
    final host = readySnapshot.socksHost;
    final port = readySnapshot.socksPort;
    if (!readySnapshot.isTorReady || host == null || port == null) {
      throw StateError('Refusing to start VPN: Tor SOCKS5 is not verified');
    }

    state = state.copyWith(
      snapshot: readySnapshot,
      phase: TorVpnPhase.startingVpn,
      vpnActive: false,
      tunnelVerified: false,
      statusMessage: 'Starting sing-box VPN tunnel ($host:$port)...',
    );

    final configJson = TorOutboundAdapter.encodeFullSingboxConfig(
      socksHost: host,
      socksPort: port,
    );

    final directories = await _ref.read(appDirectoriesProvider.future);
    if (_disposed || opId != _operationId) return;
    final configFile = File('${directories.workingDir.path}/visionvpn_tor_active.json');
    await configFile.writeAsString(configJson, flush: true);

    final coreService = _ref.read(hiddifyCoreServiceProvider);
    final setupRes = await coreService.setup().run();
    if (_disposed || opId != _operationId) return;
    setupRes.match(
      (err) => throw StateError('Core setup failed: $err'),
      (_) {},
    );

    final disableMemoryLimit = _ref.read(Preferences.disableMemoryLimit);
    final startRes = await coreService
        .start(
          configFile.path,
          'Vision VPN · Tor (${readySnapshot.activeTransport})',
          disableMemoryLimit,
          enableRawConfig: true,
        )
        .run();
    if (_disposed || opId != _operationId) return;

    startRes.match(
      (failure) => throw StateError('Failed to start Tor VPN core: $failure'),
      (_) {},
    );

    state = state.copyWith(
      phase: TorVpnPhase.verifyingTunnel,
      statusMessage: 'Verifying real internet traffic over Tor VPN...',
    );

    // End-to-End probe through sing-box mixed inbound -> DNS over Tor -> select -> tor SOCKS5 -> Exit Node.
    // Never mark Connected from Tor bootstrap or SOCKS5 alone.
    final verifiedTarget = await _verifyLiveSingboxTunnel(
      mixedPort: TorOutboundAdapter.defaultMixedPort,
      opId: opId,
    );
    if (_disposed || opId != _operationId) return;

    if (verifiedTarget == null) {
      throw StateError(
        'VPN tunnel started but End-to-End traffic probe through sing-box -> Tor failed. '
        'No real internet traffic was confirmed.',
      );
    }

    state = state.copyWith(
      phase: TorVpnPhase.connected,
      vpnActive: true,
      tunnelVerified: true,
      statusMessage: 'Verified via Tor (${readySnapshot.activeTransport}) · $verifiedTarget',
      clearLastError: true,
    );
  }

  /// Exercises the live local sing-box mixed proxy (`127.0.0.1:mixedPort`) using HTTP CONNECT
  /// to a remote HTTPS endpoint, proving that sing-box routing, DNS-over-Tor, and the Tor exit
  /// circuit actually pass real end-to-end TCP traffic.
  Future<String?> _verifyLiveSingboxTunnel({
    required int mixedPort,
    required int opId,
  }) async {
    const targets = <(String, int)>[
      ('check.torproject.org', 443),
      ('1.1.1.1', 443),
    ];

    for (var attempt = 0; attempt < 3; attempt++) {
      if (_disposed || opId != _operationId) return null;
      if (attempt > 0) {
        await Future<void>.delayed(const Duration(seconds: 2));
      }
      for (final (targetHost, targetPort) in targets) {
        if (_disposed || opId != _operationId) return null;
        final ok = await _probeHttpConnectThroughProxy(
          proxyHost: '127.0.0.1',
          proxyPort: mixedPort,
          targetHost: targetHost,
          targetPort: targetPort,
          timeout: const Duration(seconds: 10),
        );
        if (ok) return '$targetHost:$targetPort';
      }
    }
    return null;
  }

  Future<bool> _probeHttpConnectThroughProxy({
    required String proxyHost,
    required int proxyPort,
    required String targetHost,
    required int targetPort,
    required Duration timeout,
  }) async {
    Socket? socket;
    try {
      socket = await Socket.connect(proxyHost, proxyPort, timeout: timeout);
      final req = 'CONNECT $targetHost:$targetPort HTTP/1.1\r\n'
          'Host: $targetHost:$targetPort\r\n'
          'Proxy-Connection: close\r\n\r\n';
      socket.add(utf8.encode(req));
      await socket.flush();

      final buffer = StringBuffer();
      final completer = Completer<bool>();
      final sub = socket.listen(
        (chunk) {
          buffer.write(utf8.decode(chunk, allowMalformed: true));
          final text = buffer.toString();
          if (text.contains('\r\n')) {
            final statusLine = text.split('\r\n').first;
            if (!completer.isCompleted) {
              completer.complete(statusLine.contains(' 200'));
            }
          }
        },
        onError: (_) {
          if (!completer.isCompleted) completer.complete(false);
        },
        onDone: () {
          if (!completer.isCompleted) completer.complete(false);
        },
      );

      final result = await completer.future.timeout(timeout, onTimeout: () => false);
      await sub.cancel();
      return result;
    } catch (_) {
      return false;
    } finally {
      try {
        socket?.destroy();
      } catch (_) {}
    }
  }

  /// Idempotent disconnect: cancels any in-flight connect/probe, stops sing-box core, stops the
  /// Android Tor process, deletes the temporary Tor config, and resets state to stopped.
  Future<void> disconnectTorVpn() async {
    ++_operationId;
    if (!_disposed) {
      state = state.copyWith(
        phase: TorVpnPhase.stopping,
        vpnActive: false,
        tunnelVerified: false,
        statusMessage: 'Stopping Tor VPN...',
      );
    }

    try {
      await _ref.read(hiddifyCoreServiceProvider).stop().run();
    } catch (_) {}

    if (Platform.isAndroid) {
      try {
        await TorService.stop();
      } catch (_) {}
    }

    try {
      final directories = _ref.read(appDirectoriesProvider).valueOrNull;
      if (directories != null) {
        final configFile = File('${directories.workingDir.path}/visionvpn_tor_active.json');
        if (await configFile.exists()) {
          await configFile.delete();
        }
      }
    } catch (_) {}

    if (!_disposed) {
      state = state.copyWith(
        phase: TorVpnPhase.stopped,
        vpnActive: false,
        tunnelVerified: false,
        snapshot: const TorSnapshot(state: 'STOPPED', bootstrap: 0),
        clearStatusMessage: true,
        clearLastError: true,
      );
    }
  }

  Future<void> toggleTorVpn() async {
    if (state.isBusy ||
        state.vpnActive ||
        state.phase == TorVpnPhase.connected ||
        state.phase == TorVpnPhase.failClosed) {
      await disconnectTorVpn();
    } else {
      await connectTorVpn();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    ++_operationId;
    _torSub?.cancel();
    super.dispose();
  }
}
