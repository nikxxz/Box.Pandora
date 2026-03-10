/**
 * VideoPlayer
 *
 * Thin wrapper around react-native-video's <Video> component.
 * Exposes a ref so the parent can call `ref.current.seek(seconds)`.
 *
 * Props:
 *   uri                — string  (content:// or file:// URI)
 *   paused             — boolean
 *   muted              — boolean
 *   resizeMode         — 'contain' | 'cover' | 'stretch'  (default: 'contain')
 *                        Pass 'cover' for wide-aspect videos to crop instead of letterbox.
 *   onLoad             — ({ duration }) => void
 *   onProgress         — ({ currentTime }) => void
 *   onEnd              — () => void
 *   onError            — (err) => void  (optional)
 *   onReadyForDisplay  — () => void     (fires when ExoPlayer renders first frame)
 *   style              — ViewStyle (optional)
 */

import React, { forwardRef, useState } from 'react';
import Video from 'react-native-video';
import { StyleSheet, View, Text } from 'react-native';

export const VideoPlayer = forwardRef(function VideoPlayer(
  {
    uri,
    paused = false,
    muted = false,
    resizeMode = 'contain',
    onLoad,
    onProgress,
    onEnd,
    onError: onErrorProp,
    onReadyForDisplay,
    style,
  },
  ref,
) {
  const [hasError, setHasError] = useState(false);

  if (!uri || hasError) {
    return (
      <View style={[styles.video, styles.errorWrap, style]}>
        <Text style={styles.errorText}>
          {hasError ? 'Unable to play video' : 'No video source'}
        </Text>
      </View>
    );
  }

  return (
    <Video
      ref={ref}
      source={{ uri }}
      style={[styles.video, style]}
      paused={paused}
      muted={muted}
      resizeMode={resizeMode}
      repeat={false}
      playInBackground={false}
      playWhenInactive={false}
      // Fire progress updates at most every 500 ms — halves the number of
      // re-renders compared to the default 250 ms interval.
      progressUpdateInterval={500}
      // Cap ExoPlayer's in-memory buffer to reduce OOM pressure when scrolling
      // through a list of videos.  maxBufferMs = 10 s is enough for smooth
      // playback while preventing excessive memory use.
      bufferConfig={{
        minBufferMs: 500,
        maxBufferMs: 10000,
        bufferForPlaybackMs: 100,
        bufferForPlaybackAfterRebufferMs: 500,
      }}
      onLoad={data => {
        onLoad?.({ duration: data.duration ?? 0 });
      }}
      onProgress={data => {
        onProgress?.({ currentTime: data.currentTime ?? 0 });
      }}
      onEnd={() => onEnd?.()}
      onReadyForDisplay={onReadyForDisplay}
      onError={err => {
        console.warn('[VideoPlayer] error:', err);
        setHasError(true);
        onErrorProp?.(err);
      }}
    />
  );
});

const styles = StyleSheet.create({
  video: {
    flex: 1,
    backgroundColor: '#000',
  },
  errorWrap: {
    justifyContent: 'center',
    alignItems: 'center',
  },
  errorText: {
    color: 'rgba(255,255,255,0.6)',
    fontSize: 14,
  },
});
