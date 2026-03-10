import { OPENAI_KEY } from '../../constants/keys';

const BASE_URL = 'https://api.openai.com/v1';

// ─── Helpers ──────────────────────────────────────────────────────────────────

async function post(endpoint, body) {
  if (!OPENAI_KEY) {
    throw new Error('OPENAI_KEY is not set. Check your .env file.');
  }
  const response = await fetch(`${BASE_URL}${endpoint}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${OPENAI_KEY}`,
    },
    body: JSON.stringify(body),
  });

  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(
      data?.error?.message ?? `OpenAI API error (HTTP ${response.status})`,
    );
  }
  return data;
}

// ─── Public API ───────────────────────────────────────────────────────────────

export const OpenAIService = {
  /**
   * Returns true if the key is present (does not validate remotely).
   */
  hasKey() {
    return Boolean(OPENAI_KEY);
  },

  /**
   * Sends a minimal chat completion to verify the key works end-to-end.
   * Resolves with the assistant reply string.
   */
  async testConnection() {
    const data = await post('/chat/completions', {
      model: 'gpt-4o-mini',
      messages: [
        {
          role: 'user',
          content:
            'Reply with exactly one sentence confirming the API connection is working.',
        },
      ],
      max_tokens: 64,
    });
    return data.choices?.[0]?.message?.content?.trim() ?? '(no response)';
  },

  /**
   * Generic chat completion. `messages` follows the OpenAI messages array format.
   */
  async chat(messages, { model = 'gpt-4o-mini', maxTokens = 512 } = {}) {
    const data = await post('/chat/completions', {
      model,
      messages,
      max_tokens: maxTokens,
    });
    return data.choices?.[0]?.message?.content?.trim() ?? '';
  },

  /**
   * Single AI call that receives the user's NL query PLUS a pre-filtered list
   * of real tags from the local database. The model selects which existing tags
   * best match the query and also extracts metadata filters.
   *
   * candidateTags: { id: number, name: string }[]  — pre-filtered from SQLite
   *
   * Returned schema:
   * {
   *   intent: "search_media",
   *   matched_tags: string[],        // names chosen from candidateTags only
   *   unmatched_concepts: string[],  // query ideas with no tag match
   *   metadata: {
   *     media_type: "image"|"video"|"all",
   *     date_from:  "YYYY-MM-DD"|null,
   *     date_to:    "YYYY-MM-DD"|null,
   *     folder:     string|null,
   *     sort:       "relevance"|"date_desc"|"date_asc"
   *   }
   * }
   */
  /**
   * Analyzes a single tag and returns AI suggestions for category, description,
   * aliases, and canonical name — all in one call.
   *
   * tag:        { id, name, category, description, usage_count }
   * sampleMedia: string[]  — up to 5 sample filenames with this tag (for context)
   *
   * Returned schema:
   * {
   *   category:       { suggested: string, reasoning: string } | null,
   *   description:    { suggested: string, reasoning: string } | null,
   *   aliases:        { alias: string, reasoning: string }[],
   *   canonical_name: { suggested: string, reasoning: string } | null
   * }
   */
  async analyzeTag(tag, sampleMedia = []) {
    const VALID_CATEGORIES = [
      'people', 'animals', 'places', 'objects', 'activity',
      'style', 'content', 'nsfw', 'misc',
    ];
    const sampleStr = sampleMedia.length
      ? `\nSample filenames tagged "${tag.name}": ${sampleMedia.join(', ')}`
      : '';

    const data = await post('/chat/completions', {
      model: 'gpt-4o-mini',
      messages: [
        {
          role: 'system',
          content: `You are a photo library tag analyst. Analyze the given tag and return improvement suggestions.

Valid categories: ${VALID_CATEGORIES.join(', ')}

For each field, provide a suggestion only if you have a meaningful improvement. Set to null if the current value is already correct or if you have nothing meaningful to add.

Return ONLY valid JSON matching this schema exactly:
{
  "category": { "suggested": "<one of the valid categories>", "reasoning": "<why>" } | null,
  "description": { "suggested": "<1-2 sentence description for this photo tag>", "reasoning": "<why>" } | null,
  "aliases": [{ "alias": "<lowercase search term>", "reasoning": "<why useful>" }],
  "canonical_name": { "suggested": "<cleaner/shorter canonical name>", "reasoning": "<why>" } | null
}

Rules:
- category: suggest only if the current category is wrong or 'misc' and you know better
- description: a description of what the tag is, if the tag category is a person look up the person and add details of that person. be funny, imaginative and descriptive in your descriptions.
- aliases: 1–8 common alternative terms users might search for (lowercase, singular preferred)
- canonical_name: suggest only if the current name has typos, odd casing, or could be cleaner.
- aliases must be different from the tag name itself`,
        },
        {
          role: 'user',
          content: `Tag: "${tag.name}"
Current category: ${tag.category ?? 'misc'}
Current description: ${tag.description ?? '(none)'}
Usage count: ${tag.usage_count ?? 0} photos${sampleStr}`,
        },
      ],
      max_tokens: 400,
      response_format: { type: 'json_object' },
    });
    const content = data.choices?.[0]?.message?.content ?? '{}';
    return JSON.parse(content);
  },

  async resolveTagsFromQuery(nlQuery, candidateTags) {
    const tagList = candidateTags.map(t => t.name).join(', ');
    const data = await post('/chat/completions', {
      model: 'gpt-4o-mini',
      messages: [
        {
          role: 'system',
          content: `You are a photo library search assistant. Match the user query to tags from their personal library.

AVAILABLE TAGS (select ONLY from this list):
${tagList}

Rules:
- matched_tags: pick ONLY tag names from the list above that meaningfully match the query (subjects, objects, moods, colours, styles). Prefer exact or semantic matches. Do NOT invent tags.
- unmatched_concepts: any query idea you could not match to an existing tag.
- metadata.media_type: "image" for photos, "video" for videos, "all" otherwise.
- metadata.date_from / date_to: ISO date "YYYY-MM-DD" only if a time period is mentioned, else null.
- metadata.folder: album name if explicitly mentioned, else null.
- metadata.sort: "relevance" (default) | "date_desc" | "date_asc".

Return ONLY valid JSON:
{
  "intent": "search_media",
  "matched_tags": [],
  "unmatched_concepts": [],
  "metadata": {
    "media_type": "all",
    "date_from": null,
    "date_to": null,
    "folder": null,
    "sort": "relevance"
  }
}`,
        },
        { role: 'user', content: nlQuery },
      ],
      max_tokens: 300,
      response_format: { type: 'json_object' },
    });
    const content = data.choices?.[0]?.message?.content ?? '{}';
    return JSON.parse(content);
  },
};
