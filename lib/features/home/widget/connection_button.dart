import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hiddify/core/localization/translations.dart';
import 'package:hiddify/core/preferences/general_preferences.dart';
import 'package:hiddify/core/router/bottom_sheets/bottom_sheets_notifier.dart';
import 'package:hiddify/core/router/dialog/dialog_notifier.dart';
import 'package:hiddify/core/theme/theme_extensions.dart';
import 'package:hiddify/core/widget/ambient_motion.dart';
import 'package:hiddify/core/widget/animated_text.dart';
import 'package:hiddify/core/widget/unique_keys_layout.dart';
import 'package:hiddify/features/chain/model/chain_enum.dart';
import 'package:hiddify/features/chain/overview/chain_mode_icon.dart';
import 'package:hiddify/features/connection/model/connection_failure.dart';
import 'package:hiddify/features/connection/model/connection_status.dart';
import 'package:hiddify/features/connection/notifier/connection_notifier.dart';
import 'package:hiddify/features/home/widget/eclipse_button_painter.dart';
import 'package:hiddify/features/profile/notifier/active_profile_notifier.dart';
import 'package:hiddify/features/proxy/active/active_proxy_notifier.dart';
import 'package:hiddify/features/proxy/model/connection_health.dart';
import 'package:hiddify/features/proxy/model/proxy_entity.dart';
import 'package:hiddify/features/stats/notifier/stats_notifier.dart';
import 'package:hiddify/features/tor/model/tor_mode_state.dart';
import 'package:hiddify/features/tor/notifier/tor_mode_notifier.dart';
import 'package:hiddify/gen/assets.gen.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

/// What the button shows, one value per look.
enum _Look { loading, disconnected, connecting, waiting, noProxies, connected, hotReloading, disconnecting, failed }

/// One look per status the core reports. The core decides when the tunnel is waiting for a ping
/// and when it is connected; the button only shows it.
_Look _lookOf(AsyncValue<ConnectionStatus> status) => switch (status) {
  AsyncError() => _Look.failed,
  AsyncValue(valueOrNull: Disconnected(connectionFailure: _?)) => _Look.failed,
  AsyncValue(valueOrNull: Disconnected()) => _Look.disconnected,
  AsyncValue(valueOrNull: Connecting()) => _Look.connecting,
  AsyncValue(valueOrNull: Waiting()) => _Look.waiting,
  AsyncValue(valueOrNull: NoAvailableProxies()) => _Look.noProxies,
  AsyncValue(valueOrNull: HotReloading()) => _Look.hotReloading,
  AsyncValue(valueOrNull: Connected()) => _Look.connected,
  AsyncValue(valueOrNull: Disconnecting()) => _Look.disconnecting,
  _ => _Look.loading,
};

_Look _lookOfTor(TorModeState torState) => switch (torState.phase) {
  TorVpnPhase.stopped => _Look.disconnected,
  TorVpnPhase.startingTor || TorVpnPhase.bootstrapping || TorVpnPhase.recovering => _Look.connecting,
  TorVpnPhase.verifyingSocks || TorVpnPhase.startingVpn || TorVpnPhase.verifyingTunnel => _Look.waiting,
  TorVpnPhase.connected => torState.vpnActive && torState.tunnelVerified ? _Look.connected : _Look.waiting,
  TorVpnPhase.stopping => _Look.disconnecting,
  TorVpnPhase.failClosed || TorVpnPhase.error => _Look.failed,
};

