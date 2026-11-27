import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/features/tor/data/tor_outbound_adapter.dart';

void main() {
  group('TorOutboundAdapter', () {
    test('builds valid TCP-only loopback SOCKS5 outbound', () {
      final map = TorOutboundAdapter.build(host: '127.0.0.1', port: 9050);
      expect(map['type'], 'socks');
      expect(map['tag'], 'tor');
      expect(map['server'], '127.0.0.1');
      expect(map['server_port'], 9050);
      expect(map['version'], '5');
      expect(map['network'], 'tcp');
    });

    test('rejects non-loopback hosts and invalid ports', () {
      expect(() => TorOutboundAdapter.build(host: '192.168.1.10', port: 9050), throwsArgumentError);
      expect(() => TorOutboundAdapter.build(host: '127.0.0.1', port: 0), throwsArgumentError);
      expect(() => TorOutboundAdapter.build(host: '127.0.0.1', port: 70000), throwsArgumentError);
    });

    test('builds complete fail-closed sing-box config with select->tor, clash_api, zero direct, DNS over Tor, UDP reject', () {
      final config = TorOutboundAdapter.buildFullSingboxConfig(
        socksHost: '127.0.0.1',
        socksPort: 39051,
      );

      // 1. No NTP
      expect(config.containsKey('ntp'), isFalse);

      // 2. Experimental Clash API on loopback only
      final experimental = config['experimental']! as Map<String, Object>;
      final clashApi = experimental['clash_api']! as Map<String, Object>;
      expect(clashApi['external_controller'], '127.0.0.1:16756');

      // 3. Strictly select (wrapping only tor) and tor outbound — zero direct/bypass
      final outbounds = (config['outbounds']! as List<dynamic>).cast<Map<String, Object>>();
      expect(outbounds.length, 2);
      expect(outbounds[0]['type'], 'selector');
      expect(outbounds[0]['tag'], 'select');
      expect(outbounds[0]['outbounds'], ['tor']);
      expect(outbounds[0]['default'], 'tor');

      expect(outbounds[1]['type'], 'socks');
      expect(outbounds[1]['tag'], 'tor');
      expect(outbounds[1]['server'], '127.0.0.1');
      expect(outbounds[1]['server_port'], 39051);
      expect(outbounds[1]['network'], 'tcp');

      // 4. DNS strictly detoured through tor over TCP (no local/UDP)
      final dns = config['dns']! as Map<String, Object>;
      expect(dns['final'], 'dns-tor');
      final servers = (dns['servers']! as List<dynamic>).cast<Map<String, Object>>();
      expect(servers.length, 1);
      expect(servers.first['type'], 'tcp');
      expect(servers.first['detour'], 'tor');
      expect(servers.first['server'], '1.1.1.1');

      // 5. Route: hijack-dns -> reject UDP -> TCP to select -> catch-all reject (fail-closed)
      final route = config['route']! as Map<String, Object>;
      expect(route['default_domain_resolver'], 'dns-tor');
      expect(route['final'], 'select');
      final rules = (route['rules']! as List<dynamic>).cast<Map<String, Object>>();
      expect(rules.any((r) => r['protocol'] == 'dns' && r['action'] == 'hijack-dns'), isTrue);
      expect(rules.any((r) => r['port'] == 53 && r['action'] == 'hijack-dns'), isTrue);
      expect(rules.any((r) => r['network'] == 'udp' && r['action'] == 'reject'), isTrue);
      expect(rules.any((r) => r['network'] == 'tcp' && r['outbound'] == 'select'), isTrue);
      expect(rules.last['action'], 'reject');

      // 6. Valid JSON output
      final encoded = TorOutboundAdapter.encodeFullSingboxConfig(
        socksHost: '127.0.0.1',
        socksPort: 39051,
      );
      expect(jsonDecode(encoded), isA<Map<String, dynamic>>());
    });

    test('validator rejects forbidden direct outbound or local DNS', () {
      final config = TorOutboundAdapter.buildFullSingboxConfig(
        socksHost: '127.0.0.1',
        socksPort: 39051,
      );
      final outbounds = List<Map<String, Object>>.from(config['outbounds']! as List<dynamic>);
      outbounds.add(<String, Object>{'type': 'direct', 'tag': 'direct'});
      final tampered = Map<String, Object>.from(config)..['outbounds'] = outbounds;
      expect(() => TorOutboundAdapter.validateFullSingboxConfig(tampered), throwsStateError);
    });

    test('validator rejects selector outbound containing non-tor member', () {
      final config = TorOutboundAdapter.buildFullSingboxConfig(
        socksHost: '127.0.0.1',
        socksPort: 39051,
      );
      final outbounds = List<Map<String, Object>>.from(config['outbounds']! as List<dynamic>);
      outbounds[0] = <String, Object>{
        'type': 'selector',
        'tag': 'select',
        'outbounds': <String>['tor', 'direct'],
        'default': 'tor',
      };
      final tampered = Map<String, Object>.from(config)..['outbounds'] = outbounds;
      expect(() => TorOutboundAdapter.validateFullSingboxConfig(tampered), throwsStateError);
    });
  });
}
