# Änderungen

Changelog von Nexira auf Deutsch. Gepflegt ab Version 2.3.4-beta4; die
vollständige Historie auf Englisch steht in [CHANGELOG.md](./CHANGELOG.md).

## [2.4.0-preview] - 2026-07-14

2.4.0 öffnet den Launcher zur weiteren Mod-Welt. Ein neuer Browse-Tab sucht und installiert Modrinth-Modpacks, importiert eine `.mrpack`, ein CurseForge-Zip oder eine Instanz aus einem anderen Launcher und baut ein Pack von Grund auf; ein Kleiderschrank verwaltet Skins und Umhänge über einer überarbeiteten 3D-Figur; der Launcher kann dem Farbschema des Desktops folgen; und ein Boot-Screen plus ein Wiederherstellungsmodus tragen einen Start, der schiefgeht. Darunter zieht die gesamte Oberfläche auf ein einziges Designsystem um, die Start-Engine teilt sich in GUI-freie Module mit einer nativen CLI, und der Build wechselt auf Java 26.

### Highlights
- **Modpacks entdecken und installieren**. Ein neuer Browse-Tab durchsucht Modrinths Modpacks, zeigt ihre Beschreibungen im Launcher und installiert eines mit einem Klick -- und du kannst eine `.mrpack` oder ein CurseForge-Zip importieren oder ein leeres Pack von Grund auf anlegen.
- **Ein Kleiderschrank für deine Skins**. Ein neuer Kleiderschrank hält deine Skins als kleine 3D-Figuren, wendet einen auf SmartyCraft an, wählt einen Umhang oder startet vom Standardsatz des Spiels -- der Look deiner Figur an einem Ort.
- **Der Launcher folgt deinem Desktop**. Er kann das Hell-/Dunkel-Schema deines Systems selbst übernehmen und sein Thema an die Helligkeit des Hintergrunds anpassen, mit einem neuen Erscheinungsbild-Studio für Hintergrund- und Aussehens-Regler.
- **Ein Boot-Screen und ein Wiederherstellungsmodus**. Ein schneller Boot-Screen erscheint beim Start; wenn etwas schiefgeht, halte Shift (oder übergib `--recovery`), um einen fehlerhaften Teil abzuschalten oder zurückzusetzen -- ohne Neuinstallation.
- **Eine konsistente Oberfläche**. Die ganze UI zog auf ein einziges Designsystem um -- Flächen, Schaltflächen, Menüs und Einstellungsbereiche teilen dieselben Formen, Abstände und Icons und bleiben mit oder ohne Hintergrundbild lesbar.
- **Packs zeigen, was sie tun**. Eine Pack-Karte trägt jetzt einen Live-Startzustand (Vorbereiten / Herunterladen / Läuft), und ein Teil-Import nennt die Mods, die noch einen manuellen Download brauchen, statt wie ein leeres Pack zu wirken.

## [2.3.4] - 2026-06-15

Das Anpassungs-Release, konsolidiert. 2.3.4 macht die gesamte Oberfläche bearbeitbar -- und bearbeitbare Flächen tragen jetzt ihre eigenen Einstellungen --, baut das Profil um einen lebendigen 3D-Render deines Skins neu auf, bringt einen Update-Manager mit Kanälen und Rückrollen, gibt Benachrichtigungen ein festes Zuhause in der App und auf dem Desktop, lehrt die Oberfläche, in schmale Fenster zu passen, und bündelt den eigenen Schriftsatz des Launchers. Darunter: SmartyCraft-Modpacks treten ihren Servern bei, ohne etwas von SmartyCraft mitzuliefern, ein installiertes Modpack startet offline neu, der Speicher bemisst sich selbst, und ein tiefer Robustheits-Durchgang verhindert, dass eine kaputte Datei oder ein verirrtes Widget den Launcher umwirft. Es fasst die Linie 2.3.4-beta .. beta5 und alles seither zusammen.

