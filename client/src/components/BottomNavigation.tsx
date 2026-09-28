import {
  AudioWaveform,
  Disc3,
  Library,
  Search,
  Settings,
  SlidersHorizontal,
  Waves,
  type LucideIcon,
} from "lucide-react";

type TabType = "player" | "library" | "search" | "eq" | "dsp" | "fx" | "settings";

type BottomNavigationProps = {
  activeTab: TabType;
  onTabChange: (tab: TabType) => void;
  onLibraryTab: () => void;
  eqEnabled: boolean;
  epicenterEnabled: boolean;
  spatialEffectsEnabled: boolean;
  t: (key: string) => string;
};

function NavItem({
  active,
  icon: Icon,
  label,
  onClick,
  badge = false,
}: {
  active: boolean;
  icon: LucideIcon;
  label: string;
  onClick: () => void;
  badge?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      data-active={active}
      aria-current={active ? "page" : undefined}
      className={`nav-item relative flex min-w-0 flex-1 flex-col items-center gap-0.5 rounded-xl px-0.5 py-2 ${
        active ? "text-[var(--ep-red)]" : "text-[var(--ep-text-muted)]"
      }`}
    >
      {active && (
        <span className="nav-active-indicator absolute left-1/2 top-0 h-0.5 w-6 rounded-full bg-[var(--ep-red)] shadow-[0_0_12px_rgba(255,16,42,0.85)]" />
      )}
      {badge && (
        <span className="absolute right-1.5 top-1.5 h-1.5 w-1.5 animate-pulse rounded-full bg-[var(--ep-red)] shadow-[0_0_8px_rgba(255,16,42,0.85)]" />
      )}
      <Icon className="nav-icon h-[18px] w-[18px]" strokeWidth={1.8} />
      {/* Con 7 pestañas cada hueco mide ~45 px en un teléfono de 360 px, y
          "EPICENTER" a 8 px se pasaba (45.7 px) y salía cortado. Las etiquetas
          largas bajan a 7 px; el resto conserva el tamaño. */}
      <span
        className={`w-full truncate text-center font-black uppercase leading-tight ${
          label.length > 7
            ? "text-[7px] tracking-normal"
            : "text-[8px] tracking-[0.01em]"
        }`}
      >
        {label}
      </span>
    </button>
  );
}

export function BottomNavigation({
  activeTab,
  onTabChange,
  onLibraryTab,
  eqEnabled,
  epicenterEnabled,
  spatialEffectsEnabled,
  t,
}: BottomNavigationProps) {
  const label = (key: string, fallback: string) => {
    const translated = t(key);
    return translated === key ? fallback : translated;
  };

  return (
    <nav className="fixed bottom-0 left-0 right-0 z-50 border-t border-[var(--ep-border)] bg-[var(--ep-surface)] px-1 pt-2 shadow-[0_-10px_28px_rgba(0,0,0,0.55)] bottom-nav-safe">
      <div className="flex items-stretch justify-around bg-[linear-gradient(180deg,#121212,#060606)] p-1">
        <NavItem
          active={activeTab === "player"}
          icon={Disc3}
          label="Inicio"
          onClick={() => onTabChange("player")}
        />
        <NavItem
          active={activeTab === "library"}
          icon={Library}
          label="Música"
          onClick={onLibraryTab}
        />
        <NavItem
          active={activeTab === "search"}
          icon={Search}
          label={label("tabs.search", "Buscar")}
          onClick={() => onTabChange("search")}
        />
        {/* Epicenter, EQ y Efectos ahora son pestañas propias: antes los tres
            vivían detrás de un solo botón "DSP" y la gente no descubría que el
            ecualizador y los efectos existían. */}
        <NavItem
          active={activeTab === "dsp"}
          icon={AudioWaveform}
          label="Epicenter"
          badge={epicenterEnabled}
          onClick={() => onTabChange("dsp")}
        />
        <NavItem
          active={activeTab === "eq"}
          icon={SlidersHorizontal}
          label="EQ"
          badge={eqEnabled}
          onClick={() => onTabChange("eq")}
        />
        <NavItem
          active={activeTab === "fx"}
          icon={Waves}
          label="Efectos"
          badge={spatialEffectsEnabled}
          onClick={() => onTabChange("fx")}
        />
        <NavItem
          active={activeTab === "settings"}
          icon={Settings}
          label={label("tabs.settings", "Ajustes")}
          onClick={() => onTabChange("settings")}
        />
      </div>
    </nav>
  );
}
