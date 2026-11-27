import 'dart:convert';

/// Builds and validates the live sing-box configuration used by Vision VPN's standalone Tor mode.
///
/// Security invariants enforced in Tor mode:
/// 1. Tor SOCKS5 outbound binds strictly to loopback (127.0.0.1 / ::1) and TCP only.
/// 2. Zero direct, bypass, or normal VPN proxy outbounds exist in the config (`select` wraps ONLY `tor`).
/// 3. DNS is hijacked and resolved over TCP strictly detoured through the `tor` outbound,
///    using an IP-literal server so no direct/local bootstrap resolver is ever needed or allowed.
/// 4. All non-DNS UDP traffic is explicitly rejected.
/// 5. All TCP traffic is routed strictly through `select` -> `tor`.
/// 6. Any unmatched traffic hits a catch-all `reject` rule (fail-closed).
/// 7. NTP is omitted so no direct UDP/123 packets leave the device.
class TorOutboundAdapter {
  const TorOutboundAdapter._();

  static const String defaultSelectTag = 'select';
  static const String defaultTorTag = 'tor';
  static const String defaultDnsTorTag = 'dns-tor';
  static const String defaultDnsServerIp = '1.1.1.1';
  static const int defaultMixedPort = 12334;
  static const int defaultClashApiPort = 16756;

  static Map<String, Object> build({
    required String host,
    required int port,
    String tag = defaultTorTag,
  }) {
    if (host != '127.0.0.1' && host != '::1') {
      throw ArgumentError.value(host, 'host', 'Tor SOCKS must bind to loopback');
    }
    if (port < 1 || port > 65535) {
      throw ArgumentError.value(port, 'port', 'Tor SOCKS port must be 1..65535');
    }
    return <String, Object>{
      'type': 'socks',
      'tag': tag,
      'server': host,
      'server_port': port,
      'version': '5',
      'network': 'tcp',
    };
  }

  static String encode({
    required String host,
    required int port,
    String tag = defaultTorTag,
  }) => const JsonEncoder.withIndent('  ').convert(build(host: host, port: port, tag: tag));

  /// Builds the complete live sing-box 1.12 JSON configuration for Vision VPN Tor mode,
  /// executed by hiddify-core v5.9.15 with `enableRawConfig: true`.
  ///
  /// Includes a `select` selector outbound pointing strictly to `["tor"]` and a loopback
  /// `clash_api` controller so the core's status monitor and traffic stats recognize the active
  /// outbound group and transition cleanly from `CoreWaiting` to `CoreStarted` without ever
  /// introducing a `direct` or non-Tor outbound.
  static Map<String, Object> buildFullSingboxConfig({
    required String socksHost,
    required int socksPort,
    int mixedPort = defaultMixedPort,
    int clashApiPort = defaultClashApiPort,
    int mtu = 9000,
    String logLevel = 'warn',
    String dnsServerIp = defaultDnsServerIp,
    bool enableTun = true,
  }) {
    final torOutbound = build(host: socksHost, port: socksPort, tag: defaultTorTag);
    if (dnsServerIp.trim().isEmpty || dnsServerIp.trim().toLowerCase() == 'local') {
      throw ArgumentError.value(dnsServerIp, 'dnsServerIp', 'System/local DNS is forbidden in Tor mode');
    }

    final selectorOutbound = <String, Object>{
      'type': 'selector',
      'tag': defaultSelectTag,
      'outbounds': <String>[defaultTorTag],
      'default': defaultTorTag,
    };

    final inbounds = <Map<String, Object>>[
      if (enableTun)
        <String, Object>{
          'type': 'tun',
          'tag': 'tun-in',
          'address': <String>['172.19.0.1/28', 'fdfe:dcba:9876::1/126'],
          'mtu': mtu,
          'auto_route': true,
          'strict_route': true,
          'stack': 'mixed',
          'endpoint_independent_nat': true,
        },
      <String, Object>{
        'type': 'mixed',
        'tag': 'mixed-in',
        'listen': '127.0.0.1',
        'listen_port': mixedPort,
      },
    ];

    final config = <String, Object>{
      'log': <String, Object>{
        'level': logLevel,
        'timestamp': false,
      },
      'experimental': <String, Object>{
        'clash_api': <String, Object>{
          'external_controller': '127.0.0.1:$clashApiPort',
        },
      },
      'dns': <String, Object>{
        'servers': <Map<String, Object>>[
          <String, Object>{
            'type': 'tcp',
            'tag': defaultDnsTorTag,
            'detour': defaultTorTag,
            'server': dnsServerIp,
          },
        ],
        'rules': const <Map<String, Object>>[],
        'final': defaultDnsTorTag,
        'independent_cache': true,
      },
      'inbounds': inbounds,
      'outbounds': <Map<String, Object>>[
        selectorOutbound,
        torOutbound,
      ],
      'route': <String, Object>{
        'auto_detect_interface': true,
        'default_domain_resolver': defaultDnsTorTag,
        'rules': <Map<String, Object>>[
          <String, Object>{
            'action': 'sniff',
          },
          <String, Object>{
            'protocol': 'dns',
            'action': 'hijack-dns',
          },
          <String, Object>{
            'port': 53,
            'action': 'hijack-dns',
          },
          <String, Object>{
            'network': 'udp',
            'action': 'reject',
          },
          <String, Object>{
            'network': 'tcp',
            'outbound': defaultSelectTag,
          },
          // Catch-all fail-closed reject rule so no non-TCP/unmatched packet ever escapes.
          <String, Object>{
            'action': 'reject',
          },
        ],
        'final': defaultSelectTag,
      },
    };

    validateFullSingboxConfig(config);
    return config;
  }

