import { useCallback, useEffect, useRef, type Dispatch, type SetStateAction } from "react";
import type { Track } from "@/hooks/useAudioQueue";
import type { Playlist } from "@/hooks/usePlaylists";
import type { PlaylistMenuState } from "@/hooks/useHomePlaylists";
import type { HomeLibraryView, HomeTabType } from "@/components/home/types";

const HOME_NAVIGATION_STATE_KEY = "__epicenterHomeNav";

type HomeNavigationSnapshot = {
  activeTab: HomeTabType;
  libraryView: HomeLibraryView;
  showQueue: boolean;
  showEqAutoModal: boolean;
  showDspAutoModal: boolean;
  showCreatePlaylist: boolean;
  showRenamePlaylist: boolean;
  showDeletePlaylist: boolean;
  showAddToPlaylist: boolean;
  showAddSongsToPlaylist: boolean;
  showOnboarding: boolean;
  onboardingStep: number;
  selectedPlaylistId: string | null;
  contextMenuOpen: boolean;
  playlistMenuOpen: boolean;
  duplicatesModalOpen: boolean;
};

interface HomeNavigationOptions {
  activeTab: HomeTabType;
  setActiveTab: Dispatch<SetStateAction<HomeTabType>>;
  libraryView: HomeLibraryView;
  setLibraryView: Dispatch<SetStateAction<HomeLibraryView>>;
  showQueue: boolean;
  setShowQueue: Dispatch<SetStateAction<boolean>>;
  showEqAutoModal: boolean;
  setShowEqAutoModal: Dispatch<SetStateAction<boolean>>;
  showDspAutoModal: boolean;
  setShowDspAutoModal: Dispatch<SetStateAction<boolean>>;
  showCreatePlaylist: boolean;
  setShowCreatePlaylist: Dispatch<SetStateAction<boolean>>;
  showRenamePlaylist: boolean;
  setShowRenamePlaylist: Dispatch<SetStateAction<boolean>>;
  showDeletePlaylist: boolean;
  setShowDeletePlaylist: Dispatch<SetStateAction<boolean>>;
  showAddToPlaylist: Track | null;
  setShowAddToPlaylist: Dispatch<SetStateAction<Track | null>>;
  showAddSongsToPlaylist: boolean;
  setShowAddSongsToPlaylist: Dispatch<SetStateAction<boolean>>;
  showOnboarding: boolean;
  setShowOnboarding: Dispatch<SetStateAction<boolean>>;
  onboardingStep: number;
  setOnboardingStep: Dispatch<SetStateAction<number>>;
  selectedPlaylist: Playlist | null;
  setSelectedPlaylist: Dispatch<SetStateAction<Playlist | null>>;
  playlists: Playlist[];
  contextMenu: { track: Track; x: number; y: number } | null;
  setContextMenu: Dispatch<SetStateAction<{ track: Track; x: number; y: number } | null>>;
  playlistMenu: PlaylistMenuState;
  setPlaylistMenu: Dispatch<SetStateAction<PlaylistMenuState>>;
  showDuplicatesModal: string[];
  setShowDuplicatesModal: Dispatch<SetStateAction<string[]>>;
}

