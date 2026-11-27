import 'package:flutter/material.dart';
import 'package:hiddify/features/tor/data/tor_service.dart';
import 'package:hiddify/features/tor/notifier/tor_mode_notifier.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

class TorPage extends ConsumerStatefulWidget {
  const TorPage({super.key});

  @override
  ConsumerState<TorPage> createState() => _TorPageState();
}

class _TorPageState extends ConsumerState<TorPage> {
  final _bridgeController = TextEditingController();
  bool _savingBridges = false;

  @override
  void initState() {
    super.initState();
    _loadBridges();
  }

  Future<void> _loadBridges() async {
    try {
      final lines = await TorService.loadBridges();
      if (mounted) {
        _bridgeController.text = lines.join('\n');
      }
    } catch (_) {}
  }

  List<String> _parsedBridges() => _bridgeController.text
      .split('\n')
      .map((line) => line.trim())
      .where((line) => line.isNotEmpty)
      .toList();

  Future<void> _saveBridges() async {
    setState(() => _savingBridges = true);
    try {
      await TorService.saveBridges(_parsedBridges());
      if (mounted) _showMessage('Bridge configuration encrypted and saved.');
    } catch (e) {
      if (mounted) _showMessage('Bridge validation error: $e');
    } finally {
      if (mounted) setState(() => _savingBridges = false);
    }
  }

