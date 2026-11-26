import 'dart:async';

import 'package:flutter/material.dart';

import 'src/tor_models.dart';
import 'src/vision_tor_controller.dart';

/// Vision VPN's Tor dashboard, designed around the supplied neon dashboard
/// reference. It is a feature page, not a replacement for Hiddify's complete
/// home/profile/settings navigation.
class VisionTorDashboard extends StatefulWidget {
  const VisionTorDashboard({
    super.key,
    required this.controller,
    this.initialProfile,
    this.onOutboundReady,
  });

  final VisionTorController controller;
  final TorProfile? initialProfile;
  final ValueChanged<Map<String, Object?>>? onOutboundReady;

  @override
  State<VisionTorDashboard> createState() => _VisionTorDashboardState();
}

class _VisionTorDashboardState extends State<VisionTorDashboard> {
  late TorProfile _profile;
  final TextEditingController _bridges = TextEditingController();
  late final TextEditingController _name;
  late final StreamSubscription<TorStatus> _subscription;
  List<TorProfile> _profiles = const <TorProfile>[];
  TorStatus _status = TorStatus.stopped;
  bool _busy = false;
  List<TorTransportCapability> _capabilities = const [];
  String? _error;

  @override
  void initState() {
    super.initState();
    _profile = widget.initialProfile ?? const TorProfile(
      id: 'vision-tor-default',
      name: 'Vision Tor',
    );
    _bridges.text = _profile.bridgeLines.join('\n');
    _name = TextEditingController(text: _profile.name);
    _loadProfiles();
    widget.controller.status().then((value) {
      if (mounted) setState(() => _status = value);
    }).catchError((Object error) {
      if (mounted) setState(() => _error = error.toString());
    });
    widget.controller.capabilities().then((value) {
      if (mounted) setState(() => _capabilities = value);
    });
    _subscription = widget.controller.events.listen((value) {
      if (!mounted) return;
      setState(() => _status = value);
      if (value.isConnected) {
        widget.controller.socksOutbound().then((outbound) {
          widget.onOutboundReady?.call(outbound);
        }).catchError((Object error) {
          if (mounted) setState(() => _error = error.toString());
        });
      }
    }, onError: (Object error) {
      if (mounted) setState(() => _error = error.toString());
    });
  }

  @override
  void dispose() {
    _subscription.cancel();
    _bridges.dispose();
    _name.dispose();
    super.dispose();
  }

  Future<void> _loadProfiles() async {
    try {
      final saved = await widget.controller.profiles();
      if (!mounted) return;
      setState(() {
        _profiles = saved;
        if (saved.isNotEmpty && !saved.any((item) => item.id == _profile.id)) {
          _profile = saved.first;
          _name.text = _profile.name;
          _bridges.text = _profile.bridgeLines.join('\n');
        }
      });
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    }
  }

  Future<void> _saveProfile() async {
    final selected = TorProfile(
      id: _profile.id,
      name: _name.text.trim().isEmpty ? 'Tor profile' : _name.text.trim(),
      transport: _profile.transport,
      bridgeLines: _bridges.text
          .split('\n')
          .map((line) => line.trim())
          .where((line) => line.isNotEmpty)
          .toList(growable: false),
    );
    await widget.controller.saveProfile(selected);
    if (!mounted) return;
    setState(() {
      _profile = selected;
      _profiles = <TorProfile>[
        selected,
        ..._profiles.where((item) => item.id != selected.id),
      ];
      _error = null;
    });
  }

  Future<void> _addProfile() async {
    final profile = TorProfile(
      id: DateTime.now().microsecondsSinceEpoch.toString(),
      name: 'New Tor profile',
    );
    await widget.controller.saveProfile(profile);
    if (!mounted) return;
    setState(() {
      _profile = profile;
      _name.text = profile.name;
      _bridges.clear();
      _profiles = <TorProfile>[profile, ..._profiles];
    });
  }

  Future<void> _deleteProfile() async {
    await widget.controller.deleteProfile(_profile.id);
    if (!mounted) return;
    final remaining = _profiles.where((item) => item.id != _profile.id).toList();
    setState(() {
      _profiles = remaining;
      _profile = remaining.isEmpty
          ? const TorProfile(id: 'vision-tor-default', name: 'Vision Tor')
          : remaining.first;
      _name.text = _profile.name;
      _bridges.text = _profile.bridgeLines.join('\n');
    });
  }

