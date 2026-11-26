import 'package:flutter/services.dart';

import 'tor_models.dart';

/// Flutter bridge to the Android Tor daemon. This controller does not create
/// a second Android VPNService; it only manages a local Tor SOCKS listener.
class VisionTorController {
  VisionTorController();

  static const MethodChannel _methods =
      MethodChannel('vision_vpn/tor/methods');
  static const EventChannel _events = EventChannel('vision_vpn/tor/events');

  Stream<TorStatus>? _cachedEvents;

  Stream<TorStatus> get events => _cachedEvents ??= _events
      .receiveBroadcastStream()
      .map((dynamic value) => TorStatus.fromMap(
            (value as Map).cast<Object?, Object?>(),
          ))
      .asBroadcastStream();

  Future<void> start(TorProfile profile) async {
    await _methods.invokeMethod<void>('start', profile.toJson());
  }

  Future<void> stop() async {
    await _methods.invokeMethod<void>('stop');
  }

  Future<TorStatus> status() async {
    final result = await _methods.invokeMapMethod<Object?, Object?>('status');
    return TorStatus.fromMap(result ?? <Object?, Object?>{});
  }

  Future<List<TorTransportCapability>> capabilities() async {
    final result = await _methods.invokeListMethod<Object?>('capabilities') ??
        const <Object?>[];
    return result
        .whereType<Map>()
        .map((item) => TorTransportCapability.fromMap(
              item.cast<Object?, Object?>(),
            ))
        .toList(growable: false);
  }

  Future<void> saveProfile(TorProfile profile) async {
    await _methods.invokeMethod<void>('saveProfile', profile.toJson());
  }

  Future<List<TorProfile>> profiles() async {
    final result = await _methods.invokeListMethod<Object?>('profiles') ??
        const <Object?>[];
    return result
        .whereType<Map>()
        .map((item) => TorProfile.fromJson(item.cast<String, Object?>()))
        .toList(growable: false);
  }

  Future<void> deleteProfile(String id) async {
    await _methods.invokeMethod<void>('deleteProfile', <String, Object?>{
      'id': id,
    });
  }

  /// Returns the real local SOCKS outbound only after Tor has completed its
  /// initial circuit and the loopback listener has been verified.
  Future<Map<String, Object?>> socksOutbound({String tag = 'vision-tor'}) async {
    final result = await _methods.invokeMapMethod<Object?, Object?>(
      'socksOutbound',
      <String, Object?>{'tag': tag},
    );
    if (result == null) {
      throw StateError('Tor SOCKS listener is not ready.');
    }
    return result.cast<String, Object?>();
  }
}