  Future<void> _clearBridges() async {
    setState(() => _savingBridges = true);
    try {
      await TorService.clearBridges();
      _bridgeController.clear();
      if (mounted) _showMessage('Saved bridges cleared.');
    } catch (e) {
      if (mounted) _showMessage('$e');
    } finally {
      if (mounted) setState(() => _savingBridges = false);
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  void dispose() {
    _bridgeController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final torState = ref.watch(torModeNotifierProvider);
    final notifier = ref.read(torModeNotifierProvider.notifier);
    final snap = torState.snapshot;
    final theme = Theme.of(context);

    final isConnected = torState.phase == TorVpnPhase.connected && torState.vpnActive;
    final isFailClosed = torState.phase == TorVpnPhase.failClosed || snap.failClosed;
    final statusColor = isFailClosed
        ? theme.colorScheme.error
        : (isConnected ? Colors.teal : theme.colorScheme.primary);

    return Scaffold(
      appBar: AppBar(title: const Text('Vision VPN · Tor')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('Connection Mode', style: theme.textTheme.titleMedium),
                  const SizedBox(height: 8),
                  SegmentedButton<VisionConnectionMode>(
                    segments: const [
                      ButtonSegment<VisionConnectionMode>(
                        value: VisionConnectionMode.normal,
                        icon: Icon(Icons.public_rounded),
                        label: Text('Hiddify'),
                      ),
                      ButtonSegment<VisionConnectionMode>(
                        value: VisionConnectionMode.tor,
                        icon: Icon(Icons.shield_moon_rounded),
                        label: Text('Tor'),
                      ),
                    ],
                    selected: {torState.connectionMode},
                    onSelectionChanged: torState.isBusy
                        ? null
                        : (selection) => notifier.setConnectionMode(selection.first),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                children: [
                  Icon(
                    isFailClosed ? Icons.gpp_bad_rounded : Icons.shield_moon_rounded,
                    size: 64,
                    color: statusColor,
                  ),
                  const SizedBox(height: 10),
                  Text(
                    isFailClosed
                        ? 'FAIL-CLOSED (TRAFFIC BLOCKED)'
                        : _phaseLabel(torState.phase),
                    style: theme.textTheme.titleLarge?.copyWith(color: statusColor),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 12),
                  LinearProgressIndicator(value: snap.bootstrap.clamp(0, 100) / 100),
                  const SizedBox(height: 8),
                  Text(
                    'Tor Bootstrap: ${snap.bootstrap}%'
                    '${snap.bootstrapSummary != null ? ' · ${snap.bootstrapSummary}' : ''}',
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'Transport: ${torState.transportMode.label}'
                    ' (active: ${snap.activeTransport})',
                    style: theme.textTheme.bodyMedium,
                  ),
                  if (snap.transportStatus != null) ...[
                    const SizedBox(height: 4),
                    Text(
                      snap.transportStatus!,
                      style: theme.textTheme.bodySmall,
                      textAlign: TextAlign.center,
                    ),
                  ],
                  if (snap.socksPort != null) ...[
                    const SizedBox(height: 4),
                    Text(
                      'Verified SOCKS5: ${snap.socksHost}:${snap.socksPort} (TCP only, UDP blocked, DNS over Tor)',
                      style: theme.textTheme.bodySmall,
                      textAlign: TextAlign.center,
                    ),
                  ],
                  if (torState.lastError != null || snap.transportError != null) ...[
                    const SizedBox(height: 8),
                    Text(
                      torState.lastError ?? snap.transportError!,
                      style: TextStyle(color: theme.colorScheme.error),
                      textAlign: TextAlign.center,
                    ),
                  ],
                  const SizedBox(height: 16),
                  Wrap(
                    spacing: 10,
                    runSpacing: 8,
                    alignment: WrapAlignment.center,
                    children: [
                      if (!isConnected && !isFailClosed)
                        FilledButton.icon(
                          onPressed: torState.isBusy
                              ? null
                              : () => notifier.connectTorVpn(bridges: _parsedBridges()),
                          icon: const Icon(Icons.power_settings_new),
                          label: const Text('Connect Tor VPN'),
                        ),
                      if (isFailClosed)
                        FilledButton.icon(
                          onPressed: torState.isBusy
                              ? null
                              : () => notifier.recoverFromFailClosed(bridges: _parsedBridges()),
                          icon: const Icon(Icons.refresh_rounded),
                          label: const Text('Recover Safely'),
                        ),
                      if (isConnected || isFailClosed || torState.isBusy)
                        OutlinedButton.icon(
                          onPressed: torState.phase == TorVpnPhase.stopping
                              ? null
                              : () => notifier.disconnectTorVpn(),
                          icon: const Icon(Icons.stop_rounded),
                          label: const Text('Disconnect'),
                        ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('Pluggable Transport', style: theme.textTheme.titleMedium),
                  const SizedBox(height: 8),
                  SegmentedButton<TorTransportMode>(
                    segments: const [
                      ButtonSegment<TorTransportMode>(
                        value: TorTransportMode.automatic,
                        label: Text('Automatic'),
                      ),
                      ButtonSegment<TorTransportMode>(
                        value: TorTransportMode.snowflake,
                        label: Text('Snowflake'),
                      ),
                      ButtonSegment<TorTransportMode>(
                        value: TorTransportMode.obfs4,
                        label: Text('obfs4'),
                      ),
                    ],
                    selected: {torState.transportMode},
                    onSelectionChanged: torState.isBusy
                        ? null
                        : (selection) => notifier.setTransportMode(selection.first),
                  ),
                  const SizedBox(height: 10),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      _TransportChip(label: 'Snowflake', available: snap.snowflakeAvailable),
                      _TransportChip(label: 'obfs4 (lyrebird)', available: snap.obfs4Available),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('Custom Bridges (Encrypted)', style: theme.textTheme.titleMedium),
                  const SizedBox(height: 6),
                  const Text(
                    'Supports obfs4, Snowflake, or vanilla bridge lines (one per row). '
                    'Stored encrypted in Android Keystore (AES-256-GCM).',
                  ),
                  const SizedBox(height: 10),
                  TextField(
                    controller: _bridgeController,
                    minLines: 2,
                    maxLines: 6,
                    decoration: const InputDecoration(
                      border: OutlineInputBorder(),
                      hintText: 'Bridge obfs4 <ip:port> <fingerprint> cert=... iat-mode=0',
                    ),
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: _savingBridges ? null : _saveBridges,
                          icon: const Icon(Icons.lock_outline),
                          label: const Text('Save Bridges'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      TextButton.icon(
                        onPressed: _savingBridges ? null : _clearBridges,
                        icon: const Icon(Icons.delete_outline),
                        label: const Text('Clear'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _phaseLabel(TorVpnPhase phase) => switch (phase) {
    TorVpnPhase.stopped => 'Disconnected',
    TorVpnPhase.startingTor => 'Starting Tor...',
    TorVpnPhase.bootstrapping => 'Bootstrapping Tor...',
    TorVpnPhase.verifyingSocks => 'Verifying SOCKS5...',
    TorVpnPhase.startingVpn => 'Starting Tor VPN...',
    TorVpnPhase.connected => 'Connected via Tor',
    TorVpnPhase.failClosed => 'Fail-Closed (Traffic Blocked)',
    TorVpnPhase.recovering => 'Recovering Tor...',
    TorVpnPhase.stopping => 'Disconnecting...',
    TorVpnPhase.error => 'Connection Error',
  };
}

class _TransportChip extends StatelessWidget {
  const _TransportChip({required this.label, required this.available});

  final String label;
  final bool available;

  @override
  Widget build(BuildContext context) {
    return Chip(
      avatar: Icon(
        available ? Icons.check_circle_outline : Icons.warning_amber_rounded,
        size: 18,
        color: available ? Colors.teal : Colors.orange,
      ),
      label: Text('$label: ${available ? 'Ready' : 'Binary Missing'}'),
    );
  }
}
