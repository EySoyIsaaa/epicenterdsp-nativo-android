import type { MutableRefObject } from "react";
import { toast } from "sonner";
import type { QueueController, Track } from "@/hooks/useAudioQueue";

type Translate = (key: string, values?: Record<string, string | number>) => string;

interface HomeQueueActionOptions {
  queue: QueueController;
  t: Translate;
  nowPlayingTrack: Track | null;
  playbackReasonRef: MutableRefObject<string>;
  currentTrackRef: MutableRefObject<string | null>;
  closeContextMenu: () => void;
  openPlayer: () => void;
}

export function useHomeQueueActions({
  queue,
  t,
  nowPlayingTrack,
  playbackReasonRef,
  currentTrackRef,
  closeContextMenu,
  openPlayer,
}: HomeQueueActionOptions) {
  const handleAddToQueue = (track: Track) => {
    queue.addToQueue(track);
    toast.success(t("actions.addedToQueue"));
    closeContextMenu();
  };

  const handlePlayNext = (track: Track) => {
    queue.addToQueueNext(track);
    toast.success(t("actions.willPlayNext"));
    closeContextMenu();
  };

  const handlePlayNow = (track: Track) => {
    playbackReasonRef.current = "manual";
    currentTrackRef.current = null;
    queue.playNow(track);
    closeContextMenu();
    openPlayer();
  };

  const handleShufflePlay = (tracks: Track[]) => {
    if (tracks.length === 0) {
      toast.error(t("actions.noSongsToPlay"));
      return;
    }
    const candidates = tracks.length > 1 && nowPlayingTrack
      ? tracks.filter((track) => track.id !== nowPlayingTrack.id)
      : tracks;
    const randomIndex = Math.floor(Math.random() * candidates.length);
    const randomTrack = candidates[randomIndex] ?? tracks[0];
    console.info("[SHUFFLE_REQUEST]", {
      randomIndex,
      randomTrackId: randomTrack.id,
      randomTitle: randomTrack.title,
      previousNowPlayingId: nowPlayingTrack?.id,
    });
    playbackReasonRef.current = "shuffle";
    currentTrackRef.current = null;
    queue.shuffleAll(tracks, randomTrack.id);
    toast.success(t("actions.playingShuffled", { count: tracks.length }));
    openPlayer();
  };

  const handlePlayInOrder = (tracks: Track[]) => {
    if (tracks.length === 0) {
      toast.error(t("actions.noSongsToPlay"));
      return;
    }
    playbackReasonRef.current = "manual-order";
    currentTrackRef.current = null;
    queue.playAllInOrder(tracks);
    toast.success(t("actions.playingAll", { count: tracks.length }));
    openPlayer();
  };

  return {
    handleAddToQueue,
    handlePlayNext,
    handlePlayNow,
    handleShufflePlay,
    handlePlayInOrder,
  };
}
