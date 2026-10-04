import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { usePlaylists, type Playlist } from "@/hooks/usePlaylists";
import type { Track } from "@/hooks/useAudioQueue";

export type PlaylistMenuState = {
  playlist: Playlist;
  x: number;
  y: number;
} | null;

type Translate = (key: string, values?: Record<string, string | number>) => string;

export function useHomePlaylists(
  library: Track[],
  t: Translate,
  onReturnToPlaylistList: () => void,
) {
  const playlistManager = usePlaylists(library);
  const [selectedPlaylist, setSelectedPlaylist] = useState<Playlist | null>(null);
  const [showCreatePlaylist, setShowCreatePlaylist] = useState(false);
  const [showRenamePlaylist, setShowRenamePlaylist] = useState(false);
  const [showDeletePlaylist, setShowDeletePlaylist] = useState(false);
  const [showAddToPlaylist, setShowAddToPlaylist] = useState<Track | null>(null);
  const [showAddSongsToPlaylist, setShowAddSongsToPlaylist] = useState(false);
  const [newPlaylistName, setNewPlaylistName] = useState("");
  const [playlistMenu, setPlaylistMenu] = useState<PlaylistMenuState>(null);

  useEffect(() => {
    if (!selectedPlaylist) return;
    const updated = playlistManager.playlists.find(
      (playlist) => playlist.id === selectedPlaylist.id,
    );
    if (updated && updated.trackIds.length !== selectedPlaylist.trackIds.length) {
      setSelectedPlaylist(updated);
    }
  }, [playlistManager.playlists, selectedPlaylist]);

  const handleCreatePlaylist = useCallback(async () => {
    if (!newPlaylistName.trim()) return;
    await playlistManager.createPlaylist(newPlaylistName.trim());
    toast.success(t("playlists.created"));
    setNewPlaylistName("");
    setShowCreatePlaylist(false);
  }, [newPlaylistName, playlistManager, t]);

  const handleRenamePlaylist = useCallback(async () => {
    if (!selectedPlaylist || !newPlaylistName.trim()) return;
    const name = newPlaylistName.trim();
    await playlistManager.renamePlaylist(selectedPlaylist.id, name);
    setSelectedPlaylist({ ...selectedPlaylist, name });
    toast.success(t("playlists.renamed"));
    setNewPlaylistName("");
    setShowRenamePlaylist(false);
    setPlaylistMenu(null);
  }, [newPlaylistName, playlistManager, selectedPlaylist, t]);

  const handleDeletePlaylist = useCallback(async () => {
    if (!selectedPlaylist) return;
    await playlistManager.deletePlaylist(selectedPlaylist.id);
    toast.success(t("playlists.deleted"));
    setSelectedPlaylist(null);
    setShowDeletePlaylist(false);
    setPlaylistMenu(null);
    onReturnToPlaylistList();
  }, [onReturnToPlaylistList, playlistManager, selectedPlaylist, t]);

  const handleAddToPlaylist = useCallback(async (playlistId: string, track: Track) => {
    await playlistManager.addTrackToPlaylist(playlistId, track.id);
    toast.success(t("playlists.songAdded"));
    setShowAddToPlaylist(null);
  }, [playlistManager, t]);

  const handleRemoveFromPlaylist = useCallback(async (track: Track) => {
    if (!selectedPlaylist) return;
    await playlistManager.removeTrackFromPlaylist(selectedPlaylist.id, track.id);
    const updated = playlistManager.playlists.find(
      (playlist) => playlist.id === selectedPlaylist.id,
    );
    if (updated) setSelectedPlaylist(updated);
    toast.success(t("playlists.songRemoved"));
  }, [playlistManager, selectedPlaylist, t]);

  const handleOpenAddToPlaylist = useCallback((track: Track) => {
    setShowAddToPlaylist(track);
  }, []);

  const handleAddSongToSelectedPlaylist = useCallback(async (track: Track) => {
    if (!selectedPlaylist) return;
    if (selectedPlaylist.trackIds.includes(track.id)) {
      toast.error(t("duplicates.alreadyInPlaylist"));
      return;
    }
    await playlistManager.addTrackToPlaylist(selectedPlaylist.id, track.id);
    toast.success(t("playlists.songAdded"));
  }, [playlistManager, selectedPlaylist, t]);

  return {
    playlistManager,
    selectedPlaylist,
    setSelectedPlaylist,
    showCreatePlaylist,
    setShowCreatePlaylist,
    showRenamePlaylist,
    setShowRenamePlaylist,
    showDeletePlaylist,
    setShowDeletePlaylist,
    showAddToPlaylist,
    setShowAddToPlaylist,
    showAddSongsToPlaylist,
    setShowAddSongsToPlaylist,
    newPlaylistName,
    setNewPlaylistName,
    playlistMenu,
    setPlaylistMenu,
    handleCreatePlaylist,
    handleRenamePlaylist,
    handleDeletePlaylist,
    handleAddToPlaylist,
    handleRemoveFromPlaylist,
    handleOpenAddToPlaylist,
    handleAddSongToSelectedPlaylist,
  };
}
