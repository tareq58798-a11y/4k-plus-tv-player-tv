package com.fourkplus.tvplayer

import com.fourkplus.tvplayer.data.PlaylistItem

/**
 * The identity a title is stored under: favourites, resume positions, watch history and the
 * continue-watching record all key on this.
 *
 * Shared rather than defined per build. It is a storage key, not a screen concern, and two copies
 * of the formula would be two things to keep in step - a drift in either one silently orphans every
 * position and star already saved under the other.
 *
 * Provider items carry their own id. Plain M3U entries do not, so those fall back to the group and
 * name together, which is the only pair a playlist reliably makes unique.
 */
internal fun channelKey(channel: PlaylistItem): String =
    channel.channelId ?: "${channel.group}:${channel.name}"