### Highlights
- **Mach den Launcher zu deinem**. Strg+E zum Bearbeiten. Ziehe, skaliere, restyle und platziere jedes Widget frei -- auf der Startseite, in der Bibliothek, den Seitenleisten und der App-Hülle selbst -- mit Glas-Hintergrund je Widget und Speichern / Laden / Export von Layout-Presets. Auch Flächen haben jetzt eigene Einstellungen -- der Auswahlstil der linken Leiste liegt in ihrem Editor-Panel, nicht in einem globalen Menü.
- **Dein Skin in 3D**. Das Profil führt mit einem lebendigen, drehbaren 3D-Render deines Skins, von Grund auf ohne Abhängigkeit gezeichnet, und die Anmeldung liegt im Profil und ist auch abgemeldet erreichbar.
- **Ein Update-Manager mit Kanälen**. Das "i" neben der Version öffnet einen Manager: Release / Beta / Alpha (dazu Dev / Git aus dem Quellcode), aktualisieren oder zurückrollen, eine Desktop-Verknüpfung installieren. Der Info-Bildschirm prüft zudem selbst alle paar Minuten.
- **Benachrichtigungen, die bleiben**. Ein platzierbares Nachrichtenverlauf-Widget gruppiert Wiederholungen, wird zum Verwerfen gewischt und per "Nicht stören" stummgeschaltet -- und neu schickt der Launcher eine echte Desktop-Benachrichtigung, wenn er in den Tray rutscht, damit das nicht wie ein Absturz wirkt.
- **Der Launcher passt in schmale Fenster**. Leisten klappen per Wisch ein, die Serverliste blättert in Pillen, und der Info-Bildschirm stapelt seine Spalten, statt sie abzuschneiden.
- **SmartyCraft-Modpacks treten ihren Servern bei**. Ein Modpack aus dem Mirror, das auf einen SmartyCraft-Server ausgerichtet ist, verbindet sich und tritt bei, die Skins anderer Spieler werden geladen -- ohne dass etwas von SmartyCraft mitgeliefert wird.
- **Offline-Neustart**. Ein installiertes Modpack startet ohne Netzwerk; ein warmer Neustart sendet keine einzige Anfrage.
- **Adaptiver Speicher**. Eine nicht festgepinnte Instanz bemisst ihren Heap aus deinem echten RAM und verfeinert ihn über ein paar Sitzungen; pinne einen Wert, um das abzuschalten.
- **Der eigene Schriftsatz des Launchers**. Google Sans Flex und JetBrains Mono sind in die App eingebettet, sodass die Oberfläche auf jeder Maschine gleich aussieht, statt die System-Schriften zu leihen.
- **Ein Launcher, der nicht umkippt**. Eine kaputte Welt- oder Server-Datei, ein Widget mit verschwundenem Typ oder eine abgestürzte Fläche legen den Launcher nicht mehr lahm.

## [2.3.4-beta5] - 2026-06-09

Ein Profil-und-Updates-Release. Das Profil ist um einen lebendigen
3D-Render deines Skins herum neu aufgebaut, die Anmeldung ist ins Profil
gewandert und auch abgemeldet erreichbar. Ein neuer Update-Manager bringt
Release-Kanäle, das Zurückrollen auf eine frühere Version, die
Installation einer Desktop-Verknüpfung und -- für Entwickler -- das Bauen
des Launchers aus dem Quellcode.

### Highlights
- **Dein Skin in 3D**. Der Konto-Tab im Profil führt jetzt mit einem lebendigen, drehbaren 3D-Render deines Skins, von Grund auf ohne zusätzliche Abhängigkeit gezeichnet.
- **Anmeldung aus dem Profil**. Das Login-Formular liegt im Profil und ist auch abgemeldet erreichbar; das beengte Login in der rechten Leiste ist weg.
- **Ein Update-Manager mit Kanälen**. Das "i" neben der Version öffnet einen Manager: Kanal wählen (Release / Beta / Alpha, dazu Dev / Git aus dem Quellcode), auf eine neuere Version aktualisieren oder zurückrollen, und eine Desktop-Verknüpfung installieren.
- **Update-Prüfung im Hintergrund**. Der Info-Bildschirm prüft selbst alle paar Minuten auf Updates und färbt die laufende Version nach ihrem Kanal.
- **Eine kaputte Welt- oder Server-Datei stürzt den Launcher nicht mehr ab**. Eine fehlerhafte NBT-Länge legte beim Scan den ganzen Launcher lahm.

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