  Future<void> _toggle() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      if (_status.state == TorState.connected ||
          _status.state == TorState.bootstrapping ||
          _status.state == TorState.starting) {
        await widget.controller.stop();
      } else {
        final selected = TorProfile(
          id: _profile.id,
          name: _name.text.trim().isEmpty ? 'Tor profile' : _name.text.trim(),
          transport: _profile.transport,
          bridgeLines: _bridges.text
              .split('\n')
              .map((line) => line.trim())
              .where((line) => line.isNotEmpty)
              .toList(growable: false),
        );
        await widget.controller.saveProfile(selected);
        await widget.controller.start(selected);
      }
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  bool _isSupported(TorTransport transport) => _capabilities
      .where((item) => item.transport == transport)
      .any((item) => item.supported);

  @override
  Widget build(BuildContext context) {
    final connected = _status.state == TorState.connected;
    final active = connected ||
        _status.state == TorState.starting ||
        _status.state == TorState.bootstrapping;
    final accent = connected ? const Color(0xFF42F4EA) : const Color(0xFFB36BFF);

    return Scaffold(
      backgroundColor: const Color(0xFF07051F),
      appBar: AppBar(
        backgroundColor: const Color(0xFF07051F),
        foregroundColor: Colors.white,
        title: const Text('VISION VPN',
            style: TextStyle(fontWeight: FontWeight.w800, letterSpacing: 2)),
        centerTitle: true,
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
          children: [
            Center(
              child: Image.asset(
                'assets/vision_vpn_logo.png',
                package: 'vision_tor_plugin',
                height: 116,
                fit: BoxFit.contain,
              ),
            ),
            const SizedBox(height: 12),
            _neonCard(
              child: Column(
                children: [
                  Semantics(
                    button: true,
                    label: connected ? 'Disconnect Tor' : 'Connect Tor',
                    child: InkWell(
                      onTap: _busy ? null : _toggle,
                      customBorder: const CircleBorder(),
                      child: Container(
                        width: 184,
                        height: 184,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          border: Border.all(color: accent, width: 5),
                          boxShadow: [
                            BoxShadow(
                              color: accent.withValues(alpha: 0.35),
                              blurRadius: 28,
                              spreadRadius: 3,
                            ),
                          ],
                          gradient: const RadialGradient(
                            colors: [Color(0xFF20104A), Color(0xFF0B092B)],
                          ),
                        ),
                        child: Icon(
                          active ? Icons.stop_rounded : Icons.power_settings_new,
                          size: 78,
                          color: accent,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 18),
                  Text(
                    _stateLabel(_status.state),
                    style: TextStyle(
                      color: accent,
                      fontSize: 22,
                      fontWeight: FontWeight.w800,
                      letterSpacing: 1.4,
                    ),
                  ),
                  const SizedBox(height: 14),
                  if (_status.state == TorState.bootstrapping ||
                      _status.state == TorState.starting) ...[
                    LinearProgressIndicator(
                      value: _status.bootstrapPercent.clamp(0, 100) / 100,
                      minHeight: 6,
                      color: accent,
                      backgroundColor: const Color(0xFF28244B),
                    ),
                    const SizedBox(height: 8),
                    Text('Bootstrap ${_status.bootstrapPercent}%',
                        style: const TextStyle(color: Colors.white70)),
                  ],
                  if (connected)
                    Text(
                      'SOCKS5  ${_status.socksHost}:${_status.socksPort}',
                      style: const TextStyle(color: Colors.white70),
                    ),
                  if (_busy)
                    const Padding(
                      padding: EdgeInsets.only(top: 12),
                      child: CircularProgressIndicator(),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 14),
            _neonCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('TOR PROFILE',
                      style: TextStyle(
                          color: Colors.white, fontWeight: FontWeight.bold)),
                  const SizedBox(height: 10),
                  if (_profiles.isNotEmpty)
                    DropdownButtonFormField<String>(
                      value: _profiles.any((item) => item.id == _profile.id)
                          ? _profile.id
                          : null,
                      dropdownColor: const Color(0xFF17123A),
                      decoration: const InputDecoration(
                        border: OutlineInputBorder(),
                        labelText: 'Saved profile',
                      ),
                      items: _profiles.map((item) => DropdownMenuItem<String>(
                        value: item.id,
                        child: Text(item.name, style: const TextStyle(color: Colors.white)),
                      )).toList(growable: false),
                      onChanged: active ? null : (id) {
                        final matches = _profiles.where((item) => item.id == id).toList();
                        if (matches.isNotEmpty) {
                          final found = matches.first;
                          setState(() {
                            _profile = found;
                            _name.text = found.name;
                            _bridges.text = found.bridgeLines.join('\n');
                          });
                        }
                      },
                    ),
                  const SizedBox(height: 10),
                  TextField(
                    controller: _name,
                    enabled: !active,
                    style: const TextStyle(color: Colors.white),
                    decoration: const InputDecoration(
                      border: OutlineInputBorder(),
                      labelText: 'Profile name',
                    ),
                  ),
                  const SizedBox(height: 8),
                  Wrap(spacing: 8, runSpacing: 8, children: [
                    OutlinedButton.icon(
                      onPressed: active ? null : _addProfile,
                      icon: const Icon(Icons.add),
                      label: const Text('New'),
                    ),
                    OutlinedButton.icon(
                      onPressed: active ? null : _saveProfile,
                      icon: const Icon(Icons.save),
                      label: const Text('Save'),
                    ),
                    OutlinedButton.icon(
                      onPressed: active || _profiles.isEmpty ? null : _deleteProfile,
                      icon: const Icon(Icons.delete_outline),
                      label: const Text('Delete'),
                    ),
                  ]),
                  const SizedBox(height: 18),
                  const Text('TOR TRANSPORT',
                      style: TextStyle(
                          color: Colors.white, fontWeight: FontWeight.bold)),
                  const SizedBox(height: 10),
                  DropdownButtonFormField<TorTransport>(
                    value: _profile.transport,
                    dropdownColor: const Color(0xFF17123A),
                    decoration: const InputDecoration(
                      border: OutlineInputBorder(),
                      labelText: 'Connection method',
                    ),
                    items: TorTransport.values.map((transport) {
                      final supported = _isSupported(transport);
                      return DropdownMenuItem<TorTransport>(
                        value: transport,
                        enabled: supported,
                        child: Text(
                          supported
                              ? transport.label
                              : '${transport.label} — unavailable',
                          style: TextStyle(
                              color: supported ? Colors.white : Colors.white38),
                        ),
                      );
                    }).toList(growable: false),
                    onChanged: active
                        ? null
                        : (value) {
                            if (value != null) {
                              setState(() => _profile = TorProfile(
                                    id: _profile.id,
                                    name: _profile.name,
                                    transport: value,
                                    bridgeLines: _profile.bridgeLines,
                                  ));
                            }
                          },
                  ),
                  if (_profile.transport == TorTransport.customBridges) ...[
                    const SizedBox(height: 12),
                    TextField(
                      controller: _bridges,
                      enabled: !active,
                      minLines: 2,
                      maxLines: 5,
                      style: const TextStyle(color: Colors.white),
                      decoration: const InputDecoration(
                        border: OutlineInputBorder(),
                        labelText: 'Direct Bridge lines',
                        hintText: 'host:port fingerprint (one per line)',
                        helperText:
                            'This build accepts direct bridge relays only. PT bridges require their native transport binary.',
                      ),
                    ),
                  ],
                  const SizedBox(height: 10),
                  const Text(
                    'obfs4, Snowflake and Meek are shown as unavailable until their real Android transport binaries are bundled and tested.',
                    style: TextStyle(color: Colors.white60, fontSize: 12),
                  ),
                ],
              ),
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              _neonCard(
                child: Text(_error!, style: const TextStyle(color: Colors.redAccent)),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _neonCard({required Widget child}) => Container(
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(
          color: const Color(0xDD11102F),
          borderRadius: BorderRadius.circular(22),
          border: Border.all(color: const Color(0xFF7660D9), width: 1.2),
          boxShadow: const [
            BoxShadow(color: Color(0x3327E8F5), blurRadius: 18),
          ],
        ),
        child: child,
      );

  String _stateLabel(TorState state) => switch (state) {
        TorState.stopped => 'DISCONNECTED',
        TorState.starting => 'STARTING',
        TorState.bootstrapping => 'BOOTSTRAPPING',
        TorState.connected => 'CONNECTED',
        TorState.reconnecting => 'RECONNECTING',
        TorState.stopping => 'DISCONNECTING',
        TorState.error => 'ERROR',
      };
}