class ConnectionButton extends HookConsumerWidget {
  const ConnectionButton({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = ref.watch(translationsProvider).requireValue;
    final torState = ref.watch(torModeNotifierProvider);
    final status = ref.watch(connectionNotifierProvider);

    final rawLook = torState.isTorModeSelected ? _lookOfTor(torState) : _lookOf(status);
    final look = _held(rawLook);

    final onTap = torState.isTorModeSelected
        ? (torState.phase == TorVpnPhase.stopping
              ? null
              : () => ref.read(torModeNotifierProvider.notifier).toggleTorVpn())
        : switch (status) {
            AsyncData(value: Disconnected()) || AsyncError() => () => _connect(ref),
            AsyncData(value: Connected() || Waiting() || NoAvailableProxies() || Connecting() || HotReloading()) =>
              () => ref.read(connectionNotifierProvider.notifier).toggleConnection(),
            _ => null,
          };

    final failure = torState.isTorModeSelected
        ? null
        : switch (status) {
            AsyncError(:final ConnectionFailure error) => error,
            AsyncValue(valueOrNull: Disconnected(connectionFailure: final failure?)) => failure,
            _ => null,
          };

    final (download, upload) = ref.watch(
      statsNotifierProvider.select((stats) {
        final s = stats.valueOrNull;
        return s == null ? (0, 0) : (s.downlink.toInt(), s.uplink.toInt());
      }),
    );

    final health = torState.isTorModeSelected ? null : ref.watch(connectionHealthProvider);
    final h = t.connection.health;
    final chainName = health?.chain?.present(t) ?? '';
    final degraded = !torState.isTorModeSelected && look == _Look.connected && (health?.degraded ?? false);
    final hopPending = !torState.isTorModeSelected && look == _Look.connected && (health?.hopPending ?? false);

    final String label = torState.isTorModeSelected
        ? _torLabel(torState, t)
        : switch (look) {
            _Look.loading => "",
            _Look.disconnected => t.connection.tapToConnect,
            _Look.connecting => t.connection.connecting,
            _Look.waiting => t.connection.waiting,
            _Look.noProxies => t.connection.noAvailableProxies,
            _Look.connected when degraded =>
              health!.pick == HealthState.failed ? h.pickDead : h.hopFailed(chain: chainName),
            _Look.connected when hopPending => h.hopPending(chain: chainName),
            _Look.connected => t.connection.connected,
            _Look.hotReloading => t.connection.hotReloading,
            _Look.disconnecting => t.connection.disconnecting,
            _Look.failed => t.connection.failed,
          };

    const buttonTheme = ConnectionButtonTheme.light;
    final today = DateTime.now();
    final button = _ConnectionButton(
      look: look,
      onTap: onTap,
      onErrorDetails: failure == null
          ? null
          : () => ref.read(dialogNotifierProvider.notifier).showCustomAlertFromErr(failure.present(t)),
      errorDetailsLabel: t.connection.errorDetails,
      download: download,
      upload: upload,
      label: label,
      hint: look == _Look.failed ? t.connection.tapToRetry : null,
      searching: hopPending,
      hop: !torState.isTorModeSelected && {_Look.connected, _Look.noProxies, _Look.waiting}.contains(look)
          ? ref.watch(activeProxyCardProvider.select((card) => card?.hopStatus == null ? null : card))
          : null,
      onHop: ref.read(bottomSheetsNotifierProvider.notifier).showConnectionHealth,
      hopLabel: health == null ? '' : '$chainName: ${_hopLabel(h, health)}',
      buttonColor: switch (look) {
        _Look.connected when degraded => _degradedColor,
        _Look.connected when hopPending => const Color(0xFF7C3AED),
        _Look.connected => const Color(0xFF14B8A6),
        _Look.noProxies => const Color.fromARGB(255, 185, 176, 103),
        _Look.failed => Colors.red,
        _Look.connecting || _Look.waiting || _Look.hotReloading || _Look.disconnecting => const Color(0xFF7C3AED),
        _ => buttonTheme.idleColor!,
      },
      image: switch (look) {
        _Look.connected || _Look.noProxies || _Look.hotReloading => Assets.images.connectNorouz,
        _ => Assets.images.disconnectNorouz,
      },
      useImage: today.day >= 19 && today.day <= 23 && today.month == 3,
      reduceFlow: ref.watch(Preferences.reduceAnimations),
    );
    return button;
  }

  static String _torLabel(TorModeState torState, Translations t) => switch (torState.phase) {
    TorVpnPhase.stopped => t.connection.tapToConnect,
    TorVpnPhase.startingTor => 'Starting Tor...',
    TorVpnPhase.bootstrapping => 'Tor Bootstrap ${torState.snapshot.bootstrap}%...',
    TorVpnPhase.verifyingSocks => 'Verifying Tor SOCKS5...',
    TorVpnPhase.startingVpn => 'Starting Tor VPN...',
    TorVpnPhase.verifyingTunnel => 'Verifying Tor Tunnel...',
    TorVpnPhase.connected => torState.vpnActive && torState.tunnelVerified ? t.connection.connected : 'Verifying Tor Tunnel...',
    TorVpnPhase.recovering => 'Recovering Tor...',
    TorVpnPhase.stopping => t.connection.disconnecting,
    TorVpnPhase.failClosed => 'Fail-Closed Active',
    TorVpnPhase.error => t.connection.failed,
  };

  _Look _held(_Look look) {
    final shown = useRef(look);
    final until = useRef<DateTime?>(null);
    final rebuild = useState(0);
    final steady = {_Look.connected, _Look.noProxies}.contains(shown.value);
    final blip = {_Look.loading}.contains(look);
    var result = look;
    if (steady && blip) {
      until.value ??= DateTime.now().add(_hold);
      if (DateTime.now().isBefore(until.value!)) {
        result = shown.value;
      } else {
        until.value = null;
      }
    } else {
      until.value = null;
    }
    shown.value = result;
    useEffect(() {
      final end = until.value;
      if (end == null) return null;
      final timer = Timer(end.difference(DateTime.now()), () => rebuild.value++);
      return timer.cancel;
    }, [until.value]);
    return result;
  }

  static const _hold = Duration(milliseconds: 1500);

  /// Connected, but the traffic does not get through: amber, between connected and failed.
  static const _degradedColor = Color(0xFFE3A63A);

  static String _hopLabel(TranslationsConnectionHealthEn h, ConnectionHealth health) => switch (health.hopState) {
    HealthState.ok => h.hopReady,
    HealthState.pending => h.hopConnecting,
    HealthState.blocked => h.hopBlocked,
    HealthState.failed => health.hopNoReply ? h.hopNoReply : h.hopBroken,
  };

  /// Connects, or sends the user to add a profile when there is none.
  Future<void> _connect(WidgetRef ref) async {
    final torState = ref.read(torModeNotifierProvider);
    if (torState.isTorModeSelected) {
      await ref.read(torModeNotifierProvider.notifier).toggleTorVpn();
      return;
    }
    final dialogs = ref.read(dialogNotifierProvider.notifier);
    if (ref.read(activeProfileProvider).valueOrNull == null) {
      await dialogs.showNoActiveProfile();
      ref.read(bottomSheetsNotifierProvider.notifier).showAddProfile();
      return;
    }
    if (await dialogs.showExperimentalFeatureNotice()) {
      await ref.read(connectionNotifierProvider.notifier).toggleConnection();
    }
  }
}

class _ConnectionButton extends HookWidget {
  const _ConnectionButton({
    required this.look,
    required this.onTap,
    required this.onErrorDetails,
    required this.errorDetailsLabel,
    required this.download,
    required this.upload,
    required this.label,
    required this.hint,
    required this.searching,
    required this.hop,
    required this.onHop,
    required this.hopLabel,
    required this.buttonColor,
    required this.image,
    required this.useImage,
    required this.reduceFlow,
  });

