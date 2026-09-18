package com.gesturemouse

import com.gesturemouse.KeyMap.MOD_SHIFT
import com.gesturemouse.KeyMap.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spot checks against the HID usage tables (keyboard page 0x07), US layout. */
class KeyMapTest {

    @Test fun letters() {
        assertEquals(Stroke(0, 0x04), KeyMap.forChar('a'))
        assertEquals(Stroke(0, 0x1D), KeyMap.forChar('z'))
        assertEquals(Stroke(MOD_SHIFT, 0x04), KeyMap.forChar('A'))
        assertEquals(Stroke(MOD_SHIFT, 0x1D), KeyMap.forChar('Z'))
    }

    @Test fun digitsAndTheirShiftedSymbols() {
        assertEquals(Stroke(0, 0x1E), KeyMap.forChar('1'))
        assertEquals(Stroke(0, 0x27), KeyMap.forChar('0'))
        assertEquals(Stroke(MOD_SHIFT, 0x1E), KeyMap.forChar('!'))
        assertEquals(Stroke(MOD_SHIFT, 0x1F), KeyMap.forChar('@'))
        assertEquals(Stroke(MOD_SHIFT, 0x27), KeyMap.forChar(')'))
    }

    @Test fun punctuation() {
        assertEquals(Stroke(0, 0x2C), KeyMap.forChar(' '))
        assertEquals(Stroke(0, 0x28), KeyMap.forChar('\n'))
        assertEquals(Stroke(0, 0x37), KeyMap.forChar('.'))
        assertEquals(Stroke(MOD_SHIFT, 0x38), KeyMap.forChar('?'))
        assertEquals(Stroke(0, 0x35), KeyMap.forChar('`'))
        assertEquals(Stroke(MOD_SHIFT, 0x35), KeyMap.forChar('~'))
        assertEquals(Stroke(MOD_SHIFT, 0x34), KeyMap.forChar('"'))
        assertEquals(Stroke(0, 0x31), KeyMap.forChar('\\'))
        assertEquals(Stroke(MOD_SHIFT, 0x31), KeyMap.forChar('|'))
    }

    @Test fun smartPunctuationIsSentPlain() {
        assertEquals(KeyMap.strokes("don't"), KeyMap.strokes("don’t"))
        assertEquals(KeyMap.strokes("\"hi\""), KeyMap.strokes("“hi”"))
        assertEquals(KeyMap.strokes("..."), KeyMap.strokes("…"))
    }

    @Test fun charactersWithNoKeyAreDropped() {
        assertNull(KeyMap.forChar('é'))
        assertEquals(KeyMap.strokes("caf"), KeyMap.strokes("café"))
        assertEquals(KeyMap.strokes("caf"), KeyMap.strokes("caf😀"))
    }
}
