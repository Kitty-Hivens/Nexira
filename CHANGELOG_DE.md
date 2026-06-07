# Änderungen

Changelog von Nexira auf Deutsch. Gepflegt ab Version 2.3.4-beta4; die
vollständige Historie auf Englisch steht in [CHANGELOG.md](./CHANGELOG.md).

## [2.3.4-beta4] - 2026-06-07

Das SmartyCraft-Modpack-Release. Ein Modpack, das auf einen
SmartyCraft-Server ausgerichtet ist, verbindet sich jetzt und tritt dem
Server bei (inklusive der Skins anderer Spieler), ohne dass irgendetwas
von SmartyCraft mitgeliefert wird. Dazu: Offline-Neustart eines
installierten Modpacks, abhängigkeitsbewusste optionale Mods, eine
Konsole, die unter einer Logflut nicht mehr einfriert, und Korrekturen
am adaptiven Speicher.

### Highlights
- **SmartyCraft-Modpacks treten ihren Servern bei**. Ein Modpack aus dem
  Mirror, das auf einen SmartyCraft-Server ausgerichtet ist, verbindet sich
  jetzt und tritt bei, und die Skins anderer Spieler werden geladen.
- **Offline-Neustart**. Ein bereits installiertes Modpack startet ohne
  Netzwerk; ein warmer Neustart sendet keine einzige Netzwerkanfrage.
- **Optionale Mods ziehen ihre Abhängigkeiten nach**. Das Aktivieren eines
  optionalen Mods aktiviert auch die benötigten Bibliotheken, und das
  Umschalten eines Mods innerhalb einer austauschbaren Gruppe (z. B. eines
  Rezept-Browsers) deaktiviert den anderen.
- **Eine Konsole, die mithält**. Eine Logflut beim Laden der Mods friert das
  Launcher-Fenster nicht mehr ein und bringt es nicht mehr zum Absturz.
- **Adaptiver Speicher erkennt den echten RAM**. Der installierte Build erkennt
  jetzt den RAM des Rechners korrekt, und die adaptive Dimensionierung
  funktioniert unter ZGC und Shenandoah.

### Hinzugefügt
- SmartyCraft-Beitritt über einen Agenten: Für ein Modpack, das an einen
  SmartyCraft-Server gebunden ist, hängt der Launcher einen abhängigkeitsfreien
  `-javaagent` an, der authlib beim Laden der Klassen auf SmartyCraft umleitet
  (die join-, session- und profile-Aufrufe sowie die Textur-Domain-Whitelist)
  und die unsignierten Skins anderer Spieler lädt. Zwei Schalter unter
  Einstellungen -> Smarty: "Netzwerk-Agent" (an) und der ältere
  authlib-Bibliothekstausch (aus, als Rückfalloption behalten).
- Offline-Warmstart: Versions-Metadaten und ein passender Asset-Index werden
  von der Festplatte wiederverwendet, sodass ein installiertes Modpack ohne
  Netzwerkanfragen neu startet; eine fehlende oder geänderte Datei wird
  trotzdem einzeln geladen.
- Abhängigkeitsbewusstes Umschalten optionaler Mods: Das Aktivieren eines Mods
  aktiviert die von ihm benötigten Bibliotheken; Mods derselben Rolle bleiben
  gegenseitig ausschließend.
- Periodisches Schreiben der Profiler-Metriken: Die Sitzungsmetriken werden
  alle 30 Sekunden geschrieben, sodass ein harter Exit (ein `Runtime.halt`
  durch einen Mod, ein nativer Absturz) nicht mehr die ganze Sitzung verliert.
- Changelogs auf Russisch und Deutsch (`CHANGELOG_RU.md`, `CHANGELOG_DE.md`),
  ab diesem Release.

### Geändert
- Die Spielkonsole läuft vollständig außerhalb des UI-Threads, von der Aufnahme
  bis zum Rendern, sodass eine Logflut das Fenster nicht mehr blockieren oder
  zum Absturz bringen kann.
- Die adaptive Heap-Dimensionierung erkennt das Live-Set unter ZGC und
  Shenandoah, nicht nur unter G1 / Parallel / CMS / Serial.
- Die Auth-Host-Umleitung eines Modpacks richtet sich jetzt nach der Herkunft
  des Modpacks: Nur SmartyCraft- und Mirror-Modpacks werden umgeleitet;
  Modrinth-, lokale und eigene Modpacks behalten die Standard-Hosts.
- Der SmartyCraft-Skin-Patch schreibt eine Zeile ins Log, wenn ein geändertes
  `getTextures` nicht mehr gepatcht werden kann, statt die Skins anderer
  Spieler still zu verlieren.

### Behoben
- Der adaptive Speicher liest den echten Host-RAM im installierten Build (zuvor
  fiel er auf der gepackten Laufzeit auf feste 16 GB zurück und dimensionierte
  den Heap falsch).
