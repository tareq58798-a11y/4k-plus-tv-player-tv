/**
 * Trailers, as the television app does them: only the one the provider supplied, and in YouTube.
 *
 * Xtream panels put a YouTube link or bare video id in `youtube_trailer` for films and series
 * (xtream.ts, trailerOf). trailerVideoId is MainActivity.kt's: it pulls the eleven-character id out
 * of that, and when there is none there is no trailer - the button is not shown at all, so it can
 * never send a viewer to a search results page to hunt for one. "Officially provided" is exactly
 * this: nothing is looked up, only what the provider sent is played.
 */

/** MainActivity.kt's youtubeVideoIdPattern. */
const VIDEO_ID = /(?:v=|youtu\.be\/|\/embed\/)([\w-]{11})/;

export function trailerVideoId(trailerUrl: string | null | undefined): string | null {
  const id = trailerUrl ? VIDEO_ID.exec(trailerUrl)?.[1] : undefined;
  return id && id.trim() ? id : null;
}

interface TizenAppControlApi {
  ApplicationControl: new (
    operation: string,
    uri: string | null,
    mime: string | null,
    category: string | null,
    data: unknown[],
  ) => unknown;
  ApplicationControlData: new (key: string, value: string[]) => unknown;
  application: {
    launchAppControl(control: unknown, appId: string, onSuccess: () => void, onError: (e: unknown) => void): void;
  };
}

/**
 * The YouTube app on a Samsung set, and how to ask it for one video.
 *
 * From Samsung's own flutter-tizen tizen_app_control example, which opens a video on a TV with
 * this app id, the default operation and this PAYLOAD. The older id is YouTube's before its move
 * to the Cobalt engine; it is tried with the same request only if the first is refused.
 */
const YOUTUBE_APPS = ['com.samsung.tv.cobalt-yt', '111299001912'];

function launch(api: TizenAppControlApi, appId: string, videoId: string): Promise<boolean> {
  return new Promise((resolve) => {
    try {
      const control = new api.ApplicationControl(
        'http://tizen.org/appcontrol/operation/default',
        null,
        null,
        null,
        [new api.ApplicationControlData('PAYLOAD', [`#watch?v=${videoId}&launch=launcher`])],
      );
      api.application.launchAppControl(control, appId, () => resolve(true), () => resolve(false));
    } catch {
      resolve(false);
    }
  });
}

/**
 * Plays [videoId] in the YouTube app on a set, or opens it in a browser anywhere else. Resolves
 * to false when nothing could open it, so the caller can say so.
 *
 * The television app does the same two steps: the YouTube app if it is installed, otherwise the
 * web page. A set has no browser to fall back to from inside an app, so there the second step is
 * the older YouTube app instead.
 */
export async function openTrailer(videoId: string): Promise<boolean> {
  const api = (window as unknown as { tizen?: Partial<TizenAppControlApi> }).tizen;
  if (api?.application?.launchAppControl && api.ApplicationControl && api.ApplicationControlData) {
    for (const appId of YOUTUBE_APPS) {
      if (await launch(api as TizenAppControlApi, appId, videoId)) return true;
    }
    return false;
  }
  const opened = window.open(`https://www.youtube.com/watch?v=${videoId}`, '_blank');
  return opened !== null;
}
