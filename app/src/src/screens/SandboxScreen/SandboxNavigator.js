import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { useTheme } from '../../providers/ThemeProvider';
import { SandboxScreen } from './index';
import { SmartTagDebugScreen } from '../SmartTagDebugScreen';
import { BatchLearnScreen } from './BatchLearnScreen';

const Stack = createNativeStackNavigator();

/**
 * SandboxNavigator — nested stack for the Sandbox tab.
 *
 * Screens:
 *   SandboxMain             ← entry point (dev tools, theme toggle)
 *   SmartTagDebug           ← embedding model, indexing stats, suggestion preview
 */
export function SandboxNavigator() {
  const { colors } = useTheme();

  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
        contentStyle: { backgroundColor: colors.background },
        animation: 'slide_from_right',
      }}
    >
      <Stack.Screen name="SandboxMain" component={SandboxScreen} />
      <Stack.Screen name="SmartTagDebug" component={SmartTagDebugScreen} />
      <Stack.Screen name="BatchLearn" component={BatchLearnScreen} />
    </Stack.Navigator>
  );
}
