import 'package:flutter_test/flutter_test.dart';
import 'package:vision_tor_plugin/vision_tor_plugin.dart';

void main() {
  test('SOCKS outbound points only to loopback and actual reported port', () {
    const status = TorStatus(
      state: TorState.connected,
      socksPort: 41521,
      socksHost: '127.0.0.1',
    );
    final outbound = TorOutboundAdapter.socksOutbound(status: status);
    expect(outbound['type'], 'socks');
    expect(outbound['server'], '127.0.0.1');
    expect(outbound['server_port'], 41521);
  });

  test('outbound is unavailable before a confirmed connection', () {
    expect(
      () => TorOutboundAdapter.socksOutbound(status: TorStatus.stopped),
      throwsStateError,
    );
  });

  test('non-loopback listener is rejected', () {
    const status = TorStatus(
      state: TorState.connected,
      socksPort: 9050,
      socksHost: '192.168.1.5',
    );
    expect(
      () => TorOutboundAdapter.socksOutbound(status: status),
      throwsStateError,
    );
  });
}
