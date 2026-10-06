import { Component, type ReactNode } from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { flush, logError } from './log';
import { ink } from './theme';

type State = { failed: boolean };

/** Catches a crash while drawing the screen, logs where it happened, and offers a reload. */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  componentDidCatch(error: unknown, info: { componentStack?: string | null }) {
    logError('render', error, { component: info.componentStack?.slice(0, 800) ?? null });
    flush();
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <View style={styles.screen}>
        <View style={styles.label}>
          <Text style={styles.title}>Something broke</Text>
          <Text style={styles.body}>The bottle tipped over. We've logged it and will fix it.</Text>
          <Pressable
            accessibilityRole="button"
            style={styles.button}
            onPress={() => (Platform.OS === 'web' ? window.location.reload() : this.setState({ failed: false }))}
          >
            <Text style={styles.buttonText}>Try again</Text>
          </Pressable>
        </View>
      </View>
    );
  }
}

// System fonts on purpose: the bundled faces may be what failed to load.
const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: ink.ground, alignItems: 'center', justifyContent: 'center', padding: 16 },
  label: { backgroundColor: ink.label, borderWidth: 2, borderColor: ink.key, padding: 20, maxWidth: 360, alignItems: 'center' },
  title: { fontSize: 24, fontWeight: '800', textTransform: 'uppercase', color: ink.key },
  body: { marginTop: 8, fontSize: 15, lineHeight: 21, color: ink.key, textAlign: 'center' },
  button: { marginTop: 16, alignSelf: 'stretch', backgroundColor: ink.key, paddingVertical: 12, alignItems: 'center' },
  buttonText: { fontSize: 18, fontWeight: '800', textTransform: 'uppercase', color: ink.label },
});
