import { useEffect } from 'react';
import { StyleSheet, View } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import Svg, { Circle, Path } from 'react-native-svg';
import { shortestDeltaDeg } from './geo';

type Props = {
  /** Screen angle the needle should point at, degrees clockwise from the top of the phone. */
  angle: number;
  size: number;
  /** Greyed out while the heading is not trustworthy. */
  dimmed: boolean;
};

export function Needle({ angle, size, dimmed }: Props) {
  // Unwrapped angle: always step by the shortest delta so the needle never spins the long way round.
  const rotation = useSharedValue(angle);
  useEffect(() => {
    const target = rotation.value + shortestDeltaDeg(rotation.value, angle);
    rotation.value = withTiming(target, { duration: 120 });
  }, [angle, rotation]);

  const style = useAnimatedStyle(() => ({ transform: [{ rotate: `${rotation.value}deg` }] }));
  const color = dimmed ? '#555b66' : '#f2b134';

  return (
    <View style={{ width: size, height: size }}>
      <Svg width={size} height={size} viewBox="0 0 100 100" style={StyleSheet.absoluteFill}>
        <Circle cx={50} cy={50} r={48} stroke="#2a2f38" strokeWidth={1.5} fill="none" />
      </Svg>
      <Animated.View style={[StyleSheet.absoluteFill, style]}>
        <Svg width={size} height={size} viewBox="0 0 100 100">
          <Path d="M50 6 L62 54 L50 46 L38 54 Z" fill={color} />
          <Path d="M50 94 L58 58 L50 62 L42 58 Z" fill="#2a2f38" />
          <Circle cx={50} cy={50} r={3} fill="#0d0f13" />
        </Svg>
      </Animated.View>
    </View>
  );
}
