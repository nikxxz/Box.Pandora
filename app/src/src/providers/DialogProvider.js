import React, {
  createContext,
  useCallback,
  useContext,
  useRef,
  useState,
} from 'react';
import { Dialog } from '../components/ui/Dialog';

/**
 * DialogProvider — global dialog / popup system.
 *
 * Wrap your app once; then call useDialog() anywhere.
 *
 * ─── API ──────────────────────────────────────────────────────────────────────
 *
 * const dialog = useDialog();
 *
 * // Simple alert (one button) — resolves when dismissed
 * await dialog.alert({
 *   title: 'Battery low!',
 *   body:  'Plug in a charger to prevent shutdown.',
 *   icon:  'information',
 *   buttonText: 'Ok',
 * });
 *
 * // Confirm — resolves true (confirm) or false (cancel)
 * const confirmed = await dialog.confirm({
 *   title:       'Allow notifications?',
 *   body:        'Pandora would like to send you notifications.',
 *   icon:        'information',
 *   confirmText: 'Allow',
 *   cancelText:  "Don't allow",
 * });
 *
 * // Destructive — resolves true (delete) or false (cancel)
 * const deleted = await dialog.destructive({
 *   title:      'Delete this item?',
 *   body:       'This item will be deleted from your device.',
 *   deleteText: 'Delete',
 * });
 *
 * // Full control
 * dialog.show({
 *   layout:  'centered',        // 'centered' | 'inline'
 *   icon:    'trash',
 *   title:   'Are you sure?',
 *   body:    '...',
 *   closable: true,
 *   buttons: [
 *     { label: 'Cancel',  btnStyle: 'cancel',      onPress: () => dialog.hide() },
 *     { label: 'Confirm', btnStyle: 'primary',     onPress: () => { ... } },
 *   ],
 * });
 *
 * dialog.hide();
 */

const DialogContext = createContext(null);

export function DialogProvider({ children }) {
  const [visible, setVisible] = useState(false);
  const [config,  setConfig]  = useState(null);
  const resolveRef = useRef(null);

  const hide = useCallback(() => setVisible(false), []);

  const show = useCallback((cfg) => {
    setConfig(cfg);
    setVisible(true);
  }, []);

  // ─── alert ──────────────────────────────────────────────────────────────────
  const alert = useCallback(({
    title,
    body,
    icon       = 'information',
    iconColor,
    layout     = 'inline',
    buttonText = 'Ok',
  } = {}) => {
    return new Promise((resolve) => {
      resolveRef.current = resolve;
      show({
        layout,
        icon,
        iconColor,
        title,
        body,
        closable: false,
        buttons: [
          {
            label:    buttonText,
            btnStyle: 'ghost',
            onPress:  () => { hide(); resolve(); },
          },
        ],
        onDismiss: () => { hide(); resolve(); },
      });
    });
  }, [show, hide]);

  // ─── confirm ────────────────────────────────────────────────────────────────
  const confirm = useCallback(({
    title,
    body,
    icon        = 'information',
    iconColor,
    layout      = 'centered',
    confirmText = 'Allow',
    cancelText  = "Don't allow",
  } = {}) => {
    return new Promise((resolve) => {
      resolveRef.current = resolve;
      show({
        layout,
        icon,
        iconColor,
        title,
        body,
        closable: false,
        buttons: [
          {
            label:    cancelText,
            btnStyle: 'ghost',
            onPress:  () => { hide(); resolve(false); },
          },
          {
            label:    confirmText,
            btnStyle: 'primary',
            onPress:  () => { hide(); resolve(true); },
          },
        ],
        onDismiss: () => { hide(); resolve(false); },
      });
    });
  }, [show, hide]);

  // ─── destructive ────────────────────────────────────────────────────────────
  const destructive = useCallback(({
    title      = 'Delete this item?',
    body       = 'This action cannot be undone.',
    icon       = 'trash',
    iconColor  = '#EF4444',
    deleteText = 'Delete',
    cancelText = 'Cancel',
  } = {}) => {
    return new Promise((resolve) => {
      resolveRef.current = resolve;
      show({
        layout:   'centered',
        icon,
        iconColor,
        title,
        body,
        closable: false,
        buttons: [
          {
            label:    cancelText,
            btnStyle: 'cancel',
            onPress:  () => { hide(); resolve(false); },
          },
          {
            label:    deleteText,
            btnStyle: 'destructive',
            onPress:  () => { hide(); resolve(true); },
          },
        ],
        onDismiss: () => { hide(); resolve(false); },
      });
    });
  }, [show, hide]);

  return (
    <DialogContext.Provider value={{ show, hide, alert, confirm, destructive }}>
      {children}
      <Dialog visible={visible} config={config} onClose={hide} />
    </DialogContext.Provider>
  );
}

export function useDialog() {
  const ctx = useContext(DialogContext);
  if (!ctx) throw new Error('useDialog must be used inside DialogProvider');
  return ctx;
}
