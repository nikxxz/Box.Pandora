import { useState, useEffect, useCallback } from 'react';
import { AppState } from 'react-native';
import { PermissionService } from '../services/permissions/PermissionService';
import { useAppContext } from '../store/AppContext';
import { AppActions } from '../store/actions';

/**
 * usePermissions
 *
 * Provides current permission status and request helpers.
 * Automatically re-checks permissions when the app returns to the foreground
 * (user may have changed settings while in the Settings screen).
 */
export function usePermissions() {
  const { state, dispatch } = useAppContext();
  const [isRequesting, setIsRequesting] = useState(false);

  const checkPermissions = useCallback(async () => {
    const status = await PermissionService.checkPermissions();
    dispatch(AppActions.setPermissions(status));
    return status;
  }, [dispatch]);

  const requestReadPermissions = useCallback(async () => {
    setIsRequesting(true);
    try {
      const granted = await PermissionService.requestMediaReadPermissions();
      const status = await PermissionService.checkPermissions();
      dispatch(AppActions.setPermissions(status));
      return granted;
    } finally {
      setIsRequesting(false);
    }
  }, [dispatch]);

  const requestAllPermissions = useCallback(async () => {
    setIsRequesting(true);
    try {
      const status = await PermissionService.requestAllPermissions();
      dispatch(AppActions.setPermissions(status));
      return status;
    } finally {
      setIsRequesting(false);
    }
  }, [dispatch]);

  const requestFullFileAccess = useCallback(async () => {
    setIsRequesting(true);
    try {
      const granted = await PermissionService.requestFullFileAccess();
      const status = await PermissionService.checkPermissions();
      dispatch(AppActions.setPermissions(status));
      return granted;
    } finally {
      setIsRequesting(false);
    }
  }, [dispatch]);

  // Initial check on mount
  useEffect(() => {
    checkPermissions();
  }, [checkPermissions]);

  // Re-check when app comes back to foreground (Settings → back)
  useEffect(() => {
    const sub = AppState.addEventListener('change', nextState => {
      if (nextState === 'active') checkPermissions();
    });
    return () => sub.remove();
  }, [checkPermissions]);

  return {
    permissions: state.permissions,
    hasReadPermission: state.permissions.read,
    hasWritePermission: state.permissions.write,
    hasManageStorage: state.permissions.manageStorage,
    hasAllPermissions: state.permissions.all,
    isRequesting,
    checkPermissions,
    requestReadPermissions,
    requestAllPermissions,
    requestFullFileAccess,
  };
}