  static String encodeFullSingboxConfig({
    required String socksHost,
    required int socksPort,
    int mixedPort = defaultMixedPort,
    int clashApiPort = defaultClashApiPort,
    int mtu = 9000,
    String logLevel = 'warn',
    String dnsServerIp = defaultDnsServerIp,
    bool enableTun = true,
  }) => const JsonEncoder.withIndent('  ').convert(
    buildFullSingboxConfig(
      socksHost: socksHost,
      socksPort: socksPort,
      mixedPort: mixedPort,
      clashApiPort: clashApiPort,
      mtu: mtu,
      logLevel: logLevel,
      dnsServerIp: dnsServerIp,
      enableTun: enableTun,
    ),
  );

  /// Strict security assertion run before writing/activating any Tor sing-box config.
  static void validateFullSingboxConfig(Map<String, Object> config) {
    if (config.containsKey('ntp')) {
      throw StateError('NTP must not be present in Tor sing-box config');
    }

    final outbounds = (config['outbounds'] as List<dynamic>? ?? const <dynamic>[])
        .cast<Map<String, Object>>();
    if (outbounds.length != 2) {
      throw StateError('Tor sing-box config must contain strictly select and tor outbounds');
    }

    Map<String, Object>? selectOut;
    Map<String, Object>? torOut;
    for (final out in outbounds) {
      final type = out['type'];
      final tag = out['tag'];
      if (type == 'direct' || tag == 'direct' || tag == 'bypass') {
        throw StateError('Forbidden direct/bypass outbound detected in Tor mode: $out');
      }
      if (tag == defaultSelectTag) {
        selectOut = out;
      } else if (tag == defaultTorTag) {
        torOut = out;
      } else {
        throw StateError('Unexpected outbound in Tor mode: $out');
      }
    }

    if (selectOut == null || selectOut['type'] != 'selector') {
      throw StateError('Tor sing-box config must include selector outbound "$defaultSelectTag"');
    }
    final selectMembers = (selectOut['outbounds'] as List<dynamic>? ?? const <dynamic>[]).cast<String>();
    if (selectMembers.length != 1 || selectMembers.first != defaultTorTag || selectOut['default'] != defaultTorTag) {
      throw StateError('Selector outbound must exclusively target "$defaultTorTag"');
    }

    if (torOut == null || torOut['type'] != 'socks' || torOut['network'] != 'tcp') {
      throw StateError('Tor outbound must be TCP-only SOCKS5');
    }
    final server = torOut['server'];
    if (server != '127.0.0.1' && server != '::1') {
      throw StateError('Tor outbound server must be loopback');
    }

    final dns = config['dns'] as Map<String, Object>?;
    if (dns == null || dns['final'] != defaultDnsTorTag) {
      throw StateError('DNS final server must be $defaultDnsTorTag');
    }
    final dnsServers = (dns['servers'] as List<dynamic>? ?? const <dynamic>[])
        .cast<Map<String, Object>>();
    if (dnsServers.isEmpty) {
      throw StateError('Tor DNS servers cannot be empty');
    }
    for (final srv in dnsServers) {
      if (srv['type'] == 'local' || srv['type'] == 'udp' || srv['detour'] != defaultTorTag) {
        throw StateError('Forbidden direct/local/UDP DNS server detected in Tor mode: $srv');
      }
    }

    final route = config['route'] as Map<String, Object>?;
    final rules = (route?['rules'] as List<dynamic>? ?? const <dynamic>[])
        .cast<Map<String, Object>>();
    final hasDnsHijack = rules.any((r) => r['protocol'] == 'dns' && r['action'] == 'hijack-dns');
    final hasUdpReject = rules.any((r) => r['network'] == 'udp' && r['action'] == 'reject');
    final hasTcpTor = rules.any(
      (r) => r['network'] == 'tcp' && (r['outbound'] == defaultSelectTag || r['outbound'] == defaultTorTag),
    );
    final hasCatchAllReject = rules.isNotEmpty && rules.last['action'] == 'reject';
    if (!hasDnsHijack || !hasUdpReject || !hasTcpTor || !hasCatchAllReject) {
      throw StateError('Tor route rules failed fail-closed / UDP-block / DNS-hijack validation');
    }
  }
}
