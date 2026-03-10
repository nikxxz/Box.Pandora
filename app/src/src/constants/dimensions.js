import { Dimensions, Platform, StatusBar } from 'react-native';

const { width: SCREEN_WIDTH, height: SCREEN_HEIGHT } = Dimensions.get('window');

const GRID_COLUMNS = 2;
const GRID_PADDING = 12;
const GRID_GAP = 8;
// Row gap between card rows — ~1% of screen height (≈8-9 px on most phones)
const GRID_ROW_GAP = Math.round(SCREEN_HEIGHT * 0.01);

// Card sizing for 2-column grid
const CARD_WIDTH = (SCREEN_WIDTH - GRID_PADDING * 2 - GRID_GAP) / GRID_COLUMNS;
const CARD_HEIGHT = Math.round(CARD_WIDTH); // perfect square

// Thumbnail size for media grid (3-column)
const THUMB_GAP = 2;
const THUMB_SIZE = (SCREEN_WIDTH - THUMB_GAP * 2) / 3;

export const DIMENSIONS = {
  // Screen
  screenWidth: SCREEN_WIDTH,
  screenHeight: SCREEN_HEIGHT,

  // Grid layout (folder cards — 2 col)
  gridColumns: GRID_COLUMNS,
  gridPadding: GRID_PADDING,
  gridSpacing: GRID_GAP,
  gridRowGap: GRID_ROW_GAP,
  cardWidth: CARD_WIDTH,
  cardHeight: CARD_HEIGHT,
  cardBorderRadius: 14,

  // Media thumbnail grid (3 col)
  thumbSize: THUMB_SIZE,
  thumbGap: THUMB_GAP,

  // Chrome
  headerHeight: 56,
  tabBarHeight: Platform.OS === 'ios' ? 82 : 64,
  statusBarHeight:
    Platform.OS === 'android' ? StatusBar.currentHeight ?? 24 : 0,
};
