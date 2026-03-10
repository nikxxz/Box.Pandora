/**
 * ZoomableImage — Pinch-to-zoom image wrapper for the MediaViewer.
 *
 * Behaviour:
 *  • Pinch     — zoom 1x – 5x; spring back to 1x if released below threshold.
 *  • Double-tap — toggle between 1x and 2x at the tap focal point.
 *  • Pan       — single-finger drag when zoomed in (disabled at 1x so the
 *                parent FlatList can handle horizontal swipe navigation).
 *  • reset()   — expose via ref; call when the page is navigated away from.
 *
 * props:
 *   uri          — string  image URI
 *   contentFit   — expo-image contentFit ('cover' | 'contain' | …)
 *   recyclingKey — string  passed to expo-image for cache recycling
 *   transition   — number  fade-in duration ms (default 150)
 *   cachePolicy  — expo-image cachePolicy (default 'memory-disk')
 */

import React, {
  useState,
  useCallback,
  forwardRef,
  useImperativeHandle,
} from 'react';
import { StyleSheet } from 'react-native';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withSpring,
  runOnJS,
} from 'react-native-reanimated';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import { Image } from 'expo-image';

// ─── Constants ────────────────────────────────────────────────────────────────

const MAX_SCALE = 5;
const SNAP_THRESHOLD = 1.15; // release below this → snap back to 1x
const DOUBLE_TAP_ZOOM = 2.5; // scale target when double-tapping to zoom in
const SPRING = { damping: 18, stiffness: 180, mass: 0.4 };

// ─── Component ────────────────────────────────────────────────────────────────

export const ZoomableImage = React.memo(
  forwardRef(function ZoomableImage(
    {
      uri,
      contentFit = 'cover',
      recyclingKey,
      transition = 150,
      cachePolicy = 'memory-disk',
      zoomEnabled = true,
      onScaleChange,
    },
    ref,
  ) {
    // Pan is enabled only while scale > 1 (JS-side toggle so the gesture
    // descriptor can be re-evaluated, keeping the FlatList unblocked at 1x).
    const [panEnabled, setPanEnabled] = useState(false);

    // ── Shared values (UI thread) ────────────────────────────────────────────
    const scale = useSharedValue(1);
    const savedScale = useSharedValue(1);
    const translateX = useSharedValue(0);
    const translateY = useSharedValue(0);
    const savedTranslateX = useSharedValue(0);
    const savedTranslateY = useSharedValue(0);

    // ── JS: reset called from the imperative handle (JS thread) ─────────────
    const resetFromJS = useCallback(() => {
      scale.value = withSpring(1, SPRING);
      savedScale.value = 1;
      translateX.value = withSpring(0, SPRING);
      translateY.value = withSpring(0, SPRING);
      savedTranslateX.value = 0;
      savedTranslateY.value = 0;
      setPanEnabled(false);
      onScaleChange?.(false);
    }, [
      scale,
      savedScale,
      translateX,
      translateY,
      savedTranslateX,
      savedTranslateY,
      onScaleChange,
    ]);

    // ── Expose reset() ───────────────────────────────────────────────────────
    useImperativeHandle(ref, () => ({ reset: resetFromJS }), [resetFromJS]);

    // ── Pinch gesture ────────────────────────────────────────────────────────
    // e.scale is cumulative from the gesture start, so multiply by the
    // saved baseline taken at the last gesture end.
    const pinchGesture = Gesture.Pinch()
      .enabled(zoomEnabled)
      .onUpdate(e => {
        scale.value = Math.max(
          1,
          Math.min(MAX_SCALE, savedScale.value * e.scale),
        );
      })
      .onEnd(() => {
        if (scale.value < SNAP_THRESHOLD) {
          // Snap back to identity
          scale.value = withSpring(1, SPRING);
          savedScale.value = 1;
          translateX.value = withSpring(0, SPRING);
          translateY.value = withSpring(0, SPRING);
          savedTranslateX.value = 0;
          savedTranslateY.value = 0;
          runOnJS(setPanEnabled)(false);
          if (onScaleChange) runOnJS(onScaleChange)(false);
        } else {
          savedScale.value = scale.value;
          runOnJS(setPanEnabled)(true);
          if (onScaleChange) runOnJS(onScaleChange)(true);
        }
      });

    // ── Pan gesture ──────────────────────────────────────────────────────────
    // Disabled at 1x so horizontal swipes fall through to the parent FlatList.
    const panGesture = Gesture.Pan()
      .enabled(panEnabled)
      .averageTouches(true)
      .minDistance(0)
      .onUpdate(e => {
        translateX.value = savedTranslateX.value + e.translationX;
        translateY.value = savedTranslateY.value + e.translationY;
      })
      .onEnd(() => {
        savedTranslateX.value = translateX.value;
        savedTranslateY.value = translateY.value;
      });

    // ── Double-tap gesture ───────────────────────────────────────────────────
    const doubleTapGesture = Gesture.Tap()
      .enabled(zoomEnabled)
      .numberOfTaps(2)
      .maxDelay(300)
      .onEnd((e, success) => {
        if (!success) return;
        if (scale.value > 1.05) {
          // Already zoomed — snap back to identity
          scale.value = withSpring(1, SPRING);
          savedScale.value = 1;
          translateX.value = withSpring(0, SPRING);
          translateY.value = withSpring(0, SPRING);
          savedTranslateX.value = 0;
          savedTranslateY.value = 0;
          runOnJS(setPanEnabled)(false);
          if (onScaleChange) runOnJS(onScaleChange)(false);
        } else {
          scale.value = withSpring(DOUBLE_TAP_ZOOM, SPRING);
          savedScale.value = DOUBLE_TAP_ZOOM;
          runOnJS(setPanEnabled)(true);
          if (onScaleChange) runOnJS(onScaleChange)(true);
        }
      });

    // ── Composed gesture ─────────────────────────────────────────────────────
    // All three run simultaneously.  Pinch and pan work together for two-finger
    // drag-while-pinching.  Double-tap only fires on 2 taps; at 1 tap (with no
    // second tap arriving) it naturally fails, so a plain press/tap falls
    // through to underlying views.
    const gesture = Gesture.Simultaneous(
      pinchGesture,
      panGesture,
      doubleTapGesture,
    );

    // ── Animated style ───────────────────────────────────────────────────────
    const animStyle = useAnimatedStyle(() => ({
      transform: [
        { translateX: translateX.value },
        { translateY: translateY.value },
        { scale: scale.value },
      ],
    }));

    return (
      <GestureDetector gesture={gesture}>
        <Animated.View style={[StyleSheet.absoluteFill, animStyle]}>
          <Image
            source={{ uri }}
            style={StyleSheet.absoluteFill}
            contentFit={contentFit}
            contentPosition="center"
            cachePolicy={cachePolicy}
            recyclingKey={recyclingKey}
            transition={transition}
          />
        </Animated.View>
      </GestureDetector>
    );
  }),
);
