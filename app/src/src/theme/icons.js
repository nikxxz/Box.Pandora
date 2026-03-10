/**
 * Icon Registry
 *
 * Every icon available in the app, keyed by camelCase name.
 *
 * Properties per entry:
 *   png     — require() for the raster PNG (always present in src/assets/icons/)
 *   colored — true when the icon carries its own colour and must NOT be tinted
 *
 * Colored icons (do not apply tintColor):
 *   starYellow (Favourite star)
 */
export const Icons = {
  // ─── A ────────────────────────────────────────────────────────────────────
  /** Tag category: Misc (catch-all). */
  atlas: {
    png: require('../assets/icons/gadgets.png'),
    colored: false,
  },

  // ─── B ────────────────────────────────────────────────────────────────────
  back: {
    png: require('../assets/icons/back.png'),
    colored: false,
  },

  // ─── C ────────────────────────────────────────────────────────────────────
  checked: {
    png: require('../assets/icons/checked.png'),
    colored: false,
  },
  close: {
    png: require('../assets/icons/remove.png'),
    colored: false,
  },
  copy: {
    png: require('../assets/icons/copy.png'),
    colored: false,
  },

  // ─── D ────────────────────────────────────────────────────────────────────
  /** Tag category: Style / Aesthetic. */
  demeter: {
    png: require('../assets/icons/aesthetic.png'),
    colored: false,
  },

  // ─── E ────────────────────────────────────────────────────────────────────
  /** Monochrome — tintable (Nothing OS style). */
  error: {
    png: require('../assets/icons/error.png'),
    colored: false,
  },

  // ─── F ────────────────────────────────────────────────────────────────────
  facialRecognition: {
    png: require('../assets/icons/facial-recognition.png'),
    colored: false,
  },
  fingerprintScan: {
    png: require('../assets/icons/fingerprint-scan.png'),
    colored: false,
  },
  // ─── H ────────────────────────────────────────────────────────────────────
  /** Tag category: Activity. */
  hermes: {
    png: require('../assets/icons/dancing-woman-silhouette.png'),
    colored: false,
  },
  heartOutline: {
    png: require('../assets/icons/heart_outline.png'),
    colored: false,
  },
  /** Monochrome — tintable (Nothing OS style). */
  heartRed: {
    png: require('../assets/icons/heart_red.png'),
    colored: false,
  },
  hidden: {
    png: require('../assets/icons/hidden.png'),
    colored: false,
  },

  // ─── I ────────────────────────────────────────────────────────────────────
  information: {
    png: require('../assets/icons/information.png'),
    colored: false,
  },

  // ─── L ────────────────────────────────────────────────────────────────────
  legs: {
    png: require('../assets/icons/nsfw.png'),
    colored: false,
  },

  /** Tag category: People. */
  man: {
    png: require('../assets/icons/fashion.png'),
    colored: false,
  },
  maximize: {
    png: require('../assets/icons/maximize.png'),
    colored: false,
  },
  minimize: {
    png: require('../assets/icons/minimize.png'),
    colored: false,
  },
  more: {
    png: require('../assets/icons/more.png'),
    colored: false,
  },
  moveRight: {
    png: require('../assets/icons/move-right.png'),
    colored: false,
  },

  // ─── O ────────────────────────────────────────────────────────────────────
  /** Tag category: Content Type. */
  oldMap: {
    png: require('../assets/icons/format.png'),
    colored: false,
  },
  /** Tag category: Places. */
  olympus: {
    png: require('../assets/icons/beach.png'),
    colored: false,
  },
  openWith: {
    png: require('../assets/icons/openWith.png'),
    colored: false,
  },
  options: {
    png: require('../assets/icons/options.png'),
    colored: false,
  },
  optionsCrystal: {
    png: require('../assets/icons/options_crystal.png'),
    colored: false,
  },

  // ─── P ────────────────────────────────────────────────────────────────────
  /** Tag category: Animals. */
  pegasus: {
    png: require('../assets/icons/dog.png'),
    colored: false,
  },
  padlock: {
    png: require('../assets/icons/padlock.png'),
    colored: false,
  },
  pause: {
    png: require('../assets/icons/pause.png'),
    colored: false,
  },
  picture: {
    png: require('../assets/icons/picture.png'),
    colored: false,
  },
  play: {
    png: require('../assets/icons/play.png'),
    colored: false,
  },
  previous: {
    png: require('../assets/icons/previous.png'),
    colored: false,
  },

  // ─── R ────────────────────────────────────────────────────────────────────
  refresh: {
    png: require('../assets/icons/refresh.png'),
    colored: false,
  },
  remove: {
    png: require('../assets/icons/remove.png'),
    colored: false,
  },
  rename: {
    png: require('../assets/icons/rename.png'),
    colored: false,
  },

  // ─── S ────────────────────────────────────────────────────────────────────
  search: {
    png: require('../assets/icons/search.png'),
    colored: false,
  },
  setting: {
    png: require('../assets/icons/setting.png'),
    colored: false,
  },
  share1: {
    png: require('../assets/icons/share_1.png'),
    colored: false,
  },
  sidebar: {
    png: require('../assets/icons/sidebar.png'),
    colored: false,
  },
  sort: {
    png: require('../assets/icons/sort.png'),
    colored: false,
  },
  // ─── S (continued) ───────────────────────────────────────────────────────
  /** Tag category: NSFW. */
  sphinx: {
    png: require('../assets/icons/nsfw.png'),
    colored: false,
  },
  /** Colored — yellow star. Used for ratings/starred items. Do not tint. */
  starYellow: {
    png: require('../assets/icons/star_yellow.png'),
    colored: true,
  },
  /** Monochrome — black/filled star. Tintable. */
  starBlack: {
    png: require('../assets/icons/star_black.png'),
    colored: false,
  },
  /** Monochrome — outline star. Tintable. */
  starOutline: {
    png: require('../assets/icons/star_outline.png'),
    colored: false,
  },
  /** Monochrome — tintable (Nothing OS style). */
  success: {
    png: require('../assets/icons/success.png'),
    colored: false,
  },

  // ─── T ────────────────────────────────────────────────────────────────────
  test: {
    png: require('../assets/icons/test.png'),
    colored: false,
  },
  tags: {
    png: require('../assets/icons/tags.png'),
    colored: false,
  },
  trash: {
    png: require('../assets/icons/trash.png'),
    colored: false,
  },

  // ─── U ────────────────────────────────────────────────────────────────────
  unlock: {
    png: require('../assets/icons/unlock.png'),
    colored: false,
  },
  /** Monochrome — tintable (Nothing OS style). */
  userFemale: {
    png: require('../assets/icons/user_female.png'),
    colored: false,
  },
  /** Colored — male user avatar. Do not tint. */
  userMale: {
    png: require('../assets/icons/user_male.png'),
    colored: false,
  },

  // ─── V ────────────────────────────────────────────────────────────────────
  /** Tag category: Objects. */
  vase: {
    png: require('../assets/icons/tools-box.png'),
    colored: false,
  },
  video: {
    png: require('../assets/icons/video.png'),
    colored: false,
  },
  volumeMute: {
    png: require('../assets/icons/volume_mute.png'),
    colored: false,
  },
  volumeOn: {
    png: require('../assets/icons/volume_on.png'),
    colored: false,
  },
};
