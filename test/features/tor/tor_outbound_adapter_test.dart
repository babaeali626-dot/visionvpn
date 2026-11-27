import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/features/tor/data/tor_outbound_adapter.dart';
import 'package:hiddify/features/tor/model/tor_mode_state.dart';

void main() {
  group('TorOutboundAdapter & live sing-box Tor config security', () {
    test('builds loopback TCP-only SOCKS5 outbound', () {
      final out = TorOutboundAdapter.build(host: '127.0.0.1', port: 41234);
      expect(out['type'], 'socks');
      expect(out['tag'], 'tor');
      expect(out['server'], '127.0.0.1');
      expect(out['server_port'], 41234);
      expect(out['version'], '5');
      expect(out['network'], 'tcp');
    });

    test('rejects non-loopback host and invalid ports', () {
      expect(() => TorOutboundAdapter.build(host: '0.0.0.0', port: 9050), throwsArgumentError);
      expect(() => TorOutboundAdapter.build(host: '192.168.1.5', port: 9050), throwsArgumentError);
      expect(() => TorOutboundAdapter.build(host: '127.0.0.1', port: 0), throwsArgumentError);
      expect(() => TorOutboundAdapter.build(host: '127.0.0.1', port: 70000), throwsArgumentError);
    });

    test('full sing-box Tor config enforces DNS over Tor, UDP block, zero direct, and fail-closed', () {
      final raw = TorOutboundAdapter.encodeFullSingboxConfig(
        socksHost: '127.0.0.1',
        socksPort: 45123,
      );
      final config = jsonDecode(raw) as Map<String, dynamic>;

      // 1. No NTP (prevents direct UDP/123 leaks)
      expect(config.containsKey('ntp'), isFalse);

      // 2. Outbounds: strictly one Tor SOCKS5 TCP outbound, zero direct/bypass outbounds
      final outbounds = (config['outbounds'] as List<dynamic>).cast<Map<String, dynamic>>();
      expect(outbounds.length, 1);
      expect(outbounds.first['type'], 'socks');
      expect(outbounds.first['tag'], 'tor');
      expect(outbounds.first['server'], '127.0.0.1');
      expect(outbounds.first['server_port'], 45123);
      expect(outbounds.first['network'], 'tcp');

      // 3. DNS: strictly over Tor (TCP detoured through tor), zero local/direct/UDP DNS
      final dns = config['dns'] as Map<String, dynamic>;
      expect(dns['final'], 'dns-tor');
      final dnsServers = (dns['servers'] as List<dynamic>).cast<Map<String, dynamic>>();
      expect(dnsServers.length, 1);
      expect(dnsServers.first['type'], 'tcp');
      expect(dnsServers.first['detour'], 'tor');
      expect(dnsServers.first['server'], '1.1.1.1');

      // 4. Route: hijack-dns -> reject UDP -> TCP to tor -> catch-all reject (fail-closed)
      final route = config['route'] as Map<String, dynamic>;
      final rules = (route['rules'] as List<dynamic>).cast<Map<String, dynamic>>();
      expect(rules.any((r) => r['protocol'] == 'dns' && r['action'] == 'hijack-dns'), isTrue);
      expect(rules.any((r) => r['port'] == 53 && r['action'] == 'hijack-dns'), isTrue);
      expect(rules.any((r) => r['network'] == 'udp' && r['action'] == 'reject'), isTrue);
      expect(rules.any((r) => r['network'] == 'tcp' && r['outbound'] == 'tor'), isTrue);
      expect(rules.last['action'], 'reject');
    });

    test('forbids system/local DNS in Tor config', () {
      expect(
        () => TorOutboundAdapter.buildFullSingboxConfig(
          socksHost: '127.0.0.1',
          socksPort: 45123,
          dnsServerIp: 'local',
        ),
        throwsArgumentError,
      );
    });

    test('validator rejects tampered config with direct outbound or NTP', () {
      final config = TorOutboundAdapter.buildFullSingboxConfig(
        socksHost: '127.0.0.1',
        socksPort: 45123,
      );
      final withNtp = Map<String, Object>.from(config)..['ntp'] = <String, Object>{'enabled': true};
      expect(() => TorOutboundAdapter.validateFullSingboxConfig(withNtp), throwsStateError);
    });
  });

  group('TorSnapshot, TransportMode & default Hiddify preservation', () {
    test('default TorModeState keeps normal Hiddify mode active', () {
      const state = TorModeState();
      expect(state.connectionMode, VisionConnectionMode.normal);
      expect(state.isTorModeSelected, isFalse);
      expect(state.transportMode, TorTransportMode.automatic);
      expect(state.phase, TorVpnPhase.stopped);
    });

    test('TorSnapshot.isTorReady requires CONNECTED, bootstrap 100%, loopback host, and valid port', () {
      const ready = TorSnapshot(
        state: 'CONNECTED',
        bootstrap: 100,
        socksHost: '127.0.0.1',
        socksPort: 39123,
        transportMode: 'snowflake',
        activeTransport: 'snowflake',
        snowflakeAvailable: true,
        obfs4Available: true,
      );
      expect(ready.isTorReady, isTrue);

      const bootstrapping = TorSnapshot(
        state: 'BOOTSTRAPPING',
        bootstrap: 85,
        socksHost: '127.0.0.1',
        socksPort: 39123,
      );
      expect(bootstrapping.isTorReady, isFalse);

      const failClosed = TorSnapshot(
        state: 'FAIL_CLOSED',
        bootstrap: 100,
        socksHost: null,
        socksPort: null,
        failClosed: true,
        error: 'SOCKS5 probe failed',
      );
      expect(failClosed.isTorReady, isFalse);
      expect(failClosed.failClosed, isTrue);
    });

    test('TorTransportMode parses automatic, snowflake, and obfs4 cleanly', () {
      expect(TorTransportMode.fromKey('automatic'), TorTransportMode.automatic);
      expect(TorTransportMode.fromKey('snowflake'), TorTransportMode.snowflake);
      expect(TorTransportMode.fromKey('obfs4'), TorTransportMode.obfs4);
      expect(TorTransportMode.fromKey('unknown'), TorTransportMode.automatic);
    });
  });
}
