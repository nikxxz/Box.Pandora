/**
 * TagCategories
 *
 * Single source of truth for the 9 tag categories used across the app.
 *
 * Each category has:
 *   key      — stored in tags.category column
 *   label    — display name
 *   icon     — require() of the category PNG asset
 *   keywords — lowercase terms used to auto-classify a tag name
 *
 * Classification priority (most discriminating first):
 *   nsfw > people > animals > places > objects > activity > style > content > misc
 *
 * Used by:
 *   • TagService.createTag() — auto-assigns category on tag creation
 *   • TagsScreen — category icon per tag row + grouped Category sort mode
 *   • TagTaxonomy — ML label → category mapping
 */

// ─── Category definitions ────────────────────────────────────────────────────

export const TAG_CATEGORIES = {
  people: {
    key: 'people',
    label: 'People',
    icon: require('../assets/icons/fashion.png'),
    keywords: [
      'person',
      'people',
      'man',
      'woman',
      'child',
      'boy',
      'girl',
      'baby',
      'infant',
      'toddler',
      'teenager',
      'adult',
      'elderly',
      'face',
      'human',
      'selfie',
      'portrait',
      'couple',
      'family',
      'crowd',
      'group',
      'friends',
      'friend',
      'bride',
      'groom',
      'mother',
      'father',
      'sister',
      'brother',
      'grandmother',
      'grandfather',
      'uncle',
      'aunt',
      'kid',
      'children',
    ],
  },

  animals: {
    key: 'animals',
    label: 'Animals',
    icon: require('../assets/icons/dog.png'),
    keywords: [
      'dog',
      'cat',
      'bird',
      'fish',
      'rabbit',
      'hamster',
      'guinea pig',
      'parrot',
      'cockatiel',
      'budgie',
      'canary',
      'horse',
      'pony',
      'donkey',
      'cow',
      'sheep',
      'goat',
      'pig',
      'lion',
      'tiger',
      'leopard',
      'cheetah',
      'elephant',
      'rhino',
      'hippo',
      'giraffe',
      'zebra',
      'gorilla',
      'monkey',
      'bear',
      'wolf',
      'fox',
      'deer',
      'raccoon',
      'squirrel',
      'dolphin',
      'whale',
      'shark',
      'turtle',
      'frog',
      'snake',
      'lizard',
      'butterfly',
      'bee',
      'spider',
      'insect',
      'puppy',
      'kitten',
      'pet',
      'wildlife',
      'fauna',
      'animal',
    ],
  },

  places: {
    key: 'places',
    label: 'Places',
    icon: require('../assets/icons/beach.png'),
    keywords: [
      'beach',
      'home',
      'bedroom',
      'bathroom',
      'kitchen',
      'living room',
      'office',
      'park',
      'garden',
      'mountain',
      'forest',
      'jungle',
      'lake',
      'river',
      'ocean',
      'sea',
      'city',
      'town',
      'street',
      'road',
      'building',
      'restaurant',
      'cafe',
      'bar',
      'hotel',
      'airport',
      'school',
      'library',
      'museum',
      'gym',
      'stadium',
      'church',
      'temple',
      'mosque',
      'outdoors',
      'outdoor',
      'indoor',
      'interior',
      'exterior',
      'nature',
      'landscape',
      'travel',
      'vacation',
      'trip',
      'country',
      'village',
      'island',
      'desert',
      'valley',
      'cave',
      'waterfall',
      'location',
      'place',
      'destination',
    ],
  },

  objects: {
    key: 'objects',
    label: 'Objects',
    icon: require('../assets/icons/tools-box.png'),
    keywords: [
      'book',
      'guitar',
      'car',
      'phone',
      'dress',
      'instrument',
      'camera',
      'laptop',
      'furniture',
      'chair',
      'table',
      'vase',
      'lamp',
      'candle',
      'food',
      'meal',
      'coffee',
      'drink',
      'flower',
      'plant',
      'tree',
      'bag',
      'shoes',
      'watch',
      'glasses',
      'hat',
      'jewelry',
      'ring',
      'necklace',
      'bicycle',
      'motorcycle',
      'airplane',
      'boat',
      'vehicle',
      'toy',
      'tool',
      'bottle',
      'cup',
      'glass',
      'umbrella',
      'key',
      'money',
      'gift',
      'painting',
      'sculpture',
      'art',
      'clothes',
      'clothing',
      'object',
    ],
  },

  activity: {
    key: 'activity',
    label: 'Activity',
    icon: require('../assets/icons/dancing-woman-silhouette.png'),
    keywords: [
      'dancing',
      'dance',
      'running',
      'run',
      'reading',
      'sleeping',
      'concert',
      'party',
      'swimming',
      'swim',
      'hiking',
      'hike',
      'cycling',
      'cooking',
      'cook',
      'eating',
      'eat',
      'drinking',
      'playing',
      'play',
      'working',
      'studying',
      'traveling',
      'shopping',
      'gaming',
      'game',
      'singing',
      'drawing',
      'writing',
      'yoga',
      'exercise',
      'workout',
      'jumping',
      'climbing',
      'skiing',
      'surfing',
      'driving',
      'walking',
      'laughing',
      'smiling',
      'celebrating',
      'event',
      'sport',
      'sports',
      'fitness',
    ],
  },

  style: {
    key: 'style',
    label: 'Style',
    icon: require('../assets/icons/aesthetic.png'),
    keywords: [
      'monochrome',
      'vintage',
      'illustration',
      '3d',
      'cinematic',
      'black and white',
      'sepia',
      'colorful',
      'neon',
      'minimalist',
      'bokeh',
      'aesthetic',
      'artistic',
      'retro',
      'lo-fi',
      'dreamy',
      'dramatic',
      'abstract',
      'surreal',
      'editorial',
      'film grain',
      'analog',
      'polaroid',
      'hdr',
      'long exposure',
      'macro',
      'wide angle',
      'silhouette',
      'double exposure',
      'motion blur',
      'grainy',
      'flat lay',
      'moody',
    ],
  },

  content: {
    key: 'content',
    label: 'Content',
    icon: require('../assets/icons/format.png'),
    keywords: [
      'video',
      'gif',
      'screenshot',
      'download',
      'animation',
      'meme',
      'comic',
      'manga',
      'cartoon',
      'document',
      'chart',
      'graph',
      'diagram',
      'infographic',
      'map',
      'qr code',
      'barcode',
      'receipt',
      'invoice',
      'certificate',
      'id card',
      'logo',
      'typography',
      'sticker',
      'clipart',
      'clip art',
      'format',
      'media',
      'file',
    ],
  },

  nsfw: {
    key: 'nsfw',
    label: 'NSFW',
    icon: require('../assets/icons/nsfw.png'),
    keywords: [
      'nsfw',
      'nude',
      'explicit',
      'private',
      'adult',
      'mature',
      'sensitive',
      'suggestive',
      'intimate',
      'erotic',
    ],
  },

  misc: {
    key: 'misc',
    label: 'Misc',
    icon: require('../assets/icons/gadgets.png'),
    keywords: [], // catch-all — matches everything not classified above
  },
};

