import 'dart:convert';

enum TorState {
  stopped,
  starting,
  bootstrapping,
  connected,
  reconnecting,
  stopping,
  error,
}

enum TorTransport {
  direct,
  customBridges,
  obfs4,
  snowflake,
  meek,
}

extension TorTransportWire on TorTransport {
  String get wireName => switch (this) {
        TorTransport.direct => 'direct',
        TorTransport.customBridges => 'customBridges',
        TorTransport.obfs4 => 'obfs4',
        TorTransport.snowflake => 'snowflake',
        TorTransport.meek => 'meek',
      };

  String get label => switch (this) {
        TorTransport.direct => 'Tor Direct',
        TorTransport.customBridges => 'Custom Bridges (Direct)',
        TorTransport.obfs4 => 'obfs4',
        TorTransport.snowflake => 'Snowflake',
        TorTransport.meek => 'Meek',
      };

  static TorTransport fromWire(String? value) => TorTransport.values.firstWhere(
        (item) => item.wireName == value,
        orElse: () => TorTransport.direct,
      );
}

class TorProfile {
  const TorProfile({
    required this.id,
    required this.name,
    this.transport = TorTransport.direct,
    this.bridgeLines = const <String>[],
  });

  final String id;
  final String name;
  final TorTransport transport;
  final List<String> bridgeLines;

  Map<String, Object?> toJson() => <String, Object?>{
        'id': id,
        'name': name,
        'transport': transport.wireName,
        'bridgeLines': bridgeLines,
      };

  factory TorProfile.fromJson(Map<String, Object?> json) => TorProfile(
        id: json['id'] as String,
        name: json['name'] as String? ?? 'Tor profile',
        transport: TorTransportWire.fromWire(json['transport'] as String?),
        bridgeLines: (json['bridgeLines'] as List<Object?>? ?? const <Object?>[])
            .whereType<String>()
            .toList(growable: false),
      );

  String encode() => jsonEncode(toJson());

  factory TorProfile.decode(String value) => TorProfile.fromJson(
        (jsonDecode(value) as Map).cast<String, Object?>(),
      );
}

class TorStatus {
  const TorStatus({
    required this.state,
    this.bootstrapPercent = 0,
    this.socksHost = '127.0.0.1',
    this.socksPort,
    this.message = '',
  });

  final TorState state;
  final int bootstrapPercent;
  final String socksHost;
  final int? socksPort;
  final String message;

  bool get isConnected => state == TorState.connected && socksPort != null;

  factory TorStatus.fromMap(Map<Object?, Object?> map) {
    final rawState = map['state'] as String? ?? 'stopped';
    final state = TorState.values.firstWhere(
      (value) => value.name == rawState,
      orElse: () => TorState.error,
    );
    return TorStatus(
      state: state,
      bootstrapPercent: (map['bootstrapPercent'] as num?)?.toInt() ?? 0,
      socksHost: map['socksHost'] as String? ?? '127.0.0.1',
      socksPort: (map['socksPort'] as num?)?.toInt(),
      message: map['message'] as String? ?? '',
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
        'state': state.name,
        'bootstrapPercent': bootstrapPercent,
        'socksHost': socksHost,
        'socksPort': socksPort,
        'message': message,
      };

  static const stopped = TorStatus(state: TorState.stopped);
}

class TorTransportCapability {
  const TorTransportCapability({
    required this.transport,
    required this.supported,
    this.reason = '',
  });

  final TorTransport transport;
  final bool supported;
  final String reason;

  factory TorTransportCapability.fromMap(Map<Object?, Object?> map) =>
      TorTransportCapability(
        transport: TorTransportWire.fromWire(map['transport'] as String?),
        supported: map['supported'] as bool? ?? false,
        reason: map['reason'] as String? ?? '',
      );
}
