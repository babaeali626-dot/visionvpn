import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/features/tor/data/tor_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const methodChannel = MethodChannel('visionvpn.tor/method');
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (MethodCall call) async {
      calls.add(call);
      switch (call.method) {
        case 'start':
          final args = call.arguments as Map<Object?, Object?>;
          final mode = args['transportMode'] as String? ?? 'automatic';
          return <String, Object?>{
            'state': 'CONNECTED',
            'bootstrap': 100,
            'bootstrapSummary': 'Done',
            'socksHost': '127.0.0.1',
            'socksPort': 41999,
            'transportMode': mode,
            'activeTransport': mode == 'automatic' ? 'direct' : mode,
            'snowflakeAvailable': true,
            'obfs4Available': true,
            'failClosed': false,
          };
        case 'status':
          return <String, Object?>{
            'state': 'CONNECTED',
            'bootstrap': 100,
            'socksHost': '127.0.0.1',
            'socksPort': 41999,
            'transportMode': 'snowflake',
            'activeTransport': 'snowflake',
            'snowflakeAvailable': true,
            'obfs4Available': true,
            'failClosed': false,
          };
        case 'triggerFailClosed':
          return <String, Object?>{
            'state': 'FAIL_CLOSED',
            'bootstrap': 100,
            'socksHost': null,
            'socksPort': null,
            'transportMode': 'snowflake',
            'activeTransport': 'snowflake',
            'failClosed': true,
            'error': 'Tor SOCKS5 probe failed',
          };
        case 'recover':
          return <String, Object?>{
            'state': 'CONNECTED',
            'bootstrap': 100,
            'socksHost': '127.0.0.1',
            'socksPort': 42001,
            'transportMode': 'snowflake',
            'activeTransport': 'snowflake',
            'failClosed': false,
          };
        case 'stop':
          return true;
        default:
          return null;
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, null);
  });

  test('passes Snowflake transport selection directly to native TorEngineManager', () async {
    final snap = await TorService.start(transportMode: TorTransportMode.snowflake);
    expect(calls.single.method, 'start');
    expect((calls.single.arguments as Map<Object?, Object?>)['transportMode'], 'snowflake');
    expect(snap.activeTransport, 'snowflake');
    expect(snap.isTorReady, isTrue);
  });

  test('passes obfs4 transport selection and custom bridges to native TorEngineManager', () async {
    const bridge =
        'Bridge obfs4 192.95.36.142:443 CDF2E852BF539B82BD10E27E9115A31734E378C2 cert=abc1234567890 iat-mode=1';
    final snap = await TorService.start(
      transportMode: TorTransportMode.obfs4,
      bridges: const [bridge],
    );
    final args = calls.single.arguments as Map<Object?, Object?>;
    expect(args['transportMode'], 'obfs4');
    expect(args['bridges'], [bridge]);
    expect(snap.activeTransport, 'obfs4');
  });

  test('fail-closed transition clears SOCKS endpoint and recovery restores verified loopback port', () async {
    final fc = await TorService.triggerFailClosed(reason: 'Simulated Tor crash');
    expect(fc.state, 'FAIL_CLOSED');
    expect(fc.failClosed, isTrue);
    expect(fc.isTorReady, isFalse);
    expect(fc.socksPort, isNull);

    final recovered = await TorService.recover();
    expect(recovered.state, 'CONNECTED');
    expect(recovered.failClosed, isFalse);
    expect(recovered.isTorReady, isTrue);
    expect(recovered.socksPort, 42001);
  });
}
