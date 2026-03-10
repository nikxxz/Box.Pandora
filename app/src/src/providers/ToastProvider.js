import React, { createContext, useCallback, useContext, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Toast } from '../components/ui/Toast';

/**
 * ToastProvider — global toast notification system.
 *
 * Wrap your app once; then call useToast() anywhere to fire toasts.
 *
 * API:
 *   const toast = useToast();
 *
 *   toast.success('Saved!', 'Your changes have been saved.');
 *   toast.error('Failed', 'Something went wrong.');
 *   toast.warning('Heads up', 'Storage is almost full.');
 *   toast.info('Did you know?', 'You can tag items.');
 *
 *   // Full control:
 *   toast.show({ title, subtitle, type, duration });
 */

const ToastContext = createContext(null);

let _uid = 0;

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);

  const dismiss = useCallback(id => {
    setToasts(prev => prev.filter(t => t.id !== id));
  }, []);

  const show = useCallback(
    ({ title, subtitle, type = 'info', duration = 3200, progress = null }) => {
      const id = ++_uid;
      setToasts(prev => {
        // Keep max 3 toasts visible — drop the oldest if over limit
        const next = [
          ...prev,
          { id, title, subtitle, type, duration, progress },
        ];
        return next.length > 3 ? next.slice(next.length - 3) : next;
      });
      return id;
    },
    [],
  );

  /**
   * Update an existing toast in-place (title, subtitle, progress).
   * No remount, no re-animation. Useful for live progress updates.
   *
   * @param {number} id   — id returned by show()
   * @param {{ title?, subtitle?, progress? }} patch
   */
  const update = useCallback((id, patch) => {
    setToasts(prev => prev.map(t => (t.id === id ? { ...t, ...patch } : t)));
  }, []);

  const success = useCallback(
    (title, subtitle, opts) =>
      show({ title, subtitle, type: 'success', ...opts }),
    [show],
  );

  const error = useCallback(
    (title, subtitle, opts) =>
      show({ title, subtitle, type: 'error', ...opts }),
    [show],
  );

  const warning = useCallback(
    (title, subtitle, opts) =>
      show({ title, subtitle, type: 'warning', ...opts }),
    [show],
  );

  const info = useCallback(
    (title, subtitle, opts) => show({ title, subtitle, type: 'info', ...opts }),
    [show],
  );

  return (
    <ToastContext.Provider
      value={{ show, update, success, error, warning, info, dismiss }}
    >
      {children}
      <ToastOverlay toasts={toasts} onDismiss={dismiss} />
    </ToastContext.Provider>
  );
}

function ToastOverlay({ toasts, onDismiss }) {
  const insets = useSafeAreaInsets();

  if (toasts.length === 0) return null;

  return (
    <View
      style={[styles.overlay, { bottom: insets.bottom + 80 }]}
      pointerEvents="box-none"
    >
      {toasts.map(t => (
        <Toast key={t.id} {...t} onDismiss={onDismiss} />
      ))}
    </View>
  );
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside ToastProvider');
  return ctx;
}

const styles = StyleSheet.create({
  overlay: {
    position: 'absolute',
    left: 12,
    right: 12,
    gap: 8,
    zIndex: 9999,
  },
});
