import React, {
  createContext,
  useCallback,
  useContext,
  useRef,
  useState,
} from 'react';
import { Animated, StyleSheet, Text, View } from 'react-native';
import { useTheme } from './ThemeProvider';
import { DotsSpinner } from '../components/common/LoadingSpinner';

/**
 * LoadingProvider — global full-screen loading overlay.
 *
 * Sits above the NavigationContainer so it covers everything:
 * tab bars, modals, drawers, status bar area, etc.
 *
 * Usage anywhere in the tree:
 *   const { showLoader, hideLoader } = useLoader();
 *   showLoader();            // no message
 *   showLoader('Saving…');   // with message
 *   hideLoader();
 *
 * Stacking-safe: showLoader/hideLoader use a depth counter so nested
 * callers can't accidentally dismiss a loader started by a parent.
 */

const LoadingContext = createContext(null);

export function LoadingProvider({ children }) {
  const { colors } = useTheme();
  const [visible, setVisible] = useState(false);
  const [message, setMessage] = useState(null);
  const depthRef = useRef(0);
  const fadeAnim = useRef(new Animated.Value(0)).current;

  const _show = useCallback(
    msg => {
      setMessage(msg ?? null);
      if (!visible) {
        fadeAnim.setValue(0);
        setVisible(true);
        Animated.timing(fadeAnim, {
          toValue: 1,
          duration: 180,
          useNativeDriver: true,
        }).start();
      }
    },
    [visible, fadeAnim],
  );

  const _hide = useCallback(() => {
    Animated.timing(fadeAnim, {
      toValue: 0,
      duration: 140,
      useNativeDriver: true,
    }).start(({ finished }) => {
      if (finished) {
        setVisible(false);
        setMessage(null);
      }
    });
  }, [fadeAnim]);

  const showLoader = useCallback(
    msg => {
      depthRef.current += 1;
      _show(msg);
    },
    [_show],
  );

  const hideLoader = useCallback(() => {
    depthRef.current = Math.max(0, depthRef.current - 1);
    if (depthRef.current === 0) _hide();
  }, [_hide]);

  return (
    <LoadingContext.Provider value={{ showLoader, hideLoader }}>
      {children}
      {visible && (
        <Animated.View
          style={[
            styles.overlay,
            { backgroundColor: colors.overlayStrong, opacity: fadeAnim },
          ]}
          pointerEvents="auto"
        >
          <View style={[styles.card, { backgroundColor: colors.surface }]}>
            <DotsSpinner dotSize={13} gap={9} />
            {message ? (
              <Text style={[styles.message, { color: colors.textSecondary }]}>
                {message}
              </Text>
            ) : null}
          </View>
        </Animated.View>
      )}
    </LoadingContext.Provider>
  );
}

export function useLoader() {
  const ctx = useContext(LoadingContext);
  if (!ctx) throw new Error('useLoader must be used inside LoadingProvider');
  return ctx;
}

const styles = StyleSheet.create({
  overlay: {
    ...StyleSheet.absoluteFillObject,
    zIndex: 9999,
    elevation: 9999,
    alignItems: 'center',
    justifyContent: 'center',
  },
  card: {
    borderRadius: 16,
    paddingVertical: 28,
    paddingHorizontal: 36,
    alignItems: 'center',
    gap: 14,
    shadowColor: '#000',
    shadowOpacity: 0.3,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 4 },
    elevation: 8,
  },
  message: {
    fontSize: 14,
    fontWeight: '500',
    textAlign: 'center',
  },
});
