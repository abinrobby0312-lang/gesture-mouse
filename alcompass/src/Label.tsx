import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { font, ink } from './theme';

type Props = {
  width: number;
  /** Left of the serial strip, e.g. "No. 2 of 5". Without it the strip is left off. */
  serial?: string;
  emblem: ReactNode;
  /** Set in the black banner under the emblem. */
  title: string;
  children?: ReactNode;
};

/** A matchbox label: yellow stock, an inset keyline, serial strip, emblem, banner, then small print. */
export function Label({ width, serial, emblem, title, children }: Props) {
  return (
    <View style={[styles.stock, { width }]}>
      <View style={styles.keyline}>
        {serial ? (
          <View style={styles.serialRow}>
            <Text style={styles.serial}>{serial}</Text>
            <Text style={styles.serial}>Alcompass</Text>
          </View>
        ) : (
          <View style={styles.bare} />
        )}
        <View style={styles.emblem}>{emblem}</View>
        <View style={styles.banner}>
          <Text style={styles.title} numberOfLines={2} accessibilityRole="header">
            {title}
          </Text>
        </View>
        <View style={styles.print}>{children}</View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  stock: { backgroundColor: ink.label, padding: 7 },
  keyline: { borderWidth: 2, borderColor: ink.key, paddingHorizontal: 14, paddingBottom: 16 },
  serialRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 9,
    borderBottomWidth: 1,
    borderBottomColor: ink.key,
  },
  serial: {
    fontFamily: font.textBold,
    fontSize: 12,
    letterSpacing: 1.4,
    textTransform: 'uppercase',
    color: ink.key,
    fontVariant: ['tabular-nums'],
  },
  bare: { height: 6 },
  emblem: { alignItems: 'center', paddingTop: 18, paddingBottom: 18 },
  banner: {
    backgroundColor: ink.key,
    marginHorizontal: -14,
    paddingHorizontal: 14,
    paddingTop: 8,
    paddingBottom: 10,
  },
  title: {
    fontFamily: font.display,
    fontSize: 32,
    lineHeight: 34,
    letterSpacing: 0.4,
    textTransform: 'uppercase',
    textAlign: 'center',
    color: ink.label,
  },
  print: { alignItems: 'center', paddingTop: 12 },
});
