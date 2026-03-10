/**
 * TagResolverService — Stage 2 of the AI search pipeline.
 *
 * Responsibility: map natural-language query concepts to real tag IDs from
 * the local SQLite database. Nothing else.
 *
 * Pipeline:
 *   1. Load all tags from SQLite
 *   2. Pre-filter: narrow to ~80 candidates via normalization + alias expansion
 *   3. AI selection: send (query + candidates) → model picks real matching tags
 *   4. ID lookup: map chosen tag names back to their SQLite IDs
 *
 * Output: ResolveResult
 *   matched       — { tagId, tagName }[]      resolved tags with DB IDs
 *   unmatched     — string[]                   concepts AI couldn't map
 *   candidates    — { id, name }[]             tags that were sent to AI
 *   metadata      — object                     metadata filters (type, date, etc.)
 *   aiRaw         — raw AI JSON response
 */

import { DatabaseService } from '../database/DatabaseService';
import { TagAliasService } from '../database/TagAliasService';
import { OpenAIService } from './OpenAIService';

// ─── Alias dictionary ─────────────────────────────────────────────────────────
// Maps a normalized query word → alternative tag words to search for locally.
// Keeps the candidate net wide enough so the AI sees relevant tags.

const ALIAS_EXPANSIONS = new Map([
  ['black',       ['monochrome', 'bw', 'grayscale', 'greyscale']],
  ['white',       ['monochrome', 'bw', 'grayscale', 'greyscale']],
  ['bw',          ['monochrome', 'grayscale', 'greyscale', 'black']],
  ['monochrome',  ['grayscale', 'greyscale', 'bw', 'black', 'white']],
  ['grayscale',   ['monochrome', 'bw', 'greyscale']],
  ['greyscale',   ['monochrome', 'bw', 'grayscale']],
  ['selfie',      ['portrait', 'self', 'face']],
  ['portrait',    ['face', 'person', 'headshot', 'selfie']],
  ['dog',         ['puppy', 'canine', 'pup', 'pet']],
  ['cat',         ['kitten', 'feline', 'kitty', 'pet']],
  ['pet',         ['dog', 'cat', 'animal']],
  ['landscape',   ['nature', 'outdoor', 'scenery', 'sky']],
  ['outdoor',     ['nature', 'landscape', 'scenery', 'outside']],
  ['food',        ['meal', 'cuisine', 'dining', 'dish', 'restaurant', 'eat']],
  ['city',        ['urban', 'street', 'downtown', 'building']],
  ['street',      ['urban', 'city', 'road']],
  ['screenshot',  ['screen', 'capture']],
  ['travel',      ['trip', 'vacation', 'holiday', 'abroad', 'tourism']],
  ['beach',       ['sea', 'ocean', 'shore', 'sand', 'coastal', 'water']],
  ['sunset',      ['sunrise', 'dusk', 'dawn', 'golden', 'sky']],
  ['night',       ['dark', 'evening', 'low light', 'stars', 'city lights']],
  ['sky',         ['clouds', 'sunset', 'sunrise', 'blue', 'outdoor']],
]);

// ─── Normalization ────────────────────────────────────────────────────────────

function normalize(str) {
  return str
    .toLowerCase()
    .trim()
    .replace(/[-_]/g, ' ')
    .replace(/\s+/g, ' ');
}

// ─── Pre-filter: candidate tags ───────────────────────────────────────────────
// Produces a candidate set of ≤80 tags to send to the AI.
// Scored by how closely each tag word overlaps with query words + alias words.