  final _Look look;
  final VoidCallback? onTap;
  final VoidCallback? onErrorDetails;
  final String errorDetailsLabel;
  final int download;
  final int upload;
  final String label;
  final String? hint;
  final bool searching;
  final ActiveProxyCardData? hop;
  final VoidCallback onHop;
  final String hopLabel;
  final Color buttonColor;
  final AssetGenImage image;
  final bool useImage;
  final bool reduceFlow;

  static const _size = 148.0;
  static const _glow = 110.0;
  static const _fullFrom = 860.0;
  static const _smallFrom = 640.0;
  static const _smallest = .65;

  static double _settleAfter(EclipseLook look) => look == EclipseLook.idle ? 1 : 3;

  static EclipseTraffic _stepped(EclipseTraffic t) =>
      EclipseTraffic(down: (t.down * 4).round() / 4, up: (t.up * 4).round() / 4);

  static double _scaleFor(double height) =>
      _smallest + (1 - _smallest) * ((height - _smallFrom) / (_fullFrom - _smallFrom)).clamp(0.0, 1.0);

  EclipseLook get _eclipseLook => switch (look) {
    _Look.loading || _Look.disconnected => EclipseLook.idle,
    _Look.connecting => EclipseLook.starting,
    _Look.disconnecting => EclipseLook.stopping,
    _Look.waiting || _Look.hotReloading => EclipseLook.waiting,
    _Look.connected when searching => EclipseLook.waiting,
    _Look.connected || _Look.noProxies => EclipseLook.connected,
    _Look.failed => EclipseLook.error,
  };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final reduced = MediaQuery.disableAnimationsOf(context);
    final scale = _scaleFor(MediaQuery.sizeOf(context).height);
    final clock = useMemoized(() => Stopwatch()..start());
    double now() => clock.elapsedMicroseconds / 1e6;
    final focused = useState(false);