// ─── Ordered category list ────────────────────────────────────────────────────

/** Canonical display order for categories (used in UI grouping). */
export const TAG_CATEGORY_LIST = [
  TAG_CATEGORIES.people,
  TAG_CATEGORIES.animals,
  TAG_CATEGORIES.places,
  TAG_CATEGORIES.objects,
  TAG_CATEGORIES.activity,
  TAG_CATEGORIES.style,
  TAG_CATEGORIES.content,
  TAG_CATEGORIES.nsfw,
  TAG_CATEGORIES.misc,
];

// ─── Classification ───────────────────────────────────────────────────────────

/**
 * Priority order for classification.
 * NSFW is checked first so sensitive tags are never misclassified.
 */
const CLASSIFY_ORDER = [
  'nsfw',
  'people',
  'animals',
  'places',
  'activity',
  'style',
  'content',
  'objects',
];

/**
 * Determine the category key for a tag name.
 *
 * Matching uses substring comparison both ways — the keyword includes the tag
 * name (e.g. 'friends' matches keyword 'friend') and the tag name includes the
 * keyword (e.g. 'wildlife photography' matches keyword 'wildlife').
 *
 * Falls back to 'misc' if no keyword matches.
 *
 * @param {string} tagName
 * @returns {string} category key
 */
export function getCategoryForTag(tagName) {
  if (!tagName) return 'misc';
  const lower = tagName.toLowerCase().trim();

  for (const key of CLASSIFY_ORDER) {
    const { keywords } = TAG_CATEGORIES[key];
    if (
      keywords.some(
        kw => lower === kw || lower.includes(kw) || kw.includes(lower),
      )
    ) {
      return key;
    }
  }
  return 'misc';
}

/**
 * Return the category definition object for a given key.
 * Falls back to 'misc' for unknown keys.
 *
 * @param {string|null|undefined} key
 * @returns {{ key: string, label: string, icon: number, keywords: string[] }}
 */
export function getCategoryDef(key) {
  return TAG_CATEGORIES[key] ?? TAG_CATEGORIES.misc;
}