function getCandidates(query, allTags) {
  const normQuery = normalize(query).replace(/[^a-z0-9\s]/g, ' ');
  const queryWords = normQuery.split(/\s+/).filter(w => w.length >= 3);

  // No meaningful words → return top-N tags alphabetically as broad candidates
  if (!queryWords.length) {
    return allTags.slice(0, 80);
  }

  // Expand query words with aliases
  const searchWords = new Set(queryWords);
  for (const w of queryWords) {
    (ALIAS_EXPANSIONS.get(w) ?? []).forEach(alias => searchWords.add(alias));
  }

  // Score each tag (also checks _aliasTerms if present)
  const scored = [];
  for (const tag of allTags) {
    const normTag = normalize(tag.name);
    const tagWords = normTag.split(/\s+/);
    // Alias terms from DB (space-separated string injected by resolve())
    const aliasTerms = tag._aliasTerms
      ? normalize(tag._aliasTerms).split(/\s+/)
      : [];
    let score = 0;

    for (const sw of searchWords) {
      if (normTag === sw) { score += 10; break; }
      if (normTag.startsWith(sw) || sw.startsWith(normTag)) { score += 6; continue; }
      if (tagWords.some(tw => tw === sw)) { score += 5; continue; }
      if (normTag.includes(sw) && sw.length >= 4) { score += 2; continue; }
      if (sw.includes(normTag) && normTag.length >= 4) { score += 2; continue; }
      // Check against DB aliases (lower weight than direct match)
      if (aliasTerms.some(a => a === sw || a.startsWith(sw) || sw.startsWith(a))) {
        score += 4;
      }
    }

    if (score > 0) scored.push({ ...tag, _score: score });
  }

  scored.sort((a, b) => b._score - a._score);
  // Strip internal score before returning
  return scored.slice(0, 80).map(({ _score: _s, ...rest }) => rest);
}

// ─── Main resolve ─────────────────────────────────────────────────────────────

async function resolve(nlQuery) {
  const db = DatabaseService.getDb();
  if (!db) {
    return { matched: [], unmatched: [], candidates: [], metadata: {}, aiRaw: null };
  }

  // 1. Load all tags
  const { rows } = await db.execute(
    'SELECT id, name FROM tags ORDER BY name ASC',
  );
  const allTags = rows.map(r => ({ id: r.id, name: r.name }));

  if (!allTags.length) {
    return { matched: [], unmatched: [nlQuery], candidates: [], metadata: {}, aiRaw: null };
  }

  // 2. Load DB aliases and merge into the tag list so alias terms score
  //    hits for their canonical tag during pre-filtering.
  //    aliasMap: alias → tagId[]
  const aliasMap = await TagAliasService.getAllAliasMap();

  // Inject alias terms into each tag so getCandidates sees them
  const tagsWithAliases = allTags.map(t => {
    const extraAliases = [];
    for (const [alias, tagIds] of aliasMap) {
      if (tagIds.includes(t.id)) extraAliases.push(alias);
    }
    return extraAliases.length
      ? { ...t, _aliasTerms: extraAliases.join(' ') }
      : t;
  });

  // 3. Pre-filter to candidate set (using alias-enriched list)
  // Strip internal _aliasTerms before passing to AI so we only send {id, name}
  const candidates = getCandidates(nlQuery, tagsWithAliases).map(
    ({ _aliasTerms: _a, ...rest }) => rest,
  );

  // 4. Ask AI to select from the real candidate tags
  const aiRaw = await OpenAIService.resolveTagsFromQuery(nlQuery, candidates);

  // 5. Map AI-chosen tag names back to IDs
  // Build a name→id lookup (case-insensitive)
  const nameToId = new Map(allTags.map(t => [normalize(t.name), t.id]));

  const matched = (aiRaw.matched_tags ?? [])
    .filter(name => typeof name === 'string' && name.length > 0)
    .map(name => ({
      tagId: nameToId.get(normalize(name)),
      tagName: name,
    }))
    .filter(m => m.tagId != null); // reject any name the AI hallucinated

  return {
    matched,                                    // { tagId, tagName }[]
    unmatched: aiRaw.unmatched_concepts ?? [],  // string[]
    candidates,                                 // { id, name }[] sent to AI
    metadata: aiRaw.metadata ?? {},             // type/date/folder/sort
    aiRaw,                                      // full AI response for debug
  };
}

export const TagResolverService = { resolve };
