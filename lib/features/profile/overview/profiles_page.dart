import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hiddify/core/localization/translations.dart';
import 'package:hiddify/core/model/failures.dart';
import 'package:hiddify/core/notification/in_app_notification_controller.dart';
import 'package:hiddify/core/router/bottom_sheets/bottom_sheets_notifier.dart';
import 'package:hiddify/core/router/dialog/dialog_notifier.dart';
import 'package:hiddify/features/profile/model/profile_entity.dart';
import 'package:hiddify/features/profile/notifier/profiles_update_notifier.dart';
import 'package:hiddify/features/profile/overview/profiles_notifier.dart';
import 'package:hiddify/features/profile/widget/profile_tile.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

enum _ProfileCategory { all, subscriptions, local, active }

class ProfilesPage extends HookConsumerWidget {
  const ProfilesPage({super.key});

  Future<void> _removeInactive(WidgetRef ref) async {
    final notifier = ref.read(profilesNotifierProvider.notifier);
    final inactive = await notifier.findInactive();
    if (inactive.isEmpty) {
      final t = ref.read(translationsProvider).requireValue;
      ref.read(inAppNotificationControllerProvider).showInfoToast(t.pages.profiles.removeInactive.none);
      return;
    }
    final ids = await ref.read(dialogNotifierProvider.notifier).showRemoveInactiveProfiles(inactive);
    if (ids == null || ids.isEmpty) return;
    await notifier.deleteProfiles([
      for (final item in inactive)
        if (ids.contains(item.profile.id)) item.profile,
    ]);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = ref.watch(translationsProvider).requireValue;
    final asyncProfiles = ref.watch(profilesNotifierProvider);
    final searchQuery = useState('');
    final selectedCategory = useState(_ProfileCategory.all);
    final searchController = useTextEditingController();

    return Scaffold(
      appBar: AppBar(
        title: Text(t.pages.profiles.title),
        actions: [
          IconButton(
            onPressed: () => ref.read(foregroundProfilesUpdateNotifierProvider.notifier).trigger(),
            icon: const Icon(Icons.sync_rounded),
            tooltip: t.pages.profiles.updateSubscriptions,
          ),
          IconButton(
            onPressed: () => _removeInactive(ref),
            icon: const Icon(Icons.delete_sweep_rounded),
            tooltip: t.pages.profiles.removeInactive.action,
          ),
          IconButton(
            onPressed: () => ref.read(dialogNotifierProvider.notifier).showSortProfiles(),
            icon: const Icon(Icons.sort_rounded),
            tooltip: t.common.sort,
          ),
          const Gap(8),
        ],
      ),
      floatingActionButton: Padding(
        padding: const EdgeInsets.only(bottom: 72),
        child: FloatingActionButton.extended(
          onPressed: () async => await ref.read(bottomSheetsNotifierProvider.notifier).showAddProfile(),
          label: Text(t.pages.profiles.add),
          icon: const Icon(Icons.add_rounded),
        ),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
            child: TextField(
              controller: searchController,
              onChanged: (value) => searchQuery.value = value.trim().toLowerCase(),
              decoration: InputDecoration(
                prefixIcon: const Icon(Icons.search_rounded),
                hintText: t.pages.profiles.title,
                isDense: true,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(16)),
                suffixIcon: searchQuery.value.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.close_rounded),
                        onPressed: () {
                          searchController.clear();
                          searchQuery.value = '';
                        },
                      )
                    : null,
              ),
            ),
          ),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            child: Row(
              children: [
                _CategoryChip(
                  label: 'All',
                  selected: selectedCategory.value == _ProfileCategory.all,
                  onSelected: () => selectedCategory.value = _ProfileCategory.all,
                ),
                const Gap(8),
                _CategoryChip(
                  label: 'Subscriptions',
                  selected: selectedCategory.value == _ProfileCategory.subscriptions,
                  onSelected: () => selectedCategory.value = _ProfileCategory.subscriptions,
                ),
                const Gap(8),
                _CategoryChip(
                  label: 'Local',
                  selected: selectedCategory.value == _ProfileCategory.local,
                  onSelected: () => selectedCategory.value = _ProfileCategory.local,
                ),
                const Gap(8),
                _CategoryChip(
                  label: 'Active',
                  selected: selectedCategory.value == _ProfileCategory.active,
                  onSelected: () => selectedCategory.value = _ProfileCategory.active,
                ),
              ],
            ),
          ),
          Expanded(
            child: asyncProfiles.when(
              data: (data) {
                final filtered = data.where((profile) {
                  final matchesCategory = switch (selectedCategory.value) {
                    _ProfileCategory.all => true,
                    _ProfileCategory.subscriptions => profile is RemoteProfileEntity,
                    _ProfileCategory.local => profile is LocalProfileEntity,
                    _ProfileCategory.active => profile.active,
                  };
                  if (!matchesCategory) return false;
                  if (searchQuery.value.isEmpty) return true;
                  return profile.name.toLowerCase().contains(searchQuery.value);
                }).toList();

                if (data.isEmpty) {
                  return Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.layers_outlined, size: 56, color: Color(0xFFA855F7)),
                          const Gap(12),
                          Text(
                            t.pages.profiles.empty,
                            style: Theme.of(context).textTheme.titleMedium,
                            textAlign: TextAlign.center,
                          ),
                          const Gap(16),
                          FilledButton.icon(
                            onPressed: () => ref.read(bottomSheetsNotifierProvider.notifier).showAddProfile(),
                            icon: const Icon(Icons.add_rounded),
                            label: Text(t.pages.profiles.add),
                          ),
                        ],
                      ),
                    ),
                  );
                }

                if (filtered.isEmpty) {
                  return Center(
                    child: Text(
                      'No matching profiles',
                      style: Theme.of(context).textTheme.bodyMedium,
                    ),
                  );
                }

                return ListView.separated(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 150),
                  separatorBuilder: (context, index) => const Gap(10),
                  itemBuilder: (context, index) => ProfileTile(
                    key: ValueKey(filtered[index].id),
                    profile: filtered[index],
                  ),
                  itemCount: filtered.length,
                );
              },
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (error, stackTrace) => Center(child: Text(t.presentShortError(error))),
            ),
          ),
        ],
      ),
    );
  }
}

class _CategoryChip extends StatelessWidget {
  const _CategoryChip({
    required this.label,
    required this.selected,
    required this.onSelected,
  });

  final String label;
  final bool selected;
  final VoidCallback onSelected;

  @override
  Widget build(BuildContext context) {
    return ChoiceChip(
      label: Text(label),
      selected: selected,
      onSelected: (_) => onSelected(),
    );
  }
}
