import 'package:dartx/dartx.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:go_router/go_router.dart';
import 'package:hiddify/core/app_info/app_info_provider.dart';
import 'package:hiddify/core/localization/translations.dart';
import 'package:hiddify/core/router/bottom_sheets/bottom_sheets_notifier.dart';
import 'package:hiddify/features/connection/model/connection_status.dart';
import 'package:hiddify/features/connection/notifier/connection_notifier.dart';
import 'package:hiddify/features/home/widget/connection_button.dart';
import 'package:hiddify/features/home/widget/local_proxy_hint.dart';
import 'package:hiddify/features/profile/notifier/active_profile_notifier.dart';
import 'package:hiddify/features/profile/widget/profile_tile.dart';
import 'package:hiddify/features/proxy/active/active_proxy_card.dart';
import 'package:hiddify/features/proxy/active/active_proxy_delay_indicator.dart';
import 'package:hiddify/features/stats/notifier/stats_notifier.dart';
import 'package:hiddify/features/tor/model/tor_mode_state.dart';
import 'package:hiddify/features/tor/notifier/tor_mode_notifier.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:sliver_tools/sliver_tools.dart';

class HomePage extends HookConsumerWidget {
  const HomePage({super.key});

  static String _formatBytes(int bytes) {
    if (bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    var value = bytes.toDouble();
    var idx = 0;
    while (value >= 1024 && idx < units.length - 1) {
      value /= 1024;
      idx++;
    }
    return '${value.toStringAsFixed(idx == 0 ? 0 : 1)} ${units[idx]}';
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final appVersion = ref.watch(appInfoProvider).valueOrNull?.presentVersion;
    final isDark = theme.brightness == Brightness.dark;
    final t = ref.watch(translationsProvider).requireValue;
    final activeProfile = ref.watch(activeProfileProvider);
    final torState = ref.watch(torModeNotifierProvider);
    final connStatus = ref.watch(connectionNotifierProvider).valueOrNull;
    final stats = ref.watch(statsNotifierProvider).valueOrNull;
    final hasAnyProfile = ref.watch(hasAnyProfileProvider).value ?? false;

    final isTorConnected =
        torState.isTorModeSelected && torState.phase == TorVpnPhase.connected && torState.vpnActive && torState.tunnelVerified;
    final isConnected = torState.isTorModeSelected ? isTorConnected : (connStatus is Connected);
    final isFailClosed = torState.isTorModeSelected && torState.phase == TorVpnPhase.failClosed;

    final heroTitle = isFailClosed
        ? 'Fail-Closed Active'
        : (isConnected
            ? (torState.isTorModeSelected ? 'Connected via Tor' : 'Connected')
            : (torState.isTorModeSelected && torState.phase == TorVpnPhase.verifyingTunnel
                ? 'Verifying Tor Tunnel'
                : (torState.isTorModeSelected && torState.isBusy
                    ? 'Tor ${torState.snapshot.bootstrap}%'
                    : 'Not Connected')));

    final heroSubtitle = isFailClosed
        ? 'Tor stopped · Device traffic blocked safely'
        : (torState.isTorModeSelected
            ? (torState.lastError ??
                torState.statusMessage ??
                'Standalone Tor · ${torState.transportMode.label} · DNS over Tor')
            : 'Tap the button below to establish a secure VPN connection');

    final showProxiesBar = !torState.isTorModeSelected && hasAnyProfile;

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Container(
              width: 32,
              height: 32,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: const LinearGradient(
                  colors: [Color(0xFF7C3AED), Color(0xFFA855F7)],
                ),
                boxShadow: [
                  BoxShadow(
                    color: const Color(0xFF8B5CF6).withValues(alpha: 0.45),
                    blurRadius: 12,
                  ),
                ],
              ),
              child: const Icon(Icons.visibility_rounded, size: 18, color: Colors.white),
            ),
            const Gap(10),
            Text.rich(
              TextSpan(
                children: [
                  const TextSpan(
                    text: 'Vision ',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 19),
                  ),
                  const TextSpan(
                    text: 'VPN',
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      fontSize: 19,
                      color: Color(0xFFA855F7),
                    ),
                  ),
                  const TextSpan(text: ' '),
                  WidgetSpan(
                    child: Text(appVersion ?? '', style: theme.textTheme.labelSmall),
                    alignment: PlaceholderAlignment.middle,
                  ),
                ],
              ),
            ),
          ],
        ),
        actions: [
          Semantics(
            key: const ValueKey("profile_add_button"),
            label: t.pages.profiles.add,
            child: IconButton(
              icon: const Icon(Icons.add_circle_outline_rounded, color: Color(0xFFA855F7)),
              onPressed: () => ref.read(bottomSheetsNotifierProvider.notifier).showAddProfile(),
            ),
          ),
          const Gap(8),
        ],
      ),
      body: Stack(
        fit: StackFit.expand,
        children: [
          RepaintBoundary(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: isDark
                    ? const RadialGradient(
                        center: Alignment(0, -0.35),
                        radius: 1.05,
                        colors: [
                          Color(0xFF230E52),
                          Color(0xFF11072B),
                          Color(0xFF090518),
                        ],
                        stops: [0.0, 0.45, 1.0],
                      )
                    : RadialGradient(
                        center: const Alignment(0, -.15),
                        radius: 1.1,
                        colors: [
                          Colors.white.withValues(alpha: .55),
                          Colors.white.withValues(alpha: 0),
                          Colors.black.withValues(alpha: .045),
                        ],
                        stops: const [0, .5, 1],
                      ),
                image: DecorationImage(
                  image: const AssetImage('assets/images/world_map.png'),
                  fit: BoxFit.cover,
                  opacity: isDark ? 0.12 : 0.08,
                  colorFilter: ColorFilter.mode(
                    isDark ? const Color(0xFFA855F7).withValues(alpha: 0.35) : Colors.grey,
                    BlendMode.srcATop,
                  ),
                ),
              ),
            ),
          ),
          Stack(
            alignment: Alignment.center,
            children: [
              Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 600),
                  child: ScrollConfiguration(
                    behavior: ScrollConfiguration.of(context).copyWith(overscroll: false),
                    child: CustomScrollView(
                      physics: const AlwaysScrollableScrollPhysics(parent: ClampingScrollPhysics()),
                      slivers: [
                        MultiSliver(
                          children: [
                            // 1. Hero Connection Card
                            Padding(
                              padding: const EdgeInsets.fromLTRB(16, 8, 16, 10),
                              child: Container(
                                decoration: BoxDecoration(
                                  borderRadius: BorderRadius.circular(24),
                                  gradient: LinearGradient(
                                    begin: Alignment.topLeft,
                                    end: Alignment.bottomRight,
                                    colors: isDark
                                        ? const [Color(0xE61D0D45), Color(0xE6110829)]
                                        : [theme.colorScheme.surface, theme.colorScheme.surfaceContainerLow],
                                  ),
                                  border: Border.all(
                                    color: isFailClosed
                                        ? theme.colorScheme.error.withValues(alpha: 0.7)
                                        : (isConnected
                                            ? const Color(0xFF2DD4BF).withValues(alpha: 0.6)
                                            : const Color(0xFF8B5CF6).withValues(alpha: 0.35)),
                                  ),
                                  boxShadow: isDark
                                      ? [
                                          BoxShadow(
                                            color: const Color(0xFF7C3AED).withValues(alpha: 0.22),
                                            blurRadius: 24,
                                            offset: const Offset(0, 8),
                                          ),
                                        ]
                                      : null,
                                ),
                                padding: const EdgeInsets.fromLTRB(18, 16, 18, 14),
                                child: Column(
                                  children: [
                                    Row(
                                      children: [
                                        Container(
                                          padding: const EdgeInsets.all(8),
                                          decoration: BoxDecoration(
                                            color: (isFailClosed
                                                    ? theme.colorScheme.error
                                                    : (isConnected
                                                        ? const Color(0xFF14B8A6)
                                                        : const Color(0xFF7C3AED)))
                                                .withValues(alpha: 0.22),
                                            borderRadius: BorderRadius.circular(12),
                                          ),
                                          child: Icon(
                                            isFailClosed
                                                ? Icons.gpp_bad_rounded
                                                : (torState.isTorModeSelected
                                                    ? Icons.shield_moon_rounded
                                                    : Icons.verified_user_rounded),
                                            color: isFailClosed
                                                ? theme.colorScheme.error
                                                : (isConnected
                                                    ? const Color(0xFF2DD4BF)
                                                    : const Color(0xFFA855F7)),
                                            size: 20,
                                          ),
                                        ),
                                        const Gap(12),
                                        Expanded(
                                          child: Column(
                                            crossAxisAlignment: CrossAxisAlignment.start,
                                            children: [
                                              Text(
                                                heroTitle,
                                                style: theme.textTheme.titleMedium?.copyWith(
                                                  fontWeight: FontWeight.bold,
                                                ),
                                              ),
                                              Text(
                                                heroSubtitle,
                                                maxLines: 2,
                                                overflow: TextOverflow.ellipsis,
                                                style: theme.textTheme.bodySmall?.copyWith(
                                                  color: theme.colorScheme.onSurfaceVariant,
                                                ),
                                              ),
                                            ],
                                          ),
                                        ),
                                      ],
                                    ),
                                    const Gap(10),
                                    const ConnectionButton(),
                                    if (!torState.isTorModeSelected) const ActiveProxyDelayIndicator(),
                                  ],
                                ),
                              ),
                            ),

                            // 2. Mode Switcher (VPN | Tor)
                            Padding(
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                              child: SegmentedButton<VisionConnectionMode>(
                                segments: const [
                                  ButtonSegment<VisionConnectionMode>(
                                    value: VisionConnectionMode.normal,
                                    icon: Icon(Icons.shield_rounded, size: 18),
                                    label: Text('VPN'),
                                  ),
                                  ButtonSegment<VisionConnectionMode>(
                                    value: VisionConnectionMode.tor,
                                    icon: Icon(Icons.shield_moon_rounded, size: 18),
                                    label: Text('Tor'),
                                  ),
                                ],
                                selected: {torState.connectionMode},
                                onSelectionChanged: torState.isBusy
                                    ? null
                                    : (selection) => ref
                                        .read(torModeNotifierProvider.notifier)
                                        .setConnectionMode(selection.first),
                              ),
                            ),

                            // 3. Active VPN Profile (VPN mode) OR compact shortcut to dedicated Tor tab (Tor mode)
                            if (torState.isTorModeSelected)
                              Padding(
                                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                                child: Card(
                                  child: ListTile(
                                    leading: Icon(
                                      isFailClosed ? Icons.gpp_bad_rounded : Icons.shield_moon_rounded,
                                      color: isFailClosed ? theme.colorScheme.error : const Color(0xFFA855F7),
                                    ),
                                    title: Text(
                                      isFailClosed
                                          ? 'Tor Fail-Closed · Traffic Blocked'
                                          : 'Tor · ${torState.transportMode.label} (${torState.snapshot.bootstrap}%)',
                                      style: const TextStyle(fontWeight: FontWeight.w600),
                                    ),
                                    subtitle: Text(
                                      torState.lastError ??
                                          torState.statusMessage ??
                                          'Manage Transport & Bridges in the Tor tab',
                                      maxLines: 2,
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                    trailing: const Icon(Icons.tune_rounded, color: Color(0xFFA855F7)),
                                    onTap: () => context.goNamed('tor'),
                                  ),
                                ),
                              )
                            else
                              switch (activeProfile) {
                                AsyncData(value: final profile?) => ProfileTile(
                                  profile: profile,
                                  isMain: true,
                                  margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                                ),
                                _ => const SizedBox.shrink(),
                              },

                            // 4. Traffic Usage Card (Download / Upload)
                            Padding(
                              padding: const EdgeInsets.fromLTRB(16, 6, 16, 12),
                              child: Card(
                                margin: EdgeInsets.zero,
                                child: Padding(
                                  padding: const EdgeInsets.all(14),
                                  child: Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      Row(
                                        children: [
                                          const Icon(Icons.bar_chart_rounded, size: 18, color: Color(0xFFA855F7)),
                                          const Gap(6),
                                          Text(
                                            'Traffic Usage',
                                            style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
                                          ),
                                        ],
                                      ),
                                      const Gap(10),
                                      Row(
                                        children: [
                                          Expanded(
                                            child: Column(
                                              children: [
                                                const Icon(Icons.arrow_downward_rounded, size: 16, color: Color(0xFFA855F7)),
                                                const Gap(2),
                                                Text(
                                                  _formatBytes(stats?.downlinkTotal.toInt() ?? 0),
                                                  style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
                                                ),
                                                Text(
                                                  'Download · ${_formatBytes(stats?.downlink.toInt() ?? 0)}/s',
                                                  style: theme.textTheme.labelSmall?.copyWith(
                                                    color: theme.colorScheme.onSurfaceVariant,
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                          Container(
                                            width: 1,
                                            height: 36,
                                            color: const Color(0x448B5CF6),
                                          ),
                                          Expanded(
                                            child: Column(
                                              children: [
                                                const Icon(Icons.arrow_upward_rounded, size: 16, color: Color(0xFF38BDF8)),
                                                const Gap(2),
                                                Text(
                                                  _formatBytes(stats?.uplinkTotal.toInt() ?? 0),
                                                  style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
                                                ),
                                                Text(
                                                  'Upload · ${_formatBytes(stats?.uplink.toInt() ?? 0)}/s',
                                                  style: theme.textTheme.labelSmall?.copyWith(
                                                    color: theme.colorScheme.onSurfaceVariant,
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                        ],
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ),

                            if (!torState.isTorModeSelected) const ActiveProxyFooter(),
                            Gap(showProxiesBar ? 68 : 24),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              if (showProxiesBar)
                Positioned(
                  right: 0,
                  left: 0,
                  bottom: 0,
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      const Flexible(child: LocalProxyHint()),
                      Material(
                        color: theme.colorScheme.primaryContainer,
                        borderRadius: const BorderRadius.only(
                          topLeft: Radius.circular(16),
                          topRight: Radius.circular(16),
                        ),
                        child: InkWell(
                          borderRadius: const BorderRadius.only(
                            topLeft: Radius.circular(16),
                            topRight: Radius.circular(16),
                          ),
                          onTap: () => context.pushNamed('proxies'),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(Icons.keyboard_arrow_up_rounded, color: theme.colorScheme.onPrimaryContainer),
                                const Gap(4),
                                Text(
                                  t.pages.proxies.title.capitalize(),
                                  style: theme.textTheme.labelLarge?.copyWith(
                                    color: theme.colorScheme.onPrimaryContainer,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}
