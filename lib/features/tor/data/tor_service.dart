import 'dart:async';

import 'package:flutter/services.dart';
import 'package:hiddify/features/tor/model/tor_mode_state.dart';

export 'package:hiddify/features/tor/model/tor_mode_state.dart';

/// Platform bridge to Android's native TorEngineManager and BridgeVault.
class TorService {
  static const _methods = MethodChannel('visionvpn.tor/method');
  static const _events = EventChannel('visionvpn.tor/events');
  static Stream<TorSnapshot>? _stream;

  static Stream<TorSnapshot> get events => _stream ??= _events
      .receiveBroadcastStream()
      .where((event) => event is Map)
      .map((event) => TorSnapshot.fromMap(event as Map<Object?, Object?>));

  static Future<TorSnapshot> status() async => TorSnapshot.fromMap(
    (await _methods.invokeMapMethod<Object?, Object?>('status')) ?? const <Object?, Object?>{},
  );

  static Future<TorSnapshot> start({
    List<String> bridges = const <String>[],
    TorTransportMode transportMode = TorTransportMode.automatic,
  }) async => TorSnapshot.fromMap(
    (await _methods.invokeMapMethod<Object?, Object?>('start', <String, Object>{
          'bridges': bridges,
          'transportMode': transportMode.key,
        })) ??
        const <Object?, Object?>{},
  );

  static Future<TorSnapshot> recover({List<String> bridges = const <String>[]}) async =>
      TorSnapshot.fromMap(
        (await _methods.invokeMapMethod<Object?, Object?>('recover', <String, Object>{
              'bridges': bridges,
            })) ??
            const <Object?, Object?>{},
      );

  static Future<TorSnapshot> setTransportMode(TorTransportMode transportMode) async =>
      TorSnapshot.fromMap(
        (await _methods.invokeMapMethod<Object?, Object?>('setTransportMode', <String, Object>{
              'transportMode': transportMode.key,
            })) ??
            const <Object?, Object?>{},
      );

  static Future<void> setTorModeEnabled(bool enabled) async =>
      _methods.invokeMethod<void>('setTorModeEnabled', <String, Object>{'enabled': enabled});

  static Future<Map<String, bool>> capabilities() async {
    final raw = await _methods.invokeMapMethod<Object?, Object?>('capabilities');
    return <String, bool>{
      'snowflakeAvailable': raw?['snowflakeAvailable'] as bool? ?? false,
      'obfs4Available': raw?['obfs4Available'] as bool? ?? false,
    };
  }

  static Future<void> stop() async => _methods.invokeMethod<void>('stop');

  static Future<TorSnapshot> triggerFailClosed({String? reason}) async => TorSnapshot.fromMap(
    (await _methods.invokeMapMethod<Object?, Object?>('triggerFailClosed', <String, Object?>{
          if (reason != null) 'reason': reason,
        })) ??
        const <Object?, Object?>{},
  );

  static Future<void> saveBridges(List<String> bridges) async =>
      _methods.invokeMethod<void>('saveBridges', <String, Object>{'bridges': bridges});

  static Future<List<String>> loadBridges() async =>
      (await _methods.invokeListMethod<String>('loadBridges')) ?? const <String>[];

  static Future<void> clearBridges() async => _methods.invokeMethod<void>('clearBridges');

  /// Waits until Tor reaches bootstrap 100% and passes native SOCKS5 loopback handshake verification.
  /// Throws [StateError] immediately if Tor enters ERROR, FAIL_CLOSED, or STOPPED, or if [timeout] elapses.
  static Future<TorSnapshot> waitUntilReady({
    Duration timeout = const Duration(seconds: 90),
    void Function(TorSnapshot snapshot)? onProgress,
  }) async {
    final current = await status();
    onProgress?.call(current);
    if (current.isTorReady) return current;
    if (current.state == 'ERROR' || current.state == 'FAIL_CLOSED') {
      throw StateError(current.error ?? current.transportError ?? 'Tor failed before becoming ready');
    }

    final completer = Completer<TorSnapshot>();
    late final StreamSubscription<TorSnapshot> sub;
    final timer = Timer(timeout, () {
      if (!completer.isCompleted) {
        completer.completeError(
          TimeoutException('Tor bootstrap timed out after ${timeout.inSeconds}s'),
        );
      }
    });

    sub = events.listen(
      (snap) {
        onProgress?.call(snap);
        if (completer.isCompleted) return;
        if (snap.isTorReady) {
          completer.complete(snap);
        } else if (snap.state == 'ERROR' || snap.state == 'FAIL_CLOSED' || snap.state == 'STOPPED') {
          completer.completeError(
            StateError(snap.error ?? snap.transportError ?? 'Tor stopped during bootstrap (${snap.state})'),
          );
        }
      },
      onError: (Object err, StackTrace st) {
        if (!completer.isCompleted) completer.completeError(err, st);
      },
    );

    try {
      return await completer.future;
    } finally {
      timer.cancel();
      await sub.cancel();
    }
  }
}
