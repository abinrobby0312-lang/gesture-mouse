import { useEffect } from 'react';
import { StyleSheet, View } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import Svg, { Circle, G, Path, Rect } from 'react-native-svg';
import { shortestDeltaDeg } from './geo';
import { ink } from './theme';

type Props = {
  /** Screen angle the needle should point at, degrees clockwise from the top of the phone. */
  angle: number | null;
  size: number;
  /** Drawn as an outline while the heading is not trustworthy. */
  dimmed?: boolean;
  /** The centre hub; off when text sits in the emblem instead. */
  hub?: boolean;
};

const RAYS = 24;

// Alternate wedges of the sunburst behind the emblem, in a 100-unit box.
const rayPath = Array.from({ length: RAYS / 2 }, (_, i) => {
  const step = (2 * Math.PI) / RAYS;
  const a0 = i * 2 * step - Math.PI / 2;
  const a1 = a0 + step;
  const p = (a: number) => `${(50 + 48.5 * Math.cos(a)).toFixed(2)} ${(50 + 48.5 * Math.sin(a)).toFixed(2)}`;
  return `M50 50 L${p(a0)} L${p(a1)} Z`;
}).join(' ');

// The needle is a beer bottle; the crown cap is the end that points at the shop.
const GLASS =
  'M46.5 13 L53.5 13 L53.5 31 C53.5 37 60 39 60 45 L60 89 Q60 92 57 92 L43 92 Q40 92 40 89 L40 45 C40 39 46.5 37 46.5 31 Z';
const CAP = 'M45 11.5 L45 7 Q45 5.5 46.5 5.5 L53.5 5.5 Q55 5.5 55 7 L55 11.5 Z';

/** The label's emblem: a sunburst with the bottle over it. With no angle, only the burst and hub show. */
export function Needle({ angle, size, dimmed = false, hub = true }: Props) {
  // Unwrapped angle: always step by the shortest delta so the needle never spins the long way round.
  const rotation = useSharedValue(angle ?? 0);
  useEffect(() => {
    if (angle == null) return;
    const target = rotation.value + shortestDeltaDeg(rotation.value, angle);
    rotation.value = withTiming(target, { duration: 120 });
  }, [angle, rotation]);

  const style = useAnimatedStyle(() => ({ transform: [{ rotate: `${rotation.value}deg` }] }));
  const line = { stroke: ink.key, strokeWidth: 1.3, strokeLinejoin: 'round' as const };

  return (
    <View style={{ width: size, height: size }}>
      <Svg width={size} height={size} viewBox="0 0 100 100" style={StyleSheet.absoluteFill}>
        <G opacity={dimmed ? 0.45 : 1}>
          <Path d={rayPath} fill={ink.ray} />
        </G>
        <Circle cx={50} cy={50} r={48.5} stroke={ink.key} strokeWidth={1.2} fill="none" />
        <Circle cx={50} cy={50} r={45.5} stroke={ink.key} strokeWidth={0.5} fill="none" />
        {angle == null && hub && (
          <>
            <Circle cx={50} cy={50} r={4.2} fill={ink.key} />
            <Circle cx={50} cy={50} r={1.6} fill={ink.label} />
          </>
        )}
      </Svg>
      {angle != null && (
        <Animated.View style={[StyleSheet.absoluteFill, style]}>
          <Svg width={size} height={size} viewBox="0 0 100 100">
            <Path d={GLASS} fill={dimmed ? ink.label : ink.glass} {...line} />
            {!dimmed && <Rect x={43} y={46} width={2.4} height={41} fill={ink.glassLight} />}
            <Rect x={40} y={57} width={20} height={17} fill={dimmed ? ink.label : ink.emblem} {...line} />
            <Rect x={45.5} y={11.5} width={9} height={2.5} fill={ink.key} />
            <Path d={CAP} fill={dimmed ? ink.label : ink.emblem} {...line} />
          </Svg>
        </Animated.View>
      )}
    </View>
  );
}
