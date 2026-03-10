/**
 * PandoraMythScreen
 *
 * An Easter egg screen accessible from the sidebar.
 * A rich editorial telling of the myth of Pandora's Box,
 * closing with a reflection on how it connects to this app.
 */

import React, { useRef } from 'react';
import {
  Animated,
  Dimensions,
  Image,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { Icon } from '../../components/ui/Icon';

const { width: SCREEN_WIDTH } = Dimensions.get('window');

// ─── Images ───────────────────────────────────────────────────────────────────

const IMG_WATERHOUSE = require('../../assets/images/Pandora-John-William-Waterhouse-768px.webp');
const IMG_DARK_INTERP = require('../../assets/images/Artist-box-Pandora-interpretation-evil-misery.webp');
const IMG_CINEMATIC = require('../../assets/images/mythology-pandora-box-04-mdj-1280X720.webp');

// ─── Content blocks ───────────────────────────────────────────────────────────

// Each block is one of:
//   { type: 'hero' }
//   { type: 'chapter', numeral, title }
//   { type: 'paragraph', text }
//   { type: 'pullquote', text }
//   { type: 'image', src, caption, aspectRatio }
//   { type: 'divider' }
//   { type: 'connection_heading' }
//   { type: 'connection_quote', text }
//   { type: 'connection_body', text }
//   { type: 'footer' }

const CONTENT = [
  { type: 'hero' },

  {
    type: 'chapter',
    numeral: 'I',
    title: 'The Birth of Pandora',
  },
  {
    type: 'paragraph',
    text: 'Before Pandora, the world belonged entirely to men. There was hardship, yes — labour and hunger and cold — but there was no true suffering, no illness, no death that crept in uninvited. The gods had kept those things for themselves, stored away, out of mortal reach.',
  },
  {
    type: 'paragraph',
    text: 'Then Prometheus stole fire from Olympus and carried it down to earth in the hollow of a fennel stalk. With fire came warmth, craft, ambition — the spark that would one day birth civilisation. Zeus, sovereign of the gods, watched this from his throne on Olympus and felt something cold and precise move through him: not rage, but the resolve to answer one act of defiance with another.',
  },
  {
    type: 'paragraph',
    text: 'He summoned Hephaestus, the divine smith, and gave a simple command: fashion a woman from earth and water. Make her perfect.',
  },
  {
    type: 'paragraph',
    text: 'Each god contributed a gift. Athena wove her a robe and taught her the art of craft. Aphrodite breathed beauty into her face and longing into her bearing. Apollo gave her music and grace of movement. Hermes filled her tongue with honeyed words and a restless, curious mind. When it was done, the gods named her Pandora — "all-gifted" — for each Olympian had poured something of themselves into her.',
  },
  {
    type: 'pullquote',
    text: '"She was the most beautiful thing the world had ever seen. That was, of course, the point."',
  },
  {
    type: 'image',
    src: IMG_WATERHOUSE,
    caption: 'Pandora — John William Waterhouse, 1896',
    aspectRatio: 768 / 960,
  },

  {
    type: 'chapter',
    numeral: 'II',
    title: 'The Forbidden Vessel',
  },
  {
    type: 'paragraph',
    text: 'Zeus sent Pandora to Epimetheus, the brother of Prometheus. Prometheus had warned his brother never to accept gifts from Zeus. But Epimetheus looked at Pandora and forgot every warning he had ever been given.',
  },
  {
    type: 'paragraph',
    text: "With her came a vessel — a great sealed pithos, a clay storage jar taller than a man's chest. In later centuries, a mistranslation would transform it into a box, and the box would become the more enduring image. But in Hesiod's original telling, it was a jar: ancient, dark, stoppered with wax and bound with rope. Zeus delivered it with one absolute command: it must never be opened.",
  },
  {
    type: 'paragraph',
    text: 'No explanation was given. No inventory of its contents. Only the prohibition, and the jar sitting in the corner of the house, breathing silence into every room it occupied.',
  },
  {
    type: 'pullquote',
    text: '"What is forbidden becomes desire.\nWhat is hidden becomes obsession."',
  },

  {
    type: 'chapter',
    numeral: 'III',
    title: 'Curiosity Unchained',
  },
  {
    type: 'paragraph',
    text: 'Days became weeks. Weeks became seasons. The jar sat in shadow, and Pandora circled it the way a flame circles a moth — or perhaps the other way around. Hermes had given her curiosity. Zeus had made that gift a trap.',
  },
  {
    type: 'paragraph',
    text: "One evening, while the house was quiet, Pandora's fingers found the lid. She told herself she only wanted to look. To know. One glance, and she would close it again.",
  },
  {
    type: 'paragraph',
    text: 'The moment the seal broke, the world changed forever.',
  },
  {
    type: 'paragraph',
    text: 'Out poured everything that had been kept from mankind. Sickness rushed out in a dark cloud, clinging to skin and breath. Grief followed, heavier and slower. Then envy, and cruelty, and madness, and old age that stoops and hollows. Strife scattered herself across every village, every family, every heart. Death — patient, inevitable — drifted out last of all, almost gentle.',
  },
  {
    type: 'paragraph',
    text: 'Pandora slammed the lid shut. She pressed her whole weight against it. But it was already too late.',
  },
  {
    type: 'image',
    src: IMG_DARK_INTERP,
    caption:
      "The Opening — artist's interpretation of the moment the jar was unsealed",
  },

  {
    type: 'chapter',
    numeral: 'IV',
    title: 'The One That Remained',
  },
  {
    type: 'paragraph',
    text: 'Almost too late.',
  },
  {
    type: 'paragraph',
    text: 'At the very bottom of the jar, pressed against the clay, something had not escaped with the others. It was small — too small, perhaps, or too patient. Pandora opened the lid a second time, trembling, and this last spirit rose out slowly: Elpis. Hope.',
  },
  {
    type: 'paragraph',
    text: 'Why hope was imprisoned alongside the evils is a question scholars have wrestled with for two and a half millennia. Was it a kindness — hope saved for mankind so they could endure what had been released? Or was it a final cruelty — the one thing that keeps humans walking toward suffering they might otherwise flee?',
  },
  {
    type: 'paragraph',
    text: 'Perhaps both. The myth does not resolve the tension. It holds it.',
  },
  {
    type: 'pullquote',
    text: '"Of all the things that fly and crawl and rest beneath the sun, hope alone remains with man."',
  },
  {
    type: 'image',
    src: IMG_CINEMATIC,
    caption: 'Elpis — hope, the last spirit in the jar',
    aspectRatio: 1280 / 720,
  },

  {
    type: 'chapter',
    numeral: 'V',
    title: 'What the Myth Really Means',
  },
  {
    type: 'paragraph',
    text: "The ancient Greeks used Pandora's story to answer the oldest human question: why does the world hurt? Their answer was characteristically unsentimental — not because the gods are cruel, exactly, but because curiosity is inseparable from humanity, and knowledge always costs something.",
  },
  {
    type: 'paragraph',
    text: 'The vessel itself was not evil. It simply contained. The miseries inside had always existed — they were not created by the opening, only released. What Pandora gave the world was not suffering itself, but awareness of suffering. And awareness, once arrived, cannot be undone.',
  },
  {
    type: 'paragraph',
    text: 'But she also gave the world hope. And the Greeks, who suffered as loudly and as beautifully as any civilisation that has ever existed, seemed to believe that was enough.',
  },

  { type: 'divider' },

  { type: 'connection_heading' },
  {
    type: 'connection_quote',
    text: '"In the myth, Pandora\'s box is more than just a container — it\'s a representation of the human condition."',
  },
  {
    type: 'connection_body',
    text: 'So too is a gallery of photographs.',
  },
  {
    type: 'connection_body',
    text: 'Every image you have ever taken carries something unseen — a fragment of a moment that cannot be relived, a face that has since changed, a place you can no longer return to. A photo gallery is its own kind of sealed jar: memories stoppered behind glass, some joyful, some achingly private, all of them irreplaceable.',
  },
  {
    type: 'connection_body',
    text: "Pandora's Box was named for this truth. The app does not simply store files. It holds the things you chose to keep. And the things you chose to hide — your hidden files, locked behind a passcode or a fingerprint — are often the things that matter most. Not because they are dangerous, but because they are deeply, particularly yours.",
  },
  {
    type: 'connection_body',
    text: "Like Pandora's jar, the app holds both shadow and light. Locked folders and favourite memories. Hidden files and starred moments. And at the very bottom, beyond all the organisation and the filtering and the access controls, something like hope persists: the belief that what we carry with us — the moments we thought to photograph — says something true about who we are.",
  },
  {
    type: 'connection_body',
    text: 'Open the box. Carefully.',
  },

  { type: 'footer' },
];

// ─── Screen ───────────────────────────────────────────────────────────────────

export function PandoraMythScreen({ navigation }) {
  const insets = useSafeAreaInsets();
  const { colors, isDark } = useTheme();
  const { state: appState } = useAppContext();
  const accentColor = appState.accentColor ?? colors.accent;

  const scrollY = useRef(new Animated.Value(0)).current;

  // Back-button opacity: fully opaque always, but fades header chrome on scroll
  const headerBg = scrollY.interpolate({
    inputRange: [0, 80],
    outputRange: ['transparent', colors.background],
    extrapolate: 'clamp',
  });

  const s = makeStyles(colors, accentColor, insets, isDark);

  const renderBlock = (block, index) => {
    switch (block.type) {
      case 'hero':
        return (
          <View key="hero" style={s.hero}>
            <Text style={s.heroEyebrow}>ANCIENT GREEK MYTHOLOGY</Text>
            <Text style={s.heroTitle}>PANDORA'S{'\n'}BOX</Text>
            <Text style={s.heroSubtitle}>
              The myth of the first woman, the forbidden vessel,{'\n'}and the
              hope that survived
            </Text>
            <View style={[s.heroRule, { backgroundColor: accentColor }]} />
          </View>
        );

      case 'chapter':
        return (
          <View key={index} style={s.chapterHeader}>
            <Text style={[s.chapterNumeral, { color: accentColor }]}>
              {block.numeral}
            </Text>
            <Text style={s.chapterTitle}>{block.title}</Text>
            <View style={[s.chapterRule, { backgroundColor: accentColor }]} />
          </View>
        );

      case 'paragraph':
        return (
          <Text key={index} style={s.paragraph}>
            {block.text}
          </Text>
        );

      case 'pullquote':
        return (
          <View
            key={index}
            style={[s.pullquote, { borderLeftColor: accentColor }]}
          >
            <Text style={[s.pullquoteText, { color: accentColor }]}>
              {block.text}
            </Text>
          </View>
        );

      case 'image': {
        const asset = Image.resolveAssetSource(block.src);
        const ratio = asset
          ? asset.width / asset.height
          : block.aspectRatio ?? 1;
        const imgHeight = SCREEN_WIDTH / ratio;
        return (
          <View key={index} style={s.imageBlock}>
            <Image
              source={block.src}
              style={[s.image, { height: imgHeight }]}
              resizeMode="cover"
            />
            <Text style={s.imageCaption}>{block.caption}</Text>
          </View>
        );
      }

      case 'divider':
        return (
          <View key="divider" style={s.dividerRow}>
            <View style={[s.dividerLine, { backgroundColor: colors.border }]} />
            <Text style={[s.dividerGlyph, { color: accentColor }]}>✦</Text>
            <View style={[s.dividerLine, { backgroundColor: colors.border }]} />
          </View>
        );

      case 'connection_heading':
        return (
          <View key="connection_heading" style={s.connectionHeadingRow}>
            <Text style={[s.connectionEyebrow, { color: accentColor }]}>
              THE BOX & THE GALLERY
            </Text>
            <Text style={s.connectionHeading}>
              A reflection on memory, privacy, and what we choose to keep
            </Text>
          </View>
        );

      case 'connection_quote':
        return (
          <View
            key="connection_quote"
            style={[s.connectionQuoteBlock, { borderColor: accentColor }]}
          >
            <Text style={[s.connectionQuoteText, { color: colors.text }]}>
              {block.text}
            </Text>
          </View>
        );

      case 'connection_body':
        return (
          <Text
            key={index}
            style={[
              s.paragraph,
              s.connectionParagraph,
              block.text === 'Open the box. Carefully.'
                ? [s.closingLine, { color: accentColor }]
                : null,
            ]}
          >
            {block.text}
          </Text>
        );

      case 'footer':
        return (
          <View key="footer" style={s.footer}>
            <Text style={[s.footerTitle, { color: accentColor }]}>
              PANDORA'S BOX
            </Text>
            <Text style={s.footerSub}>Gallery · Privacy · Memory</Text>
          </View>
        );

      default:
        return null;
    }
  };

  return (
    <View style={[s.root, { backgroundColor: colors.background }]}>
      <StatusBar
        barStyle={isDark ? 'light-content' : 'dark-content'}
        translucent
        backgroundColor="transparent"
      />

      {/* ── Floating header bar ────────────────────────────────────────── */}
      <Animated.View
        style={[
          s.headerBar,
          { backgroundColor: headerBg, paddingTop: insets.top },
        ]}
      >
        <TouchableOpacity
          onPress={() => navigation.goBack()}
          style={s.backBtn}
          activeOpacity={0.7}
          hitSlop={{ top: 12, bottom: 12, left: 12, right: 12 }}
        >
          <Icon name="back" size={20} color={colors.text} />
        </TouchableOpacity>
      </Animated.View>

      {/* ── Article scroll ────────────────────────────────────────────── */}
      <Animated.ScrollView
        onScroll={Animated.event(
          [{ nativeEvent: { contentOffset: { y: scrollY } } }],
          { useNativeDriver: false },
        )}
        scrollEventThrottle={16}
        contentContainerStyle={[
          s.scroll,
          { paddingTop: insets.top + 56, paddingBottom: insets.bottom + 48 },
        ]}
        showsVerticalScrollIndicator={false}
      >
        {CONTENT.map((block, i) => renderBlock(block, i))}
      </Animated.ScrollView>
    </View>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

function makeStyles(colors, accent, insets, isDark) {
  const pagePad = 20;

  return StyleSheet.create({
    root: {
      flex: 1,
    },
    headerBar: {
      position: 'absolute',
      top: 0,
      left: 0,
      right: 0,
      zIndex: 10,
      paddingBottom: 8,
      paddingHorizontal: pagePad,
    },
    backBtn: {
      width: 40,
      height: 40,
      borderRadius: 20,
      backgroundColor: colors.surface,
      alignItems: 'center',
      justifyContent: 'center',
      marginTop: 8,
      shadowColor: '#000',
      shadowOffset: { width: 0, height: 2 },
      shadowOpacity: 0.2,
      shadowRadius: 6,
      elevation: 4,
    },
    scroll: {
      paddingHorizontal: pagePad,
    },

    // ── Hero ──────────────────────────────────────────────────────────
    hero: {
      alignItems: 'center',
      paddingTop: 32,
      paddingBottom: 48,
    },
    heroEyebrow: {
      fontSize: 10,
      fontWeight: '700',
      letterSpacing: 3.5,
      color: colors.textTertiary,
      textTransform: 'uppercase',
      marginBottom: 20,
    },
    heroTitle: {
      fontSize: 52,
      fontWeight: '300',
      letterSpacing: 6,
      color: colors.text,
      textAlign: 'center',
      lineHeight: 60,
      marginBottom: 20,
    },
    heroSubtitle: {
      fontSize: 13,
      color: colors.textSecondary,
      textAlign: 'center',
      lineHeight: 20,
      fontStyle: 'italic',
      marginBottom: 28,
    },
    heroRule: {
      width: 40,
      height: 1.5,
      borderRadius: 1,
    },

    // ── Chapter ───────────────────────────────────────────────────────
    chapterHeader: {
      marginTop: 40,
      marginBottom: 20,
    },
    chapterNumeral: {
      fontSize: 11,
      fontWeight: '700',
      letterSpacing: 3,
      marginBottom: 6,
    },
    chapterTitle: {
      fontSize: 22,
      fontWeight: '600',
      color: colors.text,
      letterSpacing: 0.3,
      marginBottom: 12,
    },
    chapterRule: {
      width: 32,
      height: 2,
      borderRadius: 1,
    },

    // ── Paragraph ─────────────────────────────────────────────────────
    paragraph: {
      fontSize: 15.5,
      lineHeight: 26,
      color: colors.textSecondary,
      marginBottom: 18,
      fontWeight: '300',
    },

    // ── Pull quote ────────────────────────────────────────────────────
    pullquote: {
      borderLeftWidth: 3,
      paddingLeft: 16,
      marginVertical: 24,
      marginLeft: 4,
    },
    pullquoteText: {
      fontSize: 17,
      fontStyle: 'italic',
      lineHeight: 28,
      fontWeight: '400',
    },

    // ── Image ─────────────────────────────────────────────────────────
    imageBlock: {
      marginVertical: 28,
      marginHorizontal: -pagePad,
    },
    image: {
      width: SCREEN_WIDTH,
      backgroundColor: colors.surface,
      minHeight: SCREEN_WIDTH * 0.5,
    },
    imageCaption: {
      fontSize: 11,
      color: colors.textTertiary,
      fontStyle: 'italic',
      textAlign: 'center',
      marginTop: 10,
      paddingHorizontal: pagePad,
      letterSpacing: 0.3,
    },

    // ── Divider ───────────────────────────────────────────────────────
    dividerRow: {
      flexDirection: 'row',
      alignItems: 'center',
      marginVertical: 40,
      gap: 12,
    },
    dividerLine: {
      flex: 1,
      height: StyleSheet.hairlineWidth,
    },
    dividerGlyph: {
      fontSize: 14,
    },

    // ── Connection section ────────────────────────────────────────────
    connectionHeadingRow: {
      marginBottom: 24,
    },
    connectionEyebrow: {
      fontSize: 10,
      fontWeight: '700',
      letterSpacing: 3.5,
      marginBottom: 10,
    },
    connectionHeading: {
      fontSize: 19,
      fontWeight: '600',
      color: colors.text,
      lineHeight: 27,
      letterSpacing: 0.2,
    },
    connectionQuoteBlock: {
      borderWidth: 1,
      borderRadius: 12,
      padding: 18,
      marginBottom: 24,
      backgroundColor: isDark ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.03)',
    },
    connectionQuoteText: {
      fontSize: 15,
      fontStyle: 'italic',
      lineHeight: 24,
      fontWeight: '400',
    },
    connectionParagraph: {
      color: colors.text,
      fontWeight: '300',
    },
    closingLine: {
      fontSize: 17,
      fontStyle: 'italic',
      fontWeight: '500',
      letterSpacing: 0.4,
      textAlign: 'center',
      marginTop: 8,
      marginBottom: 0,
    },

    // ── Footer ────────────────────────────────────────────────────────
    footer: {
      alignItems: 'center',
      paddingTop: 48,
      gap: 6,
    },
    footerTitle: {
      fontSize: 13,
      fontWeight: '700',
      letterSpacing: 4,
    },
    footerSub: {
      fontSize: 11,
      color: colors.textTertiary,
      letterSpacing: 1.5,
    },
  });
}