    final eclipseLook = _eclipseLook;
    final change = useRef((
      look: eclipseLook,
      previous: eclipseLook,
      at: -10.0,
      color: buttonColor,
      previousColor: buttonColor,
    ));
    final previousLook = usePrevious(look);
    final leftConnected = useRef(-10.0);
    if (change.value.look != eclipseLook || change.value.color != buttonColor) {
      final last = change.value;
      if (last.look == EclipseLook.connected && eclipseLook != EclipseLook.connected) leftConnected.value = now();
      final eased = Color.lerp(last.previousColor, last.color, ((now() - last.at) / .45).clamp(0.0, 1.0))!;
      final skip =
          previousLook == _Look.loading || (eclipseLook == EclipseLook.connected && now() - leftConnected.value < 2);
      change.value = (
        look: eclipseLook,
        previous: last.look,
        at: skip ? now() - 10 : now(),
        color: buttonColor,
        previousColor: eased,
      );
    }

    final target = EclipseTraffic(down: EclipseTraffic.level(download), up: EclipseTraffic.level(upload));
    final light = useMemoized(_LightMotion.new);

    final tickerOn = useValueListenable(TickerMode.getNotifier(context));
    useEffect(() {
      WidgetsBinding.instance.addPostFrameCallback((_) => wakeAmbientMotion());
      return null;
    }, [eclipseLook]);
    final allowed = useValueListenable(ambientMotionAllowed);
    final busy = const {EclipseLook.starting, EclipseLook.waiting, EclipseLook.stopping}.contains(eclipseLook);
    final ambient = busy || allowed;
    final ticking = tickerOn && ambient;
    final still = reduced || !ambient;
    final flowStill = reduceFlow && eclipseLook == EclipseLook.connected;
    final settled = useValueNotifier(false);
    final calm = useValueListenable(settled) && now() - change.value.at > _settleAfter(eclipseLook);
    final interval = calm ? AmbientClock.decoration : AmbientClock.lively;
    final moving = !still && ticking && !(flowStill && calm) && !(eclipseLook == EclipseLook.idle && calm);
    final frame = useValueNotifier(0);
    useEffect(() {
      if (!moving) return null;
      return AmbientClock.subscribe(interval, () {
        final c = change.value;
        settled.value = now() - c.at > _settleAfter(c.look);
        frame.value++;
      });
    }, [moving, interval]);

    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Semantics(
          button: true,
          enabled: onTap != null,
          label: label,
          hint: hint,
          child: SizedBox.square(
            dimension: _size * scale,
            child: OverflowBox(
              minWidth: _size,
              maxWidth: _size,
              minHeight: _size,
              maxHeight: _size,
              child: Transform.scale(
                scale: scale,
                child: SizedBox.square(
                  dimension: _size,
                  child: Stack(
                    fit: StackFit.expand,
                    clipBehavior: Clip.none,
                    children: [
                      Positioned(
                        left: -_glow,
                        top: -_glow,
                        right: -_glow,
                        bottom: -_glow,
                        child: IgnorePointer(
                          child: RepaintBoundary(
                            child: ValueListenableBuilder(
                              valueListenable: frame,
                              builder: (context, _, _) {
                                final c = change.value;
                                final at = now();
                                if (moving) {
                                  light.advance(at, target, flowing: c.look != EclipseLook.idle || at - c.at <= 1);
                                } else {
                                  light.pause();
                                }
                                return CustomPaint(
                                  isComplex: true,
                                  willChange: moving,
                                  painter: EclipseButtonPainter(
                                    frame: EclipseFrame(
                                      look: c.look,
                                      previous: c.previous,
                                      since: still ? 10 : at - c.at,
                                      time: light.time,
                                      color: c.color,
                                      previousColor: c.previousColor,
                                    ),
                                    radius: _size / 2,
                                    traffic: still || flowStill ? _stepped(target) : light.traffic,
                                    dark: theme.brightness == Brightness.dark,
                                    drawLogo: !useImage,
                                    logoOpacity: look == _Look.loading ? .38 : 1,
                                    pixelRatio: MediaQuery.devicePixelRatioOf(context),
                                    simple: still || flowStill,
                                  ),
                                );
                              },
                            ),
                          ),
                        ),
                      ),
                      Material(
                        key: const ValueKey("home_connection_button"),
                        shape: const CircleBorder(),
                        color: Colors.transparent,
                        clipBehavior: Clip.antiAlias,
                        child: InkWell(
                          onTap: onTap,
                          onFocusChange: (value) => focused.value = value,
                          focusColor: Colors.transparent,
                          hoverColor: Colors.black.withValues(alpha: .05),
                          highlightColor: Colors.black.withValues(alpha: .06),
                          splashColor: Colors.black.withValues(alpha: .08),
                          child: useImage ? Padding(padding: const EdgeInsets.all(36), child: image.image()) : null,
                        ),
                      ),
                      Positioned.fill(
                        child: IgnorePointer(
                          child: AnimatedOpacity(
                            opacity: focused.value ? 1 : 0,
                            duration: const Duration(milliseconds: 180),
                            child: Transform.scale(
                              scale: 1.1,
                              child: DecoratedBox(
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  border: Border.all(color: theme.colorScheme.primary, width: 3),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                      if (look == _Look.failed && onErrorDetails != null)
                        Positioned.fromRect(
                          rect: EclipseButtonPainter.errorBadgeRect(
                            const Offset(_size / 2, _size / 2),
                            _size / 2,
                          ).inflate(8),
                          child: Semantics(
                            button: true,
                            label: errorDetailsLabel,
                            child: GestureDetector(behavior: HitTestBehavior.opaque, onTap: onErrorDetails),
                          ),
                        ),
                      if (hop case final hop?)
                        Positioned.fromRect(
                          rect: Rect.fromCircle(
                            center:
                                const Offset(_size / 2, _size / 2) + Offset.fromDirection(5 * math.pi / 4, _size / 2),
                            radius: _ChainBadge.size / 2,
                          ),
                          child: _ChainBadge(hop, label: hopLabel, onTap: onHop),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
        Gap(20 * scale),
        Column(
          children: [
            AnimatedText(label, style: theme.textTheme.titleMedium),
            AnimatedSwitcher(
              layoutBuilder: uniqueKeysLayout(),
              duration: const Duration(milliseconds: 250),
              child: hint == null
                  ? const SizedBox.shrink()
                  : Text(
                      hint!,
                      key: ValueKey(hint),
                      style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                    ),
            ),
          ],
        ),
      ],
    );
  }
}

class _LightMotion {
  double time = 0;
  EclipseTraffic traffic = const EclipseTraffic();
  double? _last;

  void advance(double now, EclipseTraffic target, {required bool flowing}) {
    final passed = now - (_last ?? now);
    _last = now;
    if (passed <= 0) return;
    double follow(double from, double to) => from + (to - from) * (1 - math.exp(-passed / (to > from ? .8 : 2.5)));
    traffic = EclipseTraffic(down: follow(traffic.down, target.down), up: follow(traffic.up, target.up));
    if (flowing) time += passed;
  }

  void pause() => _last = null;
}

class _ChainBadge extends StatelessWidget {
  const _ChainBadge(this.card, {required this.label, required this.onTap});
  final ActiveProxyCardData card;
  final String label;
  final VoidCallback onTap;

  static const size = 56.0;

  ChainType get _type => card.chain.isUnblocker() ? ChainType.unblocker : ChainType.extraSecurity;

  IconData get _glyph => switch ((_type, card.hopStatus!)) {
    (ChainType.extraSecurity, StepStatus.connected) => Icons.gpp_good_rounded,
    (ChainType.extraSecurity, StepStatus.failed) => Icons.gpp_bad_rounded,
    (ChainType.extraSecurity, _) => Icons.shield_outlined,
    (ChainType.unblocker, StepStatus.connected || StepStatus.connecting || StepStatus.slow) =>
      Icons.meeting_room_rounded,
    (ChainType.unblocker, _) => Icons.door_front_door_rounded,
  };

  Widget _glyphWidget(StepStatus status) => switch ((_type, status)) {
    (ChainType.unblocker, _) => PassingDoor(
      size: _glyphSize,
      color: Colors.white,
      animate: status == StepStatus.connecting || status == StepStatus.slow || status == StepStatus.connected,
    ),
    _ => Icon(_glyph, size: _glyphSize, color: Colors.white),
  };

  IconData get _seal => _type == ChainType.extraSecurity ? Icons.shield_rounded : Icons.circle;
  double get _glyphSize => _type == ChainType.extraSecurity ? 40 : 30;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = card.hopStatus!;
    final (main, accent) = _type.colors(card.hopMode);
    final (from, to) = switch (status) {
      StepStatus.failed => (const Color(0xFFFF6B6B), theme.colorScheme.error),
      StepStatus.waiting => (theme.colorScheme.outline, theme.colorScheme.outlineVariant),
      _ => (main, accent),
    };
    const face = Color(0xFFF7F7F9);
    Widget glyph = SizedBox.square(
      dimension: size,
      child: Stack(
        alignment: Alignment.center,
        children: [
          Icon(
            _seal,
            size: 50,
            color: face,
            shadows: [
              Shadow(color: from.withValues(alpha: status == StepStatus.waiting ? .2 : .55), blurRadius: 14),
              Shadow(color: Colors.black.withValues(alpha: .25), blurRadius: 4, offset: const Offset(0, 2)),
            ],
          ),
          ShaderMask(
            blendMode: BlendMode.srcIn,
            shaderCallback: (rect) => LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [from, to],
            ).createShader(rect),
            child: _glyphWidget(status),
          ),
        ],
      ),
    );
    final plain = glyph;
    glyph = switch (status) {
      _ when MediaQuery.disableAnimationsOf(context) => plain,
      StepStatus.connected => AmbientLoop(
        period: 3700.ms,
        still: (_) => plain,
        builder: (context, phase) => plain
            .animate(adapter: ValueNotifierAdapter(phase))
            .shimmer(delay: 2500.ms, duration: 1200.ms, color: Colors.white.withValues(alpha: .7)),
      ),
      StepStatus.connecting || StepStatus.slow when _type == ChainType.unblocker => plain,
      StepStatus.connecting || StepStatus.slow => AmbientLoop(
        period: 1800.ms,
        still: (_) => plain,
        builder: (context, phase) => ValueListenableBuilder<double>(
          valueListenable: phase,
          builder: (context, v, child) =>
              Transform.scale(scale: .92 + .12 * Curves.easeInOut.transform(1 - (2 * v - 1).abs()), child: child),
          child: plain,
        ),
      ),
      StepStatus.failed => plain.animate().shake(hz: 4, rotation: .06, duration: 500.ms),
      StepStatus.waiting => plain,
    };
    return Semantics(
      button: true,
      label: label,
      child: Tooltip(
        message: label,
        child: GestureDetector(
          onTap: onTap,
          behavior: HitTestBehavior.opaque,
          child: RepaintBoundary(
            child: Center(
              child: AnimatedSwitcher(
                layoutBuilder: uniqueKeysLayout(),
                duration: const Duration(milliseconds: 250),
                transitionBuilder: (child, a) => ScaleTransition(scale: a, child: child),
                child: KeyedSubtree(key: ValueKey(_glyph), child: glyph),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
