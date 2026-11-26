import 'package:flutter_test/flutter_test.dart';
import 'package:vision_tor_plugin/vision_tor_plugin.dart';

void main() {
  test('Tor profile round-trips bridge transport and lines', () {
    const profile = TorProfile(
      id: 'home',
      name: 'Home Tor',
      transport: TorTransport.customBridges,
      bridgeLines: ['192.0.2.1:9001 0123456789012345678901234567890123456789'],
    );
    final decoded = TorProfile.decode(profile.encode());
    expect(decoded.id, profile.id);
    expect(decoded.transport, TorTransport.customBridges);
    expect(decoded.bridgeLines, profile.bridgeLines);
  });

  test('Tor status parser rejects unknown state to error', () {
    final status = TorStatus.fromMap({'state': 'not-a-real-state'});
    expect(status.state, TorState.error);
  });
}
