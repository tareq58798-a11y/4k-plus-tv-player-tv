/**
 * What is on a channel now, and what is on next.
 *
 * Xtream's short EPG returns a handful of entries starting from roughly now, and panels disagree
 * about whether the programme already in progress is one of them - so "now" is worked out from
 * the clock rather than assumed to be the first entry.
 *
 * Fetched for the channel the viewer settles on, never for a whole list. A category of four
 * hundred channels would otherwise be four hundred requests to show two lines about one of them.
 */
import { shortEpg } from '../shared/xtream';
import type { EpgProgram, PlaylistItem, ProviderLogin } from '../shared/models';

export interface NowNext {
  now: EpgProgram | null;
  next: EpgProgram | null;
  /** 0..1 through the current programme, for the little bar under the title. */
  progress: number | null;
}

function pick(programs: EpgProgram[]): NowNext {
  const seconds = Math.floor(Date.now() / 1000);
  const now = programs.find((p) => p.startEpochSeconds <= seconds && p.endEpochSeconds > seconds) ?? null;
  const next =
    programs.find((p) => p.startEpochSeconds > seconds) ??
    // Nothing in the future and nothing current either: the panel sent a window that has already
    // passed, which is common on channels it has no real listings for.
    null;
  const progress = now
    ? Math.min(1, Math.max(0, (seconds - now.startEpochSeconds) / (now.endEpochSeconds - now.startEpochSeconds)))
    : null;
  return { now, next, progress };
}

/**
 * Asks for [channel]'s listings after a pause, and only answers if it is still the one wanted.
 *
 * Holding the D-pad down a channel list would otherwise start a request per channel and finish
 * them out of order, so the panel for the channel the viewer stopped on could be overwritten by
 * one they passed through on the way.
 */
export function createEpgLoader(login: ProviderLogin | null, onResult: (channel: PlaylistItem, result: NowNext) => void) {
  let timer: number | null = null;
  let wanted: string | null = null;

  return {
    request(channel: PlaylistItem): void {
      wanted = channel.channelId;
      if (timer !== null) window.clearTimeout(timer);
      if (!login || !channel.channelId || channel.kind !== 'live') return;
      const id = channel.channelId;
      timer = window.setTimeout(async () => {
        try {
          const programs = await shortEpg(login, id);
          if (wanted !== id) return;
          onResult(channel, pick(programs));
        } catch {
          // A panel with no EPG for this channel is ordinary, not an error. The viewer simply
          // gets the channel name, which is what they had before.
        }
      }, 450);
    },
    cancel(): void {
      wanted = null;
      if (timer !== null) window.clearTimeout(timer);
    },
  };
}

/** 20:05, in the viewer's own locale and clock convention. */
export function clockTime(epochSeconds: number, locale: string): string {
  try {
    return new Date(epochSeconds * 1000).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
  } catch {
    const date = new Date(epochSeconds * 1000);
    return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  }
}