export function useHomeNavigation(options: HomeNavigationOptions) {
  const {
    activeTab, setActiveTab, libraryView, setLibraryView, showQueue, setShowQueue,
    showEqAutoModal, setShowEqAutoModal, showDspAutoModal, setShowDspAutoModal,
    showCreatePlaylist, setShowCreatePlaylist, showRenamePlaylist, setShowRenamePlaylist,
    showDeletePlaylist, setShowDeletePlaylist, showAddToPlaylist, setShowAddToPlaylist,
    showAddSongsToPlaylist, setShowAddSongsToPlaylist, showOnboarding, setShowOnboarding,
    onboardingStep, setOnboardingStep, selectedPlaylist, setSelectedPlaylist, playlists,
    contextMenu, setContextMenu, playlistMenu, setPlaylistMenu, showDuplicatesModal,
    setShowDuplicatesModal,
  } = options;

  const isRestoringNavigationRef = useRef(false);
  const lastNavigationSnapshotRef = useRef<HomeNavigationSnapshot | null>(null);

  const buildNavigationSnapshot = useCallback(
    (): HomeNavigationSnapshot => ({
      activeTab,
      libraryView,
      showQueue,
      showEqAutoModal,
      showDspAutoModal,
      showCreatePlaylist,
      showRenamePlaylist,
      showDeletePlaylist,
      showAddToPlaylist: !!showAddToPlaylist,
      showAddSongsToPlaylist,
      showOnboarding,
      onboardingStep,
      selectedPlaylistId: selectedPlaylist?.id ?? null,
      contextMenuOpen: !!contextMenu,
      playlistMenuOpen: !!playlistMenu,
      duplicatesModalOpen: showDuplicatesModal.length > 0,
    }),
    [
      activeTab, libraryView, showQueue, showEqAutoModal, showDspAutoModal,
      showCreatePlaylist, showRenamePlaylist, showDeletePlaylist, showAddToPlaylist,
      showAddSongsToPlaylist, showOnboarding, onboardingStep, selectedPlaylist,
      contextMenu, playlistMenu, showDuplicatesModal,
    ],
  );

  const applyNavigationSnapshot = useCallback((snapshot: HomeNavigationSnapshot) => {
    isRestoringNavigationRef.current = true;
    setActiveTab(snapshot.activeTab);
    setLibraryView(snapshot.libraryView);
    setShowQueue(snapshot.showQueue);
    setShowEqAutoModal(snapshot.showEqAutoModal);
    setShowDspAutoModal(snapshot.showDspAutoModal);
    setShowCreatePlaylist(snapshot.showCreatePlaylist);
    setShowRenamePlaylist(snapshot.showRenamePlaylist);
    setShowDeletePlaylist(snapshot.showDeletePlaylist);
    setShowAddSongsToPlaylist(snapshot.showAddSongsToPlaylist);
    setShowOnboarding(snapshot.showOnboarding);
    setOnboardingStep(snapshot.onboardingStep);

    if (!snapshot.showAddToPlaylist) setShowAddToPlaylist(null);
    if (!snapshot.contextMenuOpen) setContextMenu(null);
    if (!snapshot.playlistMenuOpen) setPlaylistMenu(null);
    if (!snapshot.duplicatesModalOpen) setShowDuplicatesModal([]);

    const snapshotPlaylist = snapshot.selectedPlaylistId
      ? playlists.find((playlist) => playlist.id === snapshot.selectedPlaylistId) ?? null
      : null;
    if (snapshot.libraryView === "playlist-detail" && !snapshotPlaylist) {
      setLibraryView("playlists");
    }
    setSelectedPlaylist(snapshotPlaylist);
    window.setTimeout(() => { isRestoringNavigationRef.current = false; }, 0);
  }, [
    playlists, setActiveTab, setContextMenu, setLibraryView, setSelectedPlaylist,
    setShowAddSongsToPlaylist, setShowAddToPlaylist, setShowCreatePlaylist,
    setShowDeletePlaylist, setShowDspAutoModal, setShowEqAutoModal, setShowOnboarding,
    setShowQueue, setShowRenamePlaylist, setShowDuplicatesModal, setPlaylistMenu,
    setOnboardingStep,
  ]);

  useEffect(() => {
    const initialSnapshot = buildNavigationSnapshot();
    lastNavigationSnapshotRef.current = initialSnapshot;
    window.history.replaceState(
      { ...(window.history.state ?? {}), [HOME_NAVIGATION_STATE_KEY]: initialSnapshot },
      "",
    );
  }, []);

  useEffect(() => {
    if (isRestoringNavigationRef.current) return;
    const nextSnapshot = buildNavigationSnapshot();
    const previousSnapshot = lastNavigationSnapshotRef.current;
    if (previousSnapshot && JSON.stringify(previousSnapshot) === JSON.stringify(nextSnapshot)) return;

    lastNavigationSnapshotRef.current = nextSnapshot;
    window.history.pushState(
      { ...(window.history.state ?? {}), [HOME_NAVIGATION_STATE_KEY]: nextSnapshot },
      "",
    );
  }, [buildNavigationSnapshot]);

  useEffect(() => {
    const onPopState = (event: PopStateEvent) => {
      const snapshot = event.state?.[HOME_NAVIGATION_STATE_KEY] as HomeNavigationSnapshot | undefined;
      if (!snapshot) return;
      lastNavigationSnapshotRef.current = snapshot;
      applyNavigationSnapshot(snapshot);
    };
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, [applyNavigationSnapshot]);
}
