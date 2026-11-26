import 'tor_models.dart';

/// Creates a sing-box SOCKS outbound fragment for the local Tor listener.
/// The caller must merge it into the existing Hiddify core configuration;
/// this class deliberately does not replace or rebuild the whole config.
class TorOutboundAdapter {
  const TorOutboundAdapter._();

  static Map<String, Object?> socksOutbound({
    required TorStatus status,
    String tag = 'vision-tor',
  }) {
    if (!status.isConnected || status.socksPort == null) {
      throw StateError('Cannot create a Tor outbound before Tor is connected.');
    }
    if (status.socksHost != '127.0.0.1' && status.socksHost != '::1') {
      throw StateError('Tor SOCKS listener must remain on loopback.');
    }
    return <String, Object?>{
      'type': 'socks',
      'tag': tag,
      'server': status.socksHost,
      'server_port': status.socksPort,
      'version': '5',
    };
  }

  /// Reference strict-route fragments for a sing-box core integration.
  /// Validate them against the exact Hiddify Core schema before merging.
  /// UDP is rejected first; all remaining traffic defaults to Tor, never
  /// to a direct fallback. DNS must use a server whose detour is this tag.
  static Map<String, Object?> strictRouteFragment({
    String torTag = 'vision-tor',
  }) => <String, Object?>{
        'rules': <Map<String, Object?>>[
          <String, Object?>{
            'network': <String>['udp'],
            'action': 'reject',
          },
        ],
        'final': torTag,
      };
}
