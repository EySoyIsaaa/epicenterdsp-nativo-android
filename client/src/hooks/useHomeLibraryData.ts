import { useMemo } from "react";
import type { Track } from "@/hooks/useAudioQueue";
import type { TranslateFn } from "@/components/home/types";

export type HomeSongSort = "default" | "name" | "artist";

export function useHomeLibraryData(
  library: Track[],
  songSort: HomeSongSort,
  language: string,
  t: TranslateFn,
) {
  const hiResTracks = useMemo(
    () => library.filter((track) => track.isHiRes),
    [library],
  );

  const sortedSongs = useMemo(() => {
    if (songSort === "default") return library;
    const copy = [...library];
    const locale = language === "es" ? "es" : "en";
    const sortKey = songSort === "name" ? "title" : "artist";
    copy.sort((a, b) => a[sortKey].localeCompare(b[sortKey], locale, { sensitivity: "base" }));
    return copy;
  }, [language, library, songSort]);

  const songsByArtist = useMemo(
    () => library.reduce((groups, track) => {
      const artist = track.artist || t("common.unknownArtist");
      (groups[artist] ??= []).push(track);
      return groups;
    }, {} as Record<string, Track[]>),
    [library, t],
  );

  const albums = useMemo(
    () => library.reduce((groups, track) => {
      const album = track.title.split(" - ")[0] || track.title;
      (groups[album] ??= []).push(track);
      return groups;
    }, {} as Record<string, Track[]>),
    [library],
  );

  return { hiResTracks, sortedSongs, songsByArtist, albums };
}
