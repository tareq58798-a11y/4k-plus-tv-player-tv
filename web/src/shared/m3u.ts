/**
 * Plain M3U playlists, ported from M3uParser.kt.
 *
 * Needed for two reasons: the activation service can hand back an M3U rather than an Xtream login,
 * and some providers only ever give out a URL. Rejecting those would have left a customer whose
 * reseller assigned them one staring at an error with nothing to do about it.
 *
 * An M3U carries no kinds. A line is a URL and a name, and whether it is a channel, a film or an
 * episode has to be guessed from the group title, the name and the file extension - which is
 * exactly what detectKind does, imperfectly and on purpose. Guessing wrong puts a film in Live TV;
 * not guessing at all puts everything there.
 */
import type { LoadedPlaylist, MediaKind, PlaylistItem } from './models';

const ATTRIBUTE = /([\w-]+)="([^"]*)"/g;
const MOVIE_EXTENSIONS = ['.mp4', '.mkv', '.avi', '.mov', '.m4v'];
const EPISODE = /s\d{1,2}e\d{1,3}/;

function detectKind(group: string, title: string, url: string): MediaKind {
  const text = `${group} ${title}`.toLowerCase();
  if (text.includes('series') || text.includes('episode') || EPISODE.test(text)) return 'series';
  const path = url.split('?')[0]!.toLowerCase();
  if (text.includes('movie') || text.includes('film') || MOVIE_EXTENSIONS.some((ext) => path.endsWith(ext))) {
    return 'movie';
  }
  return 'live';
}

export function parseM3u(name: string, content: string): LoadedPlaylist {
  const lines = content
    .replace(/^﻿/, '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);

  if (!lines[0]?.toUpperCase().startsWith('#EXTM3U')) {
    throw new Error('This address did not return a valid M3U playlist.');
  }

  const items: PlaylistItem[] = [];
  let metadata: string | null = null;

  for (const line of lines.slice(1)) {
    if (/^#EXTINF/i.test(line)) {
      metadata = line;
      continue;
    }
    // Any other directive - #EXTGRP, #EXTVLCOPT and the rest - is passed over rather than treated
    // as a URL, which is what the leading hash is for.
    if (line.startsWith('#')) continue;
    if (metadata === null) continue;

    // An exec loop rather than matchAll: matchAll arrived in Chrome 73 and a 2020 Samsung set runs
    // Chromium 69, so it would throw on exactly the oldest televisions this build targets.
    const attrs: Record<string, string> = {};
    ATTRIBUTE.lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = ATTRIBUTE.exec(metadata)) !== null) {
      attrs[match[1]!.toLowerCase()] = match[2]!;
    }
    // The display name is whatever follows the last comma; the attributes before it may contain
    // commas of their own, which is why it is the last and not the first.
    const afterComma = metadata.slice(metadata.lastIndexOf(',') + 1).trim();
    const title = afterComma || attrs['tvg-name'] || 'Unnamed item';
    const group = attrs['group-title'] || 'Other';

    items.push({
      name: title,
      streamUrl: line,
      group,
      logoUrl: attrs['tvg-logo'] || null,
      channelId: attrs['tvg-id'] || null,
      kind: detectKind(group, title, line),
    });
    metadata = null;
  }

  if (!items.length) throw new Error('The playlist is valid but contains no playable items.');
  return {
    name: name.trim(),
    items,
    groups: [...new Set(items.map((item) => item.group))],
    accountStatus: null,
    expiryEpochSeconds: null,
  };
}

/**
 * Downloads and parses. Both schemes are tried when none was given, the same way the Xtream client
 * does it, because a provider who says "example.com/list.m3u" means one of the two and does not
 * know which.
 */
export async function loadM3u(name: string, address: string): Promise<LoadedPlaylist> {
  const trimmed = address.trim();
  if (!trimmed) throw new Error('Enter a playlist or server address.');
  const candidates = /^https?:\/\//i.test(trimmed) ? [trimmed] : [`http://${trimmed}`, `https://${trimmed}`];

  let lastError: unknown = null;
  for (const url of candidates) {
    try {
      const response = await fetch(url, { redirect: 'follow' });
      if (!response.ok) throw new Error(`The server returned HTTP ${response.status}. Please try again.`);
      return parseM3u(name, await response.text());
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError instanceof Error ? lastError : new Error('The playlist address could not be reached.');
}
