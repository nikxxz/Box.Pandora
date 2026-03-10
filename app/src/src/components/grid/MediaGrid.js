import React, { useCallback, useMemo, useRef } from 'react';
import { FlashList } from '@shopify/flash-list';
import { DIMENSIONS } from '../../constants/dimensions';

// Must match contentContainerStyle.paddingTop below so scroll offsets line up.
const CONTENT_PADDING_TOP = 8;

/**
 * MediaGrid
 *
 * A memory-optimised FlashList wrapper for displaying media items
 * (folders or individual photos/videos) in a fixed-column grid.
 *
 * Key optimisations (FlashList built-in):
 *  • Cell recycling — reuses native views instead of creating/destroying them
 *  • estimatedItemSize — enables accurate scroll-bar and pre-rendering
 *  • drawDistance — head-start on rendering just off-screen rows
 *  • Internal removeClippedSubviews-like behaviour
 */
// Viewability config: item must be ≥50% visible for ≥100ms before it counts.
const VIEWABILITY_CONFIG = {
  minimumViewTime: 100,
  itemVisiblePercentThreshold: 50,
};

export function MediaGrid({
  data = [],
  renderItem,
  numColumns = 2,
  itemHeight, // hint for estimatedItemSize; defaults to cardHeight
  onEndReached,
  onEndReachedThreshold = 0.5,
  ListHeaderComponent,
  ListEmptyComponent,
  ListFooterComponent,
  refreshing = false,
  onRefresh,
  extraData,
  removeClippedSubviews, // accepted for API compat but ignored — FlashList handles internally
  onViewableItemsChanged, // optional — caller receives visible item keys
  onScroll, // optional — scroll event forwarded to FlashList (use for look-ahead)
}) {
  const {
    cardHeight,
    thumbSize,
    gridSpacing,
    gridPadding,
    tabBarHeight,
    thumbGap,
  } = DIMENSIONS;

  const rowHeight = itemHeight ?? (numColumns === 2 ? cardHeight : thumbSize);
  const spacing = numColumns === 2 ? gridSpacing : thumbGap;

  // FlashList uses estimatedItemSize instead of getItemLayout.
  const estimatedItemSize = rowHeight + spacing;

  // Provide exact item sizes so FlashList can skip measurement
  const overrideItemLayout = useCallback(
    layout => {
      layout.size = rowHeight + spacing;
    },
    [rowHeight, spacing],
  );

  const keyExtractor = useCallback(
    (item, index) => item?.id ?? item?.uri ?? String(index),
    [],
  );

  // Stable ref for onEndReached to prevent re-triggering on re-render
  const onEndReachedRef = useRef(onEndReached);
  onEndReachedRef.current = onEndReached;
  const stableOnEndReached = useCallback(() => {
    onEndReachedRef.current?.();
  }, []);

  // Stable ref for onViewableItemsChanged
  const onViewableItemsChangedRef = useRef(onViewableItemsChanged);
  onViewableItemsChangedRef.current = onViewableItemsChanged;
  const stableOnViewableItemsChanged = useCallback(info => {
    onViewableItemsChangedRef.current?.(info);
  }, []);

  const contentContainerStyle = useMemo(
    () => ({
      paddingTop: CONTENT_PADDING_TOP,
      paddingBottom: tabBarHeight + 20,
      // gridPadding (12px) is only for the 2-col folder grid.
      // The 3-col thumb grid sizes tiles to fill screen with no outer padding.
      paddingHorizontal: numColumns === 2 ? gridPadding : 0,
    }),
    [tabBarHeight, numColumns, gridPadding],
  );

  return (
    <FlashList
      data={data}
      renderItem={renderItem}
      keyExtractor={keyExtractor}
      numColumns={numColumns}
      estimatedItemSize={estimatedItemSize}
      overrideItemLayout={overrideItemLayout}
      contentContainerStyle={contentContainerStyle}
      extraData={extraData}
      // ─── Memory / perf ───────────────────────────────────────────
      // FlashList handles cell recycling and view clipping internally.
      // drawDistance renders 2 extra rows ahead for smoother scrolling.
      drawDistance={estimatedItemSize * 2}
      // ─── Pagination ───────────────────────────────────────────
      onEndReached={stableOnEndReached}
      onEndReachedThreshold={onEndReachedThreshold}
      // ─── Visibility tracking ──────────────────────────────────
      onViewableItemsChanged={
        onViewableItemsChanged ? stableOnViewableItemsChanged : undefined
      }
      viewabilityConfig={
        onViewableItemsChanged ? VIEWABILITY_CONFIG : undefined
      }
      // ─── Pull-to-refresh ──────────────────────────────────────────
      refreshing={refreshing}
      onRefresh={onRefresh}
      // ─── Slots ─────────────────────────────────────────────────
      ListHeaderComponent={ListHeaderComponent}
      ListEmptyComponent={ListEmptyComponent}
      ListFooterComponent={ListFooterComponent}
      // ─── Misc ──────────────────────────────────────────────────
      showsVerticalScrollIndicator={false}
      onScroll={onScroll}
      scrollEventThrottle={onScroll ? 200 : undefined}
    />
  );
}
