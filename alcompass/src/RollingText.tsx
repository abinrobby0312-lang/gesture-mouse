import { useEffect, useRef } from 'react';
import { StyleSheet, Text, TextStyle, View } from 'react-native';
import Animated, {
  Easing,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

type Props = { text: string; style: TextStyle; accessibilityLabel?: string };

/** Text whose characters roll in one by one as they change, like a counter wheel. */
export function RollingText({ text, style, accessibilityLabel }: Props) {
  return (
    <View
      style={styles.row}
      accessible
      accessibilityRole="text"
      accessibilityLabel={accessibilityLabel ?? text}
    >
      {Array.from(text).map((ch, i) => (
        <RollingChar key={i} ch={ch} style={style} />
      ))}
    </View>
  );
}

function RollingChar({ ch, style }: { ch: string; style: TextStyle }) {
  const reduced = useReducedMotion();
  const shift = useSharedValue(0);
  const first = useRef(true);
  const lift = (style.fontSize ?? 16) * 0.45;

  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    if (reduced) return;
    shift.value = lift;
    shift.value = withTiming(0, { duration: 260, easing: Easing.out(Easing.exp) });
  }, [ch, lift, reduced, shift]);

  const anim = useAnimatedStyle(() => ({
    transform: [{ translateY: shift.value }],
    opacity: 1 - shift.value / (lift * 1.4),
  }));

  return (
    <View style={styles.clip} importantForAccessibility="no-hide-descendants">
      <Animated.View style={anim}>
        <Text style={style}>{ch === ' ' ? ' ' : ch}</Text>
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'flex-end' },
  clip: { overflow: 'hidden' },
});
